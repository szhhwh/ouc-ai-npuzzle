package stud.g01.problem.npuzzle;

import static stud.g01.problem.npuzzle.PuzzleHeuristicType.DISJOINT_PATTERN;
import static stud.g01.problem.npuzzle.PuzzleHeuristicType.MANHATTAN;
import static stud.g01.problem.npuzzle.PuzzleHeuristicType.MISPLACED;
import static stud.g01.problem.npuzzle.PuzzleHeuristicType.ZERO;

import core.solver.algorithm.heuristic.Predictor;
import java.util.EnumMap;

/**
 * N-Puzzle 启发函数工具类。
 *
 * <p>提供零启发、不在位数字块数、曼哈顿距离和以及模式数据库启发函数的统一获取入口。
 */
public final class PuzzleHeuristics {

    private static final EnumMap<PuzzleHeuristicType, Predictor> predictors =
            new EnumMap<>(PuzzleHeuristicType.class);

    static {
        predictors.put(ZERO, (state, goal) -> 0);
        predictors.put(
                MISPLACED, (state, goal) -> misplaced((PuzzleBoard) state, (PuzzleBoard) goal));
        predictors.put(
                MANHATTAN, (state, goal) -> manhattan((PuzzleBoard) state, (PuzzleBoard) goal));
    }

    /** 私有构造方法，防止工具类被实例化。 */
    private PuzzleHeuristics() {}

    /**
     * 根据启发函数类型获取对应的预测器实例。
     *
     * <p>其中模式数据库启发函数在 3 阶使用乘积编码 3-3-2 分组，在 4 阶使用排名编码 6-6-3 分组。
     *
     * @param type 启发函数类型枚举
     * @return 对应的 Predictor 预测器实例；未注册的类型返回 null
     */
    public static Predictor predictor(PuzzleHeuristicType type) {
        if (type == DISJOINT_PATTERN) return DisjointPdbHeuristic.predictor();
        return predictors.get(type);
    }

    /**
     * 计算当前状态相对于目标状态的不在位数字块数，不计空格。
     *
     * @param state 当前盘面状态
     * @param goal 目标盘面状态
     * @return 不在位的数字块总数
     * @throws IllegalArgumentException 当两个盘面边长不一致时抛出
     */
    public static int misplaced(PuzzleBoard state, PuzzleBoard goal) {
        PuzzleBoard.requireSameSize(state, goal);
        byte[] cells = state.cellsInternal();
        byte[] goalCells = goal.cellsInternal();
        int count = 0;
        for (int i = 0; i < cells.length; i++) {
            if (cells[i] != 0 && cells[i] != goalCells[i]) count++;
        }
        return count;
    }

    /** 目标盘面与曼哈顿距离查找表配对发布；当连续查询的目标盘面相同时直接复用预计算表 */
    private static volatile GoalDistanceTable lastGoalTable;

    /**
     * 目标盘面的曼哈顿距离预计算表记录类。
     *
     * @param goal 目标盘面
     * @param dist 二维距离展平的一维数组，dist[i * count + v] 表示数字块 v 位于位置 i 时到达其目标位置的曼哈顿距离
     */
    private record GoalDistanceTable(PuzzleBoard goal, byte[] dist) {
        /**
         * 为指定目标盘面预先构建全位置曼哈顿距离查找表。
         *
         * @param goal 目标盘面
         * @return 预计算完成的 GoalDistanceTable 实例
         */
        static GoalDistanceTable build(PuzzleBoard goal) {
            byte[] cells = goal.cellsInternal();
            int count = cells.length;
            int size = goal.size();
            // pos[v] 记录数字 v 在目标盘面中的下标
            int[] pos = new int[count];
            for (int i = 0; i < count; i++) {
                pos[cells[i]] = i;
            }
            // dist[i * count + v] = 数字 v 从位置 i 到目标位置的曼哈顿距离
            byte[] dist = new byte[count * count];
            for (int i = 0; i < count; i++) {
                int row = i / size;
                int col = i % size;
                for (int v = 1; v < count; v++) {
                    int j = pos[v];
                    dist[i * count + v] =
                            (byte) (Math.abs(row - j / size) + Math.abs(col - j % size));
                }
            }
            return new GoalDistanceTable(goal, dist);
        }
    }

    /**
     * 计算当前状态相对于目标状态的所有数字块曼哈顿距离之和，不计空格。
     *
     * @param state 当前盘面状态
     * @param goal 目标盘面状态
     * @return 曼哈顿距离之和
     * @throws IllegalArgumentException 当两个盘面边长不一致时抛出
     */
    public static int manhattan(PuzzleBoard state, PuzzleBoard goal) {
        PuzzleBoard.requireSameSize(state, goal);
        GoalDistanceTable table = lastGoalTable;
        if (table == null || !table.goal().equals(goal)) {
            table = GoalDistanceTable.build(goal);
            lastGoalTable = table;
        }
        byte[] dist = table.dist();
        byte[] cells = state.cellsInternal();

        int distance = 0;
        int count = cells.length;
        for (int i = 0; i < count; i++) {
            // 空格在表中对应距离恒为 0
            distance += dist[i * count + cells[i]];
        }
        return distance;
    }
}
