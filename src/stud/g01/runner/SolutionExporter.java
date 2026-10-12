package stud.g01.runner;

import core.solver.algorithm.heuristic.HeuristicType;
import core.solver.algorithm.searcher.AbstractSearcher;
import core.solver.queue.Node;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import stud.g01.problem.npuzzle.NPuzzleProblem;
import stud.g01.problem.npuzzle.PuzzleBoard;

/** 导出真实 Java 搜索结果。一次预热、重复测量取中位数；不修改教师框架。 */
public final class SolutionExporter {
    private SolutionExporter() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 5 && args.length != 6) {
            throw new IllegalArgumentException(
                    "用法: SolutionExporter input output astar|ida|nida|dijkstra MANHATTAN,MISPLACED repeats [--allow-pdb]");
        }
        String engine = args[2].toLowerCase(Locale.ROOT);
        if (!List.of("astar", "ida", "nida", "dijkstra").contains(engine)) {
            throw new IllegalArgumentException("未知搜索引擎: " + args[2]);
        }
        boolean allowPdb = args.length == 6 && args[5].equals("--allow-pdb");
        if (args.length == 6 && !allowPdb) throw new IllegalArgumentException("未知选项: " + args[5]);
        int repeats = Integer.parseInt(args[4]);
        if (repeats < 1 || repeats > 20) throw new IllegalArgumentException("重复次数须为 1–20");
        Path output = Path.of(args[1]);
        if (Files.exists(output)) throw new IllegalArgumentException("输出已存在，请选择新文件，避免覆盖实验");
        List<HeuristicType> kinds = new ArrayList<>();
        for (String name : args[3].split(",")) {
            HeuristicType kind = HeuristicType.valueOf(name.trim().toUpperCase(Locale.ROOT));
            if (kind != HeuristicType.MANHATTAN
                    && kind != HeuristicType.MISPLACED
                    && kind != HeuristicType.DISJOINT_PATTERN) {
                throw new IllegalArgumentException("不支持的 N-Puzzle 启发函数: " + kind);
            }
            if (kind == HeuristicType.DISJOINT_PATTERN && !engine.equals("dijkstra") && !allowPdb)
                throw new IllegalArgumentException("PDB 可能占用大量内存和时间，请显式传入 --allow-pdb");
            kinds.add(kind);
        }
        if (kinds.isEmpty()) throw new IllegalArgumentException("启发函数列表为空");
        if (engine.equals("dijkstra")) kinds = List.of(HeuristicType.MANHATTAN);
        List<String> records = new ArrayList<>();
        PuzzleFeeder feeder = new PuzzleFeeder();
        int index = 0;
        for (String line : Files.readAllLines(Path.of(args[0]), StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            validateInput(line);
            NPuzzleProblem problem =
                    (NPuzzleProblem) feeder.getProblems(new ArrayList<>(List.of(line))).getFirst();
            if (!problem.solvable()) throw new IllegalArgumentException("实例不可解: " + (index + 1));
            index++;
            for (HeuristicType kind : kinds) {
                List<Double> times = new ArrayList<>();
                Deque<Node> path = null;
                int generated = -1;
                int expanded = -1;
                int length = -1;
                for (int run = 0; run <= repeats; run++) {
                    AbstractSearcher searcher = feeder.getSearcher(engine, kind);
                    long start = System.nanoTime();
                    Deque<Node> candidate = searcher.search(problem);
                    double millis = (System.nanoTime() - start) / 1_000_000.0;
                    if (candidate == null) throw new IllegalStateException("未求得解: " + index);
                    if (run > 0) {
                        if (length >= 0
                                && (length != candidate.size()
                                        || generated != searcher.nodesGenerated()
                                        || expanded != searcher.nodesExpanded())) {
                            throw new IllegalStateException("重复测量路径长度或节点计数不一致");
                        }
                        times.add(millis);
                        path = candidate;
                        length = path.size();
                        generated = searcher.nodesGenerated();
                        expanded = searcher.nodesExpanded();
                    }
                }
                if (path == null) throw new IllegalStateException("缺少测量结果");
                List<String> states = new ArrayList<>();
                PuzzleBoard previous = (PuzzleBoard) problem.root().getState();
                states.add(Arrays.toString(previous.cells()));
                for (Node node : path) {
                    PuzzleBoard board = (PuzzleBoard) node.getState();
                    validateStep(previous, board);
                    states.add(Arrays.toString(board.cells()));
                    previous = board;
                }
                if (!previous.equals(problem.goalBoard())) throw new IllegalStateException("未到目标");
                List<Double> sorted = new ArrayList<>(times);
                sorted.sort(Double::compare);
                double median = (sorted.get((repeats - 1) / 2) + sorted.get(repeats / 2)) / 2;
                String environment =
                        System.getProperty("java.version")
                                + " / "
                                + System.getProperty("os.name")
                                + " / "
                                + System.getProperty("os.arch");
                records.add(
                        "{\"name\":"
                                + quote("课程实例 " + index)
                                + ",\"algorithm\":"
                                + quote(
                                        switch (engine) {
                                            case "ida" -> "IDA*";
                                            case "nida" -> "NIDA*";
                                            case "dijkstra" -> "Dijkstra";
                                            default -> "A*";
                                        })
                                + ",\"heuristic\":"
                                + quote(engine.equals("dijkstra") ? "ZERO" : kind.name())
                                + ",\"source\":\"Java\",\"sourceFile\":"
                                + quote(
                                        Objects.requireNonNull(Path.of(args[0]).getFileName())
                                                .toString())
                                + ",\"sourceRevision\":"
                                + quote(System.getProperty("npuzzle.revision", "unknown"))
                                + ",\"environment\":"
                                + quote(environment)
                                + ",\"exportedAt\":"
                                + quote(Instant.now().toString())
                                + ",\"timing\":\"wall-median\",\"warmupRuns\":1,\"measuredRuns\":"
                                + repeats
                                + ",\"samplesMs\":"
                                + times
                                + ",\"timeMs\":"
                                + String.format(Locale.ROOT, "%.6f", median)
                                + ",\"generated\":"
                                + generated
                                + ",\"expanded\":"
                                + expanded
                                + ",\"goal\":"
                                + Arrays.toString(problem.goalBoard().cells())
                                + ",\"states\":["
                                + String.join(",", states)
                                + "]}");
                System.out.println("已导出实例 " + index + " / " + kind + " / " + length + " 步");
            }
        }
        if (records.isEmpty()) throw new IllegalArgumentException("输入没有实例");
        String json = "{\"schemaVersion\":1,\"results\":[" + String.join(",\n", records) + "]}";
        String content =
                output.toString().endsWith(".js")
                        ? "window.NPuzzleExamples = " + json + ";\n"
                        : json + "\n";
        Files.writeString(output, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }

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
                if (value < 0 || value >= seen.length || seen[value])
                    throw new IllegalArgumentException("棋盘不是合法排列");
                seen[value] = true;
            }
        }
    }

    private static void validateStep(PuzzleBoard a, PuzzleBoard b) {
        int x = a.blank();
        int y = b.blank();
        int n = a.size();
        if (Math.abs(x / n - y / n) + Math.abs(x % n - y % n) != 1)
            throw new IllegalStateException("非法移动");
        byte[] before = a.cells();
        byte[] after = b.cells();
        for (int i = 0; i < before.length; i++) {
            if (after[i] != (i == x ? before[y] : i == y ? 0 : before[i]))
                throw new IllegalStateException("非法棋盘变化");
        }
    }

    private static String quote(String value) {
        StringBuilder result = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            if (c == '\\' || c == '"') result.append('\\').append(c);
            else if (c < 32 || c == '<' || c == '\u2028' || c == '\u2029')
                result.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
            else result.append(c);
        }
        return result.append('"').toString();
    }
}
