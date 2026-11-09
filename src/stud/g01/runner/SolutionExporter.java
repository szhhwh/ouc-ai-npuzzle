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

/** One actual Java search exports both its complete process and its solution. */
public final class SolutionExporter {
    private SolutionExporter() {}

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
            // Publish only after every search and the complete JSON write succeeds; never replace a
            // file.
            Files.move(temporary, output);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

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

    static String algorithmName(String engine) {
        return switch (engine) {
            case "astar" -> "A*";
            case "ida" -> "IDA*";
            case "nida" -> "NIDA*";
            case "dijkstra" -> "Dijkstra";
            default -> throw new IllegalArgumentException("导出格式不支持搜索引擎: " + engine);
        };
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
                if (value < 0 || value >= seen.length || seen[value]) {
                    throw new IllegalArgumentException("棋盘不是合法排列");
                }
                seen[value] = true;
            }
        }
    }

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
            if (!Arrays.equals(states.get(index++), board.cells())) {
                throw new IllegalStateException("搜索路径与过程结点不一致");
            }
            previous = board;
        }
        if (!previous.equals(problem.goalBoard())) throw new IllegalStateException("未到目标");
    }

    private static void validateStep(PuzzleBoard a, PuzzleBoard b) {
        int x = a.blank();
        int y = b.blank();
        int n = a.size();
        if (Math.abs(x / n - y / n) + Math.abs(x % n - y % n) != 1) {
            throw new IllegalStateException("非法移动");
        }
        byte[] before = a.cells();
        byte[] after = b.cells();
        for (int i = 0; i < before.length; i++) {
            if (after[i] != (i == x ? before[y] : i == y ? 0 : before[i])) {
                throw new IllegalStateException("非法棋盘变化");
            }
        }
    }
}
