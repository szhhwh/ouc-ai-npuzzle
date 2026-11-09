import { describe, expect, it } from "vitest";
import { recordCodec } from "@/data/record-codec";
import { decodePayload, encodePayload } from "@/data/record-protocol";
import { isValidMeta } from "@/data/validate";
import type { RawRecord, RecordMeta } from "@/data/types";
import astar from "./fixtures/astar.json";
import ida from "./fixtures/ida.json";
import nida from "./fixtures/nida.json";

const record = {
  ...astar.results[0], source: "Java", sourceRevision: "bfc630c5", timing: "wall-single-search-with-recording",
} as RawRecord;

async function compressed(value: unknown) {
  return new Response(new Blob([JSON.stringify(value)]).stream().pipeThrough(new CompressionStream("gzip"))).blob();
}

async function imported(raw: RawRecord = record) {
  const file = new File([JSON.stringify({ schemaVersion: 3, results: [raw] })], "search.json");
  const result = await recordCodec.importFiles([file], () => {});
  const item = result.items[0];
  const meta: RecordMeta = { ...item.meta, id: "saved", importedAt: 100 };
  return { ...item, meta, first: result.first };
}

// Covers the persisted wire contract without constructing Workers, React state, or IndexedDB.
describe("record protocol and codec", () => {
  it("saves self-describing payloads and metadata and retains export provenance", async () => {
    const { blob, meta, first } = await imported();
    const json = JSON.parse(await new Response(blob.stream().pipeThrough(new DecompressionStream("gzip"))).text());
    expect(json.schemaVersion).toBe(3);
    expect(meta.schemaVersion).toBe(3);
    expect(meta.initial).toEqual(record.states[0]);
    expect(isValidMeta(meta)).toBe(true);
    const decoded = await decodePayload(blob);
    expect(decoded).toMatchObject(record);
    const loaded = await recordCodec.load(blob, meta);
    expect(loaded).toMatchObject({
      source: record.source, sourceRevision: record.sourceRevision, timing: record.timing,
      sourceFile: record.sourceFile, exportedAt: record.exportedAt, environment: record.environment,
    });
    expect(loaded.trace.boards).toBeInstanceOf(Uint8Array);
    expect(loaded.trace.parent).toBeInstanceOf(Int32Array);
    expect(loaded.trace.boards).toEqual(first.trace.boards);
    expect(loaded.states).toEqual(record.states);
  });

  it("accepts current schemaVersion 3 exports for every existing algorithm label", async () => {
    for (const raw of [...astar.results, ...ida.results, ...nida.results] as RawRecord[]) {
      const { blob, meta } = await imported(raw);
      expect((await recordCodec.load(blob, meta)).algorithm).toBe(raw.algorithm);
    }
    const dijkstra = { ...record, algorithm: "Dijkstra" as const };
    expect((await imported(dijkstra)).first.algorithm).toBe("Dijkstra");
  });

  it("rejects unversioned and unsupported payload contracts without modifying their blobs", async () => {
    for (const value of [record, { schemaVersion: 4, record }, { schemaVersion: 3, results: [record] }]) {
      const blob = await compressed(value);
      const before = await blob.arrayBuffer();
      await expect(decodePayload(blob)).rejects.toThrow();
      expect(await blob.arrayBuffer()).toEqual(before);
    }
    const { meta } = await imported();
    expect(isValidMeta({ ...meta, schemaVersion: undefined } as unknown as RecordMeta)).toBe(false);
    expect(isValidMeta({ ...meta, schemaVersion: 4 } as unknown as RecordMeta)).toBe(false);
    expect(isValidMeta({ ...meta, initial: undefined } as unknown as RecordMeta)).toBe(false);
  });

  it("rejects unsupported imports, damaged compression and mismatched metadata", async () => {
    for (const schemaVersion of [2, 4]) {
      const file = new File([JSON.stringify({ schemaVersion, results: [record] })], "unsupported.json");
      await expect(recordCodec.importFiles([file], () => {})).rejects.toThrow("格式不符合要求");
    }
    const { blob, meta } = await imported();
    await expect(recordCodec.load(new Blob(["damaged"]), meta)).rejects.toThrow("无法加载");
    await expect(recordCodec.load(blob, { ...meta, expanded: meta.expanded + 1 })).rejects.toThrow("校验未通过");
    await expect(recordCodec.load(blob, { ...meta, compressedBytes: blob.size + 1 })).rejects.toThrow("校验未通过");
    expect(await decodePayload(await encodePayload(record))).toMatchObject(record);
  });
});
