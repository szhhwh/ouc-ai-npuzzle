package stud.g01.problem.npuzzle;

import static core.solver.algorithm.heuristic.HeuristicType.DISJOINT_PATTERN;
import static core.solver.algorithm.heuristic.HeuristicType.MANHATTAN;
import static core.solver.algorithm.heuristic.HeuristicType.MISPLACED;

import core.solver.algorithm.heuristic.HeuristicType;
import core.solver.algorithm.heuristic.Predictor;
import java.util.EnumMap;

/** N-Puzzle 的启发函数。通过 Predictor 接口供通用搜索算法使用。 */
public final class PuzzleHeuristics {

    private static final EnumMap<HeuristicType, Predictor> predictors =
            new EnumMap<>(HeuristicType.class);

    static {
        predictors.put(
                MISPLACED, (state, goal) -> misplaced((PuzzleBoard) state, (PuzzleBoard) goal));
        predictors.put(
                MANHATTAN, (state, goal) -> manhattan((PuzzleBoard) state, (PuzzleBoard) goal));
    }

    private PuzzleHeuristics() {}

    /** 按类型取启发函数，未注册的类型返回 null。PDB 的 3 阶使用乘积编码 3-3-2，4 阶使用排名编码 6-6-3。 */
    public static Predictor predictor(HeuristicType type) {
        if (type == DISJOINT_PATTERN) return DisjointPdbHeuristic.predictor();
        return predictors.get(type);
    }

    /** 不在位数字块数，不计空格。 */
    public static int misplaced(PuzzleBoard state, PuzzleBoard goal) {
        byte[] cells = state.cells();
        byte[] goalCells = goal.cells();
        int count = 0;
        for (int i = 0; i < cells.length; i++) {
            if (cells[i] != 0 && cells[i] != goalCells[i]) count++;
        }
        return count;
    }

    // 目标与距离表配对发布；目标相同时复用预计算结果。
    private static volatile GoalDistanceTable lastGoalTable;

    /** dist[i * count + v] 是位置 i 上的数字块 v 到目标位置的曼哈顿距离，空格距离为 0。 */
    private record GoalDistanceTable(PuzzleBoard goal, byte[] dist) {
        static GoalDistanceTable build(PuzzleBoard goal) {
            byte[] cells = goal.cells();
            int count = cells.length;
            int size = goal.size();
            int[] pos = new int[count];
            for (int i = 0; i < count; i++) {
                pos[cells[i]] = i;
            }
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

    /** 各数字块到目标位置的曼哈顿距离之和，不计空格。 */
    public static int manhattan(PuzzleBoard state, PuzzleBoard goal) {
        GoalDistanceTable table = lastGoalTable;
        if (table == null || !table.goal().equals(goal)) {
            table = GoalDistanceTable.build(goal);
            lastGoalTable = table;
        }
        byte[] dist = table.dist();
        byte[] cells = state.cells();

        int distance = 0;
        int count = cells.length;
        for (int i = 0; i < count; i++) {
            distance += dist[i * count + cells[i]];
        }
        return distance;
    }
}
