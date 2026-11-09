import { beforeEach, describe, expect, it, vi } from "vitest";
import { compactRecord } from "@/data/compact";
import type { LoadedRecord, RawRecord, RecordMeta } from "@/data/types";
import { validateRecord } from "@/data/validate";
import astar from "./fixtures/astar.json";

const mocks = vi.hoisted(() => ({
  repository: { read: vi.fn(), load: vi.fn(), add: vi.fn(), setActive: vi.fn(), saveMeta: vi.fn(), remove: vi.fn() },
  constructWorker: vi.fn(), importFiles: vi.fn(),
  toast: { error: vi.fn(), success: vi.fn(), loading: vi.fn(), dismiss: vi.fn() },
}));
vi.mock("@/data/library-repository", async (original) => ({
  ...await original<typeof import("@/data/library-repository")>(),
  createLibraryRepository: () => mocks.repository,
}));
vi.mock("@/data/library.worker.ts?worker&inline", () => ({
  default: class { constructor() { mocks.constructWorker(); } },
}));
vi.mock("comlink", () => ({ wrap: () => ({ importFiles: mocks.importFiles }), proxy: (value: unknown) => value }));
vi.mock("sonner", () => ({ toast: mocks.toast }));

const details = compactRecord(validateRecord(astar.results[0] as RawRecord));
function meta(id: string): RecordMeta {
  return {
    schemaVersion: 3, id, name: id, size: 3, algorithm: details.algorithm, heuristic: details.heuristic,
    moves: details.states.length - 1, generated: details.generated, expanded: details.expanded,
    timeMs: details.timeMs, importedAt: 0, compressedBytes: 100, initial: details.states[0],
  };
}
function loaded(id: string): LoadedRecord { return { ...details, id, name: id }; }

beforeEach(() => {
  vi.resetModules();
  vi.clearAllMocks();
  for (const method of Object.values(mocks.repository)) method.mockReset();
  mocks.repository.read.mockResolvedValue({ metas: [meta("one"), meta("two")], activeId: "one" });
  mocks.repository.load.mockImplementation(async (item: RecordMeta) => loaded(item.id));
});

describe("library application adapter", () => {
  it("has no import-time Worker or persistence effects and starts idempotently", async () => {
    const library = await import("@/data/library");
    expect(mocks.constructWorker).not.toHaveBeenCalled();
    expect(mocks.repository.read).not.toHaveBeenCalled();
    const first = library.startLibrary();
    expect(library.startLibrary()).toBe(first);
    await first;
    expect(mocks.repository.read).toHaveBeenCalledOnce();
    expect(library.useLibrary.getState()).toMatchObject({ phase: "ready", current: { id: "one" }, busy: false });
  });

  it("serializes mutations, retains busy state, and recovers the queue after a rejected write", async () => {
    const library = await import("@/data/library");
    await library.startLibrary();
    let fail!: (error: Error) => void;
    const write = new Promise<void>((_, reject) => { fail = reject; });
    let start!: () => void;
    const started = new Promise<void>((resolve) => { start = resolve; });
    mocks.repository.saveMeta.mockImplementationOnce(() => { start(); return write; });
    const rename = library.renameRecord("one", "new name");
    const rejected = expect(rename).rejects.toThrow("disk full");
    const select = library.selectRecord("two");
    await started;
    expect(library.useLibrary.getState().busy).toBe(true);
    expect(mocks.repository.load).toHaveBeenCalledTimes(1);
    expect(library.useLibrary.getState().current?.name).toBe("one");
    fail(new Error("disk full"));
    await rejected;
    await select;
    expect(mocks.repository.setActive).toHaveBeenCalledWith("two");
    expect(library.useLibrary.getState()).toMatchObject({ current: { id: "two" }, busy: false, loadingId: null });
    expect(mocks.toast.error).toHaveBeenCalledWith("disk full");
  });

  it("does not delete the current record if the replacement cannot be loaded", async () => {
    const library = await import("@/data/library");
    await library.startLibrary();
    mocks.repository.load.mockRejectedValueOnce(new Error("damaged replacement"));
    await expect(library.removeRecords(["one"])).rejects.toThrow("damaged replacement");
    expect(mocks.repository.remove).not.toHaveBeenCalled();
    expect(library.useLibrary.getState()).toMatchObject({ current: { id: "one" }, busy: false, loadingId: null });
    expect(library.useLibrary.getState().metas).toHaveLength(2);
  });

  it("recovers a failed startup through a successful import and starts the Worker on demand", async () => {
    mocks.repository.read.mockRejectedValueOnce(new Error("unavailable"));
    mocks.importFiles.mockResolvedValueOnce({ items: [{ meta: meta("new"), blob: new Blob(["payload"]) }], first: details });
    const library = await import("@/data/library");
    await library.startLibrary();
    expect(library.useLibrary.getState().phase).toBe("failed");
    expect(mocks.constructWorker).not.toHaveBeenCalled();
    const id = await library.importFiles([]);
    expect(mocks.constructWorker).toHaveBeenCalledOnce();
    expect(mocks.repository.add).toHaveBeenCalledOnce();
    expect(library.useLibrary.getState()).toMatchObject({ phase: "ready", failure: null, current: { id }, busy: false });
  });
});
