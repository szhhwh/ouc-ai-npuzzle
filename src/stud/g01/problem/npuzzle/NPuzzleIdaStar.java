package stud.g01.problem.npuzzle;

import core.problem.Problem;
import core.solver.algorithm.heuristic.Predictor;
import core.solver.algorithm.searcher.AbstractSearcher;
import core.solver.queue.Frontier;
import core.solver.queue.Node;
import stud.solver.IdAStar;

import java.util.Arrays;
import java.util.Deque;

/**
 * N-Puzzle 专用的优化 IDA*：在标准 IDA*（{@link IdAStar}）的算法骨架上，利用可加 PDB
 * 与盘面移动的组合结构性质，把每个结点的处理成本降到 O(1)（乘积编码）或 O(k)
 * （ranked 编码），并跳过由解长奇偶性判定注定无解的迭代轮。最优性、结点序与
 * 计数口径均与 {@link IdAStar} 完全一致。
 *
 * <p>【性质一：启发值的增量维护】</p>
 * <p>一次真实移动 = 空格与某个将牌 v 交换。对 v 所属组 G，这是抽象空间中一条
 * 1 代价边，表值至多变化 1；对<b>所有其它组</b>，这只是"空格与无关将牌交换"——
 * 一条 0 代价边，0-1 BFS 语义下两抽象状态距离相等，<b>表值严格不变</b>
 * （DisjointPdbHeuristic【一致性】论证的直接推论）。因此子结点 h =
 * 父 h − table_G[旧] + table_G[新]，无需全盘重扫，两版后端分别为：</p>
 * <ul>
 *   <li><b>乘积编码</b>（3 阶 3-3-2）：组编码含空格最低维，被动组用位权增量
 *       {@code idx' = idx + (b−t)·(w_j−1)}（与建表 0-1 BFS 同一条公式），
 *       其余组仅平移空格维——1 次算术 + 1 次表访问/步；</li>
 *   <li><b>ranked 编码</b>（4 阶 7-7-1）：被动组的 tuple 排名 R 需 O(k) 的
 *       Lehmer 区间传播，非被动组的空格序 q 由组内将牌位置位图 popcount 得
 *       O(1)（位图本身每步 O(1) 更新）。</li>
 * </ul>
 * <p>对称增强态 T(s)（转置 τ + 值置换 π 的复合）中被移动的将牌是 π(v)，位置由
 * τ(t) 移到 τ(b)：对称盘面与对称编码同样逐步增量。两条链各维护标量
 * h<sub>D</sub>、h<sub>S</sub>，结点启发值 = max(h<sub>D</sub>, h<sub>S</sub>)
 * ——与全量查询 hMax 数值恒等，仅计算方式不同（-Dnpida.verify 自检）。</p>
 *
 * <p>【性质二：解长奇偶对齐的界限跳轮】</p>
 * <p>把空格视作最小值 0，对<b>含空格</b>的行主序序列数逆序数 ψ。水平移动交换相邻
 * 两项（ψ ±1）；垂直移动使被换将牌跨越 size−1 项（ψ 变化 ±(2k+1)）——每步移动 ψ
 * 的奇偶必翻转。d 步解路径共翻转 d 次，故 <b>解长 d ≡ ψ(initial) ⊕ ψ(goal) (mod 2)</b>，
 * 求解前即可确定。IDA* 的迭代界限若与该奇偶不符，本轮必无解（目标的 f = 解长）：
 * 初始 bound 与 nextBound 奇偶不符时直接 +1。被跳过的轮不会漏解——下一轮搜索的
 * 结点集合是其超集；而照常执行该轮也只会以 nextBound = bound+1 告终，整轮 DFS
 * 开销被完全节省。</p>
 *
 * <p>【性质三：h<sub>D</sub> = 0 ⟺ 目标】</p>
 * <p>多源起点建表使组表值为 0 ⟺ 组内将牌全部就位（空格在任意剩余格）。全部组
 * 同时为 0 ⟺ 所有将牌就位 ⟺ 空格被挤到唯一剩余格即 goal 空格位。故 h<sub>D</sub>=0
 * 与 {@code problem.goal(state)} 等价，目标判定退化为一次 int 比较。</p>
 *
 * <p>【与通用 IdAStar 的一致性】子结点按 UP、DOWN、LEFT、RIGHT 固定方向序枚举
 * （与 {@code PuzzleBoard.actions()} 一致），f 相同时保持该序（稳定插入排序）；
 * 排序键 f = g+1+max(h<sub>D</sub>,h<sub>S</sub>) 与通用版 child.evaluation() 数值相同。
 * 因此两版搜索树逐结点重合，nodesGenerated / nodesExpanded <b>逐位一致</b>——
 * 这本身就是等价性的实验验证手段（3 阶乘积后端已全实例逐位验证；ranked 后端
 * 经表值交叉验证 + verify 自检）。计数、剪枝、目标判定与 nextBound 语义均与
 * 通用版相同（先剪枝后判目标，f &gt; bound 的目标计入 nextBound 而非提前返回，
 * 杜绝次优解）；奇偶跳轮由性质二保证完备性。</p>
 *
 * <p>【适用范围与降级】仅支持 3/4 阶 N-Puzzle（3 阶走乘积后端、4 阶走 ranked
 * 后端）；其余问题（寻路等）一律转发通用 {@link IdAStar} 并拷回计数，对外行为
 * 保持一致。PDB 表与全量查询版共享（同一份磁盘/进程内缓存）。</p>
 */
