package core.runner;

import algs4.util.StopwatchCPU;
import core.problem.Problem;
import core.solver.algorithm.searcher.AbstractSearcher;
import core.solver.queue.Node;
import core.solver.algorithm.heuristic.HeuristicType;

import java.io.File;
import java.io.FileNotFoundException;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Scanner;

/**
 * 对学生的搜索算法进行检测的主程序
 * arg0: 问题输入样例      resources/pathfinding.txt
 * arg1: 各小组的Feeder   stud.runner.WalkerFeeder
 * arg2: 搜索引擎          astar / ida / nida / dijkstra
 * arg3: 启发函数列表，逗号分隔  MISPLACED,MANHATTAN
 */
public final class SearchTester {

    /** 搜索引擎的合法取值，算法与启发函数分别选择。 */
    private static final List<String> ENGINES = List.of("astar", "ida", "nida", "dijkstra");

    public static void main(String[] args) throws ClassNotFoundException,
            NoSuchMethodException, IllegalAccessException,
            InvocationTargetException, InstantiationException, FileNotFoundException {

        //参数个数检查
        if (args.length < 4) {
            System.err.println("参数不足：需要4个，实际只有" + args.length + "个");
            usage();
            return;
        }

        //args[0]：问题文件路径，预先检查文件是否存在
        File problemFile = new File(args[0]);
        if (!problemFile.exists()) {
            System.err.println("问题文件不存在：" + args[0]);
            usage();
            return;
        }

        //args[1]：各小组Feeder类的全限定名，反射加载并实例化
        EngineFeeder feeder;
        try {
            feeder = (EngineFeeder) Class.forName(args[1]).getDeclaredConstructor().newInstance();
        } catch (ClassNotFoundException e) {
            System.err.println("Feeder类不存在，请检查全限定类名：" + args[1]);
            usage();
            return;
        } catch (ReflectiveOperationException e) {
            System.err.println("Feeder类实例化失败：" + args[1] + "（" + e.getClass().getSimpleName() + "）");
            usage();
            return;
        }

        //从文件读入所有输入样例的文本
        ArrayList<String> problemLines;
        try (Scanner scanner = new Scanner(problemFile)) {
            problemLines = getProblemLines(scanner);
        }

        //feeder从输入样例文本解析出问题的所有实例
        ArrayList<Problem> problems = feeder.getProblems(problemLines);

        //args[2]：搜索引擎
        String engine = args[2].toLowerCase();
        if (!ENGINES.contains(engine)) {
            System.err.println("搜索引擎无法识别：" + args[2] + "，合法取值：" + ENGINES);
            usage();
            return;
        }

        //args[3]：启发函数列表，逗号分隔，大小写不敏感
        List<HeuristicType> heuristics = parseHeuristics(args[3]);
        if (heuristics == null) {
            usage();
            return;
        }

        for (HeuristicType heuristicType : heuristics) {
            //solveProblems方法根据不同启发函数生成不同的searcher
            //从Feeder获取所使用的搜索引擎（AStar，IDAStar等），     
            solveProblems(problems, feeder.getSearcher(engine, heuristicType), engine, heuristicType);
            System.out.println();
        }
    }

    /**
     * 打印命令行用法，帮助定位参数错误
     */
    private static void usage() {
        System.err.println("用法：<问题文件> <Feeder全限定类名> <搜索引擎> <启发函数列表>");
        System.err.println("搜索引擎取值：" + ENGINES);
        System.err.println("启发函数取值：" + Arrays.toString(HeuristicType.values()));
        System.err.println("例1：resources/pathfinding.txt stud.runner.WalkerFeeder astar PF_GRID,PF_EUCLID");
        System.err.println("例2：resources/problems-stage3.txt stud.g01.runner.PuzzleFeeder nida DISJOINT_PATTERN");
    }

    /**
     * 解析逗号分隔的启发函数列表
     * @param spec 逗号分隔的启发函数名，大小写不敏感
     * @return 启发函数类型列表，无法识别时返回 null
     */
    private static List<HeuristicType> parseHeuristics(String spec) {
        List<HeuristicType> heuristics = new ArrayList<>();
        for (String token : spec.split(",")) {
            String name = token.trim().toUpperCase();
            if (name.isEmpty()) {
                continue;
            }
            try {
                heuristics.add(HeuristicType.valueOf(name));
            } catch (IllegalArgumentException e) {
                System.err.println(
                        "启发函数无法识别：" + token.trim() + "，合法取值：" + Arrays.toString(HeuristicType.values()));
                return null;
            }
        }
        if (heuristics.isEmpty()) {
            System.err.println("启发函数列表为空");
            return null;
        }
        return heuristics;
    }

    /**
     * 使用给定的searcher，求解问题集合中的所有问题，同时使用解检测器对求得的解进行检测
     * @param problems     问题集合
     * @param searcher     searcher
     * @param engine 使用的搜索引擎名称
     * @param heuristicType 使用哪种启发函数？
     */
    private static void solveProblems(
            ArrayList<Problem> problems,
            AbstractSearcher searcher,
            String engine,
            HeuristicType heuristicType) {
        for (Problem problem : problems) {
            // 使用AStar引擎求解问题
            StopwatchCPU timer1 = new StopwatchCPU();
            Deque<Node> path = searcher.search(problem);
            double time1 = timer1.elapsedTime();

            if (path == null) {
                System.out.println("搜索引擎：" + engine + "，启发函数：" + heuristicType + "，No Solution" + "，执行了" + time1 + "s，"+
                        "共生成了" + searcher.nodesGenerated() + "个结点，" +
                        "扩展了" + searcher.nodesExpanded() + "个结点");
                continue;
            }

            // 解路径的可视化
            problem.showSolution(path);

            System.out.println("搜索引擎：" + engine + "，启发函数：" + heuristicType + "，解路径长度：" + path.size() + "，执行了" + time1 + "s，" +
                    "共生成了" + searcher.nodesGenerated() + "个结点，" +
                    "扩展了" + searcher.nodesExpanded() + "个结点");
        }
    }

    /**
     * 从文件读入问题实例的字符串，放入字符串数组里
     * @param scanner
     * @return
     */
    public static ArrayList<String> getProblemLines(Scanner scanner) {
        ArrayList<String> lines = new ArrayList<>();
        while (scanner.hasNext()){
            lines.add(scanner.nextLine());
        }
        return lines;
    }
}
