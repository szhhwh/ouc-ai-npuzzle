import * as Comlink from "comlink";
import { traceBuffers } from "./compact";
import { recordCodec } from "./record-codec";

const api = {
  async importFiles(...args: Parameters<typeof recordCodec.importFiles>) {
    const result = await recordCodec.importFiles(...args);
    return Comlink.transfer(result, traceBuffers(result.first.trace));
  },
  async load(...args: Parameters<typeof recordCodec.load>) {
    const result = await recordCodec.load(...args);
    return Comlink.transfer(result, traceBuffers(result.trace));
  },
};

export type LibraryWorkerApi = typeof api;
Comlink.expose(api);
