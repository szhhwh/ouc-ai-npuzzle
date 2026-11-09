package stud.g01.problem.npuzzle;

import core.problem.Action;
import core.problem.State;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * N-Puzzle 的盘面状态。
 *
 * <p>盘面不可变：cells 是行主序的一维数组，0 表示空格。 移动返回新的 PuzzleBoard，不修改当前对象。空格下标、编码与哈希值在构造时一次算好并缓存。
 */
public final class PuzzleBoard extends State {

    /** 行主序的盘面数组，0 表示空格 */
    private final byte[] cells;

    /** 棋盘边长 */
    private final int size;

    /** 空格位置的线性下标 */
    private final int blank;

    /**
     * 盘面紧凑编码：3 阶与 4 阶棋盘采用逆位置编码，第 v 个 4-bit 记录数字 v 所在的线性下标， 供模式数据库直接查表复用；其它能够容纳在 64
     * 位整型中的小棋盘则按行主序压缩存储各格数字
     */
    private final long code;

    /** 标志位：最低位 bit 0 表示是否已压缩至 code 中；次低位 bit 1 表示包含空格的全排列奇偶性，0 为偶排列，1 为奇排列 */
    private final byte flags;

    /** 缓存的哈希值：紧凑编码盘面通过乘法散列折叠 code，大盘面直接计算整个数组的哈希 */
    private final int hash;

    /**
     * 用行主序的盘面数组构造一个盘面状态。
     *
     * @param cells 行主序的盘面数组，0 表示空格；构造时会深拷贝一份，调用方后续修改原数组不影响当前状态
     * @param size 棋盘边长，如 3 阶为 3，4 阶为 4
     * @throws NullPointerException 当 cells 为 null 时抛出
     * @throws IllegalArgumentException 当格子数量不匹配、数字超出有效范围或存在重复数字时抛出
     */
    public PuzzleBoard(byte[] cells, int size) {
        Objects.requireNonNull(cells, "cells");
        int count = cellCount(size);
        if (cells.length != count) {
            throw new IllegalArgumentException("Expected " + count + " cells for size " + size);
        }
        this.cells = cells.clone();
        boolean[] seenTiles = new boolean[count];
        for (byte tile : this.cells) {
            if (tile < 0 || tile >= count || seenTiles[tile]) {
                throw new IllegalArgumentException(
                        "Board must contain each tile 0.." + (count - 1) + " once");
            }
            seenTiles[tile] = true;
        }
        this.size = size;

        long h = 0;
        long inverse = 0;
        int parity = 0, seen = 0;
        int b = -1;
        boolean fits = this.cells.length <= Long.SIZE / 4;
        for (int i = 0; i < this.cells.length; i++) {
            h = (h << 4) | this.cells[i];
            // 3 阶与 4 阶记录每个数字所在的位置，供模式数据库直接查表
            if (size == 3 || size == 4) inverse |= (long) i << (this.cells[i] * 4);
            fits &= this.cells[i] >= 0 && this.cells[i] <= 15;
            if (this.cells.length <= 16) {
                // 已出现数字中大于当前数字的个数即新增逆序对数，异或维护奇偶位
                parity ^= Integer.bitCount(seen >>> this.cells[i]) & 1;
                seen |= 1 << this.cells[i];
            }
            if (this.cells[i] == 0) b = i;
        }
        this.code = size == 3 || size == 4 ? inverse : h;
        // bit 0 为编码可用标志，bit 1 为全排列奇偶性
        this.flags = (byte) ((fits ? 1 : 0) | (parity << 1));
        this.blank = b;
        this.hash = fits ? fold(code) : Arrays.hashCode(this.cells);
    }

    /**
     * 内部高效构造方法，供生成后继盘面时直接复用状态属性。
     *
     * @param cells 已完成交换的新盘面数组
     * @param size 棋盘边长
     * @param blank 新盘面中空格的线性下标
     * @param code 新盘面的 64 位紧凑编码
     * @param flags 新盘面的标志位，包含压缩标志与排列奇偶性
     */
    private PuzzleBoard(byte[] cells, int size, int blank, long code, byte flags) {
        this.cells = cells;
        this.size = size;
        this.blank = blank;
        this.code = code;
        this.flags = flags;
        this.hash = (flags & 1) != 0 ? fold(code) : Arrays.hashCode(cells);
    }

    /**
     * 将 64 位紧凑编码折叠成 32 位的整型哈希值。
     *
     * <p>采用类似于黄金分割比的乘法散列常数 0x9E3779B97F4A7C15L，避免简单的异或折叠 导致对称盘面或对折盘面发生哈希冲突。
     *
     * @param code 盘面的 64 位编码
     * @return 散列后的 32 位整型哈希值
     */
    private static int fold(long code) {
        return (int) ((code * 0x9E3779B97F4A7C15L) >>> 32);
    }

    /**
     * 获取棋盘的边长。
     *
     * @return 棋盘边长
     */
    public int size() {
        return size;
    }

    /**
     * 获取空格当前所在的线性下标。
     *
     * @return 空格位置的线性下标
     */
    public int blank() {
        return blank;
    }