public final class NPuzzleIdaStar extends AbstractSearcher {

    /** f 值正无穷哨兵：dfs 返回它表示本子树没有任何因 f &gt; bound 被剪枝的结点 */
    private static final int INFINITY = Integer.MAX_VALUE;

    /** dfs 深度上限：4 阶 15-Puzzle 的状态空间直径为 80，留少量余量 */
    private static final int MAX_DEPTH = 88;

    /** 降级路径使用的启发函数；特化路径自带增量 PDB，不经过它 */
    private final Predictor predictor;

    /**
     * @param frontier  仅为兼容 {@code EngineFeeder} 统一构造方式的占位（IDA* 不需要）
     * @param predictor 降级路径的启发函数
     */
    public NPuzzleIdaStar(Frontier frontier, Predictor predictor) {
        super(frontier);
        this.predictor = predictor;
    }

    /**
     * 求解入口：3/4 阶 N-Puzzle 走增量 PDB 的专用 IDA*（4 阶 ranked 7-7-1、
     * 3 阶乘积 3-3-2），其余问题降级转发通用 IdAStar。
     *
     * @param problem 待求解问题
     * @return 解路径（不含根结点）；无解返回 null；初始即目标返回空路径
     */
    @Override
    public Deque<Node> search(Problem problem) {
        if (problem instanceof NPuzzleProblem) {
            PuzzleBoard initial = (PuzzleBoard) problem.root().getState();
            if (initial.size() == 4) {
                return searchSpecialized(problem, initial, new RankedCtx(initial, ((NPuzzleProblem) problem).goalBoard()));
            }
            if (initial.size() == 3) {
                return searchSpecialized(problem, initial, new ProdCtx(initial, ((NPuzzleProblem) problem).goalBoard()));
            }
        }
        return searchGeneric(problem);
    }

    /** 降级：转发通用 IdAStar，并把计数拷回，保持对外口径一致 */
    private Deque<Node> searchGeneric(Problem problem) {
        IdAStar generic = new IdAStar(frontier, predictor);
        Deque<Node> path = generic.search(problem);
        nodesGenerated = generic.nodesGenerated();
        nodesExpanded = generic.nodesExpanded();
        return path;
    }

    /** 特化主体：构造搜索上下文并运行奇偶对齐的 IDA* 迭代加深 */
    private Deque<Node> searchSpecialized(Problem problem, PuzzleBoard initial, SearchCtx ctx) {
        // 不可解判定与通用版一致：直接返回 null
        if (!problem.solvable()) {
            return null;
        }
        nodesGenerated = 0;
        nodesExpanded = 0;

        ctx.run();

        nodesGenerated = ctx.gen;
        nodesExpanded = ctx.exp;
        if (ctx.foundMoves == null) {
            return null;   //可解却穷尽：仅当启发异常（理论上不可达）
        }
        return buildPath(problem, initial, ctx.foundMoves);
    }

