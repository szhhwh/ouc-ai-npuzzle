package stud.queue;

import core.problem.State;
import core.solver.queue.Frontier;
import core.solver.queue.Node;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;

/** 用有序列表实现的 Frontier。查找和插入都要线性扫描，性能不高，但可用于所有问题 */
public class ListFrontier extends ArrayList<Node> implements Frontier {
    // 结点比较器，决定优先取出哪个元素
    private final Comparator<Node> evaluator;

    public ListFrontier(Comparator<Node> evaluator) {
        this.evaluator = evaluator;
    }

    @Override
    public Node poll() {
        return super.remove(0);
    }

    /**
     * @param node 待查找的结点
     * @return 存在状态相同的结点时返回 true
     */
    @Override
    public boolean contains(Node node) {
        return getNode(node.getState()) != null;
    }

    /**
     * 插入结点。若已存在状态相同的结点，则保留估值较小的那个。
     *
     * @param node 待插入的结点
     * @return 插入成功返回 true，被已有的更优结点取代时返回 false
     */
    @Override
    public boolean offer(Node node) {
        Node oldNode = getNode(node.getState());
        if (oldNode == null) {
            // 按估值升序插入到应有的位置
            super.add(getIndex(node), node);
            return true;
        } else {
            return discardOrReplace(oldNode, node);
        }
    }

    /**
     * 新旧结点状态相同时，保留估值较小的那个。
     *
     * @param oldNode 已在队列中的结点
     * @param node 新生成的结点
     * @return 用新结点替换旧结点时返回 true，舍弃新结点时返回 false
     */
    private boolean discardOrReplace(Node oldNode, Node node) {
        // 旧结点估值更大时用新结点替换
        if (evaluator.compare(oldNode, node) > 0) {
            replace(oldNode, node);
            return true;
        }
        return false;
    }

    private Node getNode(State state) {
        for (var node : this) {
            if (node.getState().equals(state)) {
                return node;
            }
        }
        return null;
    }

    private int getIndex(Node node) {
        int index = Collections.binarySearch(this, node, evaluator);
        if (index < 0) index = -(index + 1);
        return index;
    }

    /**
     * 用新结点替换状态相同的旧结点。
     *
     * @param oldNode 被替换的结点
     * @param newNode 新结点
     */
    private void replace(Node oldNode, Node newNode) {
        super.remove(oldNode);
        super.add(getIndex(newNode), newNode);
    }

    /** ArrayList 的 equals 只比较元素序列，这里把 evaluator 也纳入比较 */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof ListFrontier)) return false;
        ListFrontier other = (ListFrontier) obj;
        return super.equals(obj) && evaluator.equals(other.evaluator);
    }

    @Override
    public int hashCode() {
        return 31 * super.hashCode() + evaluator.hashCode();
    }
}
