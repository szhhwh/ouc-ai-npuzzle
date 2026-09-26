package stud.g01.runner;

import core.problem.Problem;
import core.runner.EngineFeeder;
import core.solver.algorithm.heuristic.HeuristicType;
import core.solver.algorithm.heuristic.Predictor;
import core.solver.queue.EvaluationType;
import core.solver.queue.Frontier;
import core.solver.queue.Node;
import stud.g01.problem.npuzzle.NPuzzleProblem;
import stud.g01.problem.npuzzle.PuzzleBoard;
import stud.queue.PqFrontier;

import java.util.ArrayList;

/**
 * N-Puzzle 问题的 EngineFeeder。
 * 从问题输入样例文本解析问题实例，并为搜索引擎提供 Frontier 与 Predictor。
 */
public class PuzzleFeeder extends EngineFeeder {

    /**
     * 解析问题输入样例的每一行（跳过空行），生成问题实例列表。
     * 每行格式：size，随后 size² 个初始盘面数字，最后 size² 个目标盘面数字；
     * 盘面数字按行主序排列，0 表示空格。
     *
     * @param problemLines 问题输入样例文本的所有行
     * @return 问题实例列表
     */
    @Override
    public ArrayList<Problem> getProblems(ArrayList<String> problemLines) {
        ArrayList<Problem> problems = new ArrayList<>();

        for (String line : problemLines) {
            if (line.isBlank()) continue;   //跳过空行

            String[] tokens = line.trim().split("\\s+");
            int size;
            try {
                size = Integer.parseInt(tokens[0]);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("问题行首个数字（阶数）无法解析，行内容：\"" + line + "\"", e);
            }
            int count = size * size;

            //fail-fast：每行应恰为 1 + 2*size² 个数字（size、初始盘面、目标盘面）
            if (tokens.length != 1 + 2 * count)
                throw new IllegalArgumentException("问题行格式错误：应有 " + (1 + 2 * count)
                        + " 个数字，实际 " + tokens.length + " 个；行内容：\"" + line + "\"");

            //读入初始盘面
            byte[] initCells = new byte[count];
            for (int i = 0; i < count; i++) {
                initCells[i] = parseCell(tokens[1 + i], line);
            }
            //读入目标盘面
            byte[] goalCells = new byte[count];
            for (int i = 0; i < count; i++) {
                goalCells[i] = parseCell(tokens[1 + count + i], line);
            }

            problems.add(new NPuzzleProblem(
                    new PuzzleBoard(initCells, size),
                    new PuzzleBoard(goalCells, size),
                    size));
        }
        return problems;
    }

    /**
     * 解析盘面单元格数字，失败时抛出带整行内容的异常，便于定位输入错误
     *
     * @param token 单个单元格的数字文本
     * @param line  整行原文（仅用于异常信息）
     * @return 解析出的字节值
     */
    private static byte parseCell(String token, String line) {
        try {
            return Byte.parseByte(token);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "盘面数字无法解析或超出范围：\"" + token + "\"，行内容：\"" + line + "\"", e);
        }
    }

    /**
     * 生成采取某种估值机制的 Frontier
     *
     * @param type 结点评估器的类型
     * @return 使用评估机制的一个 Frontier 实例
     */
    @Override
    public Frontier getFrontier(EvaluationType type) {
        //本组实现的高效 Frontier：PriorityQueue + HashMap 索引 + 惰性删除，均摊 O(log n)
        return new PqFrontier(Node.evaluator(type));
    }

    /**
     * 获得对状态进行估值的 Predictor；启发函数注册在 PuzzleBoard 中
     *
     * @param type 启发函数的类型
     * @return 对应的启发函数；未知类型返回 null
     */
    @Override
    public Predictor getPredictor(HeuristicType type) {
        return PuzzleBoard.predictor(type);
    }
}
