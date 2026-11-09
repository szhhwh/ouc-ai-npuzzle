package stud.g01.runner;

import core.problem.Problem;
import core.runner.EngineFeeder;
import core.solver.algorithm.heuristic.Predictor;
import core.solver.queue.EvaluationType;
import core.solver.queue.Frontier;
import core.solver.queue.Node;
import java.util.ArrayList;
import stud.g01.problem.npuzzle.NPuzzleIdaStar;
import stud.g01.problem.npuzzle.NPuzzleProblem;
import stud.g01.problem.npuzzle.PuzzleBoard;
import stud.g01.problem.npuzzle.PuzzleHeuristicType;
import stud.g01.problem.npuzzle.PuzzleHeuristics;
import stud.queue.PqFrontier;

/** N-Puzzle 问题的求解适配器，继承自框架的 EngineFeeder。 负责从输入文本解析问题实例，并提供开放列表与启发预测器。 */
public class PuzzleFeeder extends EngineFeeder<PuzzleHeuristicType> {

    /** 构造求解适配器，指定启发函数枚举类型，并注册专用的 N-Puzzle IDA* 搜索算法引擎。 */
    public PuzzleFeeder() {
        super(PuzzleHeuristicType.class);
        registerEngine("nida", NPuzzleIdaStar::new);
    }

    /**
     * 解析输入的每一行文本，生成问题实例列表，跳过空白行。 每一行的格式为：棋盘边长，接着是边长平方个初始盘面数字，再接着是边长平方个目标盘面数字。 数字按行主序排列，0 表示空格位置。
     *
     * @param problemLines 输入文本的所有行
     * @return 解析得到的问题实例列表
     */
    @Override
    public ArrayList<Problem> getProblems(ArrayList<String> problemLines) {
        ArrayList<Problem> problems = new ArrayList<>();

        for (String line : problemLines) {
            if (line.isBlank()) continue;

            String[] tokens = line.trim().split("\\s+");
            int size = Integer.parseInt(tokens[0]);
            int count = size * size;

            byte[] initCells = new byte[count];
            for (int i = 0; i < count; i++) {
                initCells[i] = Byte.parseByte(tokens[1 + i]);
            }
            byte[] goalCells = new byte[count];
            for (int i = 0; i < count; i++) {
                goalCells[i] = Byte.parseByte(tokens[1 + count + i]);
            }

            problems.add(
                    new NPuzzleProblem(
                            new PuzzleBoard(initCells, size),
                            new PuzzleBoard(goalCells, size),
                            size));
        }
        return problems;
    }

    /**
     * 根据结点估值类型构造优先队列开放列表。
     *
     * @param type 结点估值类型
     * @return 优先队列开放列表实例
     */
    @Override
    public Frontier getFrontier(EvaluationType type) {
        return new PqFrontier(Node.evaluator(type));
    }

    /**
     * 按类型获取启发预测器，具体实现注册在 PuzzleHeuristics 中。
     *
     * @param type 启发函数类型
     * @return 对应的启发预测器
     * @throws IllegalArgumentException 当传入不支持的启发函数类型时抛出
     */
    @Override
    public Predictor getPredictor(PuzzleHeuristicType type) {
        Predictor predictor = PuzzleHeuristics.predictor(type);
        if (predictor == null) {
            throw new IllegalArgumentException("不支持的 N-Puzzle 启发函数: " + type);
        }
        return predictor;
    }
}
