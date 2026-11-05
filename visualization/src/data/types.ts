export const ALGORITHMS = ["A*", "Dijkstra", "IDA*", "NIDA*"] as const;
export type Algorithm = (typeof ALGORITHMS)[number];

export const HEURISTICS = ["MANHATTAN", "MISPLACED", "DISJOINT_PATTERN", "ZERO"] as const;
export type Heuristic = (typeof HEURISTICS)[number];

/** Index in this list is the compact status code; codes below FIRST_PRUNED are accepted nodes. */
export const STATUSES = [
  "frontier",
  "replaced",
  "closed-pruned",
  "frontier-pruned",
  "cycle-pruned",
  "bound-pruned",
  "transposition-pruned",
] as const;
export type NodeStatus = (typeof STATUSES)[number];
export const FIRST_PRUNED = 2;
export const STATUS_CODE = Object.fromEntries(STATUSES.map((status, code) => [status, code])) as Record<NodeStatus, number>;

export interface RawNode {
  id: number;
  parent: number | null;
  board: number[];
  g: number;
  h: number;
  f: number;
  status: NodeStatus;
  reference?: number;
}

export interface RawFrame {
  current: number;
  children: number[];
  found: boolean;
  iteration: number;
  bound?: number;
}

/** One entry of `results` in a schemaVersion 2 Java export. */
export interface RawRecord {
  name?: string | null;
  algorithm: Algorithm;
  heuristic: Heuristic;
  states: number[][];
  goal: number[];
  timeMs: number;
  generated: number;
  expanded: number;
  trace: { complete: true; nodes: RawNode[]; frames: RawFrame[]; solution: number[] };
  sourceFile?: string;
  exportedAt?: string;
  environment?: string;
}

/** Persisted in the `records` object store; `initial` is absent on records imported by the previous frontend. */
export interface RecordMeta {
  id: string;
  name: string;
  size: 3 | 4;
  algorithm: Algorithm;
  heuristic: Heuristic;
  moves: number;
  generated: number;
  expanded: number;
  timeMs: number;
  importedAt: number;
  compressedBytes: number;
  initial?: number[];
}

/**
 * Typed-array view of a validated trace. Node ids are generation order, so the nodes
 * shown at any moment of the replay form one contiguous id range.
 */
export interface CompactTrace {
  size: number;
  cells: number;
  nodeCount: number;
  frameCount: number;
  depthFirst: boolean;
  pathCostOnly: boolean;
  boards: Uint8Array;
  parent: Int32Array;
  g: Int32Array;
  h: Int32Array;
  status: Uint8Array;
  reference: Int32Array;
  /** Frame index that expanded the node, or -1. */
  visitFrame: Int32Array;
  /** Node that replaced this one in the frontier, or -1. */
  replacedBy: Int32Array;
  frameCurrent: Int32Array;
  frameChildStart: Int32Array;
  frameChildCount: Uint8Array;
  frameIteration: Int32Array;
  /** IDA* threshold of the frame, or -1. */
  frameBound: Int32Array;
  /** Root node id of each iteration. */
  roots: Int32Array;
  iterationFirstFrame: Int32Array;
  /** Shot index of each frame's "pick" shot; shot 0 is the initial "ready" shot. */
  shotStart: Int32Array;
  shotCount: number;
  /** prunedPrefix[i] = pruned nodes among ids < i. */
  prunedPrefix: Int32Array;
  /** Smallest f among bound-pruned nodes from the iteration root up to id (inclusive), or -1. */
  boundMinF: Int32Array;
  boundMinId: Int32Array;
  solution: Int32Array;
}

export interface RecordDetails {
  algorithm: Algorithm;
  heuristic: Heuristic;
  size: number;
  goal: number[];
  states: number[][];
  timeMs: number;
  generated: number;
  expanded: number;
  sourceFile?: string;
  exportedAt?: string;
  environment?: string;
  trace: CompactTrace;
}

export interface LoadedRecord extends RecordDetails {
  id: string;
  name: string;
}
