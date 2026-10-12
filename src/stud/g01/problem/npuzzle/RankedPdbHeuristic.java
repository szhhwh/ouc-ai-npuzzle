package stud.g01.problem.npuzzle;

import core.solver.algorithm.heuristic.Predictor;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ranked（部分排列排名）编码的可加模式数据库启发函数 —— 大组 PDB。
 *
 * <p>与 {@link DisjointPdbHeuristic}（乘积进制编码）的抽象语义完全相同： 分组、空格免费、多源起点、0-1 BFS 建表、对称增强
 * max。唯一区别是<b>编码</b>： 每组状态 (组内将牌位置元组, 空格位置) 被压缩为部分排列排名 {@code R×(cells−k) + q}，消除乘积编码约 80%
 * 的无效空间。这一压缩使 7/8 将牌的大分组成为可能——乘积编码下 16⁸/16⁹ 项超出 int 寻址，而 ranked 编码下 7 组为 P(16,7)×9 = 5.19 亿、8 组为
 * P(16,8)×8 = 41.5 亿项。
 *
 * <p>【编码】k = 组内将牌数，rest = cells−k 个空格候选位置：
 *
 * <ul>
 *   <li>{@code R = Σᵢ Aᵢ·P(cells−1−i, k−1−i)}，Aᵢ = pᵢ 在剔除前 i 个已用位置后 的剩余序（{@code pᵢ − #{j<i : pⱼ <
 *       pᵢ}}），与「取第 Aᵢ 小未用位置」的 逆展开互逆；
 *   <li>{@code q = 空格在剩余位置中的序 = blank − #{pᵢ < blank}}；
 *   <li>8 将牌组的完整逻辑编码达 41.5 亿（超出 int 与 Java 单数组上限）， 故按 q 分块存储：{@code tables[g][q][R]}，每块恰 P(cells,k)
 *       项。
 * </ul>
 *
 * <p>【可采纳性与一致性】与乘积版逐字相同（空格移动为 0 代价边、空格编入状态）， 见 {@link DisjointPdbHeuristic}
 * 类注释——编码方式不改变抽象状态空间与距离语义。 两者已在实验中交叉验证：同分组下随机状态表值全等。
 *
 * <p>【分组】4 阶取 7-7-1：{@code {1..7}}（上两行去右下角）、 {@code {9..15}}（下两行去左上角）、{@code {8}}（单将牌组，退化为该将牌的
 * 曼哈顿距离下界）。实测（problems3.txt 最难 70 步实例）：6-6-3+对称 1.46 亿 生成结点 → 7-7-1+对称 1422 万，结点收益 10.2×。
 *
 * <p>【增量查询】见 {@link NPuzzleIdaStar}：ranked 编码同样支持逐步增量—— 被动组（被移动将牌所属组）的 R 变化为 O(k)（Lehmer 系数的区间传播），
 * 非被动组表值不变、仅 q 平移，且 q 可由组内将牌位置位图 popcount 得 O(1)。
 *
 * <p>【复杂度与内存】7-7-1 表 2×P(16,7)×9 + P(16,1)×15 ≈ 1.04 GB（每字节一项）， 构建 0-1 BFS 每状态 O(k) 出队，约数分钟，此后走磁盘缓存
 * {@code resources/pdb-cache/ranked-{size}-{组号}.bin}（头部含 goal 原文判废）。
 */
public final class RankedPdbHeuristic {

    /** 已构建表的进程内缓存：key = 盘面规模 + "#" + goal 盘面内容 */
    private static final Map<String, Tables> BUILT = new ConcurrentHashMap<>();

    /** 单槽 memo：上次查询的 (goal 引用, 表)，热路径免去字符串 key 重建 */
    private static volatile Memo lastMemo;

    private static final class Memo {
        final PuzzleBoard goal;
        final Tables tables;

        Memo(PuzzleBoard goal, Tables tables) {
            this.goal = goal;
            this.tables = tables;
        }
    }

    private static final Path CACHE_DIR = Paths.get("resources", "pdb-cache");
    private static final int MAGIC = 0x52444231; // ASCII "RDB1"

    /**
     * 各盘面规模的分组定义（4 阶 7-8：{1..7} 上两行去右下角 + {8..15} 下两行， 几何上各成连贯区块且互补）。实测（problems3.txt 最难 70
     * 步实例，含对称增强）： 6-6-3 乘积 145,767,365 gen → 7-7-1 14,226,368 gen → 7-8 3,150,240 gen。 7-8 表 4.67
     * GB（构建峰值 ~15 GB，一次性后走磁盘缓存）——内存受限场景可将 此常量换为 7-7-1 分组
     * {{1,2,3,4,5,6,7},{9,10,11,12,13,14,15},{8}}（1.04 GB， 结点数约为 7-8 的 4.5 倍）。其它规模不支持（3 阶沿用 6-6-3
     * 乘积版）。
     */
    private static final Map<Integer, int[][]> GROUPINGS =
            Map.of(4, new int[][] {{1, 2, 3, 4, 5, 6, 7}, {8, 9, 10, 11, 12, 13, 14, 15}});

    private RankedPdbHeuristic() {}

    /**
     * 获得 ranked PDB 启发函数（带对称增强，全量查询）。 供通用搜索引擎（A* / 通用 IDA*）使用；{@link NPuzzleIdaStar} 的 热路径走 {@link
     * #tablesFor} 的增量接口。
     */
    public static Predictor predictor() {
        return (state, goal) -> tablesFor((PuzzleBoard) goal).hMax((PuzzleBoard) state);
    }

    /** 按 goal 盘面取（或懒构建）整套 ranked 表。 单槽 memo 引用比较命中 → BUILT 查建，与 DisjointPdbHeuristic 相同模式。 */
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

    // ==================================================================
    //  一套 ranked 表（某个 goal 对应的全部分组）+ 全量查询
    // ==================================================================

    /** 某 goal 盘面对应的全部分组距离表与查询逻辑。构建后只读，线程安全。 字段包内可见：{@link NPuzzleIdaStar} 直接读取以实现增量查询。 */
    static final class Tables {
        final int size;
        final int cells;
        final int[][] groups;

        /** tables[g][q][R]：组 g、空格剩余序 q、tuple 排名 R 的表值 */
        final byte[][][] tables;

        final byte[] goalCells;
        final int[] transpose;
        final int[] symPerm;
        final boolean symmetric;

        /** PERM[a][b] = P(a,b) 部分排列数（按组内最大 k 预填充） */
        final int[][] perm;

        Tables(int size, int[][] groups, byte[][][] tables, byte[] goalCells) {
            this.size = size;
            this.cells = size * size;
            this.groups = groups;
            this.tables = tables;
            this.goalCells = goalCells;

            int maxK = 0;
            for (int[] g : groups) maxK = Math.max(maxK, g.length);
            this.perm = new int[cells + 1][];
            for (int a = 0; a <= cells; a++) {
                perm[a] = new int[Math.min(a, maxK) + 1];
                perm[a][0] = 1;
                long p = 1;
                for (int b = 1; b <= Math.min(a, maxK); b++) {
                    p *= (a - b + 1);
                    perm[a][b] = (int) p;
                }
            }

            this.transpose = new int[cells];
            for (int i = 0; i < cells; i++) {
                transpose[i] = (i % size) * size + i / size;
            }
            int[] goalPos = new int[cells];
            for (int i = 0; i < cells; i++) goalPos[goalCells[i]] = i;
            this.symPerm = new int[cells];
            for (int v = 0; v < cells; v++) {
                symPerm[v] = goalCells[transpose[goalPos[v]]];
            }
            this.symmetric = symPerm[0] == 0;
        }

        static Tables build(PuzzleBoard goal) {
            int size = goal.size();
            int[][] groups = GROUPINGS.get(size);
            if (groups == null) {
                throw new UnsupportedOperationException(size + " 阶未定义 ranked PDB 分组（仅支持 4 阶）");
            }
            byte[][][] ts = new byte[groups.length][][];
            for (int g = 0; g < groups.length; g++) {
                ts[g] = loadOrBuildGroup(size, goal.cells(), groups[g], g);
            }
            return new Tables(size, groups, ts, goal.cells().clone());
        }

        /** 直接查询：各组投影查表求和（全量，O(Σk²)） */
        int h(PuzzleBoard state) {
            return hCells(state.cells(), state.blank());
        }

        /** 对称增强查询：max(h(s), h(T(s)))；守卫与 DisjointPdbHeuristic 相同 */
        int hMax(PuzzleBoard state) {
            int direct = h(state);
            if (!symmetric) {
                return direct;
            }
            byte[] c = state.cells();
            byte[] t = new byte[cells];
            for (int i = 0; i < cells; i++) {
                t[i] = (byte) symPerm[c[transpose[i]]];
            }
            return Math.max(direct, hCells(t, transpose[state.blank()]));
        }

        /** 全量查表核心（h/hMax 的公共实现） */
        private int hCells(byte[] c, int blank) {
            int[] pos = new int[cells];
            for (int i = 0; i < cells; i++) {
                pos[c[i]] = i;
            }
            int[] p = new int[8];
            int sum = 0;
            for (int g = 0; g < groups.length; g++) {
                int[] tiles = groups[g];
                int k = tiles.length;
                for (int j = 0; j < k; j++) {
                    p[j] = pos[tiles[j]];
                }
                sum += tables[g][blankRank(p, k, blank)][rankTuple(p, k)] & 0xFF;
            }
            return sum;
        }

        // ―― ranked 编码（实例方法：用本表规模与 perm 权重） ――

        /** tuple 的部分排列排名（O(k²) 全量）。Aᵢ = pᵢ 的剩余序 */
        int rankTuple(int[] p, int k) {
            int r = 0;
            for (int i = 0; i < k; i++) {
                int below = 0;
                for (int j = 0; j < i; j++) {
                    if (p[j] < p[i]) below++;
                }
                r += (p[i] - below) * perm[cells - 1 - i][k - 1 - i];
            }
            return r;
        }

        /** 空格在剩余位置中的序：blank − #{pᵢ < blank} */
        int blankRank(int[] p, int k, int blank) {
            int below = 0;
            for (int i = 0; i < k; i++) {
                if (p[i] < blank) below++;
            }
            return blank - below;
        }
    }

    // ==================================================================
    //  单组距离表：磁盘缓存与 0-1 BFS 构建（按 q 分块寻址）
    // ==================================================================

    private static byte[][] loadOrBuildGroup(int size, byte[] goalCells, int[] tiles, int gi) {
        // 文件名携带分组签名（组号 + 将牌数 + 各将牌值）：分组常量改变即换文件，
        // 配合头部 tiles 原文校验双重判废，杜绝不同分组间的缓存误用
        StringBuilder sig = new StringBuilder("ranked-" + size + "-" + gi + "-" + tiles.length);
        for (int tile : tiles) sig.append('-').append(tile);
        Path file = CACHE_DIR.resolve(sig + ".bin");
        byte[][] cached = tryLoad(file, size, goalCells, tiles);
        if (cached != null) {
            System.out.println("[RPDB] " + file + " 缓存命中（" + cached.length + " 块）");
            return cached;
        }
        long t0 = System.currentTimeMillis();
        byte[][] built = buildRankedTable(size, goalCells, tiles);
        System.out.printf(
                "[RPDB] %s 构建（0-1 BFS）完成：%,d 项，耗时 %.1f s%n",
                file,
                (long) built.length * built[0].length,
                (System.currentTimeMillis() - t0) / 1000.0);
        tryWrite(file, size, goalCells, tiles, built);
        return built;
    }

    /** 缓存文件布局：magic、size、goal 原文、组将牌数、各将牌值、块数、每块项数、 逐块数据。任一头字段不符即判废重建（goal 改变自然失效）。 */
    private static byte[][] tryLoad(Path file, int size, byte[] goalCells, int[] tiles) {
        if (!Files.isRegularFile(file)) return null;
        try (DataInputStream in =
                new DataInputStream(new BufferedInputStream(Files.newInputStream(file), 1 << 20))) {
            if (in.readInt() != MAGIC) return null;
            if (in.readInt() != size) return null;
            int goalLen = in.readInt();
            if (goalLen != goalCells.length) return null;
            byte[] g = new byte[goalLen];
            in.readFully(g);
            if (!Arrays.equals(g, goalCells)) return null;
            int n = in.readInt();
            if (n != tiles.length) return null;
            for (int tile : tiles) {
                if (in.readInt() != tile) return null;
            }
            int rest = in.readInt();
            int block = in.readInt();
            byte[][] data = new byte[rest][];
            for (int q = 0; q < rest; q++) {
                data[q] = new byte[block];
                in.readFully(data[q]);
            }
            return data;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static void tryWrite(
            Path file, int size, byte[] goalCells, int[] tiles, byte[][] data) {
        try {
            Files.createDirectories(CACHE_DIR);
            try (DataOutputStream out =
                    new DataOutputStream(
                            new BufferedOutputStream(Files.newOutputStream(file), 1 << 20))) {
                out.writeInt(MAGIC);
                out.writeInt(size);
                out.writeInt(goalCells.length);
                out.write(goalCells);
                out.writeInt(tiles.length);
                for (int tile : tiles) out.writeInt(tile);
                out.writeInt(data.length);
                out.writeInt(data[0].length);
                for (byte[] blk : data) out.write(blk);
            }
            System.out.println("[RPDB] 已写缓存 " + file);
        } catch (IOException e) {
            System.out.println("[RPDB] 缓存写入失败（" + file + "）：" + e);
        }
    }

    /**
     * 0-1 BFS 构建（与 DisjointPdbHeuristic.buildGroupTable 同构，寻址换为 ranked 分块；邻居重编码为全量
     * O(k²)，构建为一次性成本，不进热路径）。
     */
    private static byte[][] buildRankedTable(int size, byte[] goalCells, int[] tiles) {
        final int cells = size * size;
        final int k = tiles.length;
        final int rest = cells - k;
        final int block = perm(cells, k);
        byte[][] dist = new byte[rest][];
        for (int q = 0; q < rest; q++) {
            dist[q] = new byte[block];
            Arrays.fill(dist[q], (byte) 0xFF);
        }

        // 组内将牌的目标位置
        int[] tp = new int[k];
        for (int i = 0; i < cells; i++) {
            for (int j = 0; j < k; j++) {
                if (goalCells[i] == tiles[j]) tp[j] = i;
            }
        }

        int[][] nbr = neighbors(size);
        // 静态 perm 权重（构建局部用，与 Tables.perm 同式）
        int[][] perm = new int[cells][];
        for (int a = 0; a < cells; a++) {
            perm[a] = new int[Math.min(a, k) + 1];
            perm[a][0] = 1;
            long p = 1;
            for (int b = 1; b <= Math.min(a, k); b++) {
                p *= (a - b + 1);
                perm[a][b] = (int) p;
            }
        }

        LongDeque deque = new LongDeque(1 << 20);
        int base = rankTuple(tp, k, perm, cells);
        boolean[] usedSrc = new boolean[cells];
        for (int j = 0; j < k; j++) usedSrc[tp[j]] = true;
        for (int b = 0; b < cells; b++) {
            if (!usedSrc[b]) {
                long code = pack(base, blankRank(tp, k, b));
                dist[qOf(code)][rOf(code)] = 0;
                deque.addLast(code);
            }
        }

        BitSet[] settled = new BitSet[rest];
        for (int q = 0; q < rest; q++) settled[q] = new BitSet(block);
        int[] p = new int[k];
        boolean[] used = new boolean[cells];
        long settledCount = 0;

        while (!deque.isEmpty()) {
            long code = deque.pollFirst();
            int q0 = qOf(code), r0 = rOf(code);
            if (settled[q0].get(r0)) continue;
            settled[q0].set(r0);
            settledCount++;
            if ((settledCount & 0x3FFFFFFF) == 0) {
                System.out.printf(
                        "    [RPDB build] settled %,d / %,d%n", settledCount, (long) rest * block);
            }
            int d = dist[q0][r0] & 0xFF;
            int blank = decodeTuple(r0, k, p, used, perm, cells);
            for (int i = 0; i < q0; i++) {
                blank = nextUnused(blank + 1, used);
            }

            for (int t : nbr[blank]) {
                int slot = -1;
                for (int j = 0; j < k; j++) {
                    if (p[j] == t) {
                        slot = j;
                        break;
                    }
                }
                long nCode;
                if (slot >= 0) {
                    p[slot] = blank;
                    nCode = pack(rankTuple(p, k, perm, cells), blankRank(p, k, t));
                    p[slot] = t;
                } else {
                    nCode = pack(r0, blankRank(p, k, t));
                }
                int nd = d + (slot >= 0 ? 1 : 0);
                int nq = qOf(nCode), nr = rOf(nCode);
                if ((dist[nq][nr] & 0xFF) > nd) {
                    dist[nq][nr] = (byte) nd;
                    if (slot >= 0) deque.addLast(nCode);
                    else deque.addFirst(nCode);
                }
            }
        }
        return dist;
    }

    // ―― 静态编码工具（构建期使用；查询/增量走 Tables 实例方法） ――

    static long pack(int r, int q) {
        return ((long) r << 6) | q;
    }

    static int qOf(long c) {
        return (int) (c & 63);
    }

    static int rOf(long c) {
        return (int) (c >>> 6);
    }

    static int perm(int n, int k) {
        long r = 1;
        for (int i = 0; i < k; i++) r *= (n - i);
        return (int) r;
    }

    static int rankTuple(int[] p, int k, int[][] perm, int cells) {
        int r = 0;
        for (int i = 0; i < k; i++) {
            int below = 0;
            for (int j = 0; j < i; j++) {
                if (p[j] < p[i]) below++;
            }
            r += (p[i] - below) * perm[cells - 1 - i][k - 1 - i];
        }
        return r;
    }

    static int blankRank(int[] p, int k, int blank) {
        int below = 0;
        for (int i = 0; i < k; i++) {
            if (p[i] < blank) below++;
        }
        return blank - below;
    }

    /** 逆展开 R → tuple；返回剩余位置中最小者（调用方按 q 推进到真正的空格位） */
    static int decodeTuple(int r, int k, int[] p, boolean[] used, int[][] perm, int cells) {
        Arrays.fill(used, false);
        for (int i = 0; i < k; i++) {
            int need = r / perm[cells - 1 - i][k - 1 - i];
            r %= perm[cells - 1 - i][k - 1 - i];
            int pos = -1;
            for (int c = 0; c < cells; c++) {
                if (!used[c] && --need < 0) {
                    pos = c;
                    break;
                }
            }
            p[i] = pos;
            used[pos] = true;
        }
        for (int c = 0; c < cells; c++) {
            if (!used[c]) return c;
        }
        return -1;
    }

    private static int nextUnused(int from, boolean[] used) {
        for (int c = from; c < used.length; c++) {
            if (!used[c]) return c;
        }
        return -1;
    }

    private static int[][] neighbors(int size) {
        int cells = size * size;
        int[][] res = new int[cells][];
        int[] buf = new int[4];
        for (int i = 0; i < cells; i++) {
            int r = i / size, c = i % size, n = 0;
            if (r > 0) buf[n++] = i - size;
            if (r < size - 1) buf[n++] = i + size;
            if (c > 0) buf[n++] = i - 1;
            if (c < size - 1) buf[n++] = i + 1;
            res[i] = Arrays.copyOf(buf, n);
        }
        return res;
    }

    // ==================================================================
    //  long 双端队列（0-1 BFS）
    // ==================================================================

    static final class LongDeque {
        private long[] buf;
        private int head = 0;
        private int len = 0;

        LongDeque(int expectedCapacity) {
            int cap = Math.max(16, Integer.highestOneBit(expectedCapacity - 1) << 1);
            buf = new long[cap];
        }

        boolean isEmpty() {
            return len == 0;
        }

        void addFirst(long v) {
            ensureCapacity();
            head = (head - 1 + buf.length) % buf.length;
            buf[head] = v;
            len++;
        }

        void addLast(long v) {
            ensureCapacity();
            buf[(head + len) % buf.length] = v;
            len++;
        }

        long pollFirst() {
            long v = buf[head];
            head = (head + 1) % buf.length;
            len--;
            return v;
        }

        private void ensureCapacity() {
            if (len < buf.length) return;
            long[] nb = new long[buf.length << 1];
            for (int i = 0; i < len; i++) {
                nb[i] = buf[(head + i) % buf.length];
            }
            buf = nb;
            head = 0;
        }
    }
}
