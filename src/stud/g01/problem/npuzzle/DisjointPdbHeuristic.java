package stud.g01.problem.npuzzle;

import core.problem.State;
import core.solver.algorithm.heuristic.Predictor;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 可加模式数据库（additive pattern database，PDB）启发函数。 */
public final class DisjointPdbHeuristic {

    /** 已构建表的进程内缓存，key 由棋盘规模和 goal 内容拼成 */
    private static final Map<String, Tables> BUILT = new ConcurrentHashMap<>();

    /** 单槽缓存，保存上一次查询用的目标盘面和对应的表 */
    private static final class Memo {
        final PuzzleBoard goal;
        final Tables tables;

        Memo(PuzzleBoard goal, Tables tables) {
            this.goal = goal;
            this.tables = tables;
        }
    }

    private static volatile Memo lastMemo;

    /** Small product tables are retained for 3 x 3; 4 x 4 uses ranked storage. */
    private static final Map<Integer, int[][]> GROUPINGS =
            Map.of(3, new int[][] {{1, 2, 3}, {4, 5, 6}, {7, 8}});

    private DisjointPdbHeuristic() {}

    /**
     * 返回带对称增强的启发函数。表按目标盘面懒构建并缓存。
     *
     * @return 启发函数
     */
    public static Predictor predictor() {
        return predictor(PdbOptions.fromSystemProperties());
    }

    /** 普通搜索逐次完整查表；仅 symmetry 选项影响启发值。 */
    public static Predictor predictor(PdbOptions options) {
        boolean symmetry = options.symmetry();
        return new PuzzleHeuristic() {
            @Override
            public int heuristics(State state, State goal) {
                if (state.equals(goal)) return 0;
                PuzzleBoard target = (PuzzleBoard) goal;
                return target.size() == 4
                        ? RegularPdbHeuristic.tablesFor(target).hMax((PuzzleBoard) state, symmetry)
                        : tablesFor(target).hMax((PuzzleBoard) state, symmetry);
            }

            @Override
            public PuzzleSearchContext newContext(PuzzleBoard initial, PuzzleBoard goal) {
                return switch (initial.size()) {
                    case 3 -> new ProductPdbContext(initial, tablesFor(goal), options);
                    case 4 ->
                            new ComponentPdbContext(
                                    initial, ComponentPdbContext.tablesFor(goal), options);
                    default ->
                            throw new UnsupportedOperationException(
                                    initial.size() + " 阶未定义 PDB（仅支持 3/4 阶）");
                };
            }
        };
    }

    /**
     * 取目标盘面对应的整套表，未构建则构建。
     *
     * <p>包内可见，供 {@link ProductPdbContext} 使用同一套表维护增量启发值。
     */
    static Tables tablesFor(PuzzleBoard goal) {
        Memo m = lastMemo;
        if (m != null && m.goal == goal) {
            return m.tables;
        }
        String key = goal.size() + "#" + goal;
        Tables t = BUILT.computeIfAbsent(key, k -> Tables.build(goal));
        lastMemo = new Memo(goal, t);
        return t;
    }

    /** 某个目标盘面对应的全部分组距离表和查询逻辑。字段在构建后只读，线程安全 */
    static final class Tables {

        /** 棋盘边长 */
        final int size;

        /** 格子总数，等于 size 的平方 */
        final int cells;

        /** 分组定义，每组是若干互不相交的数字 */
        final int[][] groups;

        /** 与 groups 一一对应的距离表 */
        final byte[][] tables;

        /** goal 盘面原文，用于构造对称置换和校验磁盘缓存 */
        final byte[] goalCells;

        /** goalPos[v] 是数字 v 在 goal 盘面上的位置 */
        private final int[] goalPos;

        /** transpose[i] 是格子 i 沿主对角线翻转后的位置 */
        final int[] transpose;

        /** 数字的对称置换 symPerm，symPerm[v] = goal[transpose[goalPos[v]]] */
        final int[] symPerm;

        /** 对称增强是否可用，即 goal 的空格是否在主对角线上 */
        final boolean symmetric;

        final int[] tileGroup, tileWeight, mirroredGroup, mirroredWeight;

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

            // 构造转置表和数字置换。只有 goal 的空格在主对角线上时，对称态的空格位置才等于
            // 转置后的空格位置，查表才有效，否则禁用对称增强。标准目标的空格在右下角，满足条件。
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

