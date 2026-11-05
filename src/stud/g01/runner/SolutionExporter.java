package stud.g01.runner;

import core.solver.algorithm.heuristic.HeuristicType;
import core.solver.algorithm.heuristic.Predictor;
import core.solver.algorithm.searcher.AbstractSearcher;
import core.solver.algorithm.searcher.BestFirstSearcher;
import core.solver.queue.EvaluationType;
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
import stud.g01.problem.npuzzle.NPuzzleIdaStar;
import stud.g01.problem.npuzzle.NPuzzleProblem;
import stud.g01.problem.npuzzle.PuzzleBoard;
import stud.queue.StackFrontier;
import stud.solver.IdAStar;

/** One actual Java search exports both its complete process and its solution. */
public final class SolutionExporter {
    private SolutionExporter() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 4 && args.length != 5) {
            throw new IllegalArgumentException(
                    "用法: SolutionExporter input output.json astar|ida|nida|dijkstra ZERO,MANHATTAN,MISPLACED,DISJOINT_PATTERN [--allow-pdb]");
        }
        String engine = args[2].toLowerCase(Locale.ROOT);
        if (!List.of("astar", "ida", "nida", "dijkstra").contains(engine)) {
            throw new IllegalArgumentException("未知搜索引擎: " + args[2]);
        }
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
        List<HeuristicType> kinds = parseHeuristics(args[3], allowPdb);
        PuzzleFeeder feeder = new PuzzleFeeder();
        Path temporary =
                Files.createTempFile(
                        Objects.requireNonNull(output.getParent()), ".npuzzle-export-", ".tmp");
        try {
            try (BufferedReader reader = Files.newBufferedReader(input, StandardCharsets.UTF_8);
                    BufferedWriter out =
                            Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                out.write("{\"schemaVersion\":2,\"results\":[");
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
                    for (HeuristicType kind : kinds) {
                        SearchTrace trace = new SearchTrace();
                        AbstractSearcher searcher =
                                recordedSearcher(feeder, engine, kind, problem, trace);
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

    private static List<HeuristicType> parseHeuristics(String names, boolean allowPdb) {
        LinkedHashSet<HeuristicType> kinds = new LinkedHashSet<>();
        for (String name : names.split(",", -1)) {
            HeuristicType kind;
            try {
                kind = HeuristicType.valueOf(name.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("不支持的 N-Puzzle 启发函数: " + name, exception);
            }
            if (kind != HeuristicType.MANHATTAN
                    && kind != HeuristicType.MISPLACED
                    && kind != HeuristicType.DISJOINT_PATTERN
                    && kind != HeuristicType.ZERO) {
                throw new IllegalArgumentException("不支持的 N-Puzzle 启发函数: " + kind);
            }
            if (!kinds.add(kind)) throw new IllegalArgumentException("重复启发函数: " + kind);
        }
        if (kinds.contains(HeuristicType.DISJOINT_PATTERN) && !allowPdb) {
            throw new IllegalArgumentException("PDB 可能占用大量内存和时间，请显式传入 --allow-pdb");
        }
        if (allowPdb && !kinds.contains(HeuristicType.DISJOINT_PATTERN)) {
            throw new IllegalArgumentException("--allow-pdb 需要 DISJOINT_PATTERN 启发函数");
        }
        return List.copyOf(kinds);
    }

    private static AbstractSearcher recordedSearcher(
            PuzzleFeeder feeder,
            String engine,
            HeuristicType kind,
            NPuzzleProblem problem,
            SearchTrace trace) {
        Predictor predictor = feeder.getPredictor(kind);
        if (engine.equals("nida")) {
            return new NPuzzleIdaStar(predictor, trace);
        }
        if (engine.equals("ida")) {
            return new IdAStar(new StackFrontier(), predictor, trace);
        }
        SearchTrace.BestFirstObservation observation =
                trace
                .new BestFirstObservation(
                        predictor,
                        problem.goalBoard(),
                        engine.equals("dijkstra") ? EvaluationType.PATH_COST : EvaluationType.FULL);
        return new BestFirstSearcher(observation, observation);
    }

    private static void writeRecord(
            BufferedWriter out,
            Path input,
            String engine,
            HeuristicType kind,
            NPuzzleProblem problem,
            AbstractSearcher searcher,
            double millis,
            List<byte[]> states,
            SearchTrace trace)
            throws IOException {
        String algorithm =
                switch (engine) {
                    case "ida" -> "IDA*";
                    case "nida" -> "NIDA*";
                    case "dijkstra" -> "Dijkstra";
                    default -> "A*";
                };
        String environment =
                System.getProperty("java.version")
                        + " / "
                        + System.getProperty("os.name")
                        + " / "
                        + System.getProperty("os.arch");
        out.write(
                "{\"name\":\"Untitled Problem\",\"algorithm\":"
                        + quote(algorithm)
                        + ",\"heuristic\":"
                        + quote(kind.name())
                        + ",\"source\":\"Java\",\"sourceFile\":"
                        + quote(Objects.requireNonNull(input.getFileName()).toString())
                        + ",\"sourceRevision\":"
                        + quote(System.getProperty("npuzzle.revision", "unknown"))
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
        trace.writeJson(out);
        out.write('}');
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

    static String quote(String value) {
        StringBuilder result = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            if (c == '\\' || c == '"') result.append('\\').append(c);
            else if (c < 32 || c == '<' || c == '\u2028' || c == '\u2029') {
                result.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
            } else result.append(c);
        }
        return result.append('"').toString();
    }
}
