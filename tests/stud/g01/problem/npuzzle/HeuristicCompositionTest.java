package stud.g01.problem.npuzzle;

import core.problem.State;
import core.solver.algorithm.heuristic.HeuristicType;
import core.solver.algorithm.heuristic.Predictor;
import core.solver.algorithm.searcher.AbstractSearcher;
import core.solver.queue.Node;
import java.util.Arrays;
import java.util.Deque;
import stud.g01.runner.PuzzleFeeder;

/** Dependency-free search/heuristic composition checks; run with scripts/test-pdb-options.sh. */
public final class HeuristicCompositionTest {
    private static final String[] ENGINES = {"astar", "ida", "nida", "dijkstra"};
    private static final PuzzleBoard GOAL =
            new PuzzleBoard(new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 0}, 3);
    private static final PuzzleBoard NEAR =
            new PuzzleBoard(new byte[] {1, 2, 3, 4, 5, 6, 0, 7, 8}, 3);
    private static final PuzzleBoard MEDIUM =
            new PuzzleBoard(new byte[] {1, 6, 2, 8, 7, 3, 5, 4, 0}, 3);

    public static void main(String[] args) {
        PuzzleFeeder feeder = new PuzzleFeeder();
        for (String engine : ENGINES) {
            customZeroDoesNotMeanGoal(feeder, engine);
            selectedZeroFindsGoal(feeder, engine);
            repeatedSearchRestoresState(feeder, engine);
        }
        System.out.println("HeuristicCompositionTest: all checks passed");
    }

    private static void customZeroDoesNotMeanGoal(PuzzleFeeder feeder, String engine) {
        CountingPredictor predictor = new CountingPredictor(true);
        AbstractSearcher searcher = feeder.getSearcher(engine, predictor);
        if (engine.equals("nida")) {
            check(searcher instanceof NPuzzleIdaStar, "nida uses the specialized search with custom h");
        }
        Deque<Node> path = searcher.search(new NPuzzleProblem(NEAR, GOAL));
        verifyPath(path, NEAR, GOAL, predictor, engine + " custom zero");
        check(predictor.calls > 0, engine + " calls the selected custom heuristic");
        check(predictor.zeroCalls > 0, engine + " evaluates a non-goal with zero h");
        check(predictor.goalCalls > 0, engine + " evaluates the goal with nonzero h");
        check(path.getLast().getHeuristic() == 3, engine + " records nonzero goal h");
        check(searcher.search(new NPuzzleProblem(GOAL, GOAL)).isEmpty(),
                engine + " recognizes an initially solved board with nonzero h");
    }

    private static void selectedZeroFindsGoal(PuzzleFeeder feeder, String engine) {
        Predictor zero = feeder.getPredictor(HeuristicType.ZERO);
        AbstractSearcher searcher = feeder.getSearcher(engine, HeuristicType.ZERO);
        verifyPath(searcher.search(new NPuzzleProblem(NEAR, GOAL)),
                NEAR, GOAL, zero, engine + " selected ZERO");
    }

    private static void repeatedSearchRestoresState(PuzzleFeeder feeder, String engine) {
        CountingPredictor predictor = new CountingPredictor(false);
        AbstractSearcher searcher = feeder.getSearcher(engine, predictor);
        byte[] initial = MEDIUM.cells().clone();
        byte[] goal = GOAL.cells().clone();
        NPuzzleProblem problem = new NPuzzleProblem(MEDIUM, GOAL);
        Deque<Node> first = searcher.search(problem);
        verifyPath(first, MEDIUM, GOAL, predictor, engine + " medium");
        check(predictor.calls > first.size(), engine + " evaluates states outside the returned path");
        int generated = searcher.nodesGenerated();
        int expanded = searcher.nodesExpanded();
        if (engine.equals("nida")) {
            check(expanded > first.size(), "custom-h nida explores and backtracks on the medium fixture");
        }

        // Reuse the engine with a different goal before returning to the original problem.
        verifyPath(searcher.search(new NPuzzleProblem(GOAL, NEAR)),
                GOAL, NEAR, predictor, engine + " alternate goal");
        int callsBeforeRepeat = predictor.calls;
        Deque<Node> repeated = searcher.search(problem);
        verifyPath(repeated, MEDIUM, GOAL, predictor, engine + " repeated medium");
        check(predictor.calls > callsBeforeRepeat, engine + " reevaluates h on repeated search");
        check(searcher.nodesGenerated() == generated, engine + " resets generated count");
        check(searcher.nodesExpanded() == expanded, engine + " resets expanded count");
        check(Arrays.equals(initial, MEDIUM.cells()), engine + " preserves the initial board");
        check(Arrays.equals(goal, GOAL.cells()), engine + " preserves the goal board");
        // A later search must not mutate the states retained in an earlier result.
        verifyPath(first, MEDIUM, GOAL, predictor, engine + " retained path");
    }

    private static void verifyPath(
            Deque<Node> path, PuzzleBoard initial, PuzzleBoard goal, Predictor predictor, String label) {
        check(path != null && !path.isEmpty(), label + " returns a nonempty solution");
        NPuzzleProblem problem = new NPuzzleProblem(initial, goal);
        PuzzleBoard previous = initial;
        int cost = 0;
        for (Node node : path) {
            check(node.getAction() != null && problem.applicable(previous, node.getAction()),
                    label + " uses a legal move");
            check(previous.next(node.getAction()).equals(node.getState()),
                    label + " move produces the recorded board");
            check(node.getParent() != null && node.getParent().getState().equals(previous),
                    label + " preserves the parent board");
            check(node.getPathCost() == ++cost, label + " records path cost");
            int expected = predictor instanceof CountingPredictor counting
                    ? counting.value((PuzzleBoard) node.getState(), goal)
                    : predictor.heuristics(node.getState(), goal);
            check(node.getHeuristic() == expected, label + " records the selected heuristic");
            previous = (PuzzleBoard) node.getState();
        }
        check(previous.equals(goal), label + " ends at the actual goal");
    }

    private static final class CountingPredictor implements Predictor {
        private final boolean zeroAwayFromGoal;
        private int calls;
        private int zeroCalls;
        private int goalCalls;

        private CountingPredictor(boolean zeroAwayFromGoal) {
            this.zeroAwayFromGoal = zeroAwayFromGoal;
        }

        @Override
        public int heuristics(State state, State goal) {
            calls++;
            int value = value((PuzzleBoard) state, (PuzzleBoard) goal);
            if (value == 0 && !state.equals(goal)) zeroCalls++;
            if (state.equals(goal)) goalCalls++;
            return value;
        }

        private int value(PuzzleBoard board, PuzzleBoard goal) {
            if (zeroAwayFromGoal) return board.equals(goal) ? 3 : 0;
            int distance = 3;
            for (int i = 0; i < board.cells().length; i++) {
                byte tile = board.cells()[i];
                if (tile == 0) continue;
                for (int j = 0; j < goal.cells().length; j++) {
                    if (goal.cells()[j] == tile) {
                        distance += Math.abs(i / 3 - j / 3) + Math.abs(i % 3 - j % 3);
                        break;
                    }
                }
            }
            return distance;
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
