package core.runner;

import algs4.util.StopwatchCPU;
import core.problem.Problem;
import core.problem.ProblemType;
import core.solver.algorithm.searcher.AbstractSearcher;
import core.solver.queue.Node;
import core.solver.algorithm.heuristic.HeuristicType;

import java.io.File;
import java.io.FileNotFoundException;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.Scanner;

import static core.solver.algorithm.heuristic.HeuristicType.*;

/**
 * 对学生的搜索算法进行检测的主程序
 * arg0: 问题输入样例      resources/pathfinding.txt
 * arg1: 问题类型         PATHFINDING
 * arg2: 项目的哪个阶段    1
 * arg3: 各小组的Feeder   stud.runner.WalkerFeeder
 */
public final class SearchTester {
    //同学们可以根据自己的需要，随意修改。
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

        //args[1]：问题类型，大小写不敏感
        ProblemType type;
        try {
            type = ProblemType.valueOf(args[1].toUpperCase());
        } catch (IllegalArgumentException e) {
            System.err.println("问题类型无法识别：" + args[1] + "，合法取值：" + Arrays.toString(ProblemType.values()));
            usage();
            return;
        }

        //args[2]：任务所处阶段
        int step;
        try {
            step = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            System.err.println("阶段应为整数（1或3），当前输入：" + args[2]);
            usage();
            return;
        }

        //args[3]：各小组Feeder类的全限定名，反射加载并实例化
        EngineFeeder feeder;
        try {
            feeder = (EngineFeeder) Class.forName(args[3]).getDeclaredConstructor().newInstance();
        } catch (ClassNotFoundException e) {
            System.err.println("Feeder类不存在，请检查全限定类名：" + args[3]);
            usage();
            return;
        } catch (ReflectiveOperationException e) {
            System.err.println("Feeder类实例化失败：" + args[3] + "（" + e.getClass().getSimpleName() + "）");
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

        //根据问题类型和当前阶段，获取所有启发函数的类型
        //寻路问题分别使用Grid距离和Euclid距离作为启发函数
        ArrayList<HeuristicType> heuristics = getHeuristicTypes(type, step);

        for (HeuristicType heuristicType : heuristics) { 
            //solveProblems方法根据不同启发函数生成不同的searcher
            //从Feeder获取所使用的搜索引擎（AStar，IDAStar等），     
            solveProblems(problems, feeder.getAStar(heuristicType), heuristicType);
            System.out.println();
        }
    }

    /**
     * 打印命令行用法，帮助定位参数错误
     */
    private static void usage() {
        System.err.println("用法：<问题文件> <问题类型> <阶段> <Feeder全限定类名>");
        System.err.println("例1：resources/problems.txt NPUZZLE 1 stud.g01.runner.PuzzleFeeder");
        System.err.println("例2：resources/pathfinding.txt PATHFINDING 1 stud.runner.WalkerFeeder");
    }

    /**
     * 根据问题类型和当前阶段，获取所有启发函数的类型
     * @param type
     * @param step
     * @return
     */
    private static ArrayList<HeuristicType> getHeuristicTypes(ProblemType type, int step) {
        //求解当前问题在当前阶段可用的启发函数类型列表
        ArrayList<HeuristicType> heuristics = new ArrayList<>();
        //根据不同的问题类型，进行不同的测试
        if (type == ProblemType.PATHFINDING) {
            heuristics.add(PF_GRID);
            heuristics.add(PF_EUCLID);
        }
        else {
            //NPuzzle问题的第一阶段，使用不在位将牌和曼哈顿距离
            if (step == 1) {
                heuristics.add(MISPLACED);
                heuristics.add(MANHATTAN);
            }
            //NPuzzle问题的第三阶段，使用Disjoint Pattern
            else if (step == 3){
                heuristics.add(DISJOINT_PATTERN);
            }
        }
        return heuristics;
    }

    /**
     * 使用给定的searcher，求解问题集合中的所有问题，同时使用解检测器对求得的解进行检测
     * @param problems     问题集合
     * @param searcher     searcher
     * @param heuristicType 使用哪种启发函数？
     */
    private static void solveProblems(ArrayList<Problem> problems, AbstractSearcher searcher, HeuristicType heuristicType) {
        for (Problem problem : problems) {
            // 使用AStar引擎求解问题
            StopwatchCPU timer1 = new StopwatchCPU();
            Deque<Node> path = searcher.search(problem);
            double time1 = timer1.elapsedTime();

            if (path == null) {
                System.out.println("No Solution" + "，执行了" + time1 + "s，"+
                        "共生成了" + searcher.nodesGenerated() + "个结点，" +
                        "扩展了" + searcher.nodesExpanded() + "个结点");
                continue;
            }

            // 解路径的可视化
            problem.showSolution(path);

            System.out.println("启发函数：" + heuristicType + "，解路径长度：" + path.size() + "，执行了" + time1 + "s，" +
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