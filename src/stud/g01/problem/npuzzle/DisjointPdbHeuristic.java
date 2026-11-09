package stud.g01.problem.npuzzle;

import core.problem.State;
import core.solver.algorithm.heuristic.Predictor;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Map;
import java.util.Objects;

/** 互不相交模式数据库启发函数。 */
public final class DisjointPdbHeuristic {

    /** 3 阶棋盘的 3-3-2 分组定义 */
    private static final Map<Integer, int[][]> GROUPINGS =
            Map.of(3, new int[][] {{1, 2, 3}, {4, 5, 6}, {7, 8}});

    /** 私有构造方法，防止工具类被实例化。 */
    private DisjointPdbHeuristic() {}

    /**
     * 返回带对称增强的启发函数，表按目标盘面懒构建并缓存。
     *
     * @return 启发函数预测器
     */
    public static Predictor predictor() {
        return predictor(PdbOptions.fromSystemProperties());
    }

    /**
     * 根据选项返回启发函数预测器。
     *
     * @param options 模式数据库配置选项
     * @return 启发函数预测器
     */
    public static Predictor predictor(PdbOptions options) {
        return predictor(options, PdbRepository.defaultRepository());
    }

    /**
     * 根据选项和指定的仓库返回启发函数预测器。
     *
     * @param options 模式数据库配置选项
     * @param repository 模式数据库仓库
     * @return 启发函数预测器
     */
    public static Predictor predictor(PdbOptions options, PdbRepository repository) {
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(repository, "repository");
        boolean symmetry = options.symmetry();
        return new PuzzleHeuristic() {
            @Override
            public int heuristics(State state, State goal) {
                PuzzleBoard board = (PuzzleBoard) state;
                PuzzleBoard target = (PuzzleBoard) goal;
                PuzzleBoard.requireSameSize(board, target);
                variant(target.size(), false);
                if (board.equals(target)) return 0;
                return target.size() == 4
                        ? repository.regularTables(target).hMax(board, symmetry)
                        : repository.productTables(target).hMax(board, symmetry);
            }

            @Override
            public PuzzleSearchContext newContext(PuzzleBoard initial, PuzzleBoard goal) {
                PuzzleBoard.requireSameSize(initial, goal);
                return switch (initial.size()) {
                    case 3 ->
                            new ProductPdbContext(initial, repository.productTables(goal), options);
                    case 4 ->
                            new ComponentPdbContext(
                                    initial, repository.componentTables(goal), options);
                    default ->
                            throw new UnsupportedOperationException(
                                    initial.size() + " 阶未定义模式数据库，仅支持 3 阶与 4 阶");
                };
            }
        };
    }

    /** 模式数据库策略变体枚举 */
    public enum Variant {
        PRODUCT_3_3_2,
        DIRECT_6_6_3,
        COMPONENT_7_8
    }

    /**
     * 根据棋盘规模和搜索模式确定采用的模式数据库策略变体。
     *
     * @param size 棋盘边长
     * @param optimizedSearch 是否为专用优化搜索
     * @return 对应的策略变体
     */
    public static Variant variant(int size, boolean optimizedSearch) {
        return switch (size) {
            case 3 -> Variant.PRODUCT_3_3_2;
            case 4 -> optimizedSearch ? Variant.COMPONENT_7_8 : Variant.DIRECT_6_6_3;
            default -> throw new UnsupportedOperationException(size + " 阶未定义模式数据库，仅支持 3 阶与 4 阶");
        };
    }

    /** 某个目标盘面对应的全部分组距离表和查询逻辑，构建后只读且线程安全 */
    static final class Tables {

        /** 棋盘边长 */
        final int size;

        /** 格子总数，等于边长的平方 */
        final int cells;

        /** 分组定义，每组是若干互不相交的数字 */
        final int[][] groups;

        /** 与 groups 一一对应的距离表 */
        final byte[][] tables;

        /** 目标盘面数字数组原文，用于构造对称置换和校验磁盘缓存 */
        final byte[] goalCells;

        /** 目标盘面中各数字所在的格子下标数组 */
        private final int[] goalPos;

        /** 各格子沿主对角线转置后的对应格子下标数组 */
        final int[] transpose;

        /** 数字的对称置换映射数组 */
        final int[] symPerm;

        /** 对称增强是否可用，即目标盘面的空格是否在主对角线上 */
        final boolean symmetric;

        /** 数字所属分组编号、乘积寻址位权、对称分组编号以及对称位权数组 */
        final int[] tileGroup, tileWeight, mirroredGroup, mirroredWeight;

        /**
         * 初始化全部分组查找表。
         *
         * @param size 棋盘边长
         * @param groups 分组数组
         * @param tables 距离表数组
         * @param goalCells 目标盘面数组
         */
        private Tables(int size, int[][] groups, byte[][] tables, byte[] goalCells) {
            this.size = size;
            this.cells = size * size;
            this.groups = groups;
            this.tables = tables;
            this.goalCells = goalCells;
            this.goalPos = new int[cells];
            for (int i = 0; i < cells; i++) {
                goalPos[goalCells[i]] = i;
            }

            this.transpose = new int[cells];
            for (int i = 0; i < cells; i++) {
                transpose[i] = (i % size) * size + i / size;
            }
            this.symPerm = new int[cells];
            for (int v = 0; v < cells; v++) {
                symPerm[v] = goalCells[transpose[goalPos[v]]];
            }
            this.symmetric = symPerm[0] == 0;
            tileGroup = new int[cells];
            tileWeight = new int[cells];
            mirroredGroup = new int[cells];
            mirroredWeight = new int[cells];
            for (int g = 0; g < groups.length; g++) {
                int weight = cells;
                for (int j = groups[g].length - 1; j >= 0; j--) {
                    int tile = groups[g][j];
                    tileGroup[tile] = g;
                    tileWeight[tile] = weight;
                    weight *= cells;
                }
            }
            for (int tile = 1; tile < cells; tile++) {
                mirroredGroup[tile] = tileGroup[symPerm[tile]];
                mirroredWeight[tile] = tileWeight[symPerm[tile]];
            }
        }

        /**
         * 为目标盘面构建整套表，逐组先查磁盘缓存，未命中则通过 0-1 广度优先搜索重建并写盘。
         *
         * @param goal 目标盘面
         * @param repository 模式数据库仓库
         * @return 构建好的 Tables 实例
         */
        static Tables build(PuzzleBoard goal, PdbRepository repository) {
            int size = goal.size();
            int[][] groups = GROUPINGS.get(size);
            if (groups == null) {
                throw new UnsupportedOperationException("Product PDBs require a 3 x 3 board");
            }
            byte[][] ts = new byte[groups.length][];
            for (int g = 0; g < groups.length; g++) {
                ts[g] = loadOrBuildGroup(size, goal.cellsInternal(), groups[g], repository);
            }
            return new Tables(size, groups, ts, goal.cellsInternal().clone());
        }

        /**
         * 对称增强查询，取直接查表和对称态查表中的较大值。
         *
         * @param state 当前盘面
         * @param useSymmetry 是否使用对称性
         * @return 启发值
         */
        int hMax(PuzzleBoard state, boolean useSymmetry) {
            long positions = state.positions();
            int direct = hPositions(positions, state.blank(), false);
            if (!useSymmetry || !symmetric) return direct;
            int mirrored = hPositions(positions, transpose[state.blank()], true);
            return Math.max(direct, mirrored);
        }

        /**
         * 查表计算指定逆位置编码和空格位置的启发总值。
         *
         * @param positions 逆位置编码
         * @param blank 空格位置
         * @param mirrored 是否为对称网格
         * @return 累加的启发值
         */
        int hPositions(long positions, int blank, boolean mirrored) {
            int sum = 0;
            for (int g = 0; g < groups.length; g++) {
                int index = 0;
                for (int tile : groups[g]) {
                    int value = mirrored ? symPerm[tile] : tile;
                    int p = (int) (positions >>> (value * 4)) & 15;
                    index = index * cells + (mirrored ? transpose[p] : p);
                }
                sum += tables[g][index * cells + blank] & 255;
            }
            return sum;
        }
    }

    /**
     * 取某一组的距离表，磁盘缓存有效就读入，否则通过 0-1 广度优先搜索重建并写盘。
     *
     * @param size 棋盘边长
     * @param goalCells 目标盘面数组
     * @param tiles 该组的数字列表
     * @param repository 模式数据库仓库
     * @return 距离表字节数组
     */
    static byte[] loadOrBuildGroup(
            int size, byte[] goalCells, int[] tiles, PdbRepository repository) {
        PdbCache cache = new PdbCache(repository, PdbCache.Format.PRODUCT, size, goalCells, tiles);
        PdbCache.Reader<byte[]> reader =
                in -> {
                    int length = in.readInt();
                    if (length != tableLength(size, tiles.length)) return null;
                    byte[] data = new byte[length];
                    in.readFully(data);
                    return data;
                };
        byte[] cached = cache.tryLoad(reader);
        if (cached != null) {
            System.out.println("[PDB] " + cache.file + " 缓存命中");
            return cached;
        }
        long t0 = System.currentTimeMillis();
        byte[] built = buildGroupTable(size, goalCells, tiles);
        System.out.println(
                "[PDB] "
                        + cache.file
                        + " 缓存未命中或校验失败，0-1 广度优先搜索重建完成，耗时 "
                        + (System.currentTimeMillis() - t0)
                        + " ms");
        cache.tryWrite(
                reader,
                out -> {
                    out.writeInt(built.length);
                    out.write(built);
                });
        return built;
    }

