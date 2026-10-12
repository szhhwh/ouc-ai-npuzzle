package stud.queue;

import core.problem.State;
import core.solver.queue.Frontier;
import core.solver.queue.Node;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;

/**
 * 基于最小堆和状态索引的高性能 Frontier。
 *
 * <p>同一状态只保留估值更好的结点。替换时旧结点仍暂存在堆中，poll 时通过状态索引跳过它；这种惰性删除避免了 {@link PriorityQueue#remove(Object)}
 * 的线性扫描。
 */
public final class PqFrontier implements Frontier {
    private final Comparator<Node> evaluator;
    private final PriorityQueue<Node> queue;
    private final Map<State, Node> bestNodeByState = new HashMap<>();

    public PqFrontier() {
        this(Comparator.naturalOrder());
    }

    public PqFrontier(Comparator<Node> evaluator) {
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator must not be null");
        this.queue = new PriorityQueue<>(evaluator);
    }

    @Override
    public Node poll() {
        Node candidate;
        while ((candidate = queue.poll()) != null) {
            State state = candidate.getState();
            if (bestNodeByState.get(state) == candidate) {
                bestNodeByState.remove(state);
                return candidate;
            }
        }
        return null;
    }

    @Override
    public void clear() {
        queue.clear();
        bestNodeByState.clear();
    }

    @Override
    public int size() {
        return bestNodeByState.size();
    }

    @Override
    public boolean isEmpty() {
        return bestNodeByState.isEmpty();
    }

    @Override
    public boolean contains(Node node) {
        Objects.requireNonNull(node, "node must not be null");
        return bestNodeByState.containsKey(node.getState());
    }

    @Override
    public boolean offer(Node node) {
        Objects.requireNonNull(node, "node must not be null");
        Node oldNode = bestNodeByState.get(node.getState());
        if (oldNode != null && evaluator.compare(oldNode, node) <= 0) {
            return false;
        }

        bestNodeByState.put(node.getState(), node);
        queue.offer(node);
        return true;
    }
}
