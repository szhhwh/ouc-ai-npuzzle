package stud.g01.benchmark;

import core.problem.Problem;
import core.solver.algorithm.heuristic.HeuristicType;
import core.solver.algorithm.heuristic.Predictor;
import core.solver.algorithm.searcher.AbstractSearcher;
import core.solver.algorithm.searcher.BestFirstSearcher;
import core.solver.queue.EvaluationType;
import core.solver.queue.Frontier;
import core.solver.queue.Node;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import stud.g01.runner.PuzzleFeeder;
import stud.queue.ListFrontier;
import stud.queue.PqFrontier;

/** 第一阶段三个 8-Puzzle 实例的可重复性能测试。 */
public final class Stage1PerformanceBenchmark {
    private static final int WARMUP_RUNS = 3;
    private static final int MEASURED_RUNS = 7;
    private static final int[] EXPECTED_PATH_LENGTHS = {21, 31, 31};
    private static final HeuristicType[] HEURISTICS = {
        HeuristicType.MISPLACED, HeuristicType.MANHATTAN
    };

    private Stage1PerformanceBenchmark() {}

    public static void main(String[] args) throws IOException {
        boolean compareList = args.length == 1 && "--compare-list".equals(args[0]);
        if (args.length > 1 || (args.length == 1 && !compareList)) {
            throw new IllegalArgumentException("usage: benchmark-stage1.sh [--compare-list]");
        }

        List<Problem> problems = loadStage1Problems();
        System.out.println(
                "frontier,heuristic,case,path_length,median_ms,generated,expanded,runs");
        runSuite("PqFrontier", problems, PqFrontier::new);
        if (compareList) {
            runSuite("ListFrontier", problems.subList(0, 1), ListFrontier::new);
        }
    }

    private static List<Problem> loadStage1Problems() throws IOException {
        Path input =
                Path.of(
                                System.getProperty(
                                        "npuzzle.stage1.file", "resources/problems-stage1.txt"))
                        .toAbsolutePath()
                        .normalize();
        if (!Files.isRegularFile(input)) {
            throw new IOException(
                    "stage-1 input not found: "
                            + input
                            + "; run scripts/benchmark-stage1.sh or set -Dnpuzzle.stage1.file");
        }
        List<Problem> problems =
                new PuzzleFeeder()
                        .getProblems(new java.util.ArrayList<>(Files.readAllLines(input)));
        if (problems.size() != EXPECTED_PATH_LENGTHS.length) {
            throw new IllegalArgumentException(
                    "stage-1 input must contain exactly "
                            + EXPECTED_PATH_LENGTHS.length
                            + " problems, but got "
                            + problems.size());
        }
        return problems;
    }

    private static void runSuite(
            String frontierName,
            List<Problem> problems,
            FrontierFactory frontierFactory) {
        PuzzleFeeder feeder = new PuzzleFeeder();
        for (HeuristicType heuristic : HEURISTICS) {
            Predictor predictor = feeder.getPredictor(heuristic);
            for (int index = 0; index < problems.size(); index++) {
                for (int warmup = 0; warmup < WARMUP_RUNS; warmup++) {
                    measure(problems.get(index), index, predictor, frontierFactory);
                }

                long[] elapsedNanos = new long[MEASURED_RUNS];
                Measurement reference = null;
                for (int run = 0; run < MEASURED_RUNS; run++) {
                    Measurement measurement =
                            measure(problems.get(index), index, predictor, frontierFactory);
                    elapsedNanos[run] = measurement.elapsedNanos();
                    if (reference == null) {
                        reference = measurement;
                    } else if (measurement.pathLength() != reference.pathLength()
                            || measurement.generated() != reference.generated()
                            || measurement.expanded() != reference.expanded()) {
                        throw new AssertionError("search counters changed between measured runs");
                    }
                }
                Arrays.sort(elapsedNanos);
                double medianMillis = elapsedNanos[MEASURED_RUNS / 2] / 1_000_000.0;
                System.out.printf(
                        Locale.ROOT,
                        "%s,%s,%d,%d,%.3f,%d,%d,%d%n",
                        frontierName,
                        heuristic,
                        index + 1,
                        reference.pathLength(),
                        medianMillis,
                        reference.generated(),
                        reference.expanded(),
                        MEASURED_RUNS);
            }
        }
    }

    private static Measurement measure(
            Problem problem,
            int problemIndex,
            Predictor predictor,
            FrontierFactory frontierFactory) {
        Frontier frontier = frontierFactory.create(Node.evaluator(EvaluationType.FULL));
        AbstractSearcher searcher = new BestFirstSearcher(frontier, predictor);
        long started = System.nanoTime();
        Deque<Node> path = searcher.search(problem);
        long elapsedNanos = System.nanoTime() - started;
        int pathLength = path == null ? -1 : path.size();
        if (pathLength != EXPECTED_PATH_LENGTHS[problemIndex]) {
            throw new AssertionError(
                    "case "
                            + (problemIndex + 1)
                            + " expected path length "
                            + EXPECTED_PATH_LENGTHS[problemIndex]
                            + " but got "
                            + pathLength);
        }
        return new Measurement(
                pathLength, elapsedNanos, searcher.nodesGenerated(), searcher.nodesExpanded());
    }

    private record Measurement(int pathLength, long elapsedNanos, int generated, int expanded) {}

    @FunctionalInterface
    private interface FrontierFactory {
        Frontier create(java.util.Comparator<Node> evaluator);
    }
}
