import * as Comlink from "comlink";
import { openDB, type DBSchema, type IDBPDatabase } from "idb";
import { toast } from "sonner";
import { create } from "zustand";
import type { LibraryWorkerApi } from "./library.worker";
import LibraryWorker from "./library.worker.ts?worker&inline";
import type { LoadedRecord, RecordDetails, RecordMeta } from "./types";
import { isValidMeta, nameOf, requireValue } from "./validate";

/** Same database layout as the previous static frontend, so saved records survive the rewrite. */
interface LibrarySchema extends DBSchema {
  records: { key: string; value: RecordMeta };
  payloads: { key: string; value: { id: string; blob: Blob } };
  settings: { key: string; value: { key: string; value: string | null } };
}

interface LibraryState {
  phase: "starting" | "ready" | "failed";
  failure: string | null;
  metas: RecordMeta[];
  current: LoadedRecord | null;
  /** Record whose payload is being decompressed and validated. */
  loadingId: string | null;
  busy: boolean;
}

export const useLibrary = create<LibraryState>(() => ({
  phase: "starting",
  failure: null,
  metas: [],
  current: null,
  loadingId: null,
  busy: false,
}));

const worker = Comlink.wrap<LibraryWorkerApi>(new LibraryWorker());
let databasePromise: Promise<IDBPDatabase<LibrarySchema>> | undefined;
let queue: Promise<unknown> = Promise.resolve();
let pending = 0;
let selectionEpoch = 0;

function database() {
  databasePromise ??= (async () => {
    requireValue(typeof indexedDB !== "undefined", "此浏览器不支持 IndexedDB，无法持久保存问题。");
    requireValue(typeof CompressionStream !== "undefined" && typeof DecompressionStream !== "undefined", "此浏览器不支持 gzip 压缩存储，请使用新版浏览器。");
    return openDB<LibrarySchema>("npuzzle-library", 1, {
      upgrade(db) {
        db.createObjectStore("records", { keyPath: "id" });
        db.createObjectStore("payloads", { keyPath: "id" });
        db.createObjectStore("settings", { keyPath: "key" });
      },
      blocked() {
        toast.error("本地问题库被其他页面占用，请关闭其他页面后重试。");
      },
      blocking() {
        void databasePromise?.then((db) => db.close());
        databasePromise = undefined;
        toast.error("本地问题库已在其他页面升级，请刷新页面。");
      },
    });
  })().catch((error) => {
    databasePromise = undefined;
    throw error;
  });
  return databasePromise;
}

const sortMetas = (metas: RecordMeta[]) => metas.sort((a, b) => a.importedAt - b.importedAt || a.id.localeCompare(b.id));

async function loadRecord(db: IDBPDatabase<LibrarySchema>, meta: RecordMeta): Promise<LoadedRecord> {
  const payload = await db.get("payloads", meta.id);
  requireValue(payload?.blob instanceof Blob, `“${meta.name}”的压缩数据丢失。`);
  const details = await worker.load(payload.blob, meta);
  return { ...details, id: meta.id, name: meta.name };
}

/** Runs after every queued library change so concurrent writes never interleave. */
function serial<T>(action: () => Promise<T>): Promise<T> {
  const result = queue.then(() => ready).then(action);
  queue = result.catch(() => {});
  return result;
}

/** Serialised mutation; failures surface as a toast and still reject for the caller. */
function mutate<T>(action: () => Promise<T>): Promise<T> {
  pending++;
  useLibrary.setState({ busy: true });
  return serial(action)
    .catch((error: Error) => {
      toast.error(error.message || "本地问题库操作失败。");
      throw error;
    })
    .finally(() => {
      pending--;
      useLibrary.setState({ busy: pending > 0, loadingId: null });
    });
}

const ready = (async () => {
  try {
    const db = await database();
    const [stored, setting] = await Promise.all([db.getAll("records"), db.get("settings", "active")]);
    requireValue(stored.every(isValidMeta), "本地问题库元数据损坏。请检查浏览器存储。");
    const metas = sortMetas(stored);
    const active = metas.find((meta) => meta.id === setting?.value) ?? metas[0];
    useLibrary.setState({ metas, loadingId: active?.id ?? null });
    const current = active ? await loadRecord(db, active) : null;
    if (active && active.id !== setting?.value) await db.put("settings", { key: "active", value: active.id });
    useLibrary.setState({ phase: "ready", current, loadingId: null });
  } catch (error) {
    const message = `无法恢复本地问题库：${(error as Error).message}`;
    useLibrary.setState({ phase: "failed", failure: message, loadingId: null });
    toast.error(message);
  }
})();

