package stud.g01.problem.npuzzle;

import core.problem.Action;
import core.problem.State;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * N-Puzzle 的盘面状态。
 *
 * <p>盘面不可变：cells 是行主序的一维数组，0 表示空格。移动（{@link #next}）返回新的 PuzzleBoard，不修改当前对象。空格下标、编码与哈希值在构造时一次算好并缓存。
 */
public final class PuzzleBoard extends State {

    // 行主序的盘面数组，0 表示空格
    private final byte[] cells;
    // 棋盘边长
    private final int size;
    // 空格位置的线性下标
    private final int blank;
    // Three/four-by-four uses inverse encoding (nibble v is tile v's position), allowing
    // PDB queries to reuse it. Other small boards retain row-major encoding.
    private final long code;
    // 是否可以用 code 无损表示全部格子
    // Bit 0: packed; bit 1: parity of the full permutation, including the blank.
    private final byte flags;
    // 小盘面折叠 code，大盘面使用完整数组计算哈希
    private final int hash;

    /**
     * 用行主序的盘面数组构造一个盘面状态
     *
     * @param cells 行主序的盘面数组，0 表示空格；构造时会拷贝一份，调用方可以继续使用该数组
     * @param size 棋盘边长
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

        // 一次扫描同时算出空格位置和盘面编码
        long h = 0;
        long inverse = 0;
        int parity = 0, seen = 0;
        int b = -1;
        boolean fits = this.cells.length <= Long.SIZE / 4;
        for (int i = 0; i < this.cells.length; i++) {
            h = (h << 4) | this.cells[i];
            if (size == 3 || size == 4) inverse |= (long) i << (this.cells[i] * 4);
            fits &= this.cells[i] >= 0 && this.cells[i] <= 15;
            if (this.cells.length <= 16) {
                parity ^= Integer.bitCount(seen >>> this.cells[i]) & 1;
                seen |= 1 << this.cells[i];
            }
            if (this.cells[i] == 0) b = i;
        }
        this.code = size == 3 || size == 4 ? inverse : h;
        this.flags = (byte) ((fits ? 1 : 0) | (parity << 1));
        this.blank = b;
        this.hash = fits ? fold(code) : Arrays.hashCode(this.cells);
    }

    /** 后继状态直接接管已复制的数组，复用编码方式和已知空格位置。 */
    private PuzzleBoard(byte[] cells, int size, int blank, long code, byte flags) {
        this.cells = cells;
        this.size = size;
        this.blank = blank;
        this.code = code;
        this.flags = flags;
        this.hash = (flags & 1) != 0 ? fold(code) : Arrays.hashCode(cells);
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

    /** 返回盘面副本，修改返回值不会影响当前状态。 */
    public byte[] cells() {
        return cells.clone();
    }

    /** Package-local read-only access for heuristics; never mutate or expose this array. */
    byte[] cellsInternal() {
        return cells;
    }

    static int cellCount(int size) {
        long count = (long) size * size;
        if (size <= 0 || count > Byte.MAX_VALUE + 1) {
            throw new IllegalArgumentException(
                    "Size must fit distinct nonnegative byte tiles: " + size);
        }
        return (int) count;
    }

    static void requireSameSize(PuzzleBoard state, PuzzleBoard goal) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(goal, "goal");
        if (state.size != goal.size) {
            throw new IllegalArgumentException("Initial and goal board sizes must match");
        }
    }

    boolean canMove(PuzzleMove move) {
        return switch (move.direction()) {
            case UP -> blank >= size;
            case DOWN -> blank < cells.length - size;
            case LEFT -> blank % size != 0;
            case RIGHT -> blank % size != size - 1;
        };
    }

    long positions() {
        return code;
    }

    int permutationParity() {
        return (flags >>> 1) & 1;
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
        if (!(action instanceof PuzzleMove move) || !canMove(move)) {
            throw new IllegalArgumentException("Illegal puzzle move: " + action);
        }
        // 空格移动一格后到达的位置
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

        // 复制盘面并交换空格与目标位置上的数字块，当前对象不变
        byte v = cells[target];
        byte[] newCells = cells.clone();
        newCells[blank] = v;
        newCells[target] = 0;

        if ((flags & 1) == 0) {
            return new PuzzleBoard(newCells, size, target, 0, (byte) 0);
        }

        long newCode;
        if (size == 3 || size == 4) {
            // Exchange the inverse encoding's blank and moving-tile positions.
            long difference = blank ^ target;
            newCode = code ^ difference ^ (difference << (v * 4));
        } else {
            newCode =
                    code
                            + ((long) v << (4 * (cells.length - 1 - blank)))
                            - ((long) v << (4 * (cells.length - 1 - target)));
        }
        return new PuzzleBoard(newCells, size, target, newCode, (byte) (flags ^ 2));
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

    /** 两个盘面相等，当且仅当边长相同且逐格相同 */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;

        if (!(obj instanceof PuzzleBoard another)
                || size != another.size
                || cells.length != another.cells.length) {
            return false;
        }
        // 只有编码无损时才能省掉逐格比较；更大的盘面必须比较完整数组。
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