    /**
     * 把记录的移动序列（每步空格的目标格下标）重放为框架的 Node 父指针链，
     * 复用 {@link AbstractSearcher#generatePath} 构造解路径。
     * 重放只发生在求解成功之后，O(d) 一次性构造，不参与搜索热路径。
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

    /** 由（移动前的空格下标, 移动后的空格下标）还原方向动作（四邻关系恰居其一） */
    private static PuzzleMove moveOf(int from, int to, int size) {
        if (to == from - size) return PuzzleMove.UP;
        if (to == from + size) return PuzzleMove.DOWN;
        if (to == from - 1) return PuzzleMove.LEFT;
        return PuzzleMove.RIGHT;
    }

    // ==================================================================
    //  搜索骨架：全部与编码无关的共享逻辑（迭代加深、dfs、排序、环检测）
    // ==================================================================

    /**
     * 一次专用搜索的全部共享状态：盘面就地修改（apply/undo 维护），路径环检测
     * 用 long 键哈希集合，子结点缓冲预分配——搜索热路径零分配。
     * 编码相关的 h 维护由子类实现（乘积 / ranked 两个后端）。
     */
    private abstract static class SearchCtx {

        final int size;
        final int cells;
        final int[][] nbr;
        final long[] shift;

        // ―― 就地修改的搜索状态 ――
        byte[] board;
        int blank;
        long code;

        // ―― 两条启发链的标量（后端负责增量维护） ――
        int hD, hS;
        boolean symmetric;

        // ―― 迭代与结果 ――
        final int[] moves = new int[MAX_DEPTH];
        final int[][] tBuf = new int[MAX_DEPTH][4];
        final int[][] fBuf = new int[MAX_DEPTH][4];
        int[] foundMoves;
        final LongHashSet pathSet = new LongHashSet(512);
        int gen, exp;

        /** goal 盘面原文（奇偶计算与子类建表校验用） */
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
            //子类构造体完成启发初始化（hD/hS/symmetric/编码状态）后再 run()
        }

        /** 当前结点的启发值 max(h_D, h_S) */
        final int hMax() {
            return symmetric ? Math.max(hD, hS) : hD;
        }

        /** 子结点 f = (depth+1) + max(h_D', h_S')：只查被动组的表（性质一） */
        abstract int fChild(int depth, int t);

        /**
         * 应用一次移动：空格移到 t（将牌 board[t] 移到当前空格位），
         * 就地更新盘面、编码状态、h_D/h_S 与 64bit 编码。
         * 逆移动 = apply(旧空格位)：同一条公式的对称形态，无需单独实现。
         */
        abstract void apply(int t);

        /** 自检不变量：增量维护的 h_D / h_S == 全量查询值（-Dnpida.verify） */
        abstract void verifyInvariant();

        /**
         * 奇偶对齐的迭代加深主体。返回时 foundMoves 非 null 即找到最优解
         * （空数组表示初始即目标）。
         */
        final void run() {
            int parity = psi(board) ^ psi(goalCells);
            int h0 = hMax();
            // 解长 ≡ parity（性质二）：奇偶不符的 h(root) 必严格小于 f*，+1 后仍 ≤ f*
            int bound = ((h0 ^ parity) & 1) != 0 ? h0 + 1 : h0;

            while (foundMoves == null) {
                int nextBound = dfs(0, bound);
                if (TRACE) {
                    System.out.printf("[nida] bound=%d next=%d gen=%d exp=%d%n",
                            bound, nextBound, gen, exp);
                }
                if (nextBound == INFINITY) {
                    return;   //树被穷尽而无目标：不可解（可解性已检，理论不可达）
                }
                // 奇偶不符的界限层不存在解（目标的 f ≡ parity），直接抬到下一个同奇偶层
                bound = ((nextBound ^ parity) & 1) != 0 ? nextBound + 1 : nextBound;
            }
        }

