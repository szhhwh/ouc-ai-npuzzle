package stud.g01.problem.npuzzle;

import core.problem.Problem;
import core.solver.algorithm.heuristic.Predictor;
import core.solver.algorithm.searcher.AbstractSearcher;
import core.solver.queue.Frontier;
import core.solver.queue.Node;
import java.util.Arrays;
import java.util.Deque;
import stud.solver.IdAStar;

/** N-Puzzle 专用的 IDA*。3 阶和 4 阶走增量启发值的特化路径，其余问题转发给 {@link IdAStar}。 */
public final class NPuzzleIdaStar extends AbstractSearcher {

    /** f 值的正无穷哨兵，dfs 返回它表示本子树没有因 f 超过界限而被剪枝的结点 */
    private static final int INFINITY = Integer.MAX_VALUE;

    /** dfs 深度上限，取值比 4 阶 15-Puzzle 的状态空间直径 80 略大 */
    private static final int MAX_DEPTH = 88;

    /** 降级路径使用的启发函数 */
    private final Predictor predictor;

    /**
     * @param frontier 占位参数，仅用于兼容 {@code EngineFeeder} 的统一构造方式，IDA* 不使用
     * @param predictor 降级路径使用的启发函数
     */
    public NPuzzleIdaStar(Frontier frontier, Predictor predictor) {
        super(frontier);
        this.predictor = predictor;
    }

    /**
     * 求解入口：3/4 阶 N-Puzzle 走增量 PDB 的专用 IDA*（4 阶 ranked 7-7-1、 3 阶乘积 3-3-2），其余问题降级转发通用 IdAStar。
     *
     * @param problem 待求解问题
     * @return 解路径（不含根结点）；无解返回 null；初始即目标返回空路径
     */
    @Override
    public Deque<Node> search(Problem problem) {
        if (problem instanceof NPuzzleProblem) {
            PuzzleBoard initial = (PuzzleBoard) problem.root().getState();
            if (initial.size() == 4) {
                return searchSpecialized(
                        problem,
                        initial,
                        new RankedCtx(initial, ((NPuzzleProblem) problem).goalBoard()));
            }
            if (initial.size() == 3) {
                return searchSpecialized(
                        problem,
                        initial,
                        new ProdCtx(initial, ((NPuzzleProblem) problem).goalBoard()));
            }
        }
        return searchGeneric(problem);
    }

    /** 降级路径：转发通用 IdAStar，并把结点计数拷回，保持对外的计数口径一致 */
    private Deque<Node> searchGeneric(Problem problem) {
        IdAStar generic = new IdAStar(frontier, predictor);
        Deque<Node> path = generic.search(problem);
        nodesGenerated = generic.nodesGenerated();
        nodesExpanded = generic.nodesExpanded();
        return path;
    }

    /** 特化路径主体：用搜索上下文跑奇偶对齐的 IDA* */
    private Deque<Node> searchSpecialized(Problem problem, PuzzleBoard initial, SearchCtx ctx) {
        // 不可解判定与通用版一致
        if (!problem.solvable()) {
            return null;
        }
        nodesGenerated = 0;
        nodesExpanded = 0;

        ctx.run();

        nodesGenerated = ctx.gen;
        nodesExpanded = ctx.exp;
        if (ctx.foundMoves == null) {
            return null; // 可解却搜遍全树，只在启发值异常时才会出现
        }
        return buildPath(problem, initial, ctx.foundMoves);
    }

    /**
     * 把记录的移动序列重放成框架的 Node 父指针链，再交给 {@link AbstractSearcher#generatePath}
     * 构造解路径。重放只在求解成功后做一次，不参与搜索热路径。
     *
     * @param problem 待求解的问题
     * @param initial 初始盘面
     * @param foundMoves 每步空格移动后的目标格下标
     * @return 解路径
     */
    private Deque<Node> buildPath(Problem problem, PuzzleBoard initial, int[] foundMoves) {
        int size = initial.size();
        Node root = problem.root(predictor);
        Node cur = root;
        PuzzleBoard prev = initial;
        for (int t : foundMoves) {
            PuzzleMove move = moveOf(prev.blank(), t, size);
            PuzzleBoard next = (PuzzleBoard) prev.next(move);
            cur = new Node(next, cur, move, cur.getPathCost() + 1);
            prev = next;
        }
        return generatePath(cur);
    }

