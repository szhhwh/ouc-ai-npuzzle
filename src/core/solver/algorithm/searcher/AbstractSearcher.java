package core.solver.algorithm.searcher;

import core.problem.Problem;
import core.problem.State;
import core.solver.queue.Node;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

/** Shared search counters and solution-path reconstruction. */
public abstract class AbstractSearcher {

    // 已经访问过的节点集合
    protected final Set<State> explored = new HashSet<>();

    protected int nodesGenerated = 0;   //已生成的节点的个数
    protected int nodesExpanded = 0;    //已扩展的节点的个数

    public int nodesGenerated() {
        return nodesGenerated;
    }

    public int nodesExpanded() {
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

    /**
     * 节点是否已经扩展过
     * @param node
     * @return
     */
    protected boolean expanded(Node node) {
        return explored.contains(node.getState());
    }
}