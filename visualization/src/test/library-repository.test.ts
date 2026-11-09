import type { IDBPDatabase } from "idb";
import { afterEach, describe, expect, it, vi } from "vitest";
import { createLibraryDatabase, createLibraryRepository, type LibrarySchema } from "@/data/library-repository";
import { recordCodec } from "@/data/record-codec";
import type { RecordMeta } from "@/data/types";
import astar from "./fixtures/astar.json";

const { open } = vi.hoisted(() => ({ open: vi.fn() }));
vi.mock("idb", () => ({ openDB: open }));
afterEach(() => {
  vi.unstubAllGlobals();
  open.mockReset();
});

async function savedRecord() {
  const { items } = await recordCodec.importFiles([new File([JSON.stringify(astar)], "search.json")], () => {});
  const item = items[0];
  return { item, meta: { ...item.meta, id: "saved", importedAt: 1 } satisfies RecordMeta };
}

describe("library repository", () => {
  it("opens lazily, shares an in-flight connection, and reopens after another tab upgrades", async () => {
    vi.stubGlobal("indexedDB", {});
    const close = vi.fn();
    open.mockResolvedValue({ close });
    const events = { blocked: vi.fn(), blocking: vi.fn() };
    const database = createLibraryDatabase(events);
    expect(open).not.toHaveBeenCalled();
    const first = database();
    expect(database()).toBe(first);
    await first;
    expect(open.mock.calls[0][0]).toBe("npuzzle-library-v3");
    const callbacks = open.mock.calls[0][2];
    callbacks.blocked();
    expect(events.blocked).toHaveBeenCalledOnce();
    callbacks.blocking();
    await Promise.resolve();
    expect(close).toHaveBeenCalledOnce();
    expect(events.blocking).toHaveBeenCalledOnce();
    await database();
    expect(open).toHaveBeenCalledTimes(2);
  });

  it("retries failed database opens", async () => {
    vi.stubGlobal("indexedDB", {});
    open.mockRejectedValueOnce(new Error("temporarily unavailable")).mockResolvedValueOnce({});
    const database = createLibraryDatabase({ blocked() {}, blocking() {} });
    await expect(database()).rejects.toThrow("temporarily unavailable");
    await expect(database()).resolves.toEqual({});
    expect(open).toHaveBeenCalledTimes(2);
  });

  it("reads and loads current data without writes and rejects incompatible data without deleting it", async () => {
    const { item, meta } = await savedRecord();
    const getAll = vi.fn().mockResolvedValue([meta]);
    const get = vi.fn().mockImplementation((store: string) => Promise.resolve(store === "settings"
      ? { key: "active", value: meta.id }
      : { id: meta.id, schemaVersion: 3, blob: item.blob }));
    const transaction = vi.fn();
    const put = vi.fn();
    const database = { getAll, get, transaction, put } as unknown as IDBPDatabase<LibrarySchema>;
    const worker = vi.fn(() => recordCodec);
    const repository = createLibraryRepository(async () => database, worker);
    expect(worker).not.toHaveBeenCalled();
    expect(await repository.read()).toEqual({ metas: [meta], activeId: meta.id });
    expect(worker).not.toHaveBeenCalled();
    expect(await repository.load(meta)).toMatchObject({ id: meta.id, name: meta.name, states: astar.results[0].states });
    getAll.mockResolvedValueOnce([{ ...meta, schemaVersion: undefined }]);
    await expect(repository.read()).rejects.toThrow("本地记录数据损坏");
    get.mockResolvedValueOnce({ id: meta.id, blob: item.blob });
    await expect(repository.load(meta)).rejects.toThrow("协议版本");
    expect(transaction).not.toHaveBeenCalled();
    expect(put).not.toHaveBeenCalled();
  });

  it("waits for the shared import transaction before resolving and includes payload versions", async () => {
    const { item, meta } = await savedRecord();
    const writes: Array<{ store: string; value: unknown }> = [];
    let commit!: () => void;
    const done = new Promise<void>((resolve) => { commit = resolve; });
    const transaction = vi.fn(() => ({
      done,
      objectStore: (store: string) => ({
        add: (value: unknown) => { writes.push({ store, value }); return Promise.resolve(); },
        put: (value: unknown) => { writes.push({ store, value }); return Promise.resolve(); },
      }),
    }));
    const database = { transaction } as unknown as IDBPDatabase<LibrarySchema>;
    const repository = createLibraryRepository(async () => database, () => recordCodec);
    let complete = false;
    const imported = repository.add([meta], [item]).then(() => { complete = true; });
    await Promise.resolve();
    await Promise.resolve();
    expect(complete).toBe(false);
    expect(transaction).toHaveBeenCalledWith(["records", "payloads", "settings"], "readwrite");
    expect(writes).toEqual([
      { store: "records", value: meta },
      { store: "payloads", value: { id: meta.id, schemaVersion: 3, blob: item.blob } },
      { store: "settings", value: { key: "active", value: meta.id } },
    ]);
    commit();
    await imported;
    expect(complete).toBe(true);
  });
});
