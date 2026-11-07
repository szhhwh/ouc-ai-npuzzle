package core.solver.algorithm.searcher;

import core.problem.Problem;
import core.problem.State;
import core.solver.queue.Frontier;
import core.solver.queue.Node;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

/**
 * 抽象的搜素者，保留搜索历史。
 * 使用explored保存已经扩展过的结点，
 * 使用Frontier保存已扩展出来但尚未被扩展的结点
 *
 */
public abstract class AbstractSearcher {

    // 已经访问过的节点集合
    protected final Set<State> explored = new HashSet<>();

    // 还未扩展的节点队列
    protected final Frontier frontier;

    protected long nodesGenerated = 0;
    protected long nodesExpanded = 0;
    protected static final boolean METRICS_ENABLED = !Boolean.getBoolean("experiment.metrics.off");

    public long peakFrontier = -1, peakDepth = 0;
    public long boundPruned = -1, cyclePruned = -1, duplicatePruned = -1;
    public long heuristicEvaluations = 0, specializedEvaluations = -1;
    public final java.util.List<long[]> iterations = new java.util.ArrayList<>();
    private long deadlineNanos;

    public final void setDeadlineNanos(long deadlineNanos) {
        this.deadlineNanos = deadlineNanos;
    }

    protected final void checkDeadline(long expanded) {
        if (deadlineNanos != 0 && (expanded & 4095) == 0
                && System.nanoTime() - deadlineNanos >= 0) {
            throw new SearchTimeout();
        }
    }

    public static final class SearchTimeout extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public SearchTimeout() {
            super("search deadline exceeded", null, false, false);
        }
    }

    protected void resetMetrics() {
        peakFrontier = -1;
        peakDepth = 0;
        boundPruned = cyclePruned = duplicatePruned = -1;
        heuristicEvaluations = 0;
        specializedEvaluations = -1;
        iterations.clear();
    }

    public AbstractSearcher(Frontier frontier) {
        this.frontier = frontier;
    }

    public long nodesGenerated() {
        return nodesGenerated;
    }

    public long nodesExpanded() {
        return nodesExpanded;
    }

    public abstract Deque<Node> search(Problem problem);

    protected Deque<Node> generatePath(Node goal) {
        Deque<Node> stack = new ArrayDeque<>();
        Node curr = goal;
        while (curr.getParent() != null) {
            stack.push(curr);
            curr = curr.getParent();
        }
        return stack;
    }

//    /**
//     *
//     * @return	算法执行至此，已经被扩展的结点个数。
//     */
//    @Override
//    public int nodesExpanded() {
//        return explored.size();  // explored表中的节点有可能从explored表中删除，然后重新放入Frontier，从而有可能被重新扩展。
//                                 // 所以搜索结束后，explored中的节点的个数，有可能小于被扩展过的节点总个数
//                                 // 当启发函数满足单调限制时，二者才相等。
//    }
//
//    /**
//     *
//     * @return 已经生成的结点的个数；
//     * 在启发函数满足单调限制时，frontier中的节点也有可能被新生成的重复访问节点替换掉
//     * 所以，这个数目即使在满足单调限制时，也是不准确的
//     */
//    @Override
//    public int nodesGenerated(){
//        return explored.size() + frontier.size();
//    }

    /**
     * 节点是否已经扩展过
     * @param node
     * @return
     */
    protected boolean expanded(Node node) {
        return explored.contains(node.getState());
    }
}