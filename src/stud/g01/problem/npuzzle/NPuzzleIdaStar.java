package stud.g01.problem.npuzzle;

import core.problem.Problem;
import core.solver.algorithm.heuristic.Predictor;
import core.solver.algorithm.searcher.AbstractSearcher;
import core.solver.algorithm.searcher.SearchObserver;
import core.solver.algorithm.searcher.SearchObserver.Pruning;
import core.solver.queue.Node;
import java.util.Arrays;
import java.util.Deque;

/**
 * 针对 3 阶与 4 阶 N-Puzzle 问题的专用迭代加深 A* 搜索引擎。
 *
 * <p>核心算法优化包括：
 *
 * <ul>
 *   <li>无结点轻量搜索：利用可变搜索上下文，通过应用移动与撤销移动在单份盘面上深度优先遍历，消除了搜索热路径上的 Node 对象分配开销；
 *   <li>奇偶对齐加深：由于 N-Puzzle 的合法移动必然同时改变全排列奇偶性与空格网格染色奇偶性，所有解路径长度具有相同的奇偶性，因此迭代加深界限能够向上按奇偶性严格对齐；
 *   <li>移动排序启发：在每一层展开分支时，对合法后继状态按启发值升序排序，优先探索最有希望的分支；
 *   <li>双阶段剪枝演进：初期采用路径哈希表进行严格的树搜索环路剪枝；当扩展结点数达到阈值后，自动激活轻量级两路组相联置换表，按最小路径代价剪除图搜索中的重复与劣势状态；
 *   <li>后置路径重放：搜索过程中仅记录紧凑的空格位置序列，命中目标后单次重放并构造完整的 Node 解路径。
 * </ul>
 */
public final class NPuzzleIdaStar extends AbstractSearcher {

    /** 搜索观察者，用于记录算法运行轨迹与统计指标 */
    private final SearchObserver observer;

    /** 启发预测器，提供各盘面状态的启发代价值 */
    private final Predictor predictor;

    /** f 值的正无穷哨兵，dfs 返回它表示本子树没有因 f 超过界限而被剪枝的结点 */
    private static final int INFINITY = Integer.MAX_VALUE;

    /** 置换表：用于检测并剪除已在更优或相同代价下访问过的重复状态 */
    private final Transpositions transpositions = new Transpositions();

    /** 搜索复用工作区：缓存移动数组、启发值及路径集合 */
    private Workspace workspace;

    /**
     * 构造 IDA* 搜索器实例，不挂接轨迹观测器。
     *
     * @param predictor 状态启发式估值预测器
     */
    public NPuzzleIdaStar(Predictor predictor) {
        this(predictor, null);
    }

    /**
     * 构造 IDA* 搜索器实例，并可指定轨迹观测器。
     *
     * @param predictor 状态启发式估值预测器
     * @param observer 搜索过程观测器，为 null 时不记录过程轨迹
     */
    public NPuzzleIdaStar(Predictor predictor, SearchObserver observer) {
        this.predictor = predictor;
        this.observer = observer;
    }

    /**
     * 求解入口：先检验可解性与初始目标，再基于选择的启发函数构建专用搜索上下文并执行搜索。
     *
     * @param problem 3 阶或 4 阶 N-Puzzle 问题实例
     * @return 命中目标时的解路径，不含根结点；若问题无解返回 null；若初始状态即为目标则返回空路径
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
        // 解路径长度的奇偶性由初始与目标的全排列奇偶性差值决定
        search.run(initial.permutationParity() ^ goal.permutationParity());

        nodesGenerated = search.generated;
        nodesExpanded = search.expanded;
        if (search.foundMoves == null) {
            return null;
        }
        return buildPath(problem, initial, search);
    }

    /**
     * 把记录的移动序列重放成框架的 Node 父指针链，再交给父类构造解路径。
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

    /** 搜索过程中可复用的工作区容器，缓存每层的移动参数与当前路径状态集合。 */
    private static final class Workspace {
        /** 每层记录的移动空格位置序列 */
        int[] moves = new int[88];

        /** 每层状态对应的启发值 */
        int[] heuristics = new int[88];

        /** 每层候选移动分支数组 */
        PuzzleSearchContext.Move[][] candidates = new PuzzleSearchContext.Move[88][];

        /** 当前路径上已访问盘面编码集合，用于环路检测 */
        final LongHashSet pathSet = new LongHashSet(512);

        /**
         * 确保工作区容量足以容纳指定深度的搜索调用栈。
         *
         * @param depth 目标搜索深度
         */
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
        /** 搜索上下文，管理盘面与增量估值 */
        private final PuzzleSearchContext context;

        /** 搜索可复用工作区 */
        private final Workspace workspace;

        /** 当前递归路径上的状态哈希集合 */
        private final LongHashSet pathSet;

        /** 跨路径剪枝置换表 */
        private final Transpositions transpositions;

        /** 置换表缓存是否已激活标志 */
        private boolean cacheActive;

        /** 命中的目标解路径移动序列 */
        int[] foundMoves;

        /** 统计的生成与展开结点数量 */
        private int generated, expanded;

        /** 搜索轨迹观察者 */
        private final SearchObserver observer;

