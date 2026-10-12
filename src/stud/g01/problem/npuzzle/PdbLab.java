package stud.g01.problem.npuzzle;

import core.problem.Problem;
import core.solver.algorithm.heuristic.Predictor;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import stud.queue.StackFrontier;
import stud.solver.IdAStar;

/**
 * 实验场：ranked（部分排列排名）编码的可加 PDB —— 验证 7-7-1 / 7-8 分组的结点收益。
 *
 * <p>与 {@link DisjointPdbHeuristic} 的乘积编码相比，ranked 编码把每组状态 (组内将牌位置元组, 空格位置) 压缩到连续区间，消除乘积编码约 80%
 * 的无效空间： 6-6-3 由 512 MB 降至 110 MB，并使 7/8 将牌大组（乘积编码 16⁸/16⁹ 超出 int 寻址） 成为可能。
 *
 * <p>编码（k = 组内将牌数，rest = 16−k 个空格候选位置）：
 *
 * <ul>
 *   <li>R = Σᵢ Aᵢ·P(15−i, k−1−i)，Aᵢ = pᵢ 在剔除前 i 个已用位置后的剩余序 （与「取第 Aᵢ 小未用位置」的逆展开互逆）；
 *   <li>q = 空格在剩余位置中的序 = blank − #{pᵢ < blank}；
 *   <li>完整逻辑编码 = R×rest + q。因 8 将牌组该值达 41.5 亿（超出 int 与 Java 数组上限），实现按 q 分块：dist/表/位图均为 rest 块、每块
 *       P(16,k) 项。
 * </ul>
 *
 * <p>用法：{@code java stud.g01.problem.npuzzle.PdbLab <问题文件> <行号> [771|78]}。 表构建后缓存于
 * /tmp/pdblab/。求解用通用 IdAStar + 全量查询（只看结点数，不看速度）。
 */
public final class PdbLab {

    private static final int CELLS = 16;

    /** PERM[a][b] = P(a, b) = a!/(a−b)!（部分排列数） */
    private static final int[][] PERM = new int[CELLS + 1][CELLS + 1];

    static {
        for (int a = 0; a <= CELLS; a++) {
            PERM[a][0] = 1;
            long p = 1;
            for (int b = 1; b <= a; b++) {
                p *= (a - b + 1);
                PERM[a][b] = (int) p;
            }
        }
    }

    private static final Path CACHE = Paths.get("/tmp/pdblab");

    // ――――――――――――――――――――― ranked 编码工具 ―――――――――――――――――――――

    /** tuple 的部分排列排名（O(k²) 全量）。Aᵢ = pᵢ 的剩余序：pᵢ − #{j<i : pⱼ < pᵢ} */
    static int rankTuple(int[] p, int k) {
        int r = 0;
        for (int i = 0; i < k; i++) {
            int below = 0;
            for (int j = 0; j < i; j++) {
                if (p[j] < p[i]) below++;
            }
            r += (p[i] - below) * PERM[15 - i][k - 1 - i];
        }
        return r;
    }

    /** 空格在剩余位置中的序：blank − #{pᵢ < blank} */
    static int blankRank(int[] p, int k, int blank) {
        int below = 0;
        for (int i = 0; i < k; i++) {
            if (p[i] < blank) below++;
        }
        return blank - below;
    }

    /** 逆展开：R → tuple（p[k] 不使用）；q → blank（返回值）。used 复用调用方数组 */
    static int decodeTuple(int r, int k, int[] p, boolean[] used) {
        Arrays.fill(used, false);
        for (int i = 0; i < k; i++) {
            int need = r / PERM[15 - i][k - 1 - i];
            r %= PERM[15 - i][k - 1 - i];
            int pos = -1;
            for (int c = 0; c < CELLS; c++) {
                if (!used[c] && --need < 0) {
                    pos = c;
                    break;
                }
            }
            p[i] = pos;
            used[pos] = true;
        }
        int blank = -1;
        for (int c = 0; c < CELLS; c++) {
            if (!used[c]) {
                blank = c;
                break;
            }
        }
        return blank; // 剩余位置中最小者，供 q>0 时继续推进
    }

    /** q → 空格位置：剩余未用位置中的第 q 小（used 已由 decodeTuple 填好） */
    static int blankOf(int q, boolean[] used) {
        int blank = -1;
        for (int c = 0; c < CELLS; c++) {
            if (!used[c] && --q < 0) {
                blank = c;
                break;
            }
        }
        return blank;
    }

    // ――――――――――――――――――――― 表构建（0-1 BFS，按 q 分块寻址） ―――――――――――――――――――――

