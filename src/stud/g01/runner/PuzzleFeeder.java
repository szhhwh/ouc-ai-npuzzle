package stud.g01.runner;

import core.problem.Problem;
import core.runner.EngineFeeder;
import core.solver.algorithm.heuristic.HeuristicType;
import core.solver.algorithm.heuristic.Predictor;
import core.solver.algorithm.searcher.AbstractSearcher;
import core.solver.queue.EvaluationType;
import core.solver.queue.Frontier;
import core.solver.queue.Node;
import java.util.ArrayList;
import stud.g01.problem.npuzzle.NPuzzleIdaStar;
import stud.g01.problem.npuzzle.NPuzzleProblem;
import stud.g01.problem.npuzzle.PuzzleBoard;
import stud.g01.problem.npuzzle.PuzzleHeuristics;
import stud.queue.PqFrontier;

/** N-Puzzle 问题的 EngineFeeder。从输入文本解析问题实例，并提供 Frontier 和启发函数。 */
public class PuzzleFeeder extends EngineFeeder {

    /**
     * 解析输入的每一行，生成问题实例列表，空行跳过。每行的格式是：规模，接着是 size 平方个初始盘面数字，再接着是 size 平方个目标盘面数字，数字按行主序排列，0 表示空格。
     *
     * @param problemLines 输入文本的所有行
     * @return 问题实例列表
     */
    @Override
    public ArrayList<Problem> getProblems(ArrayList<String> problemLines) {
        ArrayList<Problem> problems = new ArrayList<>();

        for (String line : problemLines) {
            if (line.isBlank()) continue;

            // 每行依次是规模、初始盘面、目标盘面
            String[] tokens = line.trim().split("\\s+");
            int size = Integer.parseInt(tokens[0]);
            int count = size * size;

            // 初始盘面
            byte[] initCells = new byte[count];
            for (int i = 0; i < count; i++) {
                initCells[i] = Byte.parseByte(tokens[1 + i]);
            }
            // 目标盘面
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
     * 按估值类型生成 Frontier。
     *
     * @param type 结点估值类型
     * @return Frontier 实例
     */
    @Override
    public Frontier getFrontier(EvaluationType type) {
        return new PqFrontier(Node.evaluator(type));
    }

    /**
     * 按类型取启发函数，具体实现注册在 PuzzleHeuristics 中。
     *
     * @param type 启发函数类型
     * @return 对应的启发函数，未注册时返回 null
     */
    @Override
    public Predictor getPredictor(HeuristicType type) {
        return PuzzleHeuristics.predictor(type);
    }

    /**
     * PDB 使用 N-Puzzle 专用 IDA*；基础启发函数使用通用 IDA*。
     *
     * @param type 启发函数类型
     * @return 对应的 IDA* 搜索器
     */
    @Override
    public AbstractSearcher getNIdaStar(HeuristicType type) {
        if (type == HeuristicType.DISJOINT_PATTERN) {
            return new NPuzzleIdaStar();
        }
        return getIdaStar(type);
    }
}
