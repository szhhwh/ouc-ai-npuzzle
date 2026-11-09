package stud.g01.runner;

import static stud.g01.runner.SearchTraceJsonWriter.quote;

import core.solver.algorithm.heuristic.Predictor;
import core.solver.algorithm.searcher.AbstractSearcher;
import core.solver.queue.Node;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import stud.g01.problem.npuzzle.NPuzzleProblem;
import stud.g01.problem.npuzzle.PuzzleBoard;
import stud.g01.problem.npuzzle.PuzzleHeuristicType;

/**
 * N-Puzzle 搜索过程与解路径导出工具。 运行指定的搜索算法求解输入的各个题目实例，收集完整的搜索轨迹与解路径， 并将包含结点生成、访问展开、修剪原因与性能计时的完整结构导出为 JSON
 * 文件。
 */
public final class SolutionExporter {

    /** 私有构造函数，防止工具类被实例化。 */
    private SolutionExporter() {}

    /**
     * 导出工具的命令行主程序入口。 解析命令行参数，验证输入文件与输出路径，逐题运行配置的算法与启发函数组合， 并在每道题目求解完成后严格验证解路径的有效性，最终生成结构化 JSON
     * 结果文件。
     *
     * @param args 命令行参数，依次包含输入路径、输出路径、算法标识、启发函数列表以及可选的 PDB 启用选项
     * @throws IOException 当输入输出文件读取或写入失败时抛出
     */
    public static void main(String[] args) throws IOException {
        if (args.length != 4 && args.length != 5) {
            throw new IllegalArgumentException(
                    "用法: SolutionExporter input output.json astar|ida|nida|dijkstra ZERO,MANHATTAN,MISPLACED,DISJOINT_PATTERN [--allow-pdb]");
        }
        PuzzleFeeder feeder = new PuzzleFeeder();
        String engine = feeder.validateEngine(args[2]);
        algorithmName(engine);
        boolean allowPdb = args.length == 5 && args[4].equals("--allow-pdb");
        if (args.length == 5 && !allowPdb) {
            throw new IllegalArgumentException("未知选项（不再支持预热或重复测量）: " + args[4]);
        }
        Path input = Path.of(args[0]);
        Path output = Path.of(args[1]).toAbsolutePath();
        if (!output.toString().toLowerCase(Locale.ROOT).endsWith(".json")) {
            throw new IllegalArgumentException("输出必须为 .json 文件");
        }
        if (Files.exists(output)) {
            throw new IllegalArgumentException("输出已存在，请选择新文件，避免覆盖实验");
        }
        List<PuzzleHeuristicType> kinds = parseHeuristics(args[3], allowPdb);
        // 先写临时文件，完整成功后再原子移动到目标路径
        Path temporary =
                Files.createTempFile(
                        Objects.requireNonNull(output.getParent()), ".npuzzle-export-", ".tmp");
        try {
            try (BufferedReader reader = Files.newBufferedReader(input, StandardCharsets.UTF_8);
                    BufferedWriter out =
                            Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                out.write("{\"schemaVersion\":3,\"results\":[");
                int index = 0;
                boolean first = true;
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) continue;
                    validateInput(line);
                    NPuzzleProblem problem =
                            (NPuzzleProblem)
                                    feeder.getProblems(new ArrayList<>(List.of(line))).getFirst();
                    index++;
                    if (!problem.solvable()) throw new IllegalArgumentException("实例不可解: " + index);
                    for (PuzzleHeuristicType kind : kinds) {
                        SearchTrace trace = new SearchTrace();
                        Predictor predictor = feeder.getPredictor(kind);
                        AbstractSearcher searcher = feeder.getSearcher(engine, predictor, trace);
                        long start = System.nanoTime();
                        Deque<Node> path = searcher.search(problem);
                        double millis = (System.nanoTime() - start) / 1_000_000.0;
                        if (path == null) throw new IllegalStateException("未求得解: " + index);
                        List<byte[]> states = trace.solution();
                        // 解路径与轨迹必须逐步一致，且最终到达目标
                        validateSolution(problem, path, states);
                        trace.validateCounts(searcher.nodesGenerated(), searcher.nodesExpanded());
                        if (!first) out.write(",\n");
                        first = false;
                        writeRecord(
                                out, input, engine, kind, problem, searcher, millis, states, trace);
                        System.out.println(
                                "已导出实例 " + index + " / " + kind + " / " + path.size() + " 步");
                    }
                }
                if (index == 0) throw new IllegalArgumentException("输入没有实例");
                out.write("]}\n");
            }
            Files.move(temporary, output);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /**
     * 解析命令行中由逗号分隔的启发函数名称列表，并检查模式数据库选项。
     *
     * @param names 逗号分隔的启发函数名称字符串
     * @param allowPdb 是否允许使用模式数据库
     * @return 解析得到的启发函数类型列表
     * @throws IllegalArgumentException 当启发函数不支持、存在重复或模式数据库标志不匹配时抛出
     */
    private static List<PuzzleHeuristicType> parseHeuristics(String names, boolean allowPdb) {
        LinkedHashSet<PuzzleHeuristicType> kinds = new LinkedHashSet<>();
        for (String name : names.split(",", -1)) {
            PuzzleHeuristicType kind;
            try {
                kind = PuzzleHeuristicType.valueOf(name.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("不支持的 N-Puzzle 启发函数: " + name, exception);
            }
            if (!kinds.add(kind)) throw new IllegalArgumentException("重复启发函数: " + kind);
        }
        if (kinds.contains(PuzzleHeuristicType.DISJOINT_PATTERN) && !allowPdb) {
            throw new IllegalArgumentException("PDB 可能占用大量内存和时间，请显式传入 --allow-pdb");
        }
        if (allowPdb && !kinds.contains(PuzzleHeuristicType.DISJOINT_PATTERN)) {
            throw new IllegalArgumentException("--allow-pdb 需要 DISJOINT_PATTERN 启发函数");
        }
        return List.copyOf(kinds);
    }

    /**
     * 将单次搜索实验的元数据、耗时、状态序列与搜索轨迹写入 JSON 流中。
     *
     * @param out 输出流写入器
     * @param input 输入文件路径
     * @param engine 搜索算法引擎标识
     * @param kind 启发函数枚举类型
     * @param problem 求解的问题实例
     * @param searcher 算法搜索器
     * @param millis 搜索耗时毫秒数
     * @param states 解路径包含的所有棋盘状态列表
     * @param trace 完整搜索轨迹对象
     * @throws IOException 当写入发生异常时抛出
     */
    private static void writeRecord(
            BufferedWriter out,
            Path input,
            String engine,
            PuzzleHeuristicType kind,
            NPuzzleProblem problem,
            AbstractSearcher searcher,
            double millis,
            List<byte[]> states,
            SearchTrace trace)
            throws IOException {
        String algorithm = algorithmName(engine);
        String environment =
                System.getProperty("java.version")
                        + " / "
                        + System.getProperty("os.name")
                        + " / "
                        + System.getProperty("os.arch");
        String revision = System.getProperty("npuzzle.revision");
        out.write(
                "{\"algorithm\":"
                        + quote(algorithm)
                        + ",\"heuristic\":"
                        + quote(kind.name())
                        + ",\"source\":\"Java\",\"sourceFile\":"
                        + quote(Objects.requireNonNull(input.getFileName()).toString())
                        + (revision == null || revision.isBlank()
                                ? ""
                                : ",\"sourceRevision\":" + quote(revision))
                        + ",\"environment\":"
                        + quote(environment)
                        + ",\"exportedAt\":"
                        + quote(Instant.now().toString())
                        + ",\"timing\":\"wall-single-search-with-recording\",\"timeMs\":"
                        + String.format(Locale.ROOT, "%.6f", millis)
                        + ",\"generated\":"
                        + searcher.nodesGenerated()
                        + ",\"expanded\":"
                        + searcher.nodesExpanded()
                        + ",\"goal\":"
                        + Arrays.toString(problem.goalBoard().cells())
                        + ",\"states\":[");
        for (int i = 0; i < states.size(); i++) {
            if (i > 0) out.write(',');
            out.write(Arrays.toString(states.get(i)));
        }
        out.write("],\"trace\":");
        SearchTraceJsonWriter.write(out, trace);
        out.write('}');
    }

    /**
     * 将算法引擎代码转换为标准的展示名称。
     *
     * @param engine 算法引擎简称
     * @return 对应的标准算法展示名称
     * @throws IllegalArgumentException 当传入不支持的算法引擎时抛出
     */
    static String algorithmName(String engine) {
        return switch (engine) {
            case "astar" -> "A*";
            case "ida" -> "IDA*";
            case "nida" -> "NIDA*";
            case "dijkstra" -> "Dijkstra";
            default -> throw new IllegalArgumentException("导出格式不支持搜索引擎: " + engine);
        };
    }

    /**
     * 校验单行输入文本的数据格式与排列合法性。
     *
     * @param line 包含一行题目数据的文本行
     * @throws IllegalArgumentException 当棋盘边长不为 3 或 4、标记数量不符或棋盘不是合法排列时抛出
     */
    private static void validateInput(String line) {
        String[] tokens = line.trim().split("\\s+");
        int n = Integer.parseInt(tokens[0]);
        if ((n != 3 && n != 4) || tokens.length != 1 + 2 * n * n) {
            throw new IllegalArgumentException("输入须为 n + 初始棋盘 + 目标棋盘，n 为 3 或 4");
        }
        for (int offset : new int[] {1, 1 + n * n}) {
            boolean[] seen = new boolean[n * n];
            for (int i = 0; i < n * n; i++) {
                int value = Integer.parseInt(tokens[offset + i]);
                if (value < 0 || value >= seen.length || seen[value]) {
                    throw new IllegalArgumentException("棋盘不是合法排列");
                }
                seen[value] = true;
            }
        }
    }

    /**
     * 校验搜索算法返回的解路径是否与轨迹记录一致，且每一步转移均合法并最终到达目标。
     *
     * @param problem 待求解的问题实例
     * @param path 搜索算法返回的路径结点队列
     * @param states 轨迹记录中提取的状态字节数组列表
     * @throws IllegalStateException 当路径与轨迹不一致、转移非法或未到达目标时抛出
     */
    private static void validateSolution(
            NPuzzleProblem problem, Deque<Node> path, List<byte[]> states) {
        PuzzleBoard previous = (PuzzleBoard) problem.root().getState();
        if (states.size() != path.size() + 1
                || !Arrays.equals(states.getFirst(), previous.cells())) {
            throw new IllegalStateException("搜索路径与过程根结点不一致");
        }
        int index = 1;
        for (Node node : path) {
            PuzzleBoard board = (PuzzleBoard) node.getState();
            validateStep(previous, board);
            // 路径状态必须与轨迹记录一致
            if (!Arrays.equals(states.get(index++), board.cells())) {
                throw new IllegalStateException("搜索路径与过程结点不一致");
            }
            previous = board;
        }
        if (!previous.equals(problem.goalBoard())) throw new IllegalStateException("未到目标");
    }

    /**
     * 校验两个相邻状态之间的单步移动合法性。 空格必须移动且仅在曼哈顿距离为 1 的相邻格子之间互换，其余格子保持原样不变。
     *
     * @param a 移动前的棋盘状态
     * @param b 移动后的棋盘状态
     * @throws IllegalStateException 当移动步幅不是相邻格子或数字变换非法时抛出
     */
    private static void validateStep(PuzzleBoard a, PuzzleBoard b) {
        int x = a.blank();
        int y = b.blank();
        int n = a.size();
        // 空格必须恰好移动一格
        if (Math.abs(x / n - y / n) + Math.abs(x % n - y % n) != 1) {
            throw new IllegalStateException("非法移动");
        }
        byte[] before = a.cells();
        byte[] after = b.cells();
        // 除空格与目标格子互换外，其余格子必须保持不变
        for (int i = 0; i < before.length; i++) {
            if (after[i] != (i == x ? before[y] : i == y ? 0 : before[i])) {
                throw new IllegalStateException("非法棋盘变化");
            }
        }
    }
}
