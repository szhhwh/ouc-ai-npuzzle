package stud.g01.runner;

import core.problem.Problem;
import core.runner.EngineFeeder;
import core.solver.algorithm.heuristic.HeuristicType;
import core.solver.algorithm.searcher.AbstractSearcher;
import core.solver.queue.Node;
import stud.g01.problem.npuzzle.PuzzleBoard;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;

/**
 * 启发函数与搜索引擎的基准测试工具（课程报告对比实验的数据来源）。
 *
 * <p>用法：{@code java stud.g01.runner.BenchmarkRunner <问题文件> [重复次数=5]}
 * 对文件中每个问题实例，分别测量 {MISPLACED, MANHATTAN} × {A*, IDA*} 四种组合，
 * 每种组合先做 1 次 JVM 预热（结果丢弃），再做指定次数的正式测量，
 * 输出解长、生成/扩展结点数与耗时的中位数/最小值。</p>
 *
 * <p>说明：本工具采用「预热 + 多次测量 + 中位数」的简易方法控制 JIT 编译与
 * GC 噪声。课程报告推荐的 JMH（http://openjdk.java.net/projects/code-tools/jmh/）
 * 在分支预测、死代码消除、黑盒消费等方面控制更严格；本项目为无构建工具的
 * IntelliJ 模块，故提供此轻量替代，报告中的关键结论可用 JMH 进一步精化复核。</p>
 *
 * <p><b>计时口径：</b>本工具的耗时为 System.nanoTime 测得的<b>墙上时间</b>；
 * 教师框架 SearchTester 输出的是 StopwatchCPU 的 <b>CPU 时间</b>。两表数字
 * 口径不同，不可直接混排互比。</p>
 *
 * <p>已知边界：MISPLACED 启发对 4 阶难题过弱（最优 52 步实例需数千万状态），
 * 本工具对 4 阶实例自动跳过 MISPLACED 组合并在输出中注明——该现象本身
 * 即是报告中「启发函数强度对比」的重要实验证据。</p>
 */
public class BenchmarkRunner {

    //参与对比的引擎组合：{启发函数} × {A*, IDA*}
    private static final HeuristicType[] HEURISTICS = {HeuristicType.MISPLACED, HeuristicType.MANHATTAN};
    private static final String[] ENGINES = {"A*", "IDA*"};

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("用法：java stud.g01.runner.BenchmarkRunner <问题文件> [重复次数=5]");
            return;
        }
        int repeats = 5;
        if (args.length >= 2) {
            try {
                repeats = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                System.err.println("重复次数应为正整数，当前输入：" + args[1]);
                return;
            }
        }
        if (repeats < 1) {
            System.err.println("重复次数应为正整数，当前输入：" + repeats);
            return;
        }

        //复用 SearchTester 的读入逻辑，保持口径一致
        ArrayList<String> lines;
        try (java.util.Scanner scanner = new java.util.Scanner(new java.io.File(args[0]))) {
            lines = core.runner.SearchTester.getProblemLines(scanner);
        } catch (java.io.FileNotFoundException e) {
            System.err.println("问题文件不存在：" + args[0]);
            return;
        }

        EngineFeeder feeder = new PuzzleFeeder();
        ArrayList<Problem> problems = feeder.getProblems(lines);

        //表头
        System.out.println("实例\t规模\t引擎\t启发函数\t解长\t生成结点\t扩展结点\t耗时中位数(s)\t耗时最小值(s)");
        System.out.println("-".repeat(96));

        for (int i = 0; i < problems.size(); i++) {
            Problem problem = problems.get(i);
            int size = ((PuzzleBoard) problem.root().getState()).size();
            String sizeTag = size + "阶";

            for (int h = 0; h < HEURISTICS.length; h++) {
                //弱启发 + 4 阶的组合不可行（固有代价，非缺陷），跳过并注明
                if (HEURISTICS[h] == HeuristicType.MISPLACED && size >= 4) {
                    //行尾补齐制表符，使 TSV 列数与数据行一致（说明文字落在"解长"列）
                    System.out.println("#" + i + "\t" + sizeTag + "\t-\tMISPLACED\t跳过（启发过弱，4 阶实例状态数爆炸）\t\t\t\t");
                    continue;
                }
                for (int e = 0; e < ENGINES.length; e++) {
                    runOnce(i, sizeTag, ENGINES[e], HEURISTICS[h], problem, feeder, repeats);
                }
            }
            System.out.println();
        }
    }

    /**
     * 对单个（实例, 引擎, 启发函数）组合执行预热 + repeats 次测量并输出统计行
     */
    private static void runOnce(int id, String sizeTag, String engine, HeuristicType heuristic,
                                Problem problem, EngineFeeder feeder, int repeats) {
        int solutionLen = -1;
        long gen = 0, exp = 0;
        double[] times = new double[repeats];
        Deque<Node> path = null;

        //1 次预热 + repeats 次正式测量
        for (int r = -1; r < repeats; r++) {
            AbstractSearcher searcher = engine.equals("A*")
                    ? feeder.getAStar(heuristic)
                    : feeder.getIdaStar(heuristic);

            long t0 = System.nanoTime();
            path = searcher.search(problem);
            double sec = (System.nanoTime() - t0) / 1e9;

            if (r < 0) continue;    //预热轮只触发 JIT，不计数据
            times[r] = sec;
            solutionLen = path == null ? -1 : path.size();
            gen = searcher.nodesGenerated();
            exp = searcher.nodesExpanded();
        }

        Arrays.sort(times);
        //偶数次测量取中间两值的均值（真中位数），奇数次取正中元素，
        //避免 repeats 为偶数时系统性取到偏大的"上中位数"
        int n = times.length;
        double median = (n % 2 == 1) ? times[n / 2] : (times[n / 2 - 1] + times[n / 2]) / 2.0;
        double min = times[0];
        System.out.printf("#%d\t%s\t%s\t%s\t%d\t%d\t%d\t%.4f\t%.4f%n",
                id, sizeTag, engine, heuristic, solutionLen, gen, exp, median, min);
    }
}
