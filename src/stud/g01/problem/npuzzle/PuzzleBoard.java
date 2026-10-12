package stud.g01.problem.npuzzle;

import static core.solver.algorithm.heuristic.HeuristicType.DISJOINT_PATTERN;
import static core.solver.algorithm.heuristic.HeuristicType.MANHATTAN;
import static core.solver.algorithm.heuristic.HeuristicType.MISPLACED;

import core.problem.Action;
import core.problem.State;
import core.solver.algorithm.heuristic.HeuristicType;
import core.solver.algorithm.heuristic.Predictor;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;

/**
 * N-Puzzle 的盘面状态。
 *
 * <p>盘面不可变：cells 是行主序的一维数组，0 表示空格。移动（{@link #next}）返回新的 PuzzleBoard，不修改当前对象。空格下标、编码与哈希值在构造时一次算好并缓存。
 */
public class PuzzleBoard extends State {

    // 行主序的盘面数组，0 表示空格
    private final byte[] cells;
    // 棋盘边长
    private final int size;
    // 空格位置的线性下标
    private final int blank;
    // 盘面编码：每格数字占 4 个 bit，首格在最高位，4 阶 16 格正好占满一个 long。
    // next 增量构造新盘面时直接改这个编码，避免全盘重扫，所以必须保留成 long。
    private final long code;
    // 由 code 折叠出的哈希值
    private final int hash;

    /**
     * 用行主序的盘面数组构造一个盘面状态
     *
     * @param cells 行主序的盘面数组，0 表示空格；构造时会拷贝一份，调用方可以继续使用该数组
     * @param size 棋盘边长
     */
    public PuzzleBoard(byte[] cells, int size) {
        this.cells = cells.clone();
        this.size = size;

        // 一次扫描同时算出空格位置和盘面编码
        long h = 0;
        int b = -1;
        for (int i = 0; i < this.cells.length; i++) {
            h = (h << 4) | this.cells[i];
            if (this.cells[i] == 0) b = i;
        }
        this.code = h;
        this.blank = b;
        this.hash = fold(h);
    }

    /** 增量构造，仅供 {@link #next} 使用。调用方已经算好交换后的 cells、新的空格下标和增量更新后的编码，这里不再全盘扫描。 */
    private PuzzleBoard(byte[] cells, int size, int blank, long code) {
        this.cells = cells;
        this.size = size;
        this.blank = blank;
        this.code = code;
        this.hash = fold(code);
    }

    /**
     * 把 64bit 编码折叠成 32bit 哈希。这里用乘法散列而不是简单的异或折叠：异或折叠会让"位置对折"（第 i 格与对称格互换）的两个盘面得到同一个哈希，3 阶只用到一半哈希空间。
     */
    private static int fold(long code) {
        return (int) ((code * 0x9E3779B97F4A7C15L) >>> 32);
    }

    /**
     * @return 棋盘边长
     */
    public int size() {
        return size;
    }

    /**
     * @return 空格位置的线性下标
     */
    public int blank() {
        return blank;
    }

    /**
     * 返回内部的盘面数组。为了省掉拷贝开销直接返回引用，调用方不要修改它。
     *
     * @return 内部 cells 数组引用
     */
    public byte[] cells() {
        return cells;
    }

    /** 在控制台按行打印盘面，空格显示为 # */
    @Override
    public void draw() {
        for (int i = 0; i < cells.length; i++) {
            System.out.print(cells[i] == 0 ? "#" : cells[i]);
            System.out.print((i + 1) % size == 0 ? '\n' : ' ');
        }
    }

    /**
     * 当前盘面采用动作 action 后的后继盘面，即空格朝某个方向移动一格。调用前要保证 action 可行，参见 {@link NPuzzleProblem#applicable}。
     *
     * @param action 当前盘面上一个可行的动作
     * @return 移动空格后的新盘面
     */
    @Override
    public State next(Action action) {
        // 空格移动一格后到达的位置
        int target;
        switch (((PuzzleMove) action).direction()) {
            case UP:
                target = blank - size;
                break;
            case DOWN:
                target = blank + size;
                break;
            case LEFT:
                target = blank - 1;
                break;
            default:
                target = blank + 1;
                break;
        }

        // 复制盘面并交换空格与目标位置上的数字块，当前对象不变
        byte v = cells[target];
        byte[] newCells = cells.clone();
        newCells[blank] = v;
        newCells[target] = 0;

        // 增量更新编码：把数字块 v 从 target 搬到 blank（原值为 0）。
        // 位置 i 的 4bit 段从第 4*(length-1-i) 位开始，新空格位置就是 target。
        long newCode =
                code
                        + ((long) v << (4 * (cells.length - 1 - blank)))
                        - ((long) v << (4 * (cells.length - 1 - target)));
        return new PuzzleBoard(newCells, size, target, newCode);
    }