    /** 由移动前和移动后的空格位置还原方向动作。四邻关系里恰好有一种方向成立 */
    private static PuzzleMove moveOf(int from, int to, int size) {
        if (to == from - size) return PuzzleMove.UP;
        if (to == from + size) return PuzzleMove.DOWN;
        if (to == from - 1) return PuzzleMove.LEFT;
        return PuzzleMove.RIGHT;
    }

    /** 一次特化搜索的共享状态，启发值的维护由两个后端子类各自实现 */
    private abstract static class SearchCtx {

        final int size;
        final int cells;
        final int[][] nbr;
        final long[] shift;

        // 就地修改的搜索状态
        byte[] board;
        int blank;
        long code;

        // 直链和对称链的启发值，由后端增量维护
        int hD, hS;
        boolean symmetric;

        // 迭代过程和结果
        final int[] moves = new int[MAX_DEPTH];
        final int[][] tBuf = new int[MAX_DEPTH][4];
        final int[][] fBuf = new int[MAX_DEPTH][4];
        int[] foundMoves;
        final LongHashSet pathSet = new LongHashSet(512);
        int gen, exp;

        /** 目标盘面原文，用于计算解长奇偶和校验建表结果 */
        final byte[] goalCells;

        SearchCtx(PuzzleBoard initial, PuzzleBoard goal) {
            this.size = initial.size();
            this.cells = size * size;
            this.goalCells = goal.cells();
            this.nbr = neighbors(size);
            this.shift = new long[cells];
            for (int i = 0; i < cells; i++) {
                shift[i] = 4L * (cells - 1 - i);
            }
            this.board = initial.cells().clone();
            this.blank = initial.blank();
            for (int i = 0; i < cells; i++) {
                code = (code << 4) | board[i];
            }
            // 等子类构造函数完成启发值初始化后再调用 run()
        }

        /** 当前结点的启发值，取直链和对称链的较大值 */
        final int hMax() {
            return symmetric ? Math.max(hD, hS) : hD;
        }

        /** 计算空格移到 t 后子结点的 f 值，只查被移动数字块所属组的表 */
        abstract int fChild(int depth, int t);

        /**
         * 应用一次移动：空格移到 t，board[t] 上的数字块移到原空格位置。撤销时用 apply(原空格位置) 即可。
         *
         * @param t 空格移动后的位置
         */
        abstract void apply(int t);

        /** 奇偶对齐的迭代加深主体。返回时 foundMoves 非 null 表示找到最优解，空数组表示初始即目标 */
        final void run() {
            int parity = psi(board) ^ psi(goalCells);
            int h0 = hMax();
            // 初始界限取 h(root)。若与解长奇偶不符，则 h(root) 必然小于最优解长，加一仍不超过最优解长
            int bound = ((h0 ^ parity) & 1) != 0 ? h0 + 1 : h0;

            while (foundMoves == null) {
                int nextBound = dfs(0, bound);
                if (nextBound == INFINITY) {
                    return; // 搜遍全树仍未找到目标，可解性已经检查过，实际不会走到这里
                }
                // 界限与解长奇偶不符时这一层必然无解，直接抬到下一个同奇偶的界限
                bound = ((nextBound ^ parity) & 1) != 0 ? nextBound + 1 : nextBound;
            }
        }