    /**
     * 获取盘面数字数组的副本。
     *
     * @return 行主序的盘面数组副本，修改该数组不会影响当前状态
     */
    public byte[] cells() {
        return cells.clone();
    }

    /**
     * 获取包内只读的盘面数组引用，供启发函数内部直接读取，避免数组拷贝开销。
     *
     * @return 内部盘面数组引用，调用方不得修改其内容
     */
    byte[] cellsInternal() {
        return cells;
    }

    /**
     * 计算并校验指定边长的棋盘格子总数。
     *
     * @param size 棋盘边长
     * @return 格子总数
     * @throws IllegalArgumentException 当边长非正或总格数超过单字节容量时抛出
     */
    static int cellCount(int size) {
        long count = (long) size * size;
        if (size <= 0 || count > Byte.MAX_VALUE + 1) {
            throw new IllegalArgumentException(
                    "Size must fit distinct nonnegative byte tiles: " + size);
        }
        return (int) count;
    }

    /**
     * 校验两个盘面的边长是否一致。
     *
     * @param state 当前盘面
     * @param goal 目标盘面
     * @throws NullPointerException 当任一盘面为 null 时抛出
     * @throws IllegalArgumentException 当两个盘面边长不一致时抛出
     */
    static void requireSameSize(PuzzleBoard state, PuzzleBoard goal) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(goal, "goal");
        if (state.size != goal.size) {
            throw new IllegalArgumentException("Initial and goal board sizes must match");
        }
    }

    /**
     * 检查空格在当前盘面上是否能够朝指定方向移动。
     *
     * @param move 待判断的移动动作
     * @return 若空格移动后仍在棋盘范围内返回 true，越界则返回 false
     */
    boolean canMove(PuzzleMove move) {
        return switch (move.direction()) {
            case UP -> blank >= size;
            case DOWN -> blank < cells.length - size;
            case LEFT -> blank % size != 0;
            case RIGHT -> blank % size != size - 1;
        };
    }

    /**
     * 获取盘面的 64 位紧凑编码。
     *
     * @return 3 阶与 4 阶返回逆位置编码，其它小盘面返回行主序编码
     */
    long positions() {
        return code;
    }

    /**
     * 获取包含空格在内的全排列奇偶性。
     *
     * @return 偶排列返回 0，奇排列返回 1
     */
    int permutationParity() {
        return (flags >>> 1) & 1;
    }

    /** 在控制台按行打印盘面，空格显示为 # 字符 */
    @Override
    public void draw() {
        for (int i = 0; i < cells.length; i++) {
            System.out.print(cells[i] == 0 ? "#" : cells[i]);
            System.out.print((i + 1) % size == 0 ? '\n' : ' ');
        }
    }

    /**
     * 当前盘面采用动作 action 后的后继盘面，即空格朝某个方向移动一格。
     *
     * @param action 当前盘面上一个可行的动作
     * @return 移动空格后的新盘面
     */
    @Override
    public State next(Action action) {
        if (!(action instanceof PuzzleMove move) || !canMove(move)) {
            throw new IllegalArgumentException("Illegal puzzle move: " + action);
        }
        int target;
        switch (move.direction()) {
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

        byte v = cells[target];
        byte[] newCells = cells.clone();
        newCells[blank] = v;
        newCells[target] = 0;

        if ((flags & 1) == 0) {
            return new PuzzleBoard(newCells, size, target, 0, (byte) 0);
        }

        long newCode;
        if (size == 3 || size == 4) {
            // 逆位置编码中只需把 blank 与 target 的差异异或到空格槽位和数字 v 的槽位
            long difference = blank ^ target;
            newCode = code ^ difference ^ (difference << (v * 4));
        } else {
            // 行主序编码：数字 v 从 target 移到 blank，对应两个 4 位字段的加减
            newCode =
                    code
                            + ((long) v << (4 * (cells.length - 1 - blank)))
                            - ((long) v << (4 * (cells.length - 1 - target)));
        }
        // 数字块与空格互换一次，全排列奇偶性必然翻转
        return new PuzzleBoard(newCells, size, target, newCode, (byte) (flags ^ 2));
    }

    /** 四个方向的动作列表，供 actions 方法直接返回，避免分配新对象 */
    private static final List<PuzzleMove> ALL_MOVES =
            List.of(PuzzleMove.UP, PuzzleMove.DOWN, PuzzleMove.LEFT, PuzzleMove.RIGHT);

    /**
     * 获取四个方向的动作列表。
     *
     * @return 四个方向的动作列表，只表示可能的方向，不保证均合法可行
     */
    @Override
    public Iterable<? extends Action> actions() {
        return ALL_MOVES;
    }

    /** 两个盘面相等，当且仅当边长相同且逐格相同 */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;

        if (!(obj instanceof PuzzleBoard another)
                || size != another.size
                || cells.length != another.cells.length) {
            return false;
        }
        // 只有编码无损时才能直接比较 64 位整型编码；大盘面必须逐格比较完整数组
        if ((flags & another.flags & 1) != 0) {
            return code == another.code;
        }
        return Arrays.equals(cells, another.cells);
    }

    /** 返回构造时算好的哈希值 */
    @Override
    public int hashCode() {
        return hash;
    }

    /** 空格分隔的行主序数字串，空格记为 0，用于调试输出 */
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
