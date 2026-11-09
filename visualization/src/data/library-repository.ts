import { openDB, type DBSchema, type IDBPDatabase } from "idb";
import type { ImportedItem } from "./record-codec";
import { SCHEMA_VERSION, type LoadedRecord, type RecordDetails, type RecordMeta } from "./types";
import { isValidMeta, requireValue } from "./validate";

/** Stable storage location; invalid records are reported without deleting user data. */
export interface LibrarySchema extends DBSchema {
  records: { key: string; value: RecordMeta };
  payloads: { key: string; value: { id: string; schemaVersion: typeof SCHEMA_VERSION; blob: Blob } };
  settings: { key: string; value: { key: string; value: string | null } };
}

interface DatabaseEvents {
  blocked(): void;
  blocking(): void;
}

/** Opening and recovery are explicit; importing this module never touches browser APIs. */
export function createLibraryDatabase(events: DatabaseEvents) {
  let promise: Promise<IDBPDatabase<LibrarySchema>> | undefined;
  return function database() {
    promise ??= (async () => {
      requireValue(typeof indexedDB !== "undefined", "当前浏览器不支持本地数据库，无法持久化保存记录。");
      requireValue(typeof CompressionStream !== "undefined" && typeof DecompressionStream !== "undefined", "当前浏览器版本过低，请升级至现代浏览器后使用。");
      return openDB<LibrarySchema>("npuzzle-library-v3", 1, {
        upgrade(db) {
          db.createObjectStore("records", { keyPath: "id" });
          db.createObjectStore("payloads", { keyPath: "id" });
          db.createObjectStore("settings", { keyPath: "key" });
        },
        blocked: events.blocked,
        blocking() {
          void promise?.then((db) => db.close());
          promise = undefined;
          events.blocking();
        },
      });
    })().catch((error) => {
      promise = undefined;
      throw error;
    });
    return promise;
  };
}

export const sortMetas = (metas: RecordMeta[]) => metas.sort((a, b) => a.importedAt - b.importedAt || a.id.localeCompare(b.id));

interface RecordLoader {
  load(blob: Blob, meta: RecordMeta): Promise<RecordDetails>;
}

/** Persistence has no dependency on app state or notifications; its caller serializes mutations. */
export function createLibraryRepository(database: () => Promise<IDBPDatabase<LibrarySchema>>, loader: () => RecordLoader) {
  return {
    async read() {
      const db = await database();
      const [stored, setting] = await Promise.all([db.getAll("records"), db.get("settings", "active")]);
      requireValue(stored.every(isValidMeta), "本地记录数据损坏，请尝试重新导入。");
      return { metas: sortMetas(stored), activeId: setting?.value };
    },

    async load(meta: RecordMeta): Promise<LoadedRecord> {
      const db = await database();
      const payload = await db.get("payloads", meta.id);
      requireValue(payload?.blob instanceof Blob, `“${meta.name}”的记录数据损坏或不存在。`);
      requireValue(payload.schemaVersion === SCHEMA_VERSION, "不支持的记录协议版本。");
      const details = await loader().load(payload.blob, meta);
      return { ...details, id: meta.id, name: meta.name };
    },

    async add(metas: RecordMeta[], items: ImportedItem[]) {
      const db = await database();
      const tx = db.transaction(["records", "payloads", "settings"], "readwrite");
      await Promise.all([
        ...metas.flatMap((meta, index) => [
          tx.objectStore("records").add(meta),
          tx.objectStore("payloads").add({ id: meta.id, schemaVersion: SCHEMA_VERSION, blob: items[index].blob }),
        ]),
        tx.objectStore("settings").put({ key: "active", value: metas[0].id }),
        tx.done,
      ]);
    },

    async setActive(id: string) {
      await (await database()).put("settings", { key: "active", value: id });
    },

    async saveMeta(meta: RecordMeta) {
      await (await database()).put("records", { ...meta, schemaVersion: SCHEMA_VERSION });
    },

    async remove(ids: string[], activeId: string | null | undefined) {
      const db = await database();
      const tx = db.transaction(["records", "payloads", "settings"], "readwrite");
      await Promise.all([
        ...ids.flatMap((id) => [tx.objectStore("records").delete(id), tx.objectStore("payloads").delete(id)]),
        ...(activeId !== undefined ? [tx.objectStore("settings").put({ key: "active", value: activeId })] : []),
        tx.done,
      ]);
    },
  };
}
