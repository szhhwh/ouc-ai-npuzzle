import { FIRST_PRUNED, type CompactTrace } from "@/data/types";

/**
 * The replay is a sequence of shots: one "ready" shot, then for every Java frame a "pick",
 * one "child" per generated successor and a closing "merge" (or a single "goal" for the final frame).
 */
export type Phase = "ready" | "pick" | "child" | "merge" | "goal";

export interface Scene {
  shot: number;
  round: number;
  phase: Phase;
  /** Index of the successor within the round for "child" shots, else -1. */
  child: number;
  iteration: number;
  /** IDA* threshold of this round, or -1. */
  bound: number;
  /** First node id of the visible tree (the iteration root for IDA*). */
  rangeStart: number;
  /** Nodes with ids below this have been generated. */
  revealedEnd: number;
  /** Ids in [revealedEnd, layoutEnd) are this round's successors not generated yet; the tree reserves their slots. */
  layoutEnd: number;
  /** Successors of this round. */
  childStart: number;
  childEnd: number;
  /** Node being expanded, or -1 before the first pick. */
  current: number;
  /** Node the shot talks about: the picked node, or the successor being generated. */
  focus: number;
  /** Node the search will expand next, or -1; it may start a new iteration and lie outside the range. */
  next: number;
  goal: boolean;
}

export function sceneAt(trace: CompactTrace, shot: number): Scene {
  const clamped = Math.max(0, Math.min(trace.shotCount - 1, shot));
  if (clamped === 0) {
    return {
      shot: 0,
      round: 0,
      phase: "ready",
      child: -1,
      iteration: 0,
      bound: trace.frameBound[0],
      rangeStart: 0,
      revealedEnd: 1,
      layoutEnd: 1,
      childStart: 1,
      childEnd: 1,
      current: -1,
      focus: 0,
      next: 0,
      goal: false,
    };
  }
  // Largest round whose pick shot is at or before the requested shot.
  let lo = 0;
  let hi = trace.frameCount - 1;
  while (lo < hi) {
    const mid = (lo + hi + 1) >> 1;
    if (trace.shotStart[mid] <= clamped) lo = mid;
    else hi = mid - 1;
  }
  const round = lo;
  const offset = clamped - trace.shotStart[round];
  const count = trace.frameChildCount[round];
  const childStart = trace.frameChildStart[round];
  const childEnd = childStart + count;
  const found = round === trace.frameCount - 1;
  const iteration = trace.frameIteration[round];
  const current = trace.frameCurrent[round];
  const phase: Phase = offset === 0 ? "pick" : found ? "goal" : offset <= count ? "child" : "merge";
  const child = phase === "child" ? offset - 1 : -1;
  return {
    shot: clamped,
    round,
    phase,
    child,
    iteration,
    bound: trace.frameBound[round],
    rangeStart: trace.depthFirst ? trace.roots[iteration] : 0,
    revealedEnd: phase === "child" ? childStart + child + 1 : phase === "merge" ? childEnd : childStart,
    layoutEnd: childEnd,
    childStart,
    childEnd,
    current,
    focus: phase === "child" ? childStart + child : current,
    next: phase === "merge" && round + 1 < trace.frameCount ? trace.frameCurrent[round + 1] : -1,
    goal: phase === "goal",
  };
}

export const isPruned = (trace: CompactTrace, id: number) => trace.status[id] >= FIRST_PRUNED;

/** Whether the node waits in the frontier (A*) or on the depth-first stack (IDA*) at this shot. */
export function isOpen(trace: CompactTrace, scene: Scene, id: number) {
  if (id < scene.rangeStart || id >= scene.revealedEnd || trace.status[id] >= FIRST_PRUNED) return false;
  const visit = trace.visitFrame[id];
  if (visit >= 0 && (visit < scene.round || (visit === scene.round && scene.phase !== "pick" && scene.phase !== "ready"))) return false;
  const replacement = trace.replacedBy[id];
  return !(replacement >= 0 && replacement < scene.revealedEnd);
}

/** Expanded before this shot (the current node counts once its successors start appearing). */
export function isExpanded(trace: CompactTrace, scene: Scene, id: number) {
  const visit = trace.visitFrame[id];
  return visit >= 0 && (visit < scene.round || (visit === scene.round && scene.phase !== "pick" && scene.phase !== "ready"));
}