        /** 用于轨迹记录的路径树结点数组 */
        private Node[] tracePath;

        /** 当前迭代轮次编号 */
        private int iteration;

        /**
         * 构造单次搜索上下文。
         *
         * @param context 盘面与估值管理上下文
         * @param transpositions 置换表
         * @param workspace 搜索复用工作区
         * @param observer 轨迹观测器
         */
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

        /**
         * 奇偶对齐的迭代加深搜索主体，搜索成功时将命中的空格移动序列保存在 foundMoves 中。
         *
         * @param parity 初始盘面与目标盘面之间的排列奇偶性差值
         */
        void run(int parity) {
            int h0 = context.heuristic();
            // 所有可行解步数与初始/目标排列奇偶差同奇偶，界限需向上对齐
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
                    return;
                }
                // 下一轮界限抬升到最小超出值，并继续对齐解长奇偶性
                bound = ((nextBound ^ parity) & 1) != 0 ? nextBound + 1 : nextBound;
                iteration++;
            }
        }

        /**
         * 树搜索模式下的深度优先搜索单轮迭代。
         *
         * @param depth 当前搜索深度
         * @param bound 本轮迭代的 f 值上限
         * @param previousBlank 上一步空格所在位置，用于跳过立即反向的重复移动
         * @return 本子树中被剪枝结点的最小 f 值；若找到目标则返回 INFINITY 且设置 foundMoves
         */
        private int dfsTree(int depth, int bound, int previousBlank) {
            // 达到阈值后切换到置换表图搜索，抑制树搜索的重复展开
            if (!cacheActive && expanded >= 4096) {
                transpositions.allocate();
                transpositions.clear();
                cacheActive = true;
            }
            if (cacheActive) return dfsGraph(depth, bound, previousBlank);

            if (context.isGoal()) {
                foundMoves = Arrays.copyOf(workspace.moves, depth);
                if (observer != null) observer.visit(tracePath[depth], true, bound, iteration);
                return INFINITY;
            }
            expanded++;
            if (observer != null) observer.visit(tracePath[depth], false, bound, iteration);
            pathSet.add(context.code);
            PuzzleSearchContext.Move[] level = level(depth);
            int n = 0;
            int min = INFINITY;

            // 逐一评估空格可移动到的相邻格子
            for (int t : context.neighbors[context.blank]) {
                generated++;
                // 跳过原路返回
                if (t == previousBlank) {
                    observeSkipped(depth, t, Pruning.CYCLE);
                    continue;
                }
                long childCode = context.codeAfterMove(t);
                // 环路剪枝：当前搜索路径上已出现过该状态
                if (pathSet.contains(childCode)) {
                    observeSkipped(depth, t, Pruning.CYCLE);
                    continue;
                }
                // f = g + h
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
                // 超出 f 界限，剪枝并记录最小超出值供下一轮抬升
                if (childF > bound) {
                    min = Math.min(min, childF);
                    continue;
                }
                n++;
            }

            // 移动排序：按启发值升序展开，优先探索更有希望的分支
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

        /**
         * 图搜索模式下的深度优先搜索单轮迭代。
         *
         * @param depth 当前搜索深度
         * @param bound 本轮迭代的 f 值上限
         * @param previousBlank 上一步空格所在位置，用于跳过立即反向的重复移动
         * @return 本子树中被剪枝结点的最小 f 值；若找到目标则返回 INFINITY 且设置 foundMoves
         */
        private int dfsGraph(int depth, int bound, int previousBlank) {
            if (context.isGoal()) {
                foundMoves = Arrays.copyOf(workspace.moves, depth);
                if (observer != null) observer.visit(tracePath[depth], true, bound, iteration);
                return INFINITY;
            }
            // 置换表剪枝：同一状态已以更小或相等深度访问过
            if (transpositions.visit(context.code, depth)) {
                if (observer != null) observer.prune(tracePath[depth], Pruning.TRANSPOSITION);
                return INFINITY;
            }
            expanded++;
            if (observer != null) observer.visit(tracePath[depth], false, bound, iteration);

            PuzzleSearchContext.Move[] level = level(depth);
            int n = 0;
            int min = INFINITY;

            // 逐一评估空格可移动到的相邻格子
            for (int t : context.neighbors[context.blank]) {
                generated++;
                // 跳过原路返回
                if (t == previousBlank) {
                    observeSkipped(depth, t, Pruning.CYCLE);
                    continue;
                }
                long childCode = context.codeAfterMove(t);
                // 前瞻剪枝：后继状态已存在更优路径
                if (transpositions.dominated(childCode, depth + 1)) {
                    observeSkipped(depth, t, Pruning.TRANSPOSITION);
                    continue;
                }
                // f = g + h
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
                // 超出 f 界限，剪枝并记录最小超出值供下一轮抬升
                if (childF > bound) {
                    min = Math.min(min, childF);
                    continue;
                }
                n++;
            }

            // 移动排序：按启发值升序展开，优先探索更有希望的分支
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
                    return INFINITY;
                }
                if (r < min) {
                    min = r;
                }
            }

            return min;
        }

        /**
         * 获取并按需初始化指定深度的候选移动对象数组。
         *
         * @param depth 搜索深度
         * @return 当前深度可用的 Move 数组
         */
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

        /**
         * 构造并向观测器上报生成子结点的事件。
         *
         * @param depth 当前深度
         * @param target 空格移动到的目标位置
         * @param h 后继结点的启发值
         * @param quality 启发值的精确度分类
         * @return 构造出的子结点实例
         */
        private Node observeChild(
                int depth, int target, int h, SearchObserver.HeuristicQuality quality) {
            PuzzleBoard parent = (PuzzleBoard) tracePath[depth].getState();
            PuzzleMove action = PuzzleMove.between(context.blank, target, parent.size());
            PuzzleBoard board = (PuzzleBoard) parent.next(action);
            Node child = new Node(board, tracePath[depth], action, depth + 1, h);
            observer.generated(child, quality);
            return child;
        }

        /**
         * 向观测器上报被剪除结点的事件。
         *
         * @param depth 当前深度
         * @param target 空格移动到的目标位置
         * @param reason 剪枝原因
         */
        private void observeSkipped(int depth, int target, Pruning reason) {
            if (observer == null) return;
            Node child =
                    observeChild(depth, target, 0, SearchObserver.HeuristicQuality.UNEVALUATED);
            observer.prune(child, reason);
        }
    }

    /**
     * 两路组相联的轻量级置换表。
     *
     * <p>按 64 位盘面编码记录当前轮次内到达该状态的最小路径深度。 当发生哈希槽位冲突时仅损失剪枝机会，不影响解的正确性与最优性。
     */
    private static final class Transpositions {
        /** 置换表哈希槽位总容量 */
        private static final int CAPACITY = 1 << 18;

        /** 记录盘面状态 64 位编码的键数组 */
        private long[] keys;

        /** 记录对应状态首次访问时最小搜索深度的值数组 */
        private int[] depths;

        /** 按需分配置换表数组内存。 */
        void allocate() {
            if (keys != null) return;
            keys = new long[CAPACITY];
            depths = new int[CAPACITY];
        }

        /** 清空置换表中的键值记录。 */
        void clear() {
            Arrays.fill(keys, 0);
        }

        /**
         * 计算 64 位编码在置换表中的起始槽位下标。
         *
         * @param code 盘面编码
         * @return 偶数对齐的槽位下标
         */
        private int slot(long code) {
            return (int) ((code * 0x9E3779B97F4A7C15L) >>> 47) << 1;
        }

        /**
         * 判断指定状态在当前深度是否处于劣势。
         *
         * @param code 盘面编码
         * @param depth 当前到达该状态的深度
         * @return 若已存在更优或等价记录返回 true，否则返回 false
         */
        boolean dominated(long code, int depth) {
            int slot = slot(code);
            return (keys[slot] == code && depths[slot] <= depth)
                    || (keys[slot + 1] == code && depths[slot + 1] <= depth);
        }

        /**
         * 访问并更新指定状态的最小深度记录。
         *
         * @param code 盘面编码
         * @param depth 当前到达该状态的深度
         * @return 若此前已存在更优或相等的记录返回 true；若当前为更优或新访问则更新并返回 false
         */
        boolean visit(long code, int depth) {
            int slot = slot(code);
            // 组相联两槽查找与替换策略
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

    /**
     * 专为 64 位整型编码定制的链地址哈希集合。
     *
     * <p>随搜索深度动态扩容，直接操作基本类型 long，避免装箱开销，用于深度优先搜索当前路径上的环路检测。
     */
    private static final class LongHashSet {
        /** 存放 64 位哈希键值的数组 */
        private long[] keys;

        /** 冲突链表中指向下一个元素的指针数组 */
        private int[] next;

        /** 哈希桶头指针数组 */
        private int[] heads;

        /** 空闲槽位栈数组 */
        private int[] free;

        /** 空闲栈顶指针 */
        private int freeTop;

        /** 哈希映射移位位数 */
        private int shift;

        /**
         * 构造指定初始容量的集合。
         *
         * @param capacity 初始容量，必须为 2 的幂
         */
        LongHashSet(int capacity) {
            allocate(capacity);
        }

        /**
         * 分配哈希桶及空闲链表数组。
         *
         * @param capacity 数组容量
         */
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

        /**
         * 计算 64 位整型键值的哈希桶下标。
         *
         * @param key 待哈希的键值
         * @return 桶下标
         */
        private int hash(long key) {
            return (int) ((key * 0x9E3779B97F4A7C15L) >>> shift);
        }

        /**
         * 查询集合中是否包含指定的 64 位整型键值。
         *
         * @param key 待查询的键值
         * @return 存在返回 true，否则返回 false
         */
        boolean contains(long key) {
            for (int s = heads[hash(key)]; s != -1; s = next[s]) {
                if (keys[s] == key) {
                    return true;
                }
            }
            return false;
        }

        /**
         * 向集合中添加一个 64 位整型键值。
         *
         * @param key 待添加的键值
         */
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

        /**
         * 从集合中移除指定的 64 位整型键值。
         *
         * @param key 待移除的键值
         */
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
