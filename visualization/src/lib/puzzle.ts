import type { Heuristic } from "@/data/types";
import type { Direction } from "./labels";

const indexOf = (board: ArrayLike<number>, value: number) => Array.prototype.indexOf.call(board, value) as number;

export interface Move {
  tile: number;
  direction: Direction;
  /** Cell the tile left (the new blank). */
  from: number;
  /** Cell the tile entered (the old blank). */
  to: number;
}

/** The single tile slide that turns `before` into `after`. */
export function moveBetween(before: ArrayLike<number>, after: ArrayLike<number>, size: number): Move {
  const to = indexOf(before, 0);
  const from = indexOf(after, 0);
  const delta = to - from;
  const direction: Direction = delta === size ? "down" : delta === -size ? "up" : delta === 1 ? "right" : "left";
  return { tile: before[from], direction, from, to };
}

/**
 * Per-cell contribution to h, mirroring PuzzleHeuristics.java: the blank never counts.
 * Pattern-database values are table lookups, so they have no per-tile breakdown.
 */
export function heuristicTerms(heuristic: Heuristic, board: ArrayLike<number>, goal: ArrayLike<number>, size: number): number[] | null {
  if (heuristic === "DISJOINT_PATTERN") return null;
  const terms: number[] = [];
  for (let cell = 0; cell < board.length; cell++) {
    const tile = board[cell];
    if (tile === 0 || heuristic === "ZERO") {
      terms.push(0);
      continue;
    }
    const target = indexOf(goal, tile);
    terms.push(
      heuristic === "MISPLACED"
        ? Number(target !== cell)
        : Math.abs(Math.floor(cell / size) - Math.floor(target / size)) + Math.abs((cell % size) - (target % size)),
    );
  }
  return terms;
}