        /**
         * 深度优先搜索一轮，语义与通用 IdAStar.dfs 一致。
         *
         * @param depth 当前深度
         * @param bound 本轮迭代的 f 值上限
         * @return 本子树中被剪枝结点的最小 f 值
         */
        private final int dfs(int depth, int bound) {
            int f = depth + hMax();
            if (f > bound) {
                return f;
            }
            if (hD == 0) { // 直链启发值为 0 即到达目标
                foundMoves = Arrays.copyOf(moves, depth);
                return INFINITY;
            }
            exp++;

            // 当前状态加入路径集合，回溯时再移除
            pathSet.add(code);

            // 生成全部合法子结点并计算 f，方向顺序固定，供插入排序保持稳定
            int[] ts = tBuf[depth];
            int[] fs = fBuf[depth];
            int n = 0;
            for (int t : nbr[blank]) {
                gen++;
                ts[n] = t;
                fs[n] = fChild(depth, t);
                n++;
            }
            for (int i = 1; i < n; i++) {
                int kt = ts[i], kf = fs[i], j = i - 1;
                while (j >= 0 && fs[j] > kf) {
                    ts[j + 1] = ts[j];
                    fs[j + 1] = fs[j];
                    j--;
                }
                ts[j + 1] = kt;
                fs[j + 1] = kf;
            }

            int min = INFINITY;
            for (int i = 0; i < n; i++) {
                int t = ts[i];
                int from = blank;
                // 先用编码增量算出子结点的键做环检测，命中路径内已有状态就跳过，省掉一次 apply 和撤销
                long childCode =
                        code + ((long) board[t] << shift[from]) - ((long) board[t] << shift[t]);
                if (pathSet.contains(childCode)) {
                    continue;
                }
                apply(t);
                moves[depth] = t;
                int r = dfs(depth + 1, bound);
                apply(from); // 撤销移动，盘面、编码和启发值全部复原
                if (foundMoves != null) {
                    return INFINITY; // 已找到目标，下一层界限没有意义，直接逐层返回
                }
                if (r < min) {
                    min = r;
                }
            }

            pathSet.remove(code); // 回溯，把当前状态移出路径集合
            return min;
        }

        /** 含空格的完整序列的逆序数奇偶。空格记为 0，是最小的值 */
        private static int psi(byte[] c) {
            int inv = 0;
            for (int i = 0; i < c.length; i++) {
                for (int j = i + 1; j < c.length; j++) {
                    if (c[i] > c[j]) {
                        inv++;
                    }
                }
            }
            return inv & 1;
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
    }

    /** 乘积编码的搜索上下文，用位权增量维护启发值，适用于 3 阶 */
    private static final class ProdCtx extends SearchCtx {

        private final DisjointPdbHeuristic.Tables tbl;
        private final int[] tileGroup, tileSlot;
        private final int[][] weight;
        private final int[] transpose, symPerm;
        private byte[] symBoard;
        private final int[] idx, idxSym;

        ProdCtx(PuzzleBoard initial, PuzzleBoard goal) {
            super(initial, goal);
            tbl = DisjointPdbHeuristic.tablesFor(goal);

            weight = new int[tbl.groups.length][];
            for (int g = 0; g < tbl.groups.length; g++) {
                int d = tbl.groups[g].length;
                weight[g] = new int[d];
                for (int j = 0; j < d; j++) {
                    weight[g][j] = pow(cells, d - j);
                }
            }
            tileGroup = new int[cells];
            tileSlot = new int[cells];
            for (int g = 0; g < tbl.groups.length; g++) {
                for (int j = 0; j < tbl.groups[g].length; j++) {
                    tileGroup[tbl.groups[g][j]] = g;
                    tileSlot[tbl.groups[g][j]] = j;
                }
            }
            transpose = tbl.transpose;
            symPerm = tbl.symPerm;

            // 初始编码一次算好，之后全部增量更新
            int[] pos = new int[cells];
            for (int i = 0; i < cells; i++) {
                pos[board[i]] = i;
            }
            idx = new int[tbl.groups.length];
            idxSym = new int[tbl.groups.length];
            // inv 是 symPerm 的逆置换，用来定位对称盘面里各数字块的位置
            int[] inv = new int[cells];
            for (int v = 0; v < cells; v++) {
                inv[symPerm[v]] = v;
            }
            for (int g = 0; g < tbl.groups.length; g++) {
                int[] tiles = tbl.groups[g];
                int e = 0;
                for (int j = 0; j < tiles.length; j++) {
                    e += pos[tiles[j]] * weight[g][j];
                }
                idx[g] = e + blank;
                hD += tbl.tables[g][idx[g]] & 0xFF;

                if (tbl.symmetric) {
                    int es = 0;
                    for (int j = 0; j < tiles.length; j++) {
                        es += transpose[pos[inv[tiles[j]]]] * weight[g][j];
                    }
                    idxSym[g] = es + transpose[blank];
                    hS += tbl.tables[g][idxSym[g]] & 0xFF;
                }
            }
            symmetric = tbl.symmetric;
            symBoard = new byte[cells];
            if (symmetric) {
                for (int i = 0; i < cells; i++) {
                    symBoard[i] = (byte) symPerm[board[transpose[i]]];
                }
            }
        }