    private static int[][] nbr4() {
        int[][] nbr = new int[CELLS][];
        int[] buf = new int[4];
        for (int i = 0; i < CELLS; i++) {
            int r = i / 4, c = i % 4, n = 0;
            if (r > 0) buf[n++] = i - 4;
            if (r < 3) buf[n++] = i + 4;
            if (c > 0) buf[n++] = i - 1;
            if (c < 3) buf[n++] = i + 1;
            nbr[i] = Arrays.copyOf(buf, n);
        }
        return nbr;
    }

    /** 为组 tiles 构建 ranked 距离表（对给定 goal 布局）。 返回 rest 个块：result[q][R] = 表值。0-1 BFS 与乘积版同构。 */
    static byte[][] buildRanked(int[] tiles, byte[] goalCells) {
        int k = tiles.length;
        int rest = CELLS - k;
        int block = PERM[CELLS][k];
        byte[][] dist = new byte[rest][];
        for (int q = 0; q < rest; q++) {
            dist[q] = new byte[block];
            Arrays.fill(dist[q], (byte) 0xFF);
        }

        int[] tp = new int[k];
        for (int i = 0; i < CELLS; i++) {
            for (int j = 0; j < k; j++) {
                if (goalCells[i] == tiles[j]) tp[j] = i;
            }
        }

        int[][] nbr = nbr4();
        LongDeque deque = new LongDeque(1 << 20);
        // 多源：tuple = 目标布局（R 固定），空格在任意剩余位置（q 全取）
        int base = rankTuple(tp, k);
        boolean[] usedSrc = new boolean[CELLS];
        for (int j = 0; j < k; j++) usedSrc[tp[j]] = true;
        for (int b = 0; b < CELLS; b++) {
            if (!usedSrc[b]) {
                long code = pack(base, blankRank(tp, k, b));
                dist[qOf(code)][rOf(code)] = 0;
                deque.addLast(code);
            }
        }

        BitSet[] settled = new BitSet[rest];
        for (int q = 0; q < rest; q++) settled[q] = new BitSet(block);
        int[] p = new int[k];
        boolean[] used = new boolean[CELLS];
        long settledCount = 0;

        while (!deque.isEmpty()) {
            long code = deque.pollFirst();
            int q0 = qOf(code), r0 = rOf(code);
            if (settled[q0].get(r0)) continue;
            settled[q0].set(r0);
            settledCount++;
            if ((settledCount & 0x3FFFFFF) == 0) {
                System.out.printf(
                        "    [build] settled %,d / %,d%n", settledCount, (long) rest * block);
            }
            int d = dist[q0][r0] & 0xFF;
            int blank = decodeTuple(r0, k, p, used);
            for (int i = 0; i < q0; i++) {
                blank = nextUnused(blank + 1, used); // 推进到第 q0 小
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
                    p[slot] = blank; // 将牌 slot 移到旧空格位
                    nCode = pack(rankTuple(p, k), blankRank(p, k, t));
                    p[slot] = t;
                } else {
                    // tuple 不变，仅空格序从 q0 变为 t 的剩余序
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

    /** code 打包 / 拆包：R×64 + q（R < 2^30，q < 16） */
    static long pack(int r, int q) {
        return ((long) r << 6) | q;
    }

    static int qOf(long c) {
        return (int) (c & 63);
    }

    static int rOf(long c) {
        return (int) (c >>> 6);
    }

    private static int nextUnused(int from, boolean[] used) {
        for (int c = from; c < CELLS; c++) {
            if (!used[c]) return c;
        }
        return -1;
    }

    // ――――――――――――――――――――― 一套 ranked 表 + 查询（含对称增强） ―――――――――――――――――――――

    static final class RankedTables {
        final int[][] groups;
        final byte[][][] tables; // tables[g][q][R]
        final int[] transpose = new int[CELLS];
        final int[] symPerm;
        final byte[] goalCells;
        final boolean symmetric;

        RankedTables(int[][] groups, byte[][][] tables, byte[] goalCells) {
            this.groups = groups;
            this.tables = tables;
            this.goalCells = goalCells;
            for (int i = 0; i < CELLS; i++) {
                transpose[i] = (i % 4) * 4 + i / 4;
            }
            int[] goalPos = new int[CELLS];
            for (int i = 0; i < CELLS; i++) goalPos[goalCells[i]] = i;
            symPerm = new int[CELLS];
            for (int v = 0; v < CELLS; v++) {
                symPerm[v] = goalCells[transpose[goalPos[v]]];
            }
            symmetric = symPerm[0] == 0;
        }

        int h(byte[] c, int blank) {
            int[] pos = new int[CELLS];
            for (int i = 0; i < CELLS; i++) pos[c[i]] = i;
            int sum = 0;
            int[] p = new int[8];
            for (int g = 0; g < groups.length; g++) {
                int[] tiles = groups[g];
                for (int j = 0; j < tiles.length; j++) p[j] = pos[tiles[j]];
                sum +=
                        tables[g][blankRank(p, tiles.length, blank)][rankTuple(p, tiles.length)]
                                & 0xFF;
            }
            return sum;
        }

        int hMax(byte[] c, int blank) {
            int direct = h(c, blank);
            if (!symmetric) return direct;
            byte[] t = new byte[CELLS];
            for (int i = 0; i < CELLS; i++) t[i] = (byte) symPerm[c[transpose[i]]];
            return Math.max(direct, h(t, transpose[blank]));
        }
    }

    // ――――――――――――――――――――― 分组定义与磁盘缓存 ―――――――――――――――――――――

    static final int[][] G_771 = {{1, 2, 3, 4, 5, 6, 7}, {9, 10, 11, 12, 13, 14, 15}, {8}};
    static final int[][] G_78 = {{1, 2, 3, 4, 5, 6, 7}, {8, 9, 10, 11, 12, 13, 14, 15}};

    /** 对照形状：7 组取左上 3×3 的蛇形紧凑块（与横条划分及其转置像都不同构） */
    static final int[][] G_78C = {{1, 2, 3, 5, 6, 7, 9}, {4, 8, 10, 11, 12, 13, 14, 15}};

    static byte[][] loadOrBuild(int gi, int[] tiles, byte[] goalCells) {
        String name = "ranked-" + gi + "-" + tiles.length + "-" + Arrays.hashCode(tiles) + ".bin";
        Path file = CACHE.resolve(name);
        if (Files.isRegularFile(file)) {
            try (DataInputStream in =
                    new DataInputStream(
                            new BufferedInputStream(Files.newInputStream(file), 1 << 20))) {
                int rest = in.readInt();
                int block = in.readInt();
                byte[][] data = new byte[rest][];
                for (int q = 0; q < rest; q++) {
                    data[q] = new byte[block];
                    in.readFully(data[q]);
                }
                System.out.println("[lab] " + name + " 缓存命中");
                return data;
            } catch (IOException e) {
                // falls through to rebuild
            }
        }
        long t0 = System.currentTimeMillis();
        byte[][] built = buildRanked(tiles, goalCells);
        System.out.printf(
                "[lab] %s 构建完成：%,d 项，%.1f s%n",
                name,
                (long) built.length * built[0].length,
                (System.currentTimeMillis() - t0) / 1000.0);
        try {
            Files.createDirectories(CACHE);
            try (DataOutputStream out =
                    new DataOutputStream(
                            new BufferedOutputStream(Files.newOutputStream(file), 1 << 20))) {
                out.writeInt(built.length);
                out.writeInt(built[0].length);
                for (byte[] blk : built) out.write(blk);
            }
            System.out.println("[lab] 已写缓存 " + file);
        } catch (IOException e) {
            System.out.println("[lab] 缓存写失败：" + e);
        }
        return built;
    }

    // ――――――――――――――――――――― main：构建 + 用通用 IdAStar 求解 ―――――――――――――――――――――

    public static void main(String[] args) throws IOException {
        ArrayList<String> lines = new ArrayList<>(Files.readAllLines(Path.of(args[0])));
        stud.g01.runner.PuzzleFeeder feeder = new stud.g01.runner.PuzzleFeeder();
        ArrayList<Problem> problems = feeder.getProblems(lines);
        Problem problem = problems.get(Integer.parseInt(args[1]) - 1);
        String config = args.length >= 3 ? args[2] : "771";

        PuzzleBoard goal = ((NPuzzleProblem) problem).goalBoard();
        int[][] groups = config.equals("78") ? G_78 : (config.equals("78c") ? G_78C : G_771);
        byte[][][] tables = new byte[groups.length][][];
        for (int g = 0; g < groups.length; g++) {
            System.out.printf("[lab] 组 %d：%s%n", g, Arrays.toString(groups[g]));
            tables[g] = loadOrBuild(g, groups[g], goal.cells());
        }
        RankedTables rt = new RankedTables(groups, tables, goal.cells());
        System.out.println("[lab] 对称增强可用 = " + rt.symmetric);

        Predictor predictor =
                (state, g) -> {
                    PuzzleBoard b = (PuzzleBoard) state;
                    return rt.hMax(b.cells(), b.blank());
                };

        IdAStar ida = new IdAStar(new StackFrontier(), predictor);
        long t0 = System.nanoTime();
        java.util.Deque<core.solver.queue.Node> path = ida.search(problem);
        double sec = (System.nanoTime() - t0) / 1e9;
        System.out.printf(
                "[lab] 配置=%s 解长=%d 生成=%d 扩展=%d 耗时=%.2f s%n",
                config, path.size(), ida.nodesGenerated(), ida.nodesExpanded(), sec);
    }

    private PdbLab() {}

    // ――――――――――――――――――――― long 双端队列（0-1 BFS 用） ―――――――――――――――――――――

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
