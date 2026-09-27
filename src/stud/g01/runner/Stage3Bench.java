package stud.g01.runner;

import core.problem.Problem;
import core.runner.EngineFeeder;
import core.solver.algorithm.heuristic.HeuristicType;
import core.solver.algorithm.searcher.AbstractSearcher;
import core.solver.queue.Node;
import stud.g01.problem.npuzzle.PuzzleBoard;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.Scanner;

/**
 * 第三阶段性能基准：指定启发函数 × 指定引擎，逐实例输出解长与结点数、耗时。
 *
 * <p>用法：
 * {@code java stud.g01.runner.Stage3Bench <问题文件> [实例起(1-based,默认1)] [实例止(含,默认末尾)] [测量次数=1]}
 * 系统属性 {@code -Dbench.heuristic=DISJOINT_PATTERN|MANHATTAN|MISPLACED}（默认 DISJOINT_PATTERN）、
 * {@code -Dbench.engine=ida|astar}（默认 ida）。</p>
 *
 * <p>口径与 BenchmarkRunner 一致：每组合先 1 次 JVM 预热（丢弃），再 N 次正式测量，
 * 报告墙上时间的中位数与最小值；结点数为末次测量的值（同输入下逐位确定）。
 * PDB 表构建发生在首次调用（预热轮），正式测量只计纯求解时间。</p>
 */
public final class Stage3Bench {

    public static void main(String[] args) throws FileNotFoundException {
        if (args.length < 1) {
            System.err.println("用法：java stud.g01.runner.Stage3Bench <问题文件> [起(1-based)] [止(含)] [测量次数=1]");
            return;
        }
        HeuristicType heuristic = HeuristicType.valueOf(
                System.getProperty("bench.heuristic", HeuristicType.DISJOINT_PATTERN.name()));
        String engine = System.getProperty("bench.engine", "ida").toLowerCase();

        ArrayList<String> lines;
        try (Scanner scanner = new Scanner(new File(args[0]))) {
            lines = core.runner.SearchTester.getProblemLines(scanner);
        }
        EngineFeeder feeder = new PuzzleFeeder();
        ArrayList<Problem> problems = feeder.getProblems(lines);

        int from = args.length >= 2 ? Integer.parseInt(args[1]) : 1;
        int to = args.length >= 3 ? Math.min(Integer.parseInt(args[2]), problems.size()) : problems.size();
        int repeats = args.length >= 4 ? Integer.parseInt(args[3]) : 1;

        System.out.printf("文件=%s 实例[%d..%d] 引擎=%s 启发=%s 测量次数=%d%n",
                args[0], from, to, engine, heuristic, repeats);
        System.out.println("实例\t规模\t解长\t生成结点\t扩展结点\t耗时中位数(s)\t耗时最小值(s)");
        System.out.println("-".repeat(84));

        for (int i = from; i <= to; i++) {
            Problem problem = problems.get(i - 1);
            int size = ((PuzzleBoard) problem.root().getState()).size();

            int solutionLen = -1;
            long gen = 0, exp = 0;
            double[] times = new double[repeats];
            Deque<Node> path = null;
            for (int r = -1; r < repeats; r++) {
                AbstractSearcher searcher;
                switch (engine) {
                    case "nida":   //专用优化 IDA*（增量 PDB + 奇偶跳轮）
                        searcher = new stud.g01.problem.npuzzle.NPuzzleIdaStar(
                                feeder.getFrontier(core.solver.queue.EvaluationType.FULL),
                                feeder.getPredictor(heuristic));
                        break;
                    case "astar":
                        searcher = feeder.getAStar(heuristic);
                        break;
                    default:
                        searcher = feeder.getIdaStar(heuristic);
                        break;
                }
                long t0 = System.nanoTime();
                path = searcher.search(problem);
                double sec = (System.nanoTime() - t0) / 1e9;
                if (r < 0) continue;   //预热轮不计数据（PDB 建表也发生在这一轮）
                times[r] = sec;
                solutionLen = path == null ? -1 : path.size();
                gen = searcher.nodesGenerated();
                exp = searcher.nodesExpanded();
            }
            Arrays.sort(times);
            int n = times.length;
            double median = (n % 2 == 1) ? times[n / 2] : (times[n / 2 - 1] + times[n / 2]) / 2.0;
            System.out.printf("#%d\t%d阶\t%d\t%d\t%d\t%.4f\t%.4f%n",
                    i, size, solutionLen, gen, exp, median, times[0]);
        }
    }

    private Stage3Bench() {
    }
}
