import * as Comlink from "comlink";
import { compactRecord, traceBuffers } from "./compact";
import type { RecordDetails, RecordMeta } from "./types";
import { requireValue, validateBoard, validateRecord } from "./validate";

export interface ImportedItem {
  meta: Omit<RecordMeta, "id" | "importedAt">;
  blob: Blob;
}

export interface ImportResult {
  items: ImportedItem[];
  /** Details of the first imported record, so the caller can show it without decompressing again. */
  first: RecordDetails;
}

async function gzip(text: string) {
  return new Response(new Blob([text]).stream().pipeThrough(new CompressionStream("gzip"))).blob();
}

async function gunzip(blob: Blob) {
  return new Response(blob.stream().pipeThrough(new DecompressionStream("gzip"))).text();
}

const api = {
  async importFiles(files: File[], progress: (message: string) => void): Promise<ImportResult> {
    requireValue(files.length > 0, "请选择至少一个 JSON 文件。");
    const items: ImportedItem[] = [];
    let first: RecordDetails | undefined;
    for (const [fileIndex, file] of files.entries()) {
      const label = file.name || "文件";
      progress(`正在读取 ${label}（${fileIndex + 1} / ${files.length}）`);
      let artifact: { schemaVersion?: unknown; results?: unknown[] };
      try {
        artifact = JSON.parse(await file.text());
      } catch (error) {
        throw new Error(`无法读取 ${label}：${(error as Error).message}`);
      }
      requireValue(
        artifact?.schemaVersion === 2 && Array.isArray(artifact.results) && artifact.results.length > 0,
        `${label} 必须是包含完整 results 的 schemaVersion: 2 Java 导出文件。`,
      );
      for (const [index, raw] of artifact.results.entries()) {
        progress(`正在校验 ${label} 的第 ${index + 1} / ${artifact.results.length} 条记录`);
        let record;
        try {
          record = validateRecord(raw);
        } catch (error) {
          throw new Error(`${label}，记录 ${index + 1}：${(error as Error).message}`);
        }
        first ??= compactRecord(record);
        const blob = await gzip(JSON.stringify(record));
        items.push({
          blob,
          meta: {
            name: record.name,
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
    return Comlink.transfer({ items, first: first! }, traceBuffers(first!.trace));
  },

  async load(blob: Blob, meta: RecordMeta): Promise<RecordDetails> {
    let details: RecordDetails;
    try {
      details = compactRecord(validateRecord(JSON.parse(await gunzip(blob))));
    } catch (error) {
      throw new Error(`无法解压或读取“${meta.name}”：${(error as Error).message}`);
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
      `“${meta.name}”的本地元数据与压缩记录不一致。`,
    );
    return Comlink.transfer(details, traceBuffers(details.trace));
  },

  /** Reads only the start board, for records saved before metadata carried it. */
  async initialBoard(blob: Blob, size: number): Promise<number[]> {
    const board = JSON.parse(await gunzip(blob)).states?.[0];
    validateBoard(board, size * size, "初始");
    return board;
  },
};

export type LibraryWorkerApi = typeof api;
Comlink.expose(api);
