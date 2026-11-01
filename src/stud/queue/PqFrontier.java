package stud.queue;

import core.problem.State;
import core.solver.queue.Frontier;
import core.solver.queue.Node;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;

/** 基于优先队列和状态索引的 Frontier。同一状态只保留估值较好的结点，被替换的旧结点在 poll 时通过状态索引跳过。 */
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