        /**
         * 深度优先一轮：进入时 board/编码/h_D/h_S/code 已是当前结点状态。
         * 语义与通用 IdAStar.dfs 逐条对齐（先剪枝、再判目标、后扩展、
         * 环检测只作用于当前路径、子结点按 f 稳定升序深入）。
         */
        private final int dfs(int depth, int bound) {
            if (VERIFY) {
                verifyInvariant();
            }
            int f = depth + hMax();
            if (f > bound) {
                return f;
            }
            if (hD == 0) {   //性质三：h_D=0 ⟺ 目标
                foundMoves = Arrays.copyOf(moves, depth);
                return INFINITY;
            }
            exp++;

            // 当前状态入路径集合；回溯时移除（与通用版 explored 的用法一致）
            pathSet.add(code);

            // 生成全部合法子结点（固定方向序）并计算 f，供稳定插入排序
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
                // 环检测在应用移动前完成：用编码增量直接构造子结点键，
                // 命中（路径内重复状态）者跳过，免去一次 apply/undo 往返
                long childCode = code
                        + ((long) board[t] << shift[from])
                        - ((long) board[t] << shift[t]);
                if (pathSet.contains(childCode)) {
                    continue;
                }
                apply(t);
                moves[depth] = t;
                int r = dfs(depth + 1, bound);
                apply(from);   //撤销：空格移回 from，盘面/编码/h 全部复原（公式自对称）
                if (foundMoves != null) {
                    return INFINITY;   //已找到目标：nextBound 无意义，层层快速返回
                }
                if (r < min) {
                    min = r;
                }
            }

