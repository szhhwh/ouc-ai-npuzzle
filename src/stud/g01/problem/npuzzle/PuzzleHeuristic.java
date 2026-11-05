package stud.g01.problem.npuzzle;

import core.solver.algorithm.heuristic.Predictor;

/** 为专用搜索提供可回退的启发函数状态。 */
interface PuzzleHeuristic extends Predictor {
    PuzzleSearchContext newContext(PuzzleBoard initial, PuzzleBoard goal);
}
