import * as Comlink from "comlink";
import { toast } from "sonner";
import { create } from "zustand";
import type { LibraryWorkerApi } from "./library.worker";
import LibraryWorker from "./library.worker.ts?worker&inline";
import type { LoadedRecord, RecordMeta } from "./types";
import { nameOf } from "./naming";
import { requireValue } from "./validate";
import { createLibraryDatabase, createLibraryRepository, sortMetas } from "./library-repository";

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

let remote: LibraryWorkerApi | undefined;
const worker = () => (remote ??= Comlink.wrap<LibraryWorkerApi>(new LibraryWorker()));
const repository = createLibraryRepository(createLibraryDatabase({
  blocked: () => void toast.error("本地数据库正被其他标签页使用，请关闭其他页面后重试。"),
  blocking: () => void toast.error("本地数据库已在其他标签页更新，请刷新页面。"),
}), worker);
let queue: Promise<unknown> = Promise.resolve();
let pending = 0;
let selectionEpoch = 0;
let ready: Promise<void> | undefined;

/** Runs after every queued library change so concurrent writes never interleave. */
function serial<T>(action: () => Promise<T>): Promise<T> {
  const result = queue.then(() => startLibrary()).then(action);
  queue = result.catch(() => {});
  return result;
}

/** Serialised mutation; failures surface as a toast and still reject for the caller. */
function mutate<T>(action: () => Promise<T>): Promise<T> {
  pending++;
  useLibrary.setState({ busy: true });
  return serial(action)
    .catch((error: Error) => {
      toast.error(error.message || "记录库操作失败。");
      throw error;
    })
    .finally(() => {
      pending--;
      useLibrary.setState({ busy: pending > 0, loadingId: null });
    });
}

/** Explicit, idempotent startup; importing the library creates no Worker or database connection. */
export function startLibrary(): Promise<void> {
  return ready ??= initialize();
}

async function initialize() {
  try {
    const { metas, activeId } = await repository.read();
    const active = metas.find((meta) => meta.id === activeId) ?? metas[0];
    useLibrary.setState({ metas, loadingId: active?.id ?? null });
    const current = active ? await repository.load(active) : null;
    if (active && active.id !== activeId) await repository.setActive(active.id);
    useLibrary.setState({ phase: "ready", current, loadingId: null });
  } catch (error) {
    const message = `无法读取本地记录：${(error as Error).message}`;
    useLibrary.setState({ phase: "failed", failure: message, loadingId: null });
    toast.error(message);
  }
}

export function importFiles(files: File[]) {
  return mutate(async () => {
    const toastId = toast.loading("正在读取文件…");
    try {
      const { items, first } = await worker().importFiles(
        files,
        Comlink.proxy((message: string) => void toast.loading(message, { id: toastId })),
      );
      const importedAt = Date.now();
      const metas = items.map((item) => ({ ...item.meta, id: crypto.randomUUID(), importedAt }));
      toast.loading("正在保存记录到本地…", { id: toastId });
      await repository.add(metas, items);
      selectionEpoch++;
      useLibrary.setState((state) => ({
        metas: sortMetas([...state.metas, ...metas]),
        phase: "ready",
        failure: null,
        current: { ...first, id: metas[0].id, name: metas[0].name },
      }));
      toast.success(`已成功导入 ${metas.length} 条记录`, { id: toastId, description: "已保存至本地记录库，可随时查看与回放。" });
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
    requireValue(meta, "该记录不存在或已被删除。");
    const epoch = ++selectionEpoch;
    useLibrary.setState({ loadingId: id });
    const record = await repository.load(meta);
    if (epoch !== selectionEpoch) return;
    await repository.setActive(id);
    useLibrary.setState({ current: record });
  });
}

export function renameRecord(id: string, name: string) {
  return mutate(async () => {
    const previous = useLibrary.getState().metas.find((item) => item.id === id);
    requireValue(previous, "该记录不存在或已被删除。");
    const next = { ...previous, name: nameOf(name) };
    if (next.name === previous.name) return;
    await repository.saveMeta(next);
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
    requireValue(requested.length > 0, "请先选择要删除的记录。");
    requireValue(requested.every((id) => metas.some((item) => item.id === id)), "待删除的记录列表已变动，请重新选择。");
    const removed = new Set(requested);
    const remaining = metas.filter((item) => !removed.has(item.id));
    const changeActive = !current || removed.has(current.id);
    const nextMeta = changeActive ? remaining[0] : undefined;
    const epoch = ++selectionEpoch;
    if (nextMeta) useLibrary.setState({ loadingId: nextMeta.id });
    const nextRecord = nextMeta ? await repository.load(nextMeta) : null;
    requireValue(epoch === selectionEpoch, "当前记录状态发生冲突，请重试。");
    await repository.remove(requested, changeActive ? nextMeta?.id ?? null : undefined);
    useLibrary.setState((state) => ({
      metas: state.metas.filter((item) => !removed.has(item.id)),
      current: changeActive ? nextRecord : state.current,
    }));
    toast.success(`已成功删除 ${requested.length} 条记录`);
  });
}
