package stud.solver;

import core.problem.Problem;
import core.solver.algorithm.heuristic.Predictor;
import core.solver.algorithm.searcher.AbstractSearcher;
import core.solver.queue.Frontier;
import core.solver.queue.Node;

import java.util.Deque;

/**
 * Iterative Deepening A*（迭代加深 A*）搜索器。
 *
 * <p>算法思路（标准 IDA*）：</p>
 * <ol>
 *   <li>取根结点的 f = g + h = h(root) 作为初始界限 bound；</li>
 *   <li>以 bound 为上限做一次深度优先搜索：只沿着 f = g + h ≤ bound 的结点深入，
 *       一旦某个结点的 f &gt; bound 就剪枝，并记下这些被剪掉的 f 值；</li>
 *   <li>若本轮 DFS 访问到目标结点，则回溯构造解路径返回；否则取
 *       nextBound = min{ f | f &gt; bound }（本轮所有被剪枝结点的最小 f 值）作为新的界限，
 *       重复步骤 2。nextBound 的语义：目标解的 f 值如果存在，必然落在某个界限层上，
 *       而超过当前 bound 的最小 f 就是下一层可能出现的最优 f 值；</li>
 *   <li>若某轮 DFS 结束时没有任何结点被剪枝（nextBound = ∞），说明在当前启发函数的
 *       f 值体系下搜索树已被穷尽而没有遇到目标，问题无解，返回 null。</li>
 * </ol>
 *
 * <p>为何 IDA* 的空间复杂度是 O(d)（d 为解路径深度）：算法不保存任何全局的 open/closed
 * 表，只做递归 DFS。任意时刻真正占用的存储只有"当前递归栈上从根到最深结点的那条路径"
 * （各层递归帧 + 路径上状态组成的集合），其长度不超过 d；每条结点只需 O(1) 的父指针链
 * 即可在找到目标后回溯出完整解路径。代价是同一状态在不同分支间会被重复生成与扩展，
 * 换来了极小的内存 footprint——这正是 IDA* 相对 A* 的取舍。</p>
 *
 * <p>环检测策略（与 A* 的关键区别）：IDA* 的正确性只要求剪掉"同一条路径上重复出现的
 * 状态"（防止沿路径来回走形成环路），而跨分支的重复状态允许重访。若像 A* 那样做全局
 * explored 去重，反而可能因为先到的次优 g 值而丢掉后到分支上更优的路径。本实现把
 * 从父类继承的 {@code explored} 集合复用为"当前路径状态集合"：递归进入结点时加入，
 * 回溯返回时移除。</p>
 *
 * <p>计数口径（与教师 BestFirstSearcher 保持一致）：
 * nodesGenerated 对 {@code childNodes} 生成的每一个子结点都 +1（不管它随后是否被剪掉）；
 * nodesExpanded 对每一个真正调用 {@code childNodes} 展开（通过了 f ≤ bound 且不是目标）
 * 的结点 +1。根结点与 BestFirstSearcher 一样不计入 nodesGenerated。</p>
 *
 * <p>说明：IDA* 本质上不需要 Frontier（递归调用栈即搜索栈），构造器保留该参数仅为兼容
 * {@code EngineFeeder.getIdaStar()} 的统一构造方式；除每次 search 开始时按惯例调用
 * {@code frontier.clear()} 复位外，该字段不参与算法。</p>
 */
public class IdAStar extends AbstractSearcher {

	/**
	 * 表示"正无穷"的 f 值哨兵：
	 * 1) dfs 返回它表示本子树内没有任何因 f > bound 而被剪枝的结点；
	 * 2) 整轮迭代都返回它，说明搜索树已被穷尽，问题无解。
	 */
	private static final int INFINITY = Integer.MAX_VALUE;

	//预测器，对当前状态进行启发式估值
	private final Predictor predictor;

	//本轮迭代中找到的目标结点；非 null 即表示已经找到解
	private Node goalNode;

	public IdAStar(Frontier frontier, Predictor predictor) {
		super(frontier);
		this.predictor = predictor;
	}

