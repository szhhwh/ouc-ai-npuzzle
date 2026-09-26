package stud.g01.problem.npuzzle;

import core.problem.Action;
import core.problem.State;
import core.solver.algorithm.heuristic.HeuristicType;
import core.solver.algorithm.heuristic.Predictor;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;

import static core.solver.algorithm.heuristic.HeuristicType.MANHATTAN;
import static core.solver.algorithm.heuristic.HeuristicType.MISPLACED;

/**
 * N-Puzzle 的盘面状态。
 *
 * 盘面是不可变对象：cells 为行主序的一维数组，0 表示空格；
 * 任何移动（{@link #next}）都返回新的 PuzzleBoard，而不修改当前对象。
 * 空格的线性下标与哈希值在构造时一次性计算并缓存。
 */
public class PuzzleBoard extends State {

    //行主序的盘面数组，0 表示空格
    private final byte[] cells;
    //盘面的边长
    private final int size;
    //缓存的空格线性下标
    private final int blank;
    //缓存的哈希值
    private final int hash;

    /**
     * 用行主序的盘面数组构造一个盘面状态
     *
     * @param cells 行主序盘面数组，0 表示空格（构造时深拷贝，调用方可继续持有该数组）
     * @param size  盘面边长
     */
    public PuzzleBoard(byte[] cells, int size) {
        this.cells = cells.clone();
        this.size = size;

        //构造时计算空格位置与哈希缓存：每格数字占 4 个 bit，
        //16 格（4×4）正好编码为一个 long；3 阶只占用低 36 个 bit。
        long h = 0;
        int b = -1;
        for (int i = 0; i < this.cells.length; i++) {
            h = (h << 4) | this.cells[i];
            if (this.cells[i] == 0) b = i;
        }
        this.blank = b;
        this.hash = (int) (h ^ (h >>> 32));
    }

    /**
     * @return 盘面边长
     */
    public int size() {
        return size;
    }

    /**
     * @return 空格的线性下标（行主序）
     */
    public int blank() {
        return blank;
    }

    /**
     * 返回内部盘面数组（行主序）。
     * 注意：为避免拷贝开销直接返回内部引用，调用方不得修改该数组！
     *
     * @return 内部 cells 数组引用
     */
    public byte[] cells() {
        return cells;
    }

    /**
     * 在控制台上按行打印盘面，空格显示为 #
     */
    @Override
    public void draw() {
        for (int i = 0; i < cells.length; i++) {
            System.out.print(cells[i] == 0 ? "#" : cells[i]);
            System.out.print((i + 1) % size == 0 ? '\n' : ' ');
        }
    }

    /**
     * 当前盘面采用动作 action（空格朝某方向移动一格）后的后继盘面。
     * 调用前应保证 action 是可行的（参见 NPuzzleProblem.applicable）。
     *
     * @param action 当前盘面上一个可行的动作
     * @return 移动空格后得到的新盘面
     */
    @Override
    public State next(Action action) {
        //空格移动一格后所到达的线性下标
        int target;
        switch (((PuzzleMove) action).direction()) {
            case UP:    target = blank - size; break;   //空格与上方的将牌交换
            case DOWN:  target = blank + size; break;   //空格与下方的将牌交换
            case LEFT:  target = blank - 1;     break;  //空格与左边的将牌交换
            default:    target = blank + 1;     break;  //空格与右边的将牌交换
        }

        //复制盘面，交换空格与目标位置的将牌（本对象保持不变）
        byte[] newCells = cells.clone();
        newCells[blank] = newCells[target];
        newCells[target] = 0;
        return new PuzzleBoard(newCells, size);
    }

    //四个方向动作的不可变复用列表，actions() 直接返回它，不做任何分配
    private static final List<PuzzleMove> ALL_MOVES =
            List.of(PuzzleMove.UP, PuzzleMove.DOWN, PuzzleMove.LEFT, PuzzleMove.RIGHT);

    /**
     * @return 四个方向动作的不可变 List（复用的静态列表，可能的方向，不一定都可行）
     */
    @Override
    public Iterable<? extends Action> actions() {
        return ALL_MOVES;
    }

    //枚举映射，存放不同类型的启发函数
    private static final EnumMap<HeuristicType, Predictor> predictors =
            new EnumMap<>(HeuristicType.class);

    static {
        //不在位将牌数（不计空格）
        predictors.put(MISPLACED,
                (state, goal) -> ((PuzzleBoard) state).misplaced((PuzzleBoard) goal));
        //各非空格将牌到其目标位置的曼哈顿距离之和
        predictors.put(MANHATTAN,
                (state, goal) -> ((PuzzleBoard) state).manhattan((PuzzleBoard) goal));
        //TODO: 后续阶段实现 DISJOINT_PATTERN（分离模式数据库）启发函数
    }

    /**
     * 根据启发函数类型获得对应的 Predictor
     *
     * @param type 启发函数类型
     * @return 对应的启发函数；未注册的类型（如尚未实现的 DISJOINT_PATTERN）返回 null
     */
    public static Predictor predictor(HeuristicType type) {
        return predictors.get(type);
    }

    /**
     * 不在位将牌数：当前盘面与目标盘面在同一位置上数字不同的非空格将牌个数
     *
     * @param goal 目标盘面
     * @return 不在位将牌数
     */
    private int misplaced(PuzzleBoard goal) {
        int count = 0;
        for (int i = 0; i < cells.length; i++) {
            if (cells[i] != 0 && cells[i] != goal.cells[i]) count++;
        }
        return count;
    }

    //记忆化缓存：上次计算曼哈顿距离时的目标盘面与其位置表。
    //搜索引擎单线程运行，且同一次搜索中 goal 盘面固定、被数百万次重复传入，
    //命中时省去每次 O(n) 的 int[] 分配与重建（仅剩一次 O(n) 的逐格比较，零分配）。
    private static byte[] lastGoalCells;
    private static int[] lastGoalPos;

    /**
     * 曼哈顿距离：各非空格将牌当前位置到其目标位置（行、列距离之和）的总和
     *
     * @param goal 目标盘面
     * @return 曼哈顿距离总和
     */
    private int manhattan(PuzzleBoard goal) {
        //pos[v]：将牌 v 在目标盘面上的线性下标；goal 未变时直接复用上次的表
        if (lastGoalPos == null || !Arrays.equals(lastGoalCells, goal.cells)) {
            lastGoalCells = goal.cells;   //goal 不可变，直接持有引用即可
            int[] pos = new int[goal.cells.length];
            for (int i = 0; i < goal.cells.length; i++) {
                pos[goal.cells[i]] = i;
            }
            lastGoalPos = pos;
        }
        int[] pos = lastGoalPos;

        int distance = 0;
        for (int i = 0; i < cells.length; i++) {
            byte v = cells[i];
            if (v == 0) continue;   //空格不计入
            int j = pos[v];
            distance += Math.abs(i / size - j / size) + Math.abs(i % size - j % size);
        }
        return distance;
    }

    /**
     * 两个盘面相同 ⇔ 类型相同、边长相同且逐格相等
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;

        if (obj instanceof PuzzleBoard) {
            PuzzleBoard another = (PuzzleBoard) obj;
            return this.size == another.size && Arrays.equals(this.cells, another.cells);
        }
        return false;
    }

    /**
     * 返回构造时缓存的哈希值
     */
    @Override
    public int hashCode() {
        return hash;
    }

    /**
     * 空格分隔的行主序数字串（空格记 0），用于调试
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(cells[i]);
        }
        return sb.toString();
    }
}
