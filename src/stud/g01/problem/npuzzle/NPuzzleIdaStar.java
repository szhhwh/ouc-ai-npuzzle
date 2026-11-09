package stud.g01.problem.npuzzle;

import core.problem.Problem;
import core.solver.algorithm.heuristic.Predictor;
import core.solver.algorithm.searcher.AbstractSearcher;
import core.solver.algorithm.searcher.SearchObserver;
import core.solver.algorithm.searcher.SearchObserver.Pruning;
import core.solver.queue.Node;
import java.util.Arrays;
import java.util.Deque;

/** 使用可回退盘面和状态去重的 N-Puzzle IDA*。 */
public final class NPuzzleIdaStar extends AbstractSearcher {

    private final SearchObserver observer;
    private final Predictor predictor;

    /** f 值的正无穷哨兵，dfs 返回它表示本子树没有因 f 超过界限而被剪枝的结点 */
    private static final int INFINITY = Integer.MAX_VALUE;

    private final Transpositions transpositions = new Transpositions();
    private Workspace workspace;

    /** 搜索自行维护当前路径，不使用框架的 Frontier。 */
    public NPuzzleIdaStar(Predictor predictor) {
        this(predictor, null);
    }

    public NPuzzleIdaStar(Predictor predictor, SearchObserver observer) {
        this.predictor = predictor;
        this.observer = observer;
    }

    /**
     * 求解入口：先判断可解性和目标，再创建所选启发函数的搜索上下文。
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
        PuzzleBoard initial = (PuzzleBoard) root.getState();
        PuzzleBoard goal = ((NPuzzleProblem) problem).goalBoard();
        if (problem.goal(root.getState())) {
            int h = predictor.heuristics(initial, goal);
            root.setHeuristic(h);
            if (observer != null) {
                observer.iteration(root, h, 0);
                observer.visit(root, true, h, 0);
            }
            return generatePath(root);
        }

        PuzzleSearchContext context = PuzzleSearchContext.create(initial, goal, predictor);
        if (workspace == null) workspace = new Workspace();
        Search search = new Search(context, transpositions, workspace, observer);
        search.run(initial.permutationParity() ^ goal.permutationParity());

        nodesGenerated = search.generated;
        nodesExpanded = search.expanded;
        if (search.foundMoves == null) {
            return null;
        }
        return buildPath(problem, initial, search);
    }

    /**
     * 把记录的移动序列重放成框架的 Node 父指针链，再交给 {@link AbstractSearcher#generatePath}
     * 构造解路径。重放只在求解成功后做一次，不参与搜索热路径。
     *
     * @param problem 待求解的问题
     * @param initial 初始盘面
     * @param search 已命中解的搜索状态，含移动序列及沿途启发值
     * @return 解路径
     */
    private Deque<Node> buildPath(Problem problem, PuzzleBoard initial, Search search) {
        int size = initial.size();
        Node root = problem.root().setHeuristic(search.context.heuristic());
        Node cur = root;
        PuzzleBoard prev = initial;
        for (int step = 0; step < search.foundMoves.length; step++) {
            int t = search.foundMoves[step];
            PuzzleMove move = PuzzleMove.between(prev.blank(), t, size);
            PuzzleBoard next = (PuzzleBoard) prev.next(move);
            cur =
                    new Node(
                            next,
                            cur,
                            move,
                            cur.getPathCost() + 1,
                            search.workspace.heuristics[step]);
            prev = next;
        }
        return generatePath(cur);
    }

    private static final class Workspace {
        int[] moves = new int[88];
        int[] heuristics = new int[88];
        PuzzleSearchContext.Move[][] candidates = new PuzzleSearchContext.Move[88][];
        final LongHashSet pathSet = new LongHashSet(512);

        void ensureDepth(int depth) {
            if (depth < moves.length) return;
            int capacity = moves.length * 2;
            moves = Arrays.copyOf(moves, capacity);
            heuristics = Arrays.copyOf(heuristics, capacity);
            candidates = Arrays.copyOf(candidates, capacity);
        }
    }

