package stud.solver;

import core.problem.Problem;
import core.problem.State;
import core.solver.algorithm.heuristic.Predictor;
import core.solver.algorithm.searcher.AbstractSearcher;
import core.solver.queue.Frontier;
import core.solver.queue.Node;
import java.util.Deque;
import java.util.List;

/**
 * 迭代加深 A*（IDA*）搜索器。外层循环逐步抬高 f 值界限，内层在界限内做深度优先搜索。
 *
 * <p>构造时传入的 Frontier 不参与搜索，只是为了兼容 {@code EngineFeeder.getIdaStar()} 的统一构造方式。
 */
public class IdAStar extends AbstractSearcher {

    /** Optional export observer. No observer data is allocated during ordinary searches. */
    public interface Observer {
        void iteration(Node root, int bound, int iteration);

        void visit(Node node, boolean found, int bound, int iteration);

        void generated(Node parent, List<Node> children, Predictor predictor, State goal);

        void prune(Node node, String status);
    }

    private final Observer observer;
    private int iteration;

    /** f 值的正无穷哨兵。dfs 返回它表示本子树没有因 f 超过界限而被剪枝的结点，整轮都返回它表示搜索树已穷尽 */
    private static final int INFINITY = Integer.MAX_VALUE;

    // 对状态做启发式估值的预测器
    private final Predictor predictor;
    private final Predictor rootPredictor;
    private State goalState;

    // 找到的目标结点，非 null 表示已找到解
    private Node goalNode;

    public IdAStar(Frontier frontier, Predictor predictor) {
        this(frontier, predictor, null);
    }

    public IdAStar(Frontier frontier, Predictor predictor, Observer observer) {
        super(frontier);
        this.observer = observer;
        this.predictor = predictor;
        rootPredictor =
                (state, goal) -> {
                    goalState = goal;
                    return predictor.heuristics(state, goal);
                };
    }

    /**
     * 求解问题。
     *
     * @param problem 待求解的问题
     * @return 解路径，不含根结点；无解返回 null，初始即目标返回空路径
     */
    @Override
    public Deque<Node> search(Problem problem) {
        // 复位上一次搜索留下的状态。explored 在这里充当当前路径的状态集合
        explored.clear();
        frontier.clear();
        nodesExpanded = 0;
        nodesGenerated = 0;
        resetMetrics();
        boundPruned = 0;
        cyclePruned = 0;
        goalNode = null;

        if (!problem.solvable()) {
            return null;
        }

        // 根结点的 h 值就是初始界限
        Node root = problem.root(rootPredictor);
        if (METRICS_ENABLED) heuristicEvaluations++;
        int bound = root.getHeuristic();
        iteration = 0;

        while (true) {
            goalNode = null;
            long startGenerated = nodesGenerated, startExpanded = nodesExpanded;
            if (observer != null) observer.iteration(root, bound, iteration);
            // 以当前界限做一轮深度优先搜索，返回下一轮的界限
            int nextBound;
            boolean complete = false;
            try {
                nextBound = dfs(problem, root, bound);
                complete = true;
            } finally {
                if (METRICS_ENABLED)
                    iterations.add(
                            new long[] {
                                bound,
                                nodesExpanded - startExpanded,
                                nodesGenerated - startGenerated,
                                complete ? 1 : 0
                            });
            }

            if (goalNode != null) {
                // 找到目标时是逐层提前返回的，路径上的状态还留在 explored 里，这里统一清理
                explored.clear();
                return generatePath(goalNode);
            }

            if (nextBound == INFINITY) {
                // 本轮没有结点被剪枝也没有找到目标，搜索树已穷尽，问题无解
                return null;
            }

            // 把界限抬到超过当前界限的最小 f 值。nextBound 严格大于 bound，界限单调上升，算法必然终止
            bound = nextBound;
            iteration++;
        }
    }

    /**
     * 从 node 出发、以 bound 为 f 值上限做深度优先搜索。
     *
     * @param problem 待求解的问题
     * @param node 当前结点
     * @param bound 本轮迭代的 f 值上限
     * @return 本子树中被剪枝结点的最小 f 值，没有剪枝时返回 INFINITY；找到目标时返回值无意义，调用方以 goalNode 判断
     */
    private int dfs(Problem problem, Node node, int bound) {
        checkDeadline(nodesExpanded);
        if (METRICS_ENABLED) peakDepth = Math.max(peakDepth, node.getPathCost());
        int f = node.evaluation();

        // f 超过界限就剪掉整棵子树，该 f 值作为下一层界限的候选
        if (f > bound) {
            if (METRICS_ENABLED) boundPruned++;
            if (observer != null) observer.prune(node, "bound-pruned");
            return f;
        }

        // 目标判定放在扩展之前。目标的 f 超过当前界限时会先被剪枝记入下一层界限，等界限抬上来再访问，不会漏解
        if (problem.goal(node.getState())) {
            goalNode = node;
            if (observer != null) observer.visit(node, true, bound, iteration);
            return INFINITY;
        }

        nodesExpanded++;
        if (observer != null) observer.visit(node, false, bound, iteration);

        // 当前状态加入路径集合，子孙再绕回它就是成环，会被剪掉
        explored.add(node.getState());

        List<Node> children = problem.childNodes(node);
        // 计数口径同 BestFirstSearcher：生成的子结点都计数，不论随后是否被剪掉
        nodesGenerated += children.size();
        if (observer != null) observer.generated(node, children, predictor, goalState);
        // Reject path cycles before evaluating them; use the same child list throughout.
        for (int i = children.size() - 1; i >= 0; i--) {
            Node child = children.get(i);
            if (explored.contains(child.getState())) {
                if (METRICS_ENABLED) cyclePruned++;
                if (observer != null) observer.prune(child, "cycle-pruned");
                children.remove(i);
            } else {
                child.setHeuristic(predictor, goalState);
                if (METRICS_ENABLED) heuristicEvaluations++;
            }
        }

        // 子结点按 f 升序排列后再深入。顺序不影响最优性，但先走 f 小的分支通常能更早命中目标
        for (int i = 1; i < children.size(); i++) {
            Node key = children.get(i);
            int j = i - 1;
            while (j >= 0 && children.get(j).evaluation() > key.evaluation()) {
                children.set(j + 1, children.get(j));
                j--;
            }
            children.set(j + 1, key);
        }

        int min = INFINITY; // 本子树的下一层界限
        for (Node child : children) {
            int t = dfs(problem, child, bound);
            if (goalNode != null) {
                // 已找到目标，下一层界限没有意义，逐层返回
                return INFINITY;
            }
            if (t < min) {
                min = t;
            }
        }

        // 回溯，当前状态离开路径集合
        explored.remove(node.getState());
        return min;
    }
}
