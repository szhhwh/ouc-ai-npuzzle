package stud.g01.visualization;

import core.problem.Problem;
import core.solver.algorithm.heuristic.HeuristicType;
import core.solver.algorithm.searcher.AbstractSearcher;
import core.solver.queue.Node;
import stud.g01.problem.npuzzle.PuzzleBoard;
import stud.g01.problem.npuzzle.PuzzleMove;
import stud.g01.runner.PuzzleFeeder;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.GraphicsEnvironment;
import java.awt.HeadlessException;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * N-Puzzle 解路径可视化的命令行入口（课程项目第四阶段）。
 *
 * <p>用法：</p>
 * <pre>
 *   java stud.g01.visualization.PuzzleVisualizerMain &lt;问题行文件&gt; &lt;行号(1-based)&gt; [astar|ida] [步间延迟ms]
 * </pre>
 * <ul>
 *   <li>问题文件每行一个实例，格式 {@code size size²初始 size²目标}（0 = 空格），
 *       与 {@link PuzzleFeeder#getProblems} 的解析规则一致（空行自动跳过，
 *       行号即按解析出的实例序号计，首个实例为 1）；</li>
 *   <li>第三个参数选择搜索算法：{@code astar}（默认）或 {@code ida}，
 *       启发函数一律默认 MANHATTAN（曼哈顿距离）；</li>
 *   <li>第四个参数为动画的步间延迟（毫秒，仅控制台动画与 GUI 初始间隔用到），
 *       也可用系统属性 {@code -Dviz.delay=<ms>} 指定，默认 500；</li>
 *   <li>退出码：0 正常结束；1 参数 / 文件错误；2 问题实例无解。</li>
 * </ul>
 *
 * <p><b>两种展示模式</b>：先用 {@link GraphicsEnvironment#isHeadless()} 探测图形环境——
 * 有图形环境则启动 {@link PuzzleVisualizer} 的 Swing 动画窗口；无图形环境
 * （或窗口构造时抛出 {@link HeadlessException}）则不创建任何 Swing 顶层组件，
 * 降级为控制台动画：逐步打印每步动作（U/D/L/R）与盘面（复用 PuzzleBoard.draw()），
 * 步间 {@code Thread.sleep(步间延迟)}。两种模式共享同一份「解路径步序列提取」逻辑
 * {@link #extractTrace}——这是本类的关键逻辑，保证 GUI 与控制台展示的必然一致。</p>
 */
public final class PuzzleVisualizerMain {

    /**
     * 默认步间延迟（毫秒）
     */
    private static final int DEFAULT_DELAY_MS = 500;

    private PuzzleVisualizerMain() {
    }

    public static void main(String[] args) {
        //――― 参数解析 ―――
        if (args.length < 2 || args.length > 4) {
            usage();
            System.exit(1);
            return;
        }
        Path problemFile = Path.of(args[0]);
        int lineNo;
        try {
            lineNo = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            System.err.println("行号应为正整数，当前输入：" + args[1]);
            usage();
            System.exit(1);
            return;
        }
        if (lineNo < 1) {
            System.err.println("行号从 1 开始，当前输入：" + lineNo);
            usage();
            System.exit(1);
            return;
        }
        String algorithm = args.length >= 3 ? args[2].toLowerCase() : "astar";
        if (!algorithm.equals("astar") && !algorithm.equals("ida")) {
            System.err.println("算法参数只能为 astar 或 ida，当前输入：" + args[2]);
            usage();
            System.exit(1);
            return;
        }
        int delayMs = resolveDelay(args);

        //――― 解析问题实例 ―――
        ArrayList<String> lines;
        try {
            lines = new ArrayList<>(Files.readAllLines(problemFile));
        } catch (IOException e) {
            System.err.println("无法读取问题文件：" + problemFile + "（" + e + "）");
            System.exit(1);
            return;
        }
        PuzzleFeeder feeder = new PuzzleFeeder();
        ArrayList<Problem> problems = feeder.getProblems(lines);
        if (problems.isEmpty()) {
            System.err.println("问题文件中未解析到任何实例：" + problemFile);
            System.exit(1);
            return;
        }
        if (lineNo > problems.size()) {
            System.err.println("行号超出范围：文件共解析出 " + problems.size() + " 个实例（不含空行）");
            System.exit(1);
            return;
        }
        Problem problem = problems.get(lineNo - 1);

        //――― 求解并计时 ―――
        AbstractSearcher searcher = algorithm.equals("ida")
                ? feeder.getIdaStar(HeuristicType.MANHATTAN)
                : feeder.getAStar(HeuristicType.MANHATTAN);
        String algorithmName = algorithm.equals("ida") ? "IDA*" : "A*";
        System.out.printf("问题文件：%s（实例 %d/%d），算法：%s，启发函数：MANHATTAN%n",
                problemFile, lineNo, problems.size(), algorithmName);

        long begin = System.nanoTime();
        Deque<Node> path = searcher.search(problem);
        double elapsedMs = (System.nanoTime() - begin) / 1e6;

        //――― 无解处理：打印明确提示并以退出码 2 结束 ―――
        if (path == null) {
            System.err.printf("该问题实例无解（可解性判定 solvable()=%s）。耗时 %.1f ms，生成结点 %d 个，扩展结点 %d 个。退出码 2。%n",
                    problem.solvable(), elapsedMs, searcher.nodesGenerated(), searcher.nodesExpanded());
            System.exit(2);
            return;
        }
        System.out.printf("求解完成：耗时 %.1f ms，生成结点 %d 个，扩展结点 %d 个，解路径长度 %d 步%n",
                elapsedMs, searcher.nodesGenerated(), searcher.nodesExpanded(), path.size());

        //――― 提取步序列（GUI 与控制台两种模式共用的关键逻辑）―――
        SolutionTrace trace = extractTrace(problem, path);

        //――― 展示：优先 GUI，headless 时降级为控制台动画 ―――
        if (!GraphicsEnvironment.isHeadless()) {
            try {
                runGui(trace, delayMs, algorithmName);
                return;   //GUI 已启动，事件分发线程接管，主线程到此结束
            } catch (HeadlessException e) {
                System.out.println("图形环境不可用（HeadlessException），降级为控制台动画。");
            } catch (InvocationTargetException e) {
                if (e.getCause() instanceof HeadlessException) {
                    System.out.println("图形环境不可用（HeadlessException），降级为控制台动画。");
                } else {
                    throw new IllegalStateException("启动图形界面失败", e.getCause());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        try {
            runConsoleAnimation(trace, problem, delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    //―――――――――――――――――――――――― 关键逻辑：解路径步序列的提取 ――――――――――――――――――――――――

    /**
     * 解路径步序列三元组：
     * <ul>
     *   <li>{@code initial}：初始盘面；</li>
     *   <li>{@code moves}：动作序列（按移动先后次序，长度 = 解路径步数）；</li>
     *   <li>{@code steps}：盘面序列，含初始盘面，长度 = moves.size() + 1，
     *       即 steps[i] 表示执行完前 i 步后的盘面。</li>
     * </ul>
     * 三者均为不可变列表。
     */
    public record SolutionTrace(PuzzleBoard initial, List<PuzzleMove> moves, List<PuzzleBoard> steps) {
    }

    /**
     * 从搜索器返回的解路径 {@link Deque}<{@link Node}> 中提取
     * （初始盘面, 动作序列, 盘面序列）三元组。
     * 这是 GUI 动画模式与控制台降级模式<b>共用</b>的关键逻辑：
     * 无论哪种展示方式，看到的都是同一份提取结果。
     *
     * <p>提取规则（依赖框架 {@code AbstractSearcher.generatePath} 的实现细节）：</p>
     * <ol>
     *   <li><b>迭代顺序即移动次序</b>。generatePath 从目标结点沿 parent 指针回溯到根，
     *       逐个 {@code push} 进栈，因此对 Deque 做 for-each 迭代（即出栈顺序）得到的
     *       恰好是「首步 → 末步」的动作序列，无需再反转。</li>
     *   <li><b>解路径不含根结点</b>。路径上每个结点的 action 是「父盘面 → 本盘面」的
     *       移动，故 moves[i] 与 steps[i+1] 一一对应；初始盘面不在路径里，
     *       需另行取得——这里用 {@code problem.root()}（框架公开方法）拿到
     *       初始状态对应的根结点，避免依赖 Problem 的受保护字段 initialState。</li>
     *   <li><b>不变量</b>：steps.size() == moves.size() + 1，且
     *       steps[i] == steps[i-1].next(moves[i-1])（重放每步合法移动恰得下一盘面）。
     *       该不变量由上述提取规则本身保证——提取自真实解路径的序列天然满足。</li>
     * </ol>
     *
     * <p>空路径（初始即目标的已解实例）：moves 与 steps 分别为空列表与仅含初始盘面的
     * 单元素列表，两种展示模式据此退化为静态展示。</p>
     *
     * @param problem 已求解的问题实例（用其 root() 取初始盘面）
     * @param path    搜索器返回的解路径（可为空 Deque，表示初始即目标；不应为 null）
     * @return 解路径三元组（不可变）
     */
    public static SolutionTrace extractTrace(Problem problem, Deque<Node> path) {
        //根结点 = 初始状态（parent 为 null、pathCost 为 0）
        PuzzleBoard initial = (PuzzleBoard) problem.root().getState();

        List<PuzzleMove> moves = new ArrayList<>(path.size());
        List<PuzzleBoard> steps = new ArrayList<>(path.size() + 1);
        steps.add(initial);

        //按「首步 → 末步」的顺序迭代解路径：动作进 moves，盘面进 steps
        for (Node node : path) {
            moves.add((PuzzleMove) node.getAction());
            steps.add((PuzzleBoard) node.getState());
        }
        return new SolutionTrace(initial, List.copyOf(moves), List.copyOf(steps));
    }

    //―――――――――――――――――――――――― GUI 模式 ――――――――――――――――――――――――

    /**
     * 启动 Swing 动画窗口：在事件分发线程上创建 JFrame、装入动画面板并自动播放。
     * JFrame 等顶层窗口只在本方法中创建，headless 环境下绝不会走到这里；
     * 若显示环境在构造窗口时才暴露为不可用，抛出的 HeadlessException 由调用方捕获并降级。
     *
     * <p>支持系统属性 {@code -Dviz.autoclose=<ms>}：窗口打开指定毫秒后自动退出，
     * 供自动化 / 虚拟显示下的冒烟测试使用，正常使用无需设置。</p>
     *
     * @param trace        解路径三元组
     * @param delayMs      初始步间隔（毫秒）
     * @param algorithmName 算法名（用于窗口标题）
     */
    private static void runGui(SolutionTrace trace, int delayMs, String algorithmName)
            throws HeadlessException, InvocationTargetException, InterruptedException {
        SwingUtilities.invokeAndWait(() -> {
            JFrame frame = new JFrame("N-Puzzle 解路径可视化（" + algorithmName + " / 曼哈顿，共 "
                    + trace.moves().size() + " 步）");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setContentPane(new PuzzleVisualizer(
                    trace.initial(), trace.moves(), trace.steps(), delayMs, true));
            frame.pack();
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);

            Long autocloseMs = Long.getLong("viz.autoclose");
            if (autocloseMs != null) {
                new Timer((int) Math.min(autocloseMs, Integer.MAX_VALUE), e -> System.exit(0)).start();
            }
        });
    }

    //―――――――――――――――――――――――― 控制台降级模式 ――――――――――――――――――――――――

    /**
     * 控制台动画（headless 降级）：先打印初始盘面，再按移动次序逐步打印
     * 「第 i/总 步，空格移动方向」与移动后的盘面（复用 {@link PuzzleBoard#draw()}），
     * 步间休眠 delayMs 毫秒；最后打印总步数与终盘面是否等于目标的判定。
     * 空路径（初始即目标）只打印初始盘面。
     *
     * @param trace   解路径三元组
     * @param problem 问题实例（用其 goal() 判定终盘面）
     * @param delayMs 步间休眠（毫秒），0 表示不休眠
     */
    private static void runConsoleAnimation(SolutionTrace trace, Problem problem, int delayMs)
            throws InterruptedException {
        int total = trace.moves().size();
        System.out.println("== 无图形环境（headless），降级为控制台动画，步间延迟 " + delayMs + " ms ==");
        System.out.println("初始盘面（步 0/" + total + "）：");
        trace.initial().draw();

        if (total == 0) {
            //已解实例：路径为空，只静态展示初始盘面
            System.out.println("初始状态即目标状态，无需移动（总步数 0；等于目标盘面："
                    + problem.goal(trace.initial()) + "）");
            return;
        }

        for (int i = 1; i <= total; i++) {
            if (delayMs > 0) {
                Thread.sleep(delayMs);
            }
            System.out.println("第 " + i + "/" + total + " 步，空格移动：" + trace.moves().get(i - 1));
            trace.steps().get(i).draw();
        }

        PuzzleBoard finalBoard = trace.steps().get(total);
        System.out.println("总步数：" + total + "；终盘面与目标一致：" + problem.goal(finalBoard));
    }

    //―――――――――――――――――――――――― 工具方法 ――――――――――――――――――――――――

    /**
     * 解析步间延迟：优先第 4 个命令行参数，其次系统属性 viz.delay，默认 500ms；负数视为非法
     */
    private static int resolveDelay(String[] args) {
        if (args.length >= 4) {
            try {
                int value = Integer.parseInt(args[3]);
                if (value >= 0) {
                    return value;
                }
                System.err.println("步间延迟应为非负整数，忽略当前输入：" + args[3]);
            } catch (NumberFormatException e) {
                System.err.println("步间延迟应为非负整数，忽略当前输入：" + args[3]);
            }
        }
        Integer property = Integer.getInteger("viz.delay");
        if (property != null && property >= 0) {
            return property;
        }
        return DEFAULT_DELAY_MS;
    }

    /**
     * 打印命令行用法
     */
    private static void usage() {
        System.err.println("用法：java stud.g01.visualization.PuzzleVisualizerMain <问题行文件> <行号(1-based)> [astar|ida] [步间延迟ms]");
        System.err.println("      启发函数默认 MANHATTAN；步间延迟亦可用 -Dviz.delay=<ms> 指定（默认 500）");
        System.err.println("      延迟说明：控制台降级模式允许 0（不休眠）；GUI 模式会被夹取到 [100, 2000] ms");
        System.err.println("      退出码：0 正常；1 参数/文件错误；2 问题实例无解");
    }
}