        @Override
        int fChild(int depth, int t) {
            int b = blank;
            byte v = board[t];
            int g = tileGroup[v], j = tileSlot[v];
            byte[] tab = tbl.tables[g];
            int nw = idx[g] + (b - t) * (weight[g][j] - 1);
            int hv = hD + (tab[nw] & 0xFF) - (tab[idx[g]] & 0xFF);
            if (symmetric) {
                int sv = symPerm[v];
                int gs = tileGroup[sv], js = tileSlot[sv];
                byte[] tabS = tbl.tables[gs];
                int nwS = idxSym[gs] + (transpose[b] - transpose[t]) * (weight[gs][js] - 1);
                int hs2 = hS + (tabS[nwS] & 0xFF) - (tabS[idxSym[gs]] & 0xFF);
                if (hs2 > hv) {
                    hv = hs2;
                }
            }
            return depth + 1 + hv;
        }

        @Override
        void apply(int t) {
            int b = blank;
            byte v = board[t];
            int g = tileGroup[v], j = tileSlot[v];
            byte[] tab = tbl.tables[g];
            int old = idx[g];
            int nw = old + (b - t) * (weight[g][j] - 1);
            hD += (tab[nw] & 0xFF) - (tab[old] & 0xFF);
            idx[g] = nw;
            // 其余组的表值不变，只需同步平移编码里的空格维度
            int dbt = t - b;
            for (int gg = 0; gg < idx.length; gg++) {
                if (gg != g) {
                    idx[gg] += dbt;
                }
            }

            board[b] = v;
            board[t] = 0;

            if (symmetric) {
                int tb = transpose[b], tt = transpose[t];
                byte sv = (byte) symPerm[v];
                int gs = tileGroup[sv], js = tileSlot[sv];
                byte[] tabS = tbl.tables[gs];
                int oldS = idxSym[gs];
                int nwS = oldS + (tb - tt) * (weight[gs][js] - 1);
                hS += (tabS[nwS] & 0xFF) - (tabS[oldS] & 0xFF);
                idxSym[gs] = nwS;
                int dbs = tt - tb;
                for (int gg = 0; gg < idxSym.length; gg++) {
                    if (gg != gs) {
                        idxSym[gg] += dbs;
                    }
                }
                symBoard[tb] = sv;
                symBoard[tt] = 0;
            }

            code += ((long) v << shift[b]) - ((long) v << shift[t]);
            blank = t;
        }

        private static int pow(int base, int exp) {
            int r = 1;
            for (int i = 0; i < exp; i++) {
                r *= base;
            }
            return r;
        }
    }

    /** ranked 编码的搜索上下文，用排名增量维护启发值，适用于 4 阶 */
    private static final class RankedCtx extends SearchCtx {

        private final RankedPdbHeuristic.Tables tbl;
        private final int[][] tilesOf;
        private final int[] tileGroup, tileSlot;
        private final int[] transpose, symPerm;

        // 数字块在直链和对称链上的位置，逐步增量维护
        private final int[] pos, posSym;
        private byte[] symBoard;

        // 每组的排名和空格序号，直链与对称链各一套
        private final int[] rG, qG, rS, qS;
        // 每组的数字块位置位图，bit i 为 1 表示该组有数字块在格子 i
        private final int[] maskG, maskS;

