package stud.g01.problem.npuzzle;

import core.problem.Problem;
import core.solver.algorithm.searcher.AbstractSearcher;
import core.solver.queue.Node;
import java.util.Arrays;
import java.util.Deque;

/** N-Puzzle 的增量 PDB IDA*。3 阶使用乘积编码 3-3-2，4 阶使用无损连通分量编码 7-8。 */
public final class NPuzzleIdaStar extends AbstractSearcher {

    /** f 值的正无穷哨兵，dfs 返回它表示本子树没有因 f 超过界限而被剪枝的结点 */
    private static final int INFINITY = Integer.MAX_VALUE;

    /** dfs 深度上限，取值比 4 阶 15-Puzzle 的状态空间直径 80 略大 */
    private static final int MAX_DEPTH = 88;

    private final Transpositions transpositions = new Transpositions();
    private Workspace workspace;

    /** 增量搜索自行维护当前路径，不使用框架的 Frontier。 */
    public NPuzzleIdaStar() {
        super(null);
    }

    /**
     * 求解入口：先判断可解性和目标，再按盘面规模创建增量 PDB 上下文。
     *
     * @param problem 3 阶或 4 阶 N-Puzzle 问题
     * @return 解路径（不含根结点）；无解返回 null；初始即目标返回空路径
     */
    @Override
    public Deque<Node> search(Problem problem) {
        nodesGenerated = 0;
        nodesExpanded = 0;
        if (!problem.solvable()) {
            return null;
        }

        Node root = problem.root();
        if (problem.goal(root.getState())) {
            return generatePath(root);
        }

        PuzzleBoard initial = (PuzzleBoard) root.getState();
        PuzzleBoard goal = ((NPuzzleProblem) problem).goalBoard();
        PdbContext context =
                switch (initial.size()) {
                    case 3 -> new ProductPdbContext(initial, DisjointPdbHeuristic.tablesFor(goal));
                    case 4 -> new ComponentPdbContext(initial, ComponentPdbContext.tablesFor(goal));
                    default ->
                            throw new UnsupportedOperationException(
                                    initial.size() + " 阶未定义增量 PDB（仅支持 3/4 阶）");
                };
        if (workspace == null) workspace = new Workspace();
        Search search = new Search(context, transpositions, workspace);
        search.run(initial.permutationParity() ^ goal.permutationParity());

        nodesGenerated = search.generated;
        nodesExpanded = search.expanded;
        if (search.foundMoves == null) {
            return null;
        }
        return buildPath(problem, initial, search.foundMoves);
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
        Node root = problem.root();
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

    private static final class Workspace {
        final int[] moves = new int[MAX_DEPTH];
        final PdbContext.Move[][] candidates = new PdbContext.Move[MAX_DEPTH][];
        final LongHashSet pathSet = new LongHashSet(512);
    }

    /** 一次搜索的遍历状态；盘面和启发值由 context 管理。 */
    static final class Search {
        private final PdbContext context;
        private final int[] moves;
        private final PdbContext.Move[][] candidates;
        private final LongHashSet pathSet;
        private final Transpositions transpositions;
        private boolean cacheActive;
        int[] foundMoves;
        private int generated, expanded;

        Search(PdbContext context) {
            this(context, new Transpositions());
        }

        Search(PdbContext context, Transpositions transpositions) {
            this(context, transpositions, new Workspace());
        }

        Search(PdbContext context, Transpositions transpositions, Workspace workspace) {
            this.context = context;
            this.transpositions = transpositions;
            moves = workspace.moves;
            candidates = workspace.candidates;
            pathSet = workspace.pathSet;
        }

        /** 奇偶对齐的迭代加深主体。返回时 foundMoves 非 null 表示找到最优解，空数组表示初始即目标 */
        void run(int parity) {
            int h0 = context.heuristic();
            // 初始界限取 h(root)。若与解长奇偶不符，则 h(root) 必然小于最优解长，加一仍不超过最优解长
            int bound = ((h0 ^ parity) & 1) != 0 ? h0 + 1 : h0;

            while (foundMoves == null) {
                if (cacheActive) transpositions.clear();
                int nextBound = cacheActive ? dfsGraph(0, bound, -1) : dfsTree(0, bound, -1);
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
         * @param previousBlank 上一步的空格位置，用于跳过立即反向移动
         * @return 本子树中被剪枝结点的最小 f 值
         */
        private int dfsTree(int depth, int bound, int previousBlank) {
            if (!cacheActive && expanded >= 4096) {
                transpositions.allocate();
                transpositions.clear();
                cacheActive = true;
            }
            if (cacheActive) return dfsGraph(depth, bound, previousBlank);
            // The root bound covers h(root); every child was already checked before apply.
            if (context.isGoal()) {
                foundMoves = Arrays.copyOf(moves, depth);
                return INFINITY;
            }
            expanded++;
            pathSet.add(context.code);
            PdbContext.Move[] level = candidates[depth];
            if (level == null) {
                level = new PdbContext.Move[4];
                for (int i = 0; i < level.length; i++) level[i] = new PdbContext.Move();
                candidates[depth] = level;
            }
            int n = 0, min = INFINITY;
            for (int t : context.neighbors[context.blank]) {
                generated++;
                if (t == previousBlank) continue;
                long childCode = context.codeAfterMove(t);
                if (pathSet.contains(childCode)) continue;
                int childF = depth + 1 + context.prepare(t, childCode, level[n], bound - depth - 1);
                if (childF > bound) {
                    min = Math.min(min, childF);
                    continue;
                }
                n++;
            }
            for (int i = 1; i < n; i++) {
                PdbContext.Move candidate = level[i];
                int j = i - 1;
                while (j >= 0 && level[j].heuristic > candidate.heuristic) {
                    level[j + 1] = level[j];
                    j--;
                }
                level[j + 1] = candidate;
            }
            for (int i = 0; i < n; i++) {
                PdbContext.Move move = level[i];
                context.apply(move);
                moves[depth] = move.to;
                int result = dfsTree(depth + 1, bound, move.from);
                context.undo(move);
                if (foundMoves != null) {
                    pathSet.remove(context.code);
                    return INFINITY;
                }
                min = Math.min(min, result);
            }
            pathSet.remove(context.code);
            return min;
        }

        /** Once activated, best-g pruning replaces the second path hash entirely. */
        private int dfsGraph(int depth, int bound, int previousBlank) {
            if (context.isGoal()) {
                foundMoves = Arrays.copyOf(moves, depth);
                return INFINITY;
            }
            if (transpositions.visit(context.code, depth)) return INFINITY;
            expanded++;

            // Count all legal moves as before, but only estimate acyclic candidates.
            PdbContext.Move[] level = candidates[depth];
            if (level == null) {
                level = new PdbContext.Move[4];
                for (int i = 0; i < level.length; i++) level[i] = new PdbContext.Move();
                candidates[depth] = level;
            }
            int n = 0;
            int min = INFINITY;
            for (int t : context.neighbors[context.blank]) {
                generated++;
                if (t == previousBlank) continue;
                long childCode = context.codeAfterMove(t);
                if (transpositions.dominated(childCode, depth + 1)) continue;
                int childF = depth + 1 + context.prepare(t, childCode, level[n], bound - depth - 1);
                if (childF > bound) {
                    min = Math.min(min, childF);
                    continue;
                }
                n++;
            }
            for (int i = 1; i < n; i++) {
                PdbContext.Move candidate = level[i];
                int j = i - 1;
                while (j >= 0 && level[j].heuristic > candidate.heuristic) {
                    level[j + 1] = level[j];
                    j--;
                }
                level[j + 1] = candidate;
            }

            for (int i = 0; i < n; i++) {
                PdbContext.Move move = level[i];
                context.apply(move);
                moves[depth] = move.to;
                int r = dfsGraph(depth + 1, bound, move.from);
                context.undo(move);
                if (foundMoves != null) {
                    return INFINITY; // 已找到目标，下一层界限没有意义，直接逐层返回
                }
                if (r < min) {
                    min = r;
                }
            }

            return min;
        }
    }

    /** Per-iteration best g, with exact keys; collisions only lose pruning opportunities. */
    private static final class Transpositions {
        private static final int CAPACITY = 1 << 18;
        private long[] keys;
        private byte[] depths;

        void allocate() {
            if (keys != null) return;
            keys = new long[CAPACITY];
            depths = new byte[CAPACITY];
        }

        void clear() {
            Arrays.fill(keys, 0);
        }

        private int slot(long code) {
            return (int) ((code * 0x9E3779B97F4A7C15L) >>> 47) << 1;
        }

        boolean dominated(long code, int depth) {
            int slot = slot(code);
            return (keys[slot] == code && depths[slot] <= depth)
                    || (keys[slot + 1] == code && depths[slot + 1] <= depth);
        }

        boolean visit(long code, int depth) {
            int slot = slot(code);
            if (keys[slot] != code) {
                if (keys[slot + 1] == code
                        || (keys[slot] != 0
                                && (keys[slot + 1] == 0 || depths[slot + 1] > depths[slot])))
                    slot++;
            }
            if (keys[slot] == code && depths[slot] <= depth) return true;
            keys[slot] = code;
            depths[slot] = (byte) depth;
            return false;
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
