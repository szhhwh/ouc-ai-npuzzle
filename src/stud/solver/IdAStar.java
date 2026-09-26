package stud.solver;

import core.problem.Problem;
import core.solver.algorithm.heuristic.Predictor;
import core.solver.algorithm.searcher.AbstractSearcher;
import core.solver.queue.Frontier;
import core.solver.queue.Node;

import java.util.Deque;

/**
 * Iterative Deepening A*（迭代加深 A*）搜索器骨架。
 * core.runner.EngineFeeder.getIdaStar() 通过本类构造 IDA*，
 * 构造器签名须为 (Frontier, Predictor)。
 */
public class IdAStar extends AbstractSearcher {

	//预测器，对当前状态进行启发式估值
	private final Predictor predictor;

	public IdAStar(Frontier frontier, Predictor predictor) {
		super(frontier);
		this.predictor = predictor;
	}

	@Override
	public Deque<Node> search(Problem problem) {
		//TODO: 实现迭代加深 A* 的主体搜索逻辑
		return null;
	}
}