        RankedCtx(PuzzleBoard initial, PuzzleBoard goal) {
            super(initial, goal);
            tbl = RankedPdbHeuristic.tablesFor(goal);

            int gn = tbl.groups.length;
            tilesOf = tbl.groups;
            tileGroup = new int[cells];
            tileSlot = new int[cells];
            for (int g = 0; g < gn; g++) {
                for (int j = 0; j < tbl.groups[g].length; j++) {
                    tileGroup[tbl.groups[g][j]] = g;
                    tileSlot[tbl.groups[g][j]] = j;
                }
            }
            transpose = tbl.transpose;
            symPerm = tbl.symPerm;

            pos = new int[cells];
            for (int i = 0; i < cells; i++) {
                pos[board[i]] = i;
            }

            rG = new int[gn];
            qG = new int[gn];
            rS = new int[gn];
            qS = new int[gn];
            maskG = new int[gn];
            maskS = new int[gn];

            symmetric = tbl.symmetric;
            symBoard = new byte[cells];
            if (symmetric) {
                for (int i = 0; i < cells; i++) {
                    symBoard[i] = (byte) symPerm[board[transpose[i]]];
                }
                posSym = new int[cells];
                for (int i = 0; i < cells; i++) {
                    posSym[symBoard[i]] = i;
                }
            } else {
                posSym = new int[cells];
            }

            // 初始编码一次算好，之后全部增量更新
            int[] p = new int[8];
            int[] ps = new int[8];
            for (int g = 0; g < gn; g++) {
                int[] tiles = tilesOf[g];
                int k = tiles.length;
                for (int j = 0; j < k; j++) {
                    p[j] = pos[tiles[j]];
                    maskG[g] |= 1 << p[j];
                }
                rG[g] = tbl.rankTuple(p, k);
                qG[g] = tbl.blankRank(p, k, blank);
                hD += tbl.tables[g][qG[g]][rG[g]] & 0xFF;

                if (symmetric) {
                    for (int j = 0; j < k; j++) {
                        ps[j] = posSym[tiles[j]];
                        maskS[g] |= 1 << ps[j];
                    }
                    rS[g] = tbl.rankTuple(ps, k);
                    qS[g] = tbl.blankRank(ps, k, transpose[blank]);
                    hS += tbl.tables[g][qS[g]][rS[g]] & 0xFF;
                }
            }
        }

        /**
         * 计算某一组在数字块从 t 移到 b 后的新排名和新空格序号。
         *
         * @param posArr 数字块位置数组
         * @param rArr 该组的排名
         * @param maskArr 该组的数字块位置位图
         * @param g 组号
         * @param j 被移动数字块在组内的下标
         * @param t 移动前的位置
         * @param b 移动后的位置
         * @return 打包后的新排名和新空格序号
         */
        private long passiveDelta(
                int[] posArr, int[] rArr, int[] maskArr, int g, int j, int t, int b) {
            int k = tilesOf[g].length;
            int[] tiles = tilesOf[g];
            int[][] permW = tbl.perm;
            int belowT = 0, belowB = 0, dR = 0;
            boolean up = b > t;
            for (int i = 0; i < k; i++) {
                if (i == j) continue;
                int pi = posArr[tiles[i]];
                if (i < j) {
                    if (pi < t) belowT++;
                    if (pi < b) belowB++;
                } else if (up ? (pi > t && pi < b) : (pi > b && pi < t)) {
                    // 其他数字块落在 (t, b) 区间内时，其前方更小计数变化 1，对应的排名分量反向变化一个位权
                    dR += (up ? 1 : -1) * permW[cells - 1 - i][k - 1 - i];
                }
            }
            int dAj = (b - belowB) - (t - belowT);
            dR += dAj * permW[cells - 1 - j][k - 1 - j];

            int newMask = maskArr[g] ^ ((1 << t) | (1 << b));
            int newQ = t - (Integer.bitCount(newMask & ((1 << t) - 1)));
            return ((long) (rArr[g] + dR) << 6) | newQ;
        }

