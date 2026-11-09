import { isPrunedStatus, type CompactTrace } from "@/data/types";
import type { Scene } from "./model";

export const SLOT = 124; // horizontal distance between neighbouring leaves
export const ROW = 168; // vertical distance between depths

export interface TreeLayout {
  start: number;
  end: number;
  /** Member ids in post-order. */
  members: Int32Array;
  count: number;
  /** World x per id - start; NaN for ids outside the tree. */
  x: Float32Array;
  minX: number;
  maxX: number;
  maxDepth: number;
}

/**
 * Leaf-order tidy layout: leaves take consecutive slots and parents sit over the middle of their
 * children. Successor ids of a frame are contiguous, so children come straight from the frame
 * table without building adjacency lists. Pruned nodes are always leaves; hidden ones are skipped,
 * except the successors of the current round, which stay visible until the next pick.
 */
export function layoutTree(trace: CompactTrace, scene: Scene, showPruned: boolean): TreeLayout {
  const start = scene.rangeStart;
  const end = scene.layoutEnd;
  const x = new Float32Array(Math.max(0, end - start)).fill(Number.NaN);
  const members = new Int32Array(Math.max(0, end - start));
  let count = 0;
  let leaves = 0;
  let maxDepth = 0;
  const member = (id: number) =>
    id < end && (!isPrunedStatus(trace.status[id]) || showPruned || (id >= scene.childStart && id < scene.childEnd));

  if (end > start) {
    const stack: number[] = [start];
    const cursor: number[] = [-1];
    const first: number[] = [Number.NaN];
    const last: number[] = [Number.NaN];
    while (stack.length) {
      const top = stack.length - 1;
      const id = stack[top];
      if (cursor[top] < 0) {
        const frame = trace.visitFrame[id];
        cursor[top] = frame >= 0 ? trace.frameChildStart[frame] : end;
      }
      const frame = trace.visitFrame[id];
      const stop = frame >= 0 ? Math.min(end, trace.frameChildStart[frame] + trace.frameChildCount[frame]) : end;
      let child = cursor[top];
      while (child < stop && !member(child)) child++;
      if (child < stop) {
        cursor[top] = child + 1;
        stack.push(child);
        cursor.push(-1);
        first.push(Number.NaN);
        last.push(Number.NaN);
        continue;
      }
      const position = Number.isNaN(first[top]) ? leaves++ * SLOT : (first[top] + last[top]) / 2;
      x[id - start] = position;
      members[count++] = id;
      if (trace.g[id] > maxDepth) maxDepth = trace.g[id];
      stack.pop();
      cursor.pop();
      first.pop();
      last.pop();
      if (stack.length) {
        const parent = stack.length - 1;
        if (Number.isNaN(first[parent])) first[parent] = position;
        last[parent] = position;
      }
    }
  }
  return { start, end, members, count, x, minX: 0, maxX: Math.max(0, (leaves - 1) * SLOT), maxDepth };
}
