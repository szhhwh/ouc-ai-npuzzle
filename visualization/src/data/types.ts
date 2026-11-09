export const ALGORITHMS = ["A*", "Dijkstra", "IDA*", "NIDA*"] as const;
export type Algorithm = (typeof ALGORITHMS)[number];

/** Replay behavior is separate from the stable algorithm labels in exports. */
export const ALGORITHM_TRAITS = {
  "A*": { depthFirst: false, pathCostOnly: false, parityAlignedBound: false },
  Dijkstra: { depthFirst: false, pathCostOnly: true, parityAlignedBound: false },
  "IDA*": { depthFirst: true, pathCostOnly: false, parityAlignedBound: false },
  "NIDA*": { depthFirst: true, pathCostOnly: false, parityAlignedBound: true },
} as const satisfies Record<Algorithm, { depthFirst: boolean; pathCostOnly: boolean; parityAlignedBound: boolean }>;

export const SCHEMA_VERSION = 3 as const;

export const HEURISTICS = ["MANHATTAN", "MISPLACED", "DISJOINT_PATTERN", "ZERO"] as const;
export type Heuristic = (typeof HEURISTICS)[number];

/** Index in this list is the stable compact status code. */
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
/** Explicit categories keep compact codes independent of their numeric ordering. */
export const STATUS_CATEGORY = {
  frontier: "accepted",
  replaced: "accepted",
  "closed-pruned": "pruned",
  "frontier-pruned": "pruned",
  "cycle-pruned": "pruned",
  "bound-pruned": "pruned",
  "transposition-pruned": "pruned",
} as const satisfies Record<NodeStatus, "accepted" | "pruned">;
const PRUNED_CODES = Uint8Array.from(STATUSES, (status) => Number(STATUS_CATEGORY[status] === "pruned"));
export const isPrunedStatus = (code: number) => PRUNED_CODES[code] === 1;
export const STATUS_CODE = Object.fromEntries(STATUSES.map((status, code) => [status, code])) as Record<NodeStatus, number>;

export const HEURISTIC_QUALITIES = ["exact", "lower-bound", "unevaluated"] as const;
export type HeuristicQuality = (typeof HEURISTIC_QUALITIES)[number];
export const HEURISTIC_QUALITY_CODE = { exact: 0, "lower-bound": 1, unevaluated: 2 } as const;

export interface RawNode {
  id: number;
  parent: number | null;
  board: number[];
  g: number;
  h: number | null;
  f: number | null;
  heuristicQuality: HeuristicQuality;
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

/** One entry of `results` in a schemaVersion 3 Java export. */
export interface RawRecord {
  algorithm: Algorithm;
  heuristic: Heuristic;
  states: number[][];
  goal: number[];
  timeMs: number;
  generated: number;
  expanded: number;
  trace: { complete: true; nodes: RawNode[]; frames: RawFrame[]; solution: number[] };
  source?: string;
  sourceRevision?: string;
  timing?: string;
  sourceFile?: string;
  exportedAt?: string;
  environment?: string;
}

/** Current metadata persisted in the `records` object store. */
export interface RecordMeta {
  schemaVersion: typeof SCHEMA_VERSION;
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
  initial: number[];
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
  /** -1 represents an unevaluated (null) estimate; quality is stored independently. */
  h: Int32Array;
  heuristicQuality: Uint8Array;
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
  source?: string;
  sourceRevision?: string;
  timing?: string;
  sourceFile?: string;
  exportedAt?: string;
  environment?: string;
  trace: CompactTrace;
}

export interface LoadedRecord extends RecordDetails {
  id: string;
  name: string;
}