        /** 为 goal 构建整套表。逐组先查磁盘缓存，未命中则用 0-1 BFS 重建并写盘 */
        static Tables build(PuzzleBoard goal) {
            int size = goal.size();
            int[][] groups = GROUPINGS.get(size);
            if (groups == null) {
                throw new UnsupportedOperationException("Product PDBs require a 3 x 3 board");
            }
            byte[][] ts = new byte[groups.length][];
            for (int g = 0; g < groups.length; g++) {
                ts[g] = loadOrBuildGroup(size, goal.cells(), groups[g], g);
            }
            return new Tables(size, groups, ts, goal.cells().clone());
        }

        /** 直接查询，把各组的表值相加 */
        int h(PuzzleBoard state) {
            return hCells(state.cells(), state.blank());
        }

        /** 对称增强查询，取直接查表和与对称态查表中的较大值；不满足对称条件时退化为直接查询 */
        int hMax(PuzzleBoard state) {
            return hMax(state, true);
        }

        int hMax(PuzzleBoard state, boolean useSymmetry) {
            long positions = state.positions();
            int direct = hPositions(positions, state.blank(), false);
            if (!useSymmetry || !symmetric) return direct;
            int mirrored = hPositions(positions, transpose[state.blank()], true);
            return Math.max(direct, mirrored);
        }

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

        /**
         * 把各组投影的表值相加。
         *
         * @param c 盘面数组
         * @param blank 空格位置
         * @return 各组表值之和
         */
        private int hCells(byte[] c, int blank) {
            // 一次扫描建 pos[v]，即数字 v 的当前位置，供各组复用
            int[] pos = new int[cells];
            for (int i = 0; i < cells; i++) {
                pos[c[i]] = i;
            }

            int sum = 0;
            for (int g = 0; g < groups.length; g++) {
                int[] tiles = groups[g];
                // 用 Horner 法算乘积进制下标，最高位是 tiles[0] 的位置，最低位是空格位置
                int idx = pos[tiles[0]];
                for (int j = 1; j < tiles.length; j++) {
                    idx = idx * cells + pos[tiles[j]];
                }
                idx = idx * cells + blank;
                sum += tables[g][idx] & 0xFF;
            }
            return sum;
        }
    }

    /**
     * 取某一组的距离表，磁盘缓存有效就读入，否则 0-1 BFS 重建并写盘。
     *
     * @param size 棋盘边长
     * @param goalCells 目标盘面，行主序，0 表示空格
     * @param tiles 该组的数字
     * @param gi 组号，用于命名缓存文件
     */
    static byte[] loadOrBuildGroup(int size, byte[] goalCells, int[] tiles, int gi) {
        PdbCache cache =
                new PdbCache(
                        "pdb-" + size + "-" + gi + ".bin",
                        PdbCache.Format.PRODUCT,
                        size,
                        goalCells,
                        tiles);
        byte[] cached =
                cache.tryLoad(
                        in -> {
                            int length = in.readInt();
                            if (length != tableLength(size, tiles.length)) return null;
                            byte[] data = new byte[length];
                            in.readFully(data);
                            return data;
                        });
        if (cached != null) {
            System.out.println("[PDB] " + cache.file + " 缓存命中（" + cached.length + " 项）");
            return cached;
        }
        long t0 = System.currentTimeMillis();
        byte[] built = buildGroupTable(size, goalCells, tiles);
        System.out.println(
                "[PDB] "
                        + cache.file
                        + " 缓存未命中或校验失败，0-1 BFS 重建完成："
                        + built.length
                        + " 项，耗时 "
                        + (System.currentTimeMillis() - t0)
                        + " ms");
        cache.tryWrite(
                out -> {
                    out.writeInt(built.length);
                    out.write(built);
                });
        return built;
    }