    /**
     * 用多源倒向 0-1 广度优先搜索构建某一组的距离表。
     *
     * @param size 棋盘边长
     * @param goalCells 目标盘面
     * @param tiles 组内数字
     * @return 该组的距离表
     */
    private static byte[] buildGroupTable(int size, byte[] goalCells, int[] tiles) {
        final int cells = size * size;
        final int D = tiles.length;

        // 位权 w[j] = cells^(D-j)，把 D 个数字位置与空格位置编码成一个整数索引
        final int[] w = new int[D];
        for (int j = 0; j < D; j++) {
            w[j] = pow(cells, D - j);
        }
        final int total = pow(cells, D + 1);
        byte[] dist = new byte[total];
        Arrays.fill(dist, (byte) 0xFF);

        final int[] tp = new int[D];
        for (int i = 0; i < cells; i++) {
            for (int j = 0; j < D; j++) {
                if (goalCells[i] == tiles[j]) tp[j] = i;
            }
        }

        final int[][] nbr = PuzzleNeighbors.forSize(size);

        IntDeque deque = new IntDeque(1 << 12);
        int tilesIdx = 0;
        for (int j = 0; j < D; j++) {
            tilesIdx += tp[j] * w[j];
        }
        // 多源起点：模式数字全部就位，空格位于任意非模式格子，距离均为 0
        for (int b = 0; b < cells; b++) {
            boolean occupied = false;
            for (int p : tp) {
                if (p == b) {
                    occupied = true;
                    break;
                }
            }
            if (occupied) continue;
            dist[tilesIdx + b] = 0;
            deque.addLast(tilesIdx + b);
        }

        BitSet settled = new BitSet(total);
        int[] p = new int[D];

        // 0-1 广度优先搜索：代价 0 的空格滑动入队首，代价 1 的模式数字换位入队尾
        while (!deque.isEmpty()) {
            int idx = deque.pollFirst();
            if (settled.get(idx)) continue;
            settled.set(idx);

            int d = dist[idx] & 0xFF;
            int b = idx % cells;
            int rest = idx / cells;
            // 由低位到高位解包出模式数字当前位置
            for (int j = D - 1; j >= 0; j--) {
                p[j] = rest % cells;
                rest /= cells;
            }

            for (int t : nbr[b]) {
                int ti = -1;
                for (int j = 0; j < D; j++) {
                    if (p[j] == t) {
                        ti = j;
                        break;
                    }
                }
                int nIdx, cost;
                if (ti >= 0) {
                    // 模式数字由 t 移到 b，空格反向移到 t
                    nIdx = idx + (b - t) * (w[ti] - 1);
                    cost = 1;
                } else {
                    // 空格与普通数字交换，模式数字位置不变，代价为 0
                    nIdx = idx - b + t;
                    cost = 0;
                }
                int nd = d + cost;
                if ((dist[nIdx] & 0xFF) > nd) {
                    dist[nIdx] = (byte) nd;
                    if (cost == 0) deque.addFirst(nIdx);
                    else deque.addLast(nIdx);
                }
            }
        }
        return dist;
    }

    /**
     * 求整数的非负整数次幂。
     *
     * @param base 底数
     * @param exp 指数
     * @return 幂运算结果
     */
    private static int pow(int base, int exp) {
        int r = 1;
        for (int i = 0; i < exp; i++) r *= base;
        return r;
    }

    /**
     * 计算某组距离表的项数。
     *
     * @param size 棋盘边长
     * @param tiles 组内数字个数
     * @return 状态总项数
     */
    private static int tableLength(int size, int tiles) {
        return pow(size * size, tiles + 1);
    }

    /** 可增长的循环数组基本类型整型双端队列，供 0-1 广度优先搜索使用，消除装箱开销。 */
    private static final class IntDeque {
        /** 环形缓冲区数组 */
        private int[] buf;

        /** 队首元素下标 */
        private int head = 0;

        /** 队列当前元素数量 */
        private int len = 0;

        /**
         * 构造指定预期容量的双端队列。
         *
         * @param expectedCapacity 预期容量
         */
        IntDeque(int expectedCapacity) {
            int cap = Math.max(16, Integer.highestOneBit(expectedCapacity - 1) << 1);
            buf = new int[cap];
        }

        /**
         * 判断队列是否为空。
         *
         * @return 为空返回 true，否则返回 false
         */
        boolean isEmpty() {
            return len == 0;
        }

        /**
         * 向队首插入一个整数元素。
         *
         * @param v 待插入的整数
         */
        void addFirst(int v) {
            ensureCapacity();
            head = (head - 1 + buf.length) % buf.length;
            buf[head] = v;
            len++;
        }

        /**
         * 向队尾追加一个整数元素。
         *
         * @param v 待追加的整数
         */
        void addLast(int v) {
            ensureCapacity();
            buf[(head + len) % buf.length] = v;
            len++;
        }

        /**
         * 从队首弹出一个整数元素。
         *
         * @return 出队的整数
         */
        int pollFirst() {
            int v = buf[head];
            head = (head + 1) % buf.length;
            len--;
            return v;
        }

        /** 检查并在队列已满时执行成倍扩容。 */
        private void ensureCapacity() {
            if (len < buf.length) return;
            int[] nb = new int[buf.length << 1];
            for (int i = 0; i < len; i++) {
                nb[i] = buf[(head + i) % buf.length];
            }
            buf = nb;
            head = 0;
        }
    }
}