export function boardOf(trace: CompactTrace, id: number) {
  return trace.boards.subarray(id * trace.cells, (id + 1) * trace.cells);
}

export function pathTo(trace: CompactTrace, id: number) {
  const path: number[] = [];
  for (let node = id; node >= 0; node = trace.parent[node]) path.push(node);
  return path.reverse();
}

export interface Counters {
  expanded: number;
  generated: number;
  pruned: number;
}

/** Running totals with the same meaning as the Java counters (iteration roots are not generated nodes). */
export function countersAt(trace: CompactTrace, scene: Scene): Counters {
  const rootsSeen = trace.depthFirst ? scene.iteration + 1 : 1;
  return {
    expanded: scene.round + (scene.phase === "child" || scene.phase === "merge" ? 1 : 0),
    generated: scene.revealedEnd - rootsSeen,
    pruned: trace.prunedPrefix[scene.revealedEnd],
  };
}

export interface FrontierEntry {
  id: number;
  f: number;
  g: number;
  priority: number;
}

export interface Frontier {
  size: number;
  /** Open nodes in algorithm priority order; remaining ties follow the Java pick, then id. */
  top: FrontierEntry[];
  /** Open node count per sorting value (g for Dijkstra, otherwise f), ascending. */
  histogram: Array<{ value: number; count: number }>;
  /** Open nodes tied for the head's sorting priority. */
  ties: number;
}

export function frontierAt(trace: CompactTrace, scene: Scene, limit: number): Frontier {
  // The Java queue's actual choice goes first among equal sorting priorities.
  const chosen = scene.phase === "pick" ? scene.current : scene.next;
  const priority = (id: number) => trace.g[id] + (trace.pathCostOnly ? 0 : trace.h[id]);
  const before = (a: FrontierEntry, b: FrontierEntry) => a.priority - b.priority || a.g - b.g || Number(b.id === chosen) - Number(a.id === chosen) || a.id - b.id;
  const top: FrontierEntry[] = [];
  const counts = new Map<number, number>();
  let size = 0;
  for (let id = scene.rangeStart; id < scene.revealedEnd; id++) {
    if (!isOpen(trace, scene, id)) continue;
    size++;
    const entry = { id, g: trace.g[id], f: trace.g[id] + trace.h[id], priority: priority(id) };
    counts.set(entry.priority, (counts.get(entry.priority) ?? 0) + 1);
    if (top.length === limit && before(entry, top[limit - 1]) >= 0) continue;
    let at = top.length;
    while (at > 0 && before(entry, top[at - 1]) < 0) at--;
    top.splice(at, 0, entry);
    if (top.length > limit) top.pop();
  }
  let ties = 0;
  if (top.length) {
    const head = top[0];
    for (let id = scene.rangeStart; id < scene.revealedEnd; id++) {
      if (trace.g[id] === head.g && priority(id) === head.priority && isOpen(trace, scene, id)) ties++;
    }
  }
  const histogram = [...counts].map(([value, count]) => ({ value, count })).sort((a, b) => a.value - b.value);
  return { size, top, histogram, ties };
}

/** Smallest f that exceeded the threshold so far in this iteration: IDA*'s next threshold. */
export function nextBoundAt(trace: CompactTrace, scene: Scene) {
  const last = scene.revealedEnd - 1;
  if (last < scene.rangeStart || trace.boundMinF[last] < 0) return null;
  return { f: trace.boundMinF[last], id: trace.boundMinId[last] };
}

/** Shot index of each iteration's first pick, for timeline markers. */
export function iterationShots(trace: CompactTrace) {
  return Array.from(trace.iterationFirstFrame, (frame) => trace.shotStart[frame]);
}

/** Pick shot of a round. */
export const roundShot = (trace: CompactTrace, round: number) =>
  trace.shotStart[Math.max(0, Math.min(trace.frameCount - 1, round))];

export type NodeState = "pending" | "current" | "expanded" | "open" | "superseded" | "pruned";

/** What a node is at this shot, as the tree shows it. */
export function nodeState(trace: CompactTrace, scene: Scene, id: number): NodeState {
  if (id >= scene.revealedEnd) return "pending";
  if (id === scene.current && !scene.goal) return "current";
  if (trace.status[id] >= FIRST_PRUNED) return "pruned";
  if (isExpanded(trace, scene, id) || (scene.goal && id === scene.current)) return "expanded";
  return isOpen(trace, scene, id) ? "open" : "superseded";
}