    /**
     * 用多源倒向 0-1 BFS 构建某一组的距离表。
     *
     * @param size 棋盘边长
     * @param goalCells 目标盘面
     * @param tiles 组内数字
     * @return 该组的距离表
     */
    private static byte[] buildGroupTable(int size, byte[] goalCells, int[] tiles) {
        final int cells = size * size;
        final int D = tiles.length;

        // w[j] 是第 j 个数字的位权，即 cells^(D-j)，空格那一位的权重恒为 1
        final int[] w = new int[D];
        for (int j = 0; j < D; j++) {
            w[j] = pow(cells, D - j);
        }
        final int total = pow(cells, D + 1);
        byte[] dist = new byte[total];
        Arrays.fill(dist, (byte) 0xFF); // 0xFF 表示未访问

        // tp[j] 是第 j 个数字在目标盘面上的位置
        final int[] tp = new int[D];
        for (int i = 0; i < cells; i++) {
            for (int j = 0; j < D; j++) {
                if (goalCells[i] == tiles[j]) tp[j] = i;
            }
        }

        // 预先算好各格子的四邻，热循环里就不用做边界判断
        final int[][] nbr = PuzzleNeighbors.forSize(size);

        // 起点是组内数字全部就位、空格在任意其余格子的所有配置，都以 dist=0 入队
        IntDeque deque = new IntDeque(1 << 12);
        int tilesIdx = 0;
        for (int j = 0; j < D; j++) {
            tilesIdx += tp[j] * w[j];
        }
        for (int b = 0; b < cells; b++) {
            boolean occupied = false;
            for (int p : tp) {
                if (p == b) {
                    occupied = true;
                    break;
                }
            }
            if (occupied) continue; // 空格不能和组内数字同格
            dist[tilesIdx + b] = 0;
            deque.addLast(tilesIdx + b);
        }

        // 0-1 BFS 主体，settled 记录已确定的项，防止队列里的旧项被重复展开
        BitSet settled = new BitSet(total);
        int[] p = new int[D]; // 出队状态里各数字的位置，复用同一个数组避免反复分配

        while (!deque.isEmpty()) {
            int idx = deque.pollFirst();
            if (settled.get(idx)) continue;
            settled.set(idx);

            int d = dist[idx] & 0xFF;
            int b = idx % cells; // 最低位是空格位置
            int rest = idx / cells;
            for (int j = D - 1; j >= 0; j--) { // 由低位到高位解出各数字的位置
                p[j] = rest % cells;
                rest /= cells;
            }

            for (int t : nbr[b]) { // 空格与邻格 t 交换
                // 看 t 处是不是组内数字，是就记下它的下标 ti
                int ti = -1;
                for (int j = 0; j < D; j++) {
                    if (p[j] == t) {
                        ti = j;
                        break;
                    }
                }
                int nIdx, cost;
                if (ti >= 0) {
                    // 组内数字 ti 从 t 移到 b、空格从 b 移到 t，下标增量为 (b-t)*(w[ti]-1)
                    nIdx = idx + (b - t) * (w[ti] - 1);
                    cost = 1;
                } else {
                    // 无关数字，抽象上只动了空格，代价为 0
                    nIdx = idx - b + t;
                    cost = 0;
                }
                int nd = d + cost;
                if ((dist[nIdx] & 0xFF) > nd) {
                    dist[nIdx] = (byte) nd;
                    if (cost == 0) deque.addFirst(nIdx); // 0 代价走队首
                    else deque.addLast(nIdx); // 1 代价走队尾
                }
            }
        }
        return dist;
    }

    /** 求 base 的非负整数次幂 */
    private static int pow(int base, int exp) {
        int r = 1;
        for (int i = 0; i < exp; i++) r *= base;
        return r;
    }

    /** 某组距离表的项数，即 cells^(组内数字个数+1) */
    private static int tableLength(int size, int tiles) {
        return pow(size * size, tiles + 1);
    }

    /** 可增长的循环数组 int 双端队列，供 0-1 BFS 使用，避免 Integer 装箱 */
    private static final class IntDeque {
        private int[] buf; // 循环缓冲区
        private int head = 0; // 队首下标
        private int len = 0; // 当前元素个数

        IntDeque(int expectedCapacity) {
            int cap = Math.max(16, Integer.highestOneBit(expectedCapacity - 1) << 1);
            buf = new int[cap];
        }

        boolean isEmpty() {
            return len == 0;
        }

        void addFirst(int v) {
            ensureCapacity();
            head = (head - 1 + buf.length) % buf.length;
            buf[head] = v;
            len++;
        }

        void addLast(int v) {
            ensureCapacity();
            buf[(head + len) % buf.length] = v;
            len++;
        }

        int pollFirst() {
            int v = buf[head];
            head = (head + 1) % buf.length;
            len--;
            return v;
        }

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