    // 四个方向的动作列表，actions() 直接返回，不做分配
    private static final List<PuzzleMove> ALL_MOVES =
            List.of(PuzzleMove.UP, PuzzleMove.DOWN, PuzzleMove.LEFT, PuzzleMove.RIGHT);

    /**
     * @return 四个方向的动作列表，只表示可能的方向，不一定都可行
     */
    @Override
    public Iterable<? extends Action> actions() {
        return ALL_MOVES;
    }

    // 启发函数类型到实现方法的映射
    private static final EnumMap<HeuristicType, Predictor> predictors =
            new EnumMap<>(HeuristicType.class);

    static {
        // 不在位数字块数，不计空格
        predictors.put(
                MISPLACED, (state, goal) -> ((PuzzleBoard) state).misplaced((PuzzleBoard) goal));
        // 各数字块到目标位置的曼哈顿距离之和
        predictors.put(
                MANHATTAN, (state, goal) -> ((PuzzleBoard) state).manhattan((PuzzleBoard) goal));
        // 可加模式数据库，第三阶段使用
        predictors.put(DISJOINT_PATTERN, DisjointPdbHeuristic.predictor());
    }

    /**
     * 按类型取对应的启发函数
     *
     * @param type 启发函数类型
     * @return 对应的启发函数；未注册的类型返回 null
     */
    public static Predictor predictor(HeuristicType type) {
        return predictors.get(type);
    }

    /**
     * 不在位数字块数：当前盘面与目标盘面在同一位置上数字不同的数字块个数，不计空格
     *
     * @param goal 目标盘面
     * @return 不在位数字块数
     */
    private int misplaced(PuzzleBoard goal) {
        int count = 0;
        for (int i = 0; i < cells.length; i++) {
            if (cells[i] != 0 && cells[i] != goal.cells[i]) count++;
        }
        return count;
    }

    // 缓存上一次算曼哈顿距离时用的目标盘面和它的位置表。搜索是单线程的，同一次搜索里 goal 固定但会被调用几百万次，
    // 命中时省掉每次重建位置表。盘面与位置表打包为不可变记录、volatile 单次替换，并发下至多重复构建，不会读到撕裂的组合。
    private static volatile GoalPosTable lastGoalTable;

    /** 目标盘面 cells 与其位置表（pos[v] = 数字块 v 的目标位置）的不可变配对 */
    private record GoalPosTable(byte[] goalCells, int[] pos) {}

    /**
     * 曼哈顿距离：各数字块当前位置到目标位置的行、列距离之和的总和，不计空格
     *
     * @param goal 目标盘面
     * @return 曼哈顿距离总和
     */
    private int manhattan(PuzzleBoard goal) {
        // pos[v] 是数字块 v 在目标盘面上的位置；goal 没变就直接复用上次的表
        GoalPosTable table = lastGoalTable;
        if (table == null || !Arrays.equals(table.goalCells(), goal.cells)) {
            int[] pos = new int[goal.cells.length];
            for (int i = 0; i < goal.cells.length; i++) {
                pos[goal.cells[i]] = i;
            }
            lastGoalTable = table = new GoalPosTable(goal.cells, pos); // goal 不可变，直接持有引用
        }
        int[] pos = table.pos();

        int distance = 0;
        for (int i = 0; i < cells.length; i++) {
            byte v = cells[i];
            if (v == 0) continue;
            int j = pos[v];
            distance += Math.abs(i / size - j / size) + Math.abs(i % size - j % size);
        }
        return distance;
    }

    /** 两个盘面相等，当且仅当边长相同且逐格相同 */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;

        if (obj instanceof PuzzleBoard) {
            PuzzleBoard another = (PuzzleBoard) obj;
            return this.size == another.size && Arrays.equals(this.cells, another.cells);
        }
        return false;
    }

    /** 返回构造时算好的哈希值 */
    @Override
    public int hashCode() {
        return hash;
    }

    /** 空格分隔的行主序数字串，空格记为 0，用于调试 */
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
