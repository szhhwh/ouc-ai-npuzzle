package core.runner;

import algs4.util.StopwatchCPU;
import core.problem.Problem;
import core.solver.algorithm.searcher.AbstractSearcher;
import core.solver.queue.Node;

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

    public static void main(String[] args) throws ClassNotFoundException,
            NoSuchMethodException, IllegalAccessException,
            InvocationTargetException, InstantiationException, FileNotFoundException {

        //参数个数检查
        if (args.length < 4) {
            System.err.println("参数不足：需要4个，实际只有" + args.length + "个");
            usage();
            throw new IllegalArgumentException("Invalid search arguments");
        }

        //args[0]：问题文件路径，预先检查文件是否存在
        File problemFile = new File(args[0]);
        if (!problemFile.exists()) {
            System.err.println("问题文件不存在：" + args[0]);
            usage();
            throw new IllegalArgumentException("Invalid search arguments");
        }

        //args[1]：各小组Feeder类的全限定名，反射加载并实例化
        EngineFeeder<?> feeder;
        try {
            feeder = (EngineFeeder<?>) Class.forName(args[1]).getDeclaredConstructor().newInstance();
        } catch (ClassNotFoundException e) {
            System.err.println("Feeder类不存在，请检查全限定类名：" + args[1]);
            usage();
            throw new IllegalArgumentException("Invalid search arguments");
        } catch (ReflectiveOperationException e) {
            System.err.println("Feeder类实例化失败：" + args[1] + "（" + e.getClass().getSimpleName() + "）");
            usage();
            throw new IllegalArgumentException("Invalid search arguments");
        }

        //从文件读入所有输入样例的文本
        ArrayList<String> problemLines;
        try (Scanner scanner = new Scanner(problemFile)) {
            problemLines = getProblemLines(scanner);
        }

        //feeder从输入样例文本解析出问题的所有实例
        ArrayList<Problem> problems = feeder.getProblems(problemLines);

        //args[2]：搜索引擎
        String engine = feeder.validateEngine(args[2]);
        List<String> heuristics = Arrays.stream(args[3].split(",", -1)).map(String::trim).toList();
        // Validate the entire request before starting any search.
        for (String heuristic : heuristics) feeder.getPredictor(heuristic);

        for (String heuristicType : heuristics) {
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
        System.err.println("可用引擎和启发函数由所选 Feeder 模块提供。");
        System.err.println("例1：resources/pathfinding.txt stud.runner.WalkerFeeder astar PF_GRID,PF_EUCLID");
        System.err.println("例2：resources/problems-stage3.txt stud.g01.runner.PuzzleFeeder nida DISJOINT_PATTERN");
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
            String heuristicType) {
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