    /** 一次搜索的遍历状态；盘面和启发值由 context 管理。 */
    private static final class Search {
        private final PuzzleSearchContext context;
        private final Workspace workspace;
        private final LongHashSet pathSet;
        private final Transpositions transpositions;
        private boolean cacheActive;
        int[] foundMoves;
        private int generated, expanded;
        private final SearchObserver observer;
        private Node[] tracePath;
        private int iteration;

        Search(
                PuzzleSearchContext context,
                Transpositions transpositions,
                Workspace workspace,
                SearchObserver observer) {
            this.context = context;
            this.workspace = workspace;
            this.transpositions = transpositions;
            this.observer = observer;
            tracePath = observer == null ? null : new Node[workspace.moves.length + 1];
            pathSet = workspace.pathSet;
        }

        /** 奇偶对齐的迭代加深主体，foundMoves 保存命中的移动序列。 */
        void run(int parity) {
            int h0 = context.heuristic();
            // N-Puzzle 的所有解长具有相同奇偶性，界限向上对齐。
            int bound = ((h0 ^ parity) & 1) != 0 ? h0 + 1 : h0;

            while (foundMoves == null) {
                if (cacheActive) transpositions.clear();
                if (observer != null) {
                    tracePath[0] =
                            new Node(
                                    new PuzzleBoard(context.board, (int) Math.sqrt(context.cells)),
                                    null,
                                    null,
                                    0,
                                    h0);
                    observer.iteration(tracePath[0], bound, iteration);
                }
                int nextBound = cacheActive ? dfsGraph(0, bound, -1) : dfsTree(0, bound, -1);
                if (nextBound == INFINITY) {
                    return; // 搜遍全树仍未找到目标，可解性已经检查过，实际不会走到这里
                }
                // 下一轮界限继续向上对齐解长奇偶性。
                bound = ((nextBound ^ parity) & 1) != 0 ? nextBound + 1 : nextBound;
                iteration++;
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
                foundMoves = Arrays.copyOf(workspace.moves, depth);
                if (observer != null) observer.visit(tracePath[depth], true, bound, iteration);
                return INFINITY;
            }
            expanded++;
            if (observer != null) observer.visit(tracePath[depth], false, bound, iteration);
            pathSet.add(context.code);
            PuzzleSearchContext.Move[] level = level(depth);
            int n = 0, min = INFINITY;
            for (int t : context.neighbors[context.blank]) {
                generated++;
                if (t == previousBlank) {
                    observeSkipped(depth, t, Pruning.CYCLE);
                    continue;
                }
                long childCode = context.codeAfterMove(t);
                if (pathSet.contains(childCode)) {
                    observeSkipped(depth, t, Pruning.CYCLE);
                    continue;
                }
                int childF = depth + 1 + context.prepare(t, childCode, level[n], bound - depth - 1);
                if (observer != null) {
                    level[n].traceNode =
                            observeChild(
                                    depth,
                                    t,
                                    level[n].heuristic,
                                    level[n].lowerBound
                                            ? SearchObserver.HeuristicQuality.LOWER_BOUND
                                            : SearchObserver.HeuristicQuality.EXACT);
                    if (childF > bound) observer.prune(level[n].traceNode, Pruning.BOUND);
                }
                if (childF > bound) {
                    min = Math.min(min, childF);
                    continue;
                }
                n++;
            }
            for (int i = 1; i < n; i++) {
                PuzzleSearchContext.Move candidate = level[i];
                int j = i - 1;
                while (j >= 0 && level[j].heuristic > candidate.heuristic) {
                    level[j + 1] = level[j];
                    j--;
                }
                level[j + 1] = candidate;
            }
            for (int i = 0; i < n; i++) {
                PuzzleSearchContext.Move move = level[i];
                context.apply(move);
                workspace.moves[depth] = move.to;
                workspace.heuristics[depth] = move.heuristic;
                if (observer != null) tracePath[depth + 1] = move.traceNode;
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
                foundMoves = Arrays.copyOf(workspace.moves, depth);
                if (observer != null) observer.visit(tracePath[depth], true, bound, iteration);
                return INFINITY;
            }
            if (transpositions.visit(context.code, depth)) {
                if (observer != null) observer.prune(tracePath[depth], Pruning.TRANSPOSITION);
                return INFINITY;
            }
            expanded++;
            if (observer != null) observer.visit(tracePath[depth], false, bound, iteration);

            // Count all legal moves as before, but only estimate acyclic candidates.
            PuzzleSearchContext.Move[] level = level(depth);
            int n = 0;
            int min = INFINITY;
            for (int t : context.neighbors[context.blank]) {
                generated++;
                if (t == previousBlank) {
                    observeSkipped(depth, t, Pruning.CYCLE);
                    continue;
                }
                long childCode = context.codeAfterMove(t);
                if (transpositions.dominated(childCode, depth + 1)) {
                    observeSkipped(depth, t, Pruning.TRANSPOSITION);
                    continue;
                }
                int childF = depth + 1 + context.prepare(t, childCode, level[n], bound - depth - 1);
                if (observer != null) {
                    level[n].traceNode =
                            observeChild(
                                    depth,
                                    t,
                                    level[n].heuristic,
                                    level[n].lowerBound
                                            ? SearchObserver.HeuristicQuality.LOWER_BOUND
                                            : SearchObserver.HeuristicQuality.EXACT);
                    if (childF > bound) observer.prune(level[n].traceNode, Pruning.BOUND);
                }
                if (childF > bound) {
                    min = Math.min(min, childF);
                    continue;
                }
                n++;
            }
            for (int i = 1; i < n; i++) {
                PuzzleSearchContext.Move candidate = level[i];
                int j = i - 1;
                while (j >= 0 && level[j].heuristic > candidate.heuristic) {
                    level[j + 1] = level[j];
                    j--;
                }
                level[j + 1] = candidate;
            }

            for (int i = 0; i < n; i++) {
                PuzzleSearchContext.Move move = level[i];
                context.apply(move);
                workspace.moves[depth] = move.to;
                workspace.heuristics[depth] = move.heuristic;
                if (observer != null) tracePath[depth + 1] = move.traceNode;
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

        private PuzzleSearchContext.Move[] level(int depth) {
            workspace.ensureDepth(depth);
            if (observer != null && tracePath.length <= depth + 1) {
                tracePath = Arrays.copyOf(tracePath, workspace.moves.length + 1);
            }
            PuzzleSearchContext.Move[] level = workspace.candidates[depth];
            if (level == null) {
                level = new PuzzleSearchContext.Move[4];
                for (int i = 0; i < level.length; i++) level[i] = context.newMove();
                workspace.candidates[depth] = level;
            }
            return level;
        }

        private Node observeChild(
                int depth, int target, int h, SearchObserver.HeuristicQuality quality) {
            PuzzleBoard parent = (PuzzleBoard) tracePath[depth].getState();
            PuzzleMove action = PuzzleMove.between(context.blank, target, parent.size());
            PuzzleBoard board = (PuzzleBoard) parent.next(action);
            Node child = new Node(board, tracePath[depth], action, depth + 1, h);
            observer.generated(child, quality);
            return child;
        }

        private void observeSkipped(int depth, int target, Pruning reason) {
            if (observer == null) return;
            Node child =
                    observeChild(depth, target, 0, SearchObserver.HeuristicQuality.UNEVALUATED);
            observer.prune(child, reason);
        }
    }

    /** Per-iteration best g, with exact keys; collisions only lose pruning opportunities. */
    private static final class Transpositions {
        private static final int CAPACITY = 1 << 18;
        private long[] keys;
        private int[] depths;

        void allocate() {
            if (keys != null) return;
            keys = new long[CAPACITY];
            depths = new int[CAPACITY];
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
            depths[slot] = depth;
            return false;
        }
    }

    /** 链地址哈希集合，随当前路径长度增长，存放状态的 64 位编码。 */
    private static final class LongHashSet {
        private long[] keys;
        private int[] next;
        private int[] heads;
        private int[] free;
        private int freeTop;
        private int shift;

        LongHashSet(int capacity) { // capacity 须为 2 的幂
            allocate(capacity);
        }

        private void allocate(int capacity) {
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
            if (freeTop == 0) {
                long[] previous = keys;
                allocate(keys.length * 2);
                for (long existing : previous) add(existing);
            }
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
