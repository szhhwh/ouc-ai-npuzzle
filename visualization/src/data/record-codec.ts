import { compactRecord } from "./compact";
import { SCHEMA_VERSION, type RecordDetails, type RecordMeta } from "./types";
import { DEFAULT_NAME } from "./naming";
import { decodePayload, encodePayload } from "./record-protocol";
import { requireValue, validateRecord } from "./validate";

export interface ImportedItem {
  meta: Omit<RecordMeta, "id" | "importedAt">;
  blob: Blob;
}

export interface ImportResult {
  items: ImportedItem[];
  /** Details of the first imported record, so the caller can show it without decompressing again. */
  first: RecordDetails;
}

export const recordCodec = {
  async importFiles(files: File[], progress: (message: string) => void): Promise<ImportResult> {
    requireValue(files.length > 0, "请选择至少一个 JSON 文件。");
    const items: ImportedItem[] = [];
    let first: RecordDetails | undefined;
    for (const [fileIndex, file] of files.entries()) {
      const label = file.name || "文件";
      progress(`正在读取 ${label} · ${fileIndex + 1} / ${files.length}`);
      let artifact: { schemaVersion?: unknown; results?: unknown[] };
      try {
        artifact = JSON.parse(await file.text());
      } catch (error) {
        throw new Error(`无法读取 ${label}：${(error as Error).message}`);
      }
      requireValue(
        artifact?.schemaVersion === SCHEMA_VERSION && Array.isArray(artifact.results) && artifact.results.length > 0,
        `${label} 格式不符合要求，请提供包含完整搜索记录的 JSON 文件。`,
      );
      for (const [index, raw] of artifact.results.entries()) {
        progress(`正在解析 ${label} 的第 ${index + 1} / ${artifact.results.length} 条记录`);
        let record;
        try {
          record = validateRecord(raw);
        } catch (error) {
          throw new Error(`${label}，记录 ${index + 1}：${(error as Error).message}`);
        }
        first ??= compactRecord(record);
        const blob = await encodePayload(record);
        items.push({
          blob,
          meta: {
            schemaVersion: SCHEMA_VERSION,
            name: DEFAULT_NAME,
            size: Math.sqrt(record.goal.length) as 3 | 4,
            algorithm: record.algorithm,
            heuristic: record.heuristic,
            moves: record.states.length - 1,
            generated: record.generated,
            expanded: record.expanded,
            timeMs: record.timeMs,
            compressedBytes: blob.size,
            initial: record.states[0],
          },
        });
      }
    }
    return { items, first: first! };
  },

  async load(blob: Blob, meta: RecordMeta): Promise<RecordDetails> {
    let details: RecordDetails;
    try {
      details = compactRecord(await decodePayload(blob));
    } catch (error) {
      throw new Error(`无法加载“${meta.name}”：${(error as Error).message}`);
    }
    requireValue(
      meta.size === details.size &&
        meta.algorithm === details.algorithm &&
        meta.heuristic === details.heuristic &&
        meta.moves === details.states.length - 1 &&
        meta.generated === details.generated &&
        meta.expanded === details.expanded &&
        meta.timeMs === details.timeMs &&
        meta.compressedBytes === blob.size,
      `“${meta.name}”的数据校验未通过。`,
    );
    return details;
  },
};
