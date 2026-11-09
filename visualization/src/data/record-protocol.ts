import { SCHEMA_VERSION, type RawRecord } from "./types";
import { requireValue, validateRecord } from "./validate";

/** Every new compressed record is self-describing, independent of its original export file. */
export async function encodePayload(record: RawRecord): Promise<Blob> {
  const text = JSON.stringify({ schemaVersion: SCHEMA_VERSION, record });
  return new Response(new Blob([text]).stream().pipeThrough(new CompressionStream("gzip"))).blob();
}

/** Decode the current per-record contract; protocol mismatches never rewrite stored data. */
export async function decodePayload(blob: Blob) {
  const text = await new Response(blob.stream().pipeThrough(new DecompressionStream("gzip"))).text();
  const value = JSON.parse(text);
  requireValue(value && typeof value === "object" && !Array.isArray(value), "记录数据必须是对象。");
  requireValue(value.schemaVersion === SCHEMA_VERSION, "不支持的记录协议版本。");
  return validateRecord(value.record);
}
