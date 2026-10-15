package stud.g01.problem.npuzzle;

import static stud.g01.problem.npuzzle.RankedPdbEncoding.blankRank;
import static stud.g01.problem.npuzzle.RankedPdbEncoding.blankRankOf;
import static stud.g01.problem.npuzzle.RankedPdbEncoding.pack;
import static stud.g01.problem.npuzzle.RankedPdbEncoding.rankOf;

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

    /**
     * 各棋盘规模的分组定义。4 阶取 7-8，即上两行的 {1..7} 与下两行的 {8..15}，两块在盘面上各自连贯且互补。目前只支持 4 阶，3
     * 阶走乘积版。该分组表很大，内存紧张时可换为 7-7-1。
     */
    private static final Map<Integer, int[][]> GROUPINGS =
            Map.of(4, new int[][] {{1, 2, 3, 4, 5, 6, 7}, {8, 9, 10, 11, 12, 13, 14, 15}});

    private RankedPdbHeuristic() {}

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

    /** 某个 goal 对应的全部分组距离表和对称信息。字段在构建后只读，线程安全，{@link RankedPdbContext} 使用这些表维护增量启发值。 */
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

        /** 建表和增量更新共用的排名规则 */
        final RankedPdbEncoding encoding;

        Tables(int size, int[][] groups, byte[][][] tables, byte[] goalCells) {
            this.size = size;
            this.cells = size * size;
            this.groups = groups;
            this.tables = tables;
            this.goalCells = goalCells;

            int maxK = 0;
            for (int[] g : groups) maxK = Math.max(maxK, g.length);
            this.encoding = new RankedPdbEncoding(cells, maxK);

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
    }

    private static byte[][] loadOrBuildGroup(int size, byte[] goalCells, int[] tiles, int gi) {
        // 文件名带上分组签名，分组常量一变就换文件，配合头部校验避免不同分组误用同一份缓存
        StringBuilder sig = new StringBuilder("ranked-" + size + "-" + gi + "-" + tiles.length);
        for (int tile : tiles) sig.append('-').append(tile);
        PdbCache cache = new PdbCache(sig + ".bin", PdbCache.Format.RANKED, size, goalCells, tiles);
        byte[][] cached =
                cache.tryLoad(
                        in -> {
                            int rest = in.readInt();
                            int block = in.readInt();
                            byte[][] data = new byte[rest][];
                            for (int q = 0; q < rest; q++) {
                                data[q] = new byte[block];
                                in.readFully(data[q]);
                            }
                            return data;
                        });
        if (cached != null) {
            System.out.println("[RPDB] " + cache.file + " 缓存命中（" + cached.length + " 块）");
            return cached;
        }
        long t0 = System.currentTimeMillis();
        byte[][] built = buildRankedTable(size, goalCells, tiles);
        System.out.printf(
                "[RPDB] %s 构建（0-1 BFS）完成：%,d 项，耗时 %.1f s%n",
                cache.file,
                (long) built.length * built[0].length,
                (System.currentTimeMillis() - t0) / 1000.0);
        cache.tryWrite(
                out -> {
                    out.writeInt(built.length);
                    out.writeInt(built[0].length);
                    for (byte[] block : built) out.write(block);
                });
        return built;
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
        RankedPdbEncoding encoding = new RankedPdbEncoding(cells, k);
        final int block = encoding.rankCount(k);
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

        int[][] nbr = PuzzleNeighbors.forSize(size);

        LongDeque deque = new LongDeque(1 << 20);
        int base = encoding.rankTuple(tp, k);
        boolean[] usedSrc = new boolean[cells];
        for (int j = 0; j < k; j++) usedSrc[tp[j]] = true;
        for (int b = 0; b < cells; b++) {
            if (!usedSrc[b]) {
                long code = pack(base, blankRank(tp, k, b));
                dist[blankRankOf(code)][rankOf(code)] = 0;
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
            int q0 = blankRankOf(code), r0 = rankOf(code);
            if (settled[q0].get(r0)) continue;
            settled[q0].set(r0);
            settledCount++;
            if ((settledCount & 0x3FFFFFFF) == 0) {
                System.out.printf(
                        "    [RPDB build] settled %,d / %,d%n", settledCount, (long) rest * block);
            }
            int d = dist[q0][r0] & 0xFF;
            int blank = encoding.decode(r0, q0, k, p, used);

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
                    nCode = pack(encoding.rankTuple(p, k), blankRank(p, k, t));
                    p[slot] = t;
                } else {
                    nCode = pack(r0, blankRank(p, k, t));
                }
                int nd = d + (slot >= 0 ? 1 : 0);
                int nq = blankRankOf(nCode), nr = rankOf(nCode);
                if ((dist[nq][nr] & 0xFF) > nd) {
                    dist[nq][nr] = (byte) nd;
                    if (slot >= 0) deque.addLast(nCode);
                    else deque.addFirst(nCode);
                }
            }
        }
        return dist;
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
