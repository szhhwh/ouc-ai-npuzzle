import { HEURISTIC_QUALITIES, type CompactTrace } from "./types";

export const qualityOf = (trace: CompactTrace, id: number) => HEURISTIC_QUALITIES[trace.heuristicQuality[id]];
export const hOf = (trace: CompactTrace, id: number): number | null => trace.h[id] < 0 ? null : trace.h[id];
export const fOf = (trace: CompactTrace, id: number): number | null => trace.h[id] < 0 ? null : trace.g[id] + trace.h[id];