export function importFiles(files: File[]) {
  return mutate(async () => {
    const toastId = toast.loading("正在读取导入文件…");
    try {
      const db = await database();
      const { items, first } = await worker.importFiles(
        files,
        Comlink.proxy((message: string) => void toast.loading(message, { id: toastId })),
      );
      const importedAt = Date.now();
      const metas = items.map((item) => ({ ...item.meta, id: crypto.randomUUID(), importedAt }));
      toast.loading("正在保存压缩问题库…", { id: toastId });
      const tx = db.transaction(["records", "payloads", "settings"], "readwrite");
      await Promise.all([
        ...metas.flatMap((meta, index) => [
          tx.objectStore("records").add(meta),
          tx.objectStore("payloads").add({ id: meta.id, blob: items[index].blob }),
        ]),
        tx.objectStore("settings").put({ key: "active", value: metas[0].id }),
        tx.done,
      ]);
      selectionEpoch++;
      useLibrary.setState((state) => ({
        metas: sortMetas([...state.metas, ...metas]),
        current: { ...(first as RecordDetails), id: metas[0].id, name: metas[0].name },
      }));
      toast.success(`已导入 ${metas.length} 条完整问题记录`, { id: toastId, description: "已压缩保存在此浏览器中。" });
      return metas[0].id;
    } catch (error) {
      toast.dismiss(toastId);
      throw error;
    }
  });
}

export function selectRecord(id: string) {
  return mutate(async () => {
    const { current, metas } = useLibrary.getState();
    if (current?.id === id) return;
    const meta = metas.find((item) => item.id === id);
    requireValue(meta, "此问题记录已不存在。");
    const epoch = ++selectionEpoch;
    useLibrary.setState({ loadingId: id });
    const db = await database();
    const record = await loadRecord(db, meta);
    if (epoch !== selectionEpoch) return;
    await db.put("settings", { key: "active", value: id });
    useLibrary.setState({ current: record });
  });
}

export function renameRecord(id: string, name: string) {
  return mutate(async () => {
    const previous = useLibrary.getState().metas.find((item) => item.id === id);
    requireValue(previous, "此问题记录已不存在。");
    const next = { ...previous, name: nameOf(name) };
    if (next.name === previous.name) return;
    const db = await database();
    await db.put("records", next);
    useLibrary.setState((state) => ({
      metas: state.metas.map((item) => (item.id === id ? next : item)),
      current: state.current?.id === id ? { ...state.current, name: next.name } : state.current,
    }));
    toast.success(`已重命名为“${next.name}”`);
  });
}

export function removeRecords(ids: string[]) {
  const requested = [...new Set(ids)];
  return mutate(async () => {
    const { metas, current } = useLibrary.getState();
    requireValue(requested.length > 0, "请先选择要删除的问题。");
    requireValue(requested.every((id) => metas.some((item) => item.id === id)), "待删除的记录已发生变化，请重新选择。");
    const removed = new Set(requested);
    const remaining = metas.filter((item) => !removed.has(item.id));
    const changeActive = !current || removed.has(current.id);
    const nextMeta = changeActive ? remaining[0] : undefined;
    const epoch = ++selectionEpoch;
    const db = await database();
    if (nextMeta) useLibrary.setState({ loadingId: nextMeta.id });
    const nextRecord = nextMeta ? await loadRecord(db, nextMeta) : null;
    requireValue(epoch === selectionEpoch, "所选问题已发生变化，请重试。");
    const tx = db.transaction(["records", "payloads", "settings"], "readwrite");
    await Promise.all([
      ...requested.flatMap((id) => [tx.objectStore("records").delete(id), tx.objectStore("payloads").delete(id)]),
      ...(changeActive ? [tx.objectStore("settings").put({ key: "active", value: nextMeta?.id ?? null })] : []),
      tx.done,
    ]);
    useLibrary.setState((state) => ({
      metas: remaining,
      current: changeActive ? nextRecord : state.current,
    }));
    toast.success(`已删除 ${requested.length} 条问题记录`);
  });
}

let previewsRunning = false;

/** Backfills start-board previews for records imported before metadata stored them. */
export async function ensurePreviews() {
  if (previewsRunning) return;
  previewsRunning = true;
  try {
    await ready;
    const db = await database();
    for (const meta of useLibrary.getState().metas) {
      if (meta.initial) continue;
      try {
        const payload = await db.get("payloads", meta.id);
        requireValue(payload?.blob instanceof Blob, "压缩数据丢失。");
        const initial = await worker.initialBoard(payload.blob, meta.size);
        await serial(async () => {
          const latest = useLibrary.getState().metas.find((item) => item.id === meta.id);
          if (!latest) return;
          const next = { ...latest, initial };
          await db.put("records", next);
          useLibrary.setState((state) => ({ metas: state.metas.map((item) => (item.id === meta.id ? next : item)) }));
        });
      } catch (error) {
        toast.error(`无法加载“${meta.name}”的棋盘预览：${(error as Error).message}`);
      }
    }
  } finally {
    previewsRunning = false;
  }
}
