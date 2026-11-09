package core.solver.algorithm.searcher;

import java.util.*;

import core.problem.Problem;
import core.solver.algorithm.heuristic.Predictor;
import core.solver.queue.Frontier;
import core.solver.queue.Node;

/**
 * 最佳优先搜索算法。 根据对Frontier中Node的排序方法，以及所采用的启发函数的不同 可以配置出不同的特性的算法。
 *  前提条件：f(n) = g(n) + h(n)；其中h(n)满足单调限制!  h(n)≡0时，Dijkstra算法；g(n)=0时，Greedy Best-First
 * final类，不能被继承；可通过可选 SearchObserver 观察实际搜索决策。
 *
 */
public final class BestFirstSearcher extends AbstractSearcher {

	private final SearchObserver observer;
	private final Frontier frontier;

	private final Predictor predictor; //预测器，对当前状态进行启发式估值

	/**
	 * 构造函数
	 *
	 * @param frontier Node对象的一个优先队列，可以确定一个状态所对应的结点是否在frontier中，
	 * @param predictor 具体的预测器（不在位将牌，曼哈顿距离等）
	 */
	public BestFirstSearcher(Frontier frontier, Predictor predictor) {
		this(frontier, predictor, null);
	}

	public BestFirstSearcher(Frontier frontier, Predictor predictor, SearchObserver observer) {
		this.frontier = Objects.requireNonNull(frontier);
		this.predictor = predictor;
		this.observer = observer;
	}

	@Override
	public Deque<Node> search(Problem problem) {
		frontier.clear();
		explored.clear();
		nodesExpanded = 0;
		nodesGenerated = 0;
		if (!problem.solvable()) return null;

		// 起始节点root
		Node root = problem.root(predictor);
		if (observer != null) observer.iteration(root, -1, 0);
		frontier.offer(root);

		// 搜索。。。
		while (true) {

			if (frontier.isEmpty())  // 在到达目标状态之前frontier变为空，则问题无解
				return null;

			Node node = frontier.poll(); // 从优先队列frontier中取出估值最小的节点

			if (problem.goal(node.getState())) { //进入目标状态
				if (observer != null) observer.visit(node, true, -1, 0);
				return generatePath(node);
			}

			if (observer != null) observer.visit(node, false, -1, 0);
			explored.add(node.getState());

			//对节点node进行扩展  Expansion
			for (Node child : problem.childNodes(node, predictor)) {
				nodesGenerated++;
				if (observer != null) observer.generated(child, SearchObserver.HeuristicQuality.EXACT);
				if (!expanded(child)) {
					boolean accepted = frontier.offer(child);
					if (observer != null) observer.offered(child, accepted);
				} else if (observer != null) {
					observer.prune(child, SearchObserver.Pruning.CLOSED);
				}
			}
			nodesExpanded++;
		}
	}
}