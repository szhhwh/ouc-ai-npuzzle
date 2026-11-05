package stud.g01.problem.npuzzle;

import core.solver.algorithm.heuristic.HeuristicType;
import core.solver.algorithm.heuristic.Predictor;
import core.solver.queue.Node;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.Random;

/** Dependency-free regression checks; run with scripts/test-pdb-options.sh. */
public final class PdbOptionsTest {
    private static final String INCREMENTAL = "npuzzle.pdb.incremental";
    private static final String SYMMETRY = "npuzzle.pdb.symmetry";
    private static final PuzzleBoard GOAL =
            new PuzzleBoard(new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 0}, 3);
    private static final PuzzleBoard FIXTURE =
            new PuzzleBoard(new byte[] {1, 6, 2, 8, 7, 3, 5, 4, 0}, 3);

    public static void main(String[] args) {
        String previousIncremental = System.getProperty(INCREMENTAL);
        String previousSymmetry = System.getProperty(SYMMETRY);
        try {
            symmetryCanBeDisabled();
            factoryCapturesEachConfiguration();
            searcherCapturesConfiguration();
            invalidSwitchesAreRejected();
            productModesAgree();
            searchModesFindShortestPaths();
            unsupportedSymmetryStaysDisabled();
            if (args.length == 1 && args[0].equals("--component")) componentModesAgree();
            System.out.println("PdbOptionsTest: all checks passed");
        } finally {
            restore(INCREMENTAL, previousIncremental);
            restore(SYMMETRY, previousSymmetry);
        }
    }

    private static void factoryCapturesEachConfiguration() {
        configure(true, true);
        Predictor enhanced = PuzzleHeuristics.predictor(HeuristicType.DISJOINT_PATTERN);
        configure(false, false);
        Predictor direct = PuzzleHeuristics.predictor(HeuristicType.DISJOINT_PATTERN);
        equal(14, enhanced.heuristics(FIXTURE, GOAL), "existing predictor retains configuration");
        equal(12, direct.heuristics(FIXTURE, GOAL), "factory reads new configuration");
        configure(true, true);
        equal(
                12,
                DisjointPdbHeuristic.predictor(new PdbOptions(false, false)).heuristics(FIXTURE, GOAL),
                "explicit predictor configuration");
    }

    private static void invalidSwitchesAreRejected() {
        for (String key : new String[] {INCREMENTAL, SYMMETRY}) {
            configure(true, true);
            System.setProperty(key, "flase");
            equal(
                    12,
                    PuzzleHeuristics.predictor(HeuristicType.MANHATTAN).heuristics(FIXTURE, GOAL),
                    "PDB switches do not configure Manhattan");
            NPuzzleIdaStar ordinary =
                    new NPuzzleIdaStar(PuzzleHeuristics.predictor(HeuristicType.MANHATTAN));
            Deque<Node> path = ordinary.search(new NPuzzleProblem(FIXTURE, GOAL));
            check(path != null && path.getLast().getState().equals(GOAL),
                    "ordinary search does not read PDB switches");
            try {
                DisjointPdbHeuristic.predictor();
                throw new AssertionError("invalid switch must be rejected: " + key);
            } catch (IllegalArgumentException expected) {
                check(expected.getMessage().contains(key), "error identifies invalid property");
            }
        }
        configure(true, true);
    }

    private static void searcherCapturesConfiguration() {
        configure(false, false);
        NPuzzleIdaStar captured = new NPuzzleIdaStar(DisjointPdbHeuristic.predictor());
        configure(true, true);
        NPuzzleIdaStar explicit =
                new NPuzzleIdaStar(DisjointPdbHeuristic.predictor(new PdbOptions(false, false)));
        NPuzzleProblem problem = new NPuzzleProblem(FIXTURE, GOAL);
        equal(18, captured.search(problem).size(), "captured search configuration");
        equal(18, explicit.search(problem).size(), "explicit search configuration");
        equal(explicit.nodesGenerated(), captured.nodesGenerated(), "captured generated nodes");
        equal(explicit.nodesExpanded(), captured.nodesExpanded(), "captured expanded nodes");
    }

    private static void symmetryCanBeDisabled() {
        System.clearProperty(INCREMENTAL);
        System.clearProperty(SYMMETRY);
        // Independently calculated by 0-1 BFS over the three abstract pattern spaces.
        equal(14, DisjointPdbHeuristic.predictor().heuristics(FIXTURE, GOAL), "default symmetry");
        System.setProperty(SYMMETRY, "false");
        equal(12, DisjointPdbHeuristic.predictor().heuristics(FIXTURE, GOAL), "disabled symmetry");
        equal(12, product(FIXTURE, GOAL).heuristic(), "disabled incremental-context symmetry");
    }

    private static void productModesAgree() {
        for (boolean incremental : new boolean[] {true, false}) {
            for (boolean symmetry : new boolean[] {true, false}) {
                configure(incremental, symmetry);
                PdbContext context = product(FIXTURE, GOAL);
                equal(symmetry ? 14 : 12, context.heuristic(), "product root");
                exerciseMoves(context, FIXTURE, GOAL, symmetry, 400);
            }
        }
    }

    private static void searchModesFindShortestPaths() {
        int[][] generated = new int[2][2], expanded = new int[2][2];
        boolean[] settings = {true, false};
        for (int i = 0; i < settings.length; i++) {
            boolean incremental = settings[i];
            for (int s = 0; s < settings.length; s++) {
                boolean symmetry = settings[s];
                configure(incremental, symmetry);
                NPuzzleIdaStar searcher = new NPuzzleIdaStar(DisjointPdbHeuristic.predictor());
                // A separate breadth-first search gives optimal distance 18.
                Deque<Node> path = searcher.search(new NPuzzleProblem(FIXTURE, GOAL));
                check(path != null, "fixture must be solvable");
                equal(18, path.size(), "optimal solution length");
                check(path.getLast().getState().equals(GOAL), "path must end at goal");
                generated[i][s] = searcher.nodesGenerated();
                expanded[i][s] = searcher.nodesExpanded();
                check(searcher.search(new NPuzzleProblem(GOAL, GOAL)).isEmpty(), "solved input");
                PuzzleBoard impossible =
                        new PuzzleBoard(new byte[] {2, 1, 3, 4, 5, 6, 7, 8, 0}, 3);
                check(searcher.search(new NPuzzleProblem(impossible, GOAL)) == null, "unsolvable input");
            }
        }
        for (int s = 0; s < settings.length; s++) {
            equal(generated[0][s], generated[1][s], "same generated nodes");
            equal(expanded[0][s], expanded[1][s], "same expanded nodes");
        }
    }

    private static void unsupportedSymmetryStaysDisabled() {
        PuzzleBoard goal = new PuzzleBoard(new byte[] {1, 2, 3, 4, 5, 6, 7, 0, 8}, 3);
        for (boolean incremental : new boolean[] {true, false}) {
            configure(incremental, true);
            PdbContext context = product(GOAL, goal);
            check(!context.symmetric, "off-diagonal goal blank cannot use symmetry");
            exerciseMoves(context, GOAL, goal, false, 100);
        }
    }

    private static void componentModesAgree() {
        PuzzleBoard goal =
                new PuzzleBoard(
                        new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 0}, 4);
        for (boolean incremental : new boolean[] {true, false}) {
            for (boolean symmetry : new boolean[] {true, false}) {
                configure(incremental, symmetry);
                PdbContext context =
                        new ComponentPdbContext(goal, ComponentPdbContext.tablesFor(goal));
                exerciseMoves(context, goal, goal, symmetry, 400);
                PuzzleBoard initial =
                        new PuzzleBoard(
                                new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 0, 12, 13, 14, 11, 15},
                                4);
                Deque<Node> path =
                        new NPuzzleIdaStar(DisjointPdbHeuristic.predictor())
                                .search(new NPuzzleProblem(initial, goal));
                check(path != null, "four-by-four fixture must be solvable");
                equal(2, path.size(), "four-by-four shortest path");
            }
        }
    }

    private static PdbContext product(PuzzleBoard initial, PuzzleBoard goal) {
        return new ProductPdbContext(initial, DisjointPdbHeuristic.tablesFor(goal));
    }

    private static void exerciseMoves(
            PdbContext context, PuzzleBoard initial, PuzzleBoard goal, boolean symmetry, int steps) {
        Random random = new Random(73);
        PuzzleBoard board = initial;
        Deque<PdbContext.Move> moves = new ArrayDeque<>();
        Deque<PuzzleBoard> parents = new ArrayDeque<>();
        for (int step = 0; step < steps; step++) {
            int[] neighbors = context.neighbors[context.blank];
            int target = neighbors[random.nextInt(neighbors.length)];
            PdbContext.Move move = new PdbContext.Move();
            PuzzleBoard child = child(board, target);
            byte[] before = context.board.clone();
            long code = context.code;
            int expected = reference(child, goal, symmetry);
            equal(expected, context.prepare(target, context.codeAfterMove(target), move), "prepare");
            check(Arrays.equals(before, context.board), "prepare must preserve the board");
            check(code == context.code, "prepare must preserve the code");
            context.apply(move);
            equal(expected, context.heuristic(), "apply");
            check(Arrays.equals(child.cells(), context.board), "apply board");
            moves.push(move);
            parents.push(board);
            board = child;

            // Exercise pruning shortcuts, followed by an unlimited query for the same child.
            int next = context.neighbors[context.blank][0];
            PuzzleBoard nextBoard = child(board, next);
            int full = reference(nextBoard, goal, symmetry);
            PdbContext.Move pruned = new PdbContext.Move();
            int limited = context.prepare(next, context.codeAfterMove(next), pruned, 0);
            check(full == 0 ? limited == 0 : limited > 0, "bounded query pruning");
            if (board.size() == 4) {
                PdbContext referenceContext =
                        new ComponentPdbContext(
                                board,
                                ComponentPdbContext.tablesFor(goal),
                                new PdbOptions(true, symmetry));
                equal(
                        referenceContext.prepare(
                                next, referenceContext.codeAfterMove(next), new PdbContext.Move(), 0),
                        limited,
                        "bounded mode equivalence");
            }
            equal(full, context.prepare(next, context.codeAfterMove(next), pruned), "query after pruning");
        }
        while (!moves.isEmpty()) {
            context.undo(moves.pop());
            board = parents.pop();
            equal(reference(board, goal, symmetry), context.heuristic(), "undo");
            check(Arrays.equals(board.cells(), context.board), "undo board");
        }
        check(Arrays.equals(initial.cells(), context.board), "complete undo");
    }

    private static PuzzleBoard child(PuzzleBoard board, int target) {
        byte[] cells = board.cells().clone();
        cells[board.blank()] = cells[target];
        cells[target] = 0;
        return new PuzzleBoard(cells, board.size());
    }

    private static int reference(PuzzleBoard board, PuzzleBoard goal, boolean symmetry) {
        if (board.size() == 3) {
            DisjointPdbHeuristic.Tables tables = DisjointPdbHeuristic.tablesFor(goal);
            return symmetry ? tables.hMax(board) : tables.h(board);
        }
        ComponentPdbContext.Tables tables = ComponentPdbContext.tablesFor(goal);
        int[][] groups = {{1, 2, 3, 4, 5, 6, 7}, {8, 9, 10, 11, 12, 13, 14, 15}};
        int[] positions = new int[16];
        for (int i = 0; i < 16; i++) positions[board.cells()[i]] = i;
        int direct = componentSum(tables, groups, positions, board.blank(), false);
        return symmetry
                ? Math.max(
                        direct,
                        componentSum(tables, groups, positions, tables.transpose[board.blank()], true))
                : direct;
    }

    private static int componentSum(
            ComponentPdbContext.Tables tables,
            int[][] groups,
            int[] positions,
            int blank,
            boolean mirrored) {
        int sum = 0;
        for (int g = 0; g < groups.length; g++) {
            int[] tuple = new int[groups[g].length];
            int occupied = 0;
            for (int j = 0; j < tuple.length; j++) {
                int tile = groups[g][j];
                tuple[j] = mirrored ? tables.transpose[positions[tables.permutation[tile]]] : positions[tile];
                occupied |= 1 << tuple[j];
            }
            // Independent inversion-count ranking rather than the incremental rank update.
            int rank = 0;
            for (int i = 0; i < tuple.length; i++) {
                int smaller = 0;
                for (int j = i + 1; j < tuple.length; j++) if (tuple[j] < tuple[i]) smaller++;
                rank = rank * (tuple.length - i) + smaller;
            }
            sum += tables.data[g].distance(occupied, rank, blank);
        }
        return sum;
    }

    private static void configure(boolean incremental, boolean symmetry) {
        System.setProperty(INCREMENTAL, Boolean.toString(incremental));
        System.setProperty(SYMMETRY, Boolean.toString(symmetry));
    }

    private static void restore(String key, String value) {
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
    }

    private static void equal(int expected, int actual, String label) {
        check(expected == actual, label + ": expected " + expected + ", got " + actual);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
