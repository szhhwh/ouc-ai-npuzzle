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

/** 用 ranked 编码（部分排列排名）实现的可加模式数据库启发函数，支持较大的分组。 */
public final class RankedPdbHeuristic {

    /** 已构建表的进程内缓存，key 由棋盘规模和 goal 内容拼成 */
    private static final Map<String, Tables> BUILT = new ConcurrentHashMap<>();

    /** 单槽缓存，保存上一次查询用的 goal 和对应的表 */
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
     * 各棋盘规模的分组定义。4 阶取 7-8，即上两行的 {1..7} 与下两行的 {8..15}，两块在盘面上各自连贯且互补。目前只支持 4 阶，3
     * 阶走乘积版。该分组表很大，内存紧张时可换为 7-7-1。
     */
    private static final Map<Integer, int[][]> GROUPINGS =
            Map.of(4, new int[][] {{1, 2, 3, 4, 5, 6, 7}, {8, 9, 10, 11, 12, 13, 14, 15}});

    private RankedPdbHeuristic() {}

    /** 返回带对称增强的启发函数，每次查询都全量查表。供 A* 和通用 IDA* 使用。 */
    public static Predictor predictor() {
        return (state, goal) -> tablesFor((PuzzleBoard) goal).hMax((PuzzleBoard) state);
    }

    /** 取 goal 对应的整套表，未构建则构建。查找方式与 DisjointPdbHeuristic 一致。 */
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

    /** 某个 goal 对应的全部分组距离表和查询逻辑。字段在构建后只读，线程安全，包内可见，{@link NPuzzleIdaStar} 会直接读来实现增量查询。 */
    static final class Tables {
        final int size;
        final int cells;
        final int[][] groups;

        /** tables[g][q][R] 是组 g 在空格序号 q、排名 R 处的表值 */
        final byte[][][] tables;

        final byte[] goalCells;
        final int[] transpose;
        final int[] symPerm;
        final boolean symmetric;

        /** perm[a][b] 是部分排列数 P(a,b)，按组内最大的 k 预填到对应长度 */
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

        /** 把各组的表值相加 */
        int h(PuzzleBoard state) {
            return hCells(state.cells(), state.blank());
        }

        /** 取直接查表和与对称态查表中的较大值；不满足对称条件时退化为直接查询 */
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

        /** 全量查表，h 和 hMax 的公共实现 */
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

        /** 计算 tuple 的部分排列排名 */
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

        /** 计算空格在剩余位置中的序号 */
        int blankRank(int[] p, int k, int blank) {
            int below = 0;
            for (int i = 0; i < k; i++) {
                if (p[i] < blank) below++;
            }
            return blank - below;
        }
    }

    private static byte[][] loadOrBuildGroup(int size, byte[] goalCells, int[] tiles, int gi) {
        // 文件名带上分组签名，分组常量一变就换文件，配合头部校验避免不同分组误用同一份缓存
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

    /**
     * 从缓存文件读入某一组的距离表，文件不存在或头部字段不匹配时返回 null。
     *
     * @param file 缓存文件路径
     * @param size 棋盘边长
     * @param goalCells 目标盘面
     * @param tiles 组内数字
     * @return 分块距离表，缓存无效时返回 null
     */
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
     * 用 0-1 BFS 构建某一组的距离表，按空格序号分块存储。
     *
     * @param size 棋盘边长
     * @param goalCells 目标盘面
     * @param tiles 组内数字
     * @return 分块距离表
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

        // 组内各数字块在目标盘面上的位置
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

    // 静态编码工具，供构建期使用

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

    /** 由排名逆展开出 tuple，返回剩余位置中最小的那个，调用方再按空格序号推进 */
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

    /** 可增长的循环数组 long 双端队列，供 0-1 BFS 使用 */
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
