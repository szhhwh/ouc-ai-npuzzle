package stud.g01.problem.npuzzle;

import core.solver.algorithm.heuristic.Predictor;

/**
 * N-Puzzle 专用启发函数接口。
 *
 * <p>扩展了通用 Predictor 接口，为专用搜索器提供支持增量更新与状态回退的搜索上下文。
 */
interface PuzzleHeuristic extends Predictor {
    /**
     * 为给定的初始盘面和目标盘面创建专用搜索上下文。
     *
     * @param initial 初始盘面状态
     * @param goal 目标盘面状态
     * @return 专用搜索上下文实例
     */
    PuzzleSearchContext newContext(PuzzleBoard initial, PuzzleBoard goal);
}