        @Override
        int fChild(int depth, int t) {
            int b = blank;
            int v = board[t];
            int g = tileGroup[v], j = tileSlot[v];

            long nd = passiveDelta(pos, rG, maskG, g, j, t, b);
            int hv =
                    hD
                            + (tbl.tables[g][(int) (nd & 63)][(int) (nd >>> 6)] & 0xFF)
                            - (tbl.tables[g][qG[g]][rG[g]] & 0xFF);

            if (symmetric) {
                int sv = symPerm[v];
                int gs = tileGroup[sv], js = tileSlot[sv];
                int tb = transpose[b], tt = transpose[t];
                long ns = passiveDelta(posSym, rS, maskS, gs, js, tt, tb);
                int hs2 =
                        hS
                                + (tbl.tables[gs][(int) (ns & 63)][(int) (ns >>> 6)] & 0xFF)
                                - (tbl.tables[gs][qS[gs]][rS[gs]] & 0xFF);
                if (hs2 > hv) {
                    hv = hs2;
                }
            }
            return depth + 1 + hv;
        }

        @Override
        void apply(int t) {
            int b = blank;
            int v = board[t];
            int g = tileGroup[v], j = tileSlot[v];

            // 先算直链上被移动数字块所属组的新编码和表值
            long nd = passiveDelta(pos, rG, maskG, g, j, t, b);
            int nR = (int) (nd >>> 6), nQ = (int) (nd & 63);
            hD += (tbl.tables[g][nQ][nR] & 0xFF) - (tbl.tables[g][qG[g]][rG[g]] & 0xFF);
            rG[g] = nR;
            qG[g] = nQ;
            maskG[g] ^= (1 << t) | (1 << b);

            // 其余组的排名和表值不变，只用位图重算空格序号
            for (int gg = 0; gg < rG.length; gg++) {
                if (gg != g) {
                    qG[gg] = t - Integer.bitCount(maskG[gg] & ((1 << t) - 1));
                }
            }

            pos[v] = b;
            board[b] = (byte) v;
            board[t] = 0;

            if (symmetric) {
                int sv = symPerm[v];
                int gs = tileGroup[sv], js = tileSlot[sv];
                int tb = transpose[b], tt = transpose[t];
                long ns = passiveDelta(posSym, rS, maskS, gs, js, tt, tb);
                int nRs = (int) (ns >>> 6), nQs = (int) (ns & 63);
                hS += (tbl.tables[gs][nQs][nRs] & 0xFF) - (tbl.tables[gs][qS[gs]][rS[gs]] & 0xFF);
                rS[gs] = nRs;
                qS[gs] = nQs;
                maskS[gs] ^= (1 << tt) | (1 << tb);
                for (int gg = 0; gg < rS.length; gg++) {
                    if (gg != gs) {
                        qS[gg] = tt - Integer.bitCount(maskS[gg] & ((1 << tt) - 1));
                    }
                }
                posSym[sv] = tb;
                symBoard[tb] = (byte) sv;
                symBoard[tt] = 0;
            }

            code += ((long) v << shift[b]) - ((long) v << shift[t]);
            blank = t;
        }
    }

    /** 固定容量的链地址哈希集合，存放当前路径上状态的 64 位编码 */
    private static final class LongHashSet {
        private final long[] keys;
        private final int[] next;
        private final int[] heads;
        private final int[] free;
        private int freeTop;
        private final int shift;

        LongHashSet(int capacity) { // capacity 须为 2 的幂
            int bits = Integer.numberOfTrailingZeros(capacity);
            shift = 64 - bits;
            keys = new long[capacity];
            next = new int[capacity];
            heads = new int[capacity];
            free = new int[capacity];
            for (int i = 0; i < capacity; i++) {
                heads[i] = -1;
                free[i] = i;
            }
            freeTop = capacity;
        }

        private int hash(long key) {
            return (int) ((key * 0x9E3779B97F4A7C15L) >>> shift);
        }

        boolean contains(long key) {
            for (int s = heads[hash(key)]; s != -1; s = next[s]) {
                if (keys[s] == key) {
                    return true;
                }
            }
            return false;
        }

        void add(long key) {
            int s = free[--freeTop];
            keys[s] = key;
            int h = hash(key);
            next[s] = heads[h];
            heads[h] = s;
        }

        void remove(long key) {
            int h = hash(key);
            for (int s = heads[h], prev = -1; s != -1; prev = s, s = next[s]) {
                if (keys[s] == key) {
                    if (prev == -1) {
                        heads[h] = next[s];
                    } else {
                        next[prev] = next[s];
                    }
                    free[freeTop++] = s;
                    return;
                }
            }
        }
    }
}