            pathSet.remove(code);   //回溯：离开当前路径
            return min;
        }

        /** 含空格完整逆序数的奇偶（性质二的 ψ；空格即 0，天然最小） */
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

        /** -Dnpida.verify：每结点以全量查询核对增量维护的 h_D / h_S（调试自检） */
        private static final boolean VERIFY = Boolean.getBoolean("npida.verify");

        /** -Dnpida.trace：打印每轮迭代的 bound 与 nextBound（调试自检） */
        private static final boolean TRACE = Boolean.getBoolean("npida.trace");
    }

    // ==================================================================
    //  乘积编码后端（3 阶 3-3-2，表来自 DisjointPdbHeuristic）
    // ==================================================================

    /**
     * 乘积进制编码的增量维护：被动组位权增量，其余组平移空格最低维。
     * 适用于编码空间小的规模（3 阶 9⁴ 项/组），O(1)/步。
     */
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

            // 初始编码：一次性全量计算（此后全部增量）
            int[] pos = new int[cells];
            for (int i = 0; i < cells; i++) {
                pos[board[i]] = i;
            }
            idx = new int[tbl.groups.length];
            idxSym = new int[tbl.groups.length];
            // π⁻¹：对称盘面 symBoard[i]=π(board[τ(i)]) 中将牌 u 的位置是 τ(pos[π⁻¹(u)])
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
            // 其余组：表值不变（0 代价边），但编码的空格最低维须同步平移
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

        @Override
        void verifyInvariant() {
            if (tbl.hCells(board, blank) != hD) {
                throw new IllegalStateException("hD 增量失配：board=" + Arrays.toString(board)
                        + " 增量=" + hD + " 全量=" + tbl.hCells(board, blank)
                        + " idx=" + Arrays.toString(idx));
            }
            if (symmetric && tbl.hCells(symBoard, transpose[blank]) != hS) {
                throw new IllegalStateException("hS 增量失配：sym=" + Arrays.toString(symBoard)
                        + " 增量=" + hS + " 全量=" + tbl.hCells(symBoard, transpose[blank])
                        + " idxSym=" + Arrays.toString(idxSym));
            }
        }

        private static int pow(int base, int exp) {
            int r = 1;
            for (int i = 0; i < exp; i++) {
                r *= base;
            }
            return r;
        }
    }

    // ==================================================================
    //  ranked 编码后端（4 阶 7-7-1，表来自 RankedPdbHeuristic）
    // ==================================================================

    /**
     * ranked（部分排列排名）编码的增量维护（4 阶 7-7-1 大组 PDB）：
     * <ul>
     *   <li>被动组：tuple 排名 R 的 O(k) Lehmer 区间传播
     *       （Aᵢ 对 pⱼ 跨越 (t,b) 区间者的 ±1，Aⱼ 的重算）+ 空格序 q 的
     *       位图 popcount；</li>
     *   <li>非被动组：表值不变，tuple 不变，仅 q 平移（位图 popcount，O(1)）；</li>
     *   <li>组内将牌位置位图 mask 每步 O(1) 更新，供 q 的 O(1) 计算；</li>
     *   <li>对称链同构（被移动将牌 π(v)：τ(t)→τ(b)）。</li>
     * </ul>
     */
    private static final class RankedCtx extends SearchCtx {

        private final RankedPdbHeuristic.Tables tbl;
        private final int[][] tilesOf;
        private final int[] tileGroup, tileSlot;
        private final int[] transpose, symPerm;

        // pos[v] / posSym[u]：将牌位置（增量维护，O(1)/步）
        private final int[] pos, posSym;
        private byte[] symBoard;

        // 每组编码状态：R（tuple 排名）与 q（空格剩余序），直链与对称链各一套
        private final int[] rG, qG, rS, qS;
        // 组内将牌位置位图（bit i = 组内某将牌位于格子 i）
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

            // π⁻¹（对称链的初始编码用）
            int[] inv = new int[cells];
            for (int v = 0; v < cells; v++) {
                inv[symPerm[v]] = v;
            }
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

            // 初始编码：一次性全量（此后增量）
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
         * 被动组新 (R, q) 的 O(k) 计算：tuple 排名增量 = Aⱼ 变化 + pᵢ (i&gt;j)
         * 落入 (t,b) 区间者的 ±位权；q_new 由新位图 popcount。
         * 返回 pack(newR, newQ)。
         */
        private long passiveDelta(int[] posArr, int[] rArr, int[] maskArr,
                                  int g, int j, int t, int b) {
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
                    // pⱼ 从 t 移到 b：区间内者的「前方更小计数」cntᵢ ∓1 → Aᵢ = pᵢ − cntᵢ 反向 ±1
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
            int hv = hD + (tbl.tables[g][(int) (nd & 63)][(int) (nd >>> 6)] & 0xFF)
                    - (tbl.tables[g][qG[g]][rG[g]] & 0xFF);

            if (symmetric) {
                int sv = symPerm[v];
                int gs = tileGroup[sv], js = tileSlot[sv];
                int tb = transpose[b], tt = transpose[t];
                long ns = passiveDelta(posSym, rS, maskS, gs, js, tt, tb);
                int hs2 = hS + (tbl.tables[gs][(int) (ns & 63)][(int) (ns >>> 6)] & 0xFF)
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

            // 直链被动组：新编码（与 fChild 同一计算）与表值
            long nd = passiveDelta(pos, rG, maskG, g, j, t, b);
            int nR = (int) (nd >>> 6), nQ = (int) (nd & 63);
            hD += (tbl.tables[g][nQ][nR] & 0xFF) - (tbl.tables[g][qG[g]][rG[g]] & 0xFF);
            rG[g] = nR;
            qG[g] = nQ;
            maskG[g] ^= (1 << t) | (1 << b);

            // 其余组：tuple 与表值不变，仅空格序平移（位图 popcount）
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

        @Override
        void verifyInvariant() {
            if (tbl.hCells(board, blank) != hD) {
                throw new IllegalStateException("hD(ranked) 增量失配：board=" + Arrays.toString(board)
                        + " 增量=" + hD + " 全量=" + tbl.hCells(board, blank));
            }
            if (symmetric && tbl.hCells(symBoard, transpose[blank]) != hS) {
                throw new IllegalStateException("hS(ranked) 增量失配：sym=" + Arrays.toString(symBoard)
                        + " 增量=" + hS + " 全量=" + tbl.hCells(symBoard, transpose[blank]));
            }
        }
    }

    // ==================================================================
    //  long 键链地址哈希集合：当前路径的环检测（add/remove/contains 均 O(1)，零分配）
    // ==================================================================

    /** 固定容量、freelist 分槽的链地址哈希集合，存放路径上状态的 64bit 编码 */
    private static final class LongHashSet {
        private final long[] keys;
        private final int[] next;
        private final int[] heads;
        private final int[] free;
        private int freeTop;
        private final int shift;

        LongHashSet(int capacity) {   //capacity 须为 2 的幂
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