	/**
	 * 迭代加深 A* 主体：外层循环逐步抬高 f 界限，内层 dfs 在界限内做深度优先搜索。
	 *
	 * @param problem 待求解的问题
	 * @return 解路径（不含根结点）；无解返回 null；initial == goal 时返回空路径
	 */
	@Override
	public Deque<Node> search(Problem problem) {
		// 先判断问题是否可解，无解时直接返回 null（与 BestFirstSearcher 一致）
		if (!problem.solvable()) {
			return null;
		}

		// 每次新的搜索开始前，清理上一轮遗留的状态：
		// explored 在本实现中充当"当前路径状态集合"，frontier 虽不参与算法也要复位
		explored.clear();
		frontier.clear();
		nodesExpanded = 0;
		nodesGenerated = 0;

		// 根结点（root(predictor) 已为其计算好 h 值）
		Node root = problem.root(predictor);
		// 初始界限 = f(root) = g(root) + h(root) = 0 + h(root)
		int bound = root.getHeuristic();

		while (true) {
			goalNode = null;
			// 以当前 bound 做一轮深度优先搜索，返回值为下一轮的界限（nextBound）
			int nextBound = dfs(problem, root, bound);

			if (goalNode != null) {
				// 找到目标时是层层提前返回的，路径上的状态没有从 explored 中移除，这里统一清理
				explored.clear();
				// 从目标结点沿父指针回溯构造解路径（不含根结点）
				return generatePath(goalNode);
			}

			if (nextBound == INFINITY) {
				// 本轮没有任何结点因 f > bound 被剪枝，却也没有找到目标：
				// 搜索空间在当前 f 体系下已穷尽，问题无解
				return null;
			}

			// 抬高界限到"超过 bound 的最小 f 值"，开始下一轮迭代。
			// 因为 nextBound 严格大于 bound，界限单调上升，算法必然终止
			bound = nextBound;
		}
	}

	/**
	 * 以 bound 为 f 值上限、从 node 出发的深度优先搜索（递归即栈）。
	 *
	 * <p>递归不变式：进入本方法时，explored 中恰好保存着根到 node 的路径上
	 * （不含 node 自身）的全部状态。</p>
	 *
	 * @param problem 待求解的问题
	 * @param node    当前访问的结点（已带 g、h）
	 * @param bound   本轮迭代的 f 值上限
	 * @return 本子树中所有被剪枝（f &gt; bound）结点的最小 f 值，即对本子树而言的 nextBound；
	 *         没有任何剪枝时返回 INFINITY（若本子树内找到目标，返回值无意义，
	 *         调用方以 goalNode != null 判断）
	 */
	private int dfs(Problem problem, Node node, int bound) {
		// f = g + h。g 是根到该结点的真实路径耗散，h 由 predictor 给出
		int f = node.evaluation();

		// 剪枝：f 超过界限的子树整棵不再展开，其 f 值进入 nextBound 的候选
		if (f > bound) {
			return f;
		}

		// 目标判定放在"访问时"（通过 f ≤ bound 之后、扩展之前），
		// 与 BestFirstSearcher 的"出队先判 goal 再扩展"口径一致。
		// 若 goal 的 f 超过当前 bound，本轮会先被剪枝记入 nextBound，
		// 待界限抬到 f(goal) 的那一轮自然会被访问到，故不漏解
		if (problem.goal(node.getState())) {
			goalNode = node;
			return INFINITY;
		}

		// 该结点真正被扩展
		nodesExpanded++;

		// 把当前状态加入"当前路径"集合，此后它下面的子孙若再绕回此状态即成环，须剪掉
		explored.add(node.getState());

		int min = INFINITY; // 本子树的 nextBound 初值：正无穷
		for (Node child : problem.childNodes(node, predictor)) {
			// 计数口径同 BestFirstSearcher：每个由 childNodes 生成的子结点都计数，
			// 不论它随后是否被环检测剪掉或因 f 超界被剪枝
			nodesGenerated++;

			// 环检测：只剪"与当前路径上某状态相同"的子结点。
			// 此刻 explored = 根..node 的整条路径状态，因此：
			//   a) 子状态等于父状态（直接回退一步）的情形已被自然包含，无需单独比较祖父状态；
			//   b) 更长的绕环路径（走出若干步后回到路径上的旧状态）同样被剪掉；
			//   c) 跨分支的重复状态不在 explored 中，允许重访——这是 IDA* 正确性的要求
			if (explored.contains(child.getState())) {
				continue;
			}

			int t = dfs(problem, child, bound);
			if (goalNode != null) {
				// 子树中已找到目标，不再关心 nextBound，层层快速返回
				return INFINITY;
			}
			if (t < min) {
				min = t;
			}
		}

		// 回溯：当前状态离开"当前路径"集合，供其他分支重访该状态
		explored.remove(node.getState());
		return min;
	}
}
