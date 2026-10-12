package stud.g01.problem.npuzzle;

import core.problem.Action;
import core.problem.Problem;
import core.solver.algorithm.heuristic.HeuristicType;
import core.solver.algorithm.searcher.AbstractSearcher;
import core.solver.queue.Node;
import stud.g01.runner.PuzzleFeeder;

import java.util.ArrayList;
import java.util.Deque;

/** N-Puzzle 基础回归测试。 */
public final class NPuzzleFoundationTest {
    private static int assertions;

    private NPuzzleFoundationTest() {}

    public static void main(String[] args) {
        testConstructionAndImmutability();
        testEqualityAndHashCode();
        testMovesAndApplicability();
        testHeuristics();
        testSolvability();
        testFeederParsingAndValidation();
        testOneMoveSearch();
        System.out.println("全部通过：" + assertions + " 个断言");
    }

    private static void testConstructionAndImmutability() {
        int[] source = {1, 2, 3, 4, 5, 6, 7, 0, 8};
        PuzzleBoard board = board3(source);
        source[0] = 8;
        check(board.cells()[0] == 1, "constructor must defensively copy input");
        check(board.size() == 3, "board size must be retained");
        check(board.blank() == 7, "blank linear index must be detected (row-major)");
        expectThrows(NullPointerException.class, () -> new PuzzleBoard(null, 3));
    }

    private static void testEqualityAndHashCode() {
        PuzzleBoard first = board3(1, 2, 3, 4, 5, 6, 7, 0, 8);
        PuzzleBoard same = board3(1, 2, 3, 4, 5, 6, 7, 0, 8);
        PuzzleBoard other = board3(1, 2, 3, 4, 5, 6, 7, 8, 0);
        check(first.equals(same), "equal tile arrangements must be equal");
        check(first.hashCode() == same.hashCode(), "equal boards must have equal hashes");
        check(!first.equals(other), "different tile arrangements must not be equal");
        check(!first.equals(null), "board must not equal null");
    }

    private static void testMovesAndApplicability() {
        PuzzleBoard topLeft = board3(0, 1, 2, 3, 4, 5, 6, 7, 8);
        NPuzzleProblem problem = new NPuzzleProblem(topLeft, topLeft, 3);
        check(!problem.applicable(topLeft, PuzzleMove.UP), "top-left blank cannot move up");
        check(!problem.applicable(topLeft, PuzzleMove.LEFT), "top-left blank cannot move left");
        check(problem.applicable(topLeft, PuzzleMove.RIGHT), "top-left blank can move right");
        check(problem.applicable(topLeft, PuzzleMove.DOWN), "top-left blank can move down");

        int actionCount = 0;
        for (Action ignored : topLeft.actions()) {
            actionCount++;
        }
        check(actionCount == 4, "actions must expose four candidate blank moves");

        PuzzleBoard movedRight = (PuzzleBoard) topLeft.next(PuzzleMove.RIGHT);
        check(
                movedRight.equals(board3(1, 0, 2, 3, 4, 5, 6, 7, 8)),
                "right move must swap blank and right tile");
        check(topLeft.equals(board3(0, 1, 2, 3, 4, 5, 6, 7, 8)), "next must not mutate source board");
        PuzzleBoard restored = (PuzzleBoard) movedRight.next(PuzzleMove.LEFT);
        check(restored.equals(topLeft), "opposite moves must restore original board");
        check(problem.stepCost(movedRight, PuzzleMove.RIGHT) == 1, "each puzzle move must cost one");
        check("U".equals(PuzzleMove.UP.toString()), "up move must use the U label");
        check("R".equals(PuzzleMove.RIGHT.toString()), "right move must use the R label");
        check("D".equals(PuzzleMove.DOWN.toString()), "down move must use the D label");
        check("L".equals(PuzzleMove.LEFT.toString()), "left move must use the L label");

        PuzzleBoard center = board3(1, 2, 3, 4, 0, 5, 6, 7, 8);
        PuzzleMove[] allMoves = {PuzzleMove.UP, PuzzleMove.DOWN, PuzzleMove.LEFT, PuzzleMove.RIGHT};
        for (PuzzleMove move : allMoves) {
            check(problem.applicable(center, move), "center blank must move " + move);
        }
    }

    private static void testHeuristics() {
        PuzzleBoard goal = board3(1, 2, 3, 4, 5, 6, 7, 8, 0);
        PuzzleBoard state = board3(1, 2, 3, 4, 5, 6, 0, 7, 8);
        check(
                PuzzleBoard.predictor(HeuristicType.MISPLACED).heuristics(state, goal) == 2,
                "misplaced must exclude blank");
        check(
                PuzzleBoard.predictor(HeuristicType.MANHATTAN).heuristics(state, goal) == 2,
                "Manhattan must exclude blank");
        check(
                PuzzleBoard.predictor(HeuristicType.MISPLACED).heuristics(goal, goal) == 0,
                "goal misplaced value must be zero");
        check(
                PuzzleBoard.predictor(HeuristicType.MANHATTAN).heuristics(goal, goal) == 0,
                "goal Manhattan value must be zero");

        PuzzleBoard arbitraryGoal = board3(1, 2, 3, 4, 5, 6, 0, 7, 8);
        check(
                PuzzleBoard.predictor(HeuristicType.MANHATTAN).heuristics(goal, arbitraryGoal) == 2,
                "heuristics must support a non-canonical goal");
        check(
                PuzzleBoard.predictor(HeuristicType.DISJOINT_PATTERN) != null,
                "this branch registers a real disjoint-PDB predictor (stage 3)");
    }

    private static void testSolvability() {
        PuzzleBoard goal3 = board3(1, 2, 3, 4, 5, 6, 7, 8, 0);
        check(
                new NPuzzleProblem(board3(1, 2, 3, 4, 5, 6, 7, 0, 8), goal3, 3).solvable(),
                "one-move 3x3 board must be solvable");
        check(
                !new NPuzzleProblem(board3(1, 2, 3, 4, 5, 6, 8, 7, 0), goal3, 3).solvable(),
                "single tile swap in 3x3 board must be unsolvable");

        PuzzleBoard goal4 = board4(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 0);
        PuzzleBoard oneMove4 = board4(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 0, 15);
        PuzzleBoard swapped4 = board4(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 15, 14, 0);
        check(new NPuzzleProblem(oneMove4, goal4, 4).solvable(), "one-move 4x4 board must be solvable");
        check(
                !new NPuzzleProblem(swapped4, goal4, 4).solvable(),
                "single tile swap in 4x4 board must be unsolvable");

        PuzzleBoard arbitraryInitial = board3(1, 2, 3, 4, 5, 6, 7, 8, 0);
        PuzzleBoard arbitraryGoal = board3(1, 2, 3, 4, 5, 6, 7, 0, 8);
        check(
                new NPuzzleProblem(arbitraryInitial, arbitraryGoal, 3).solvable(),
                "solvability must compare against the provided goal");
    }

    private static void testFeederParsingAndValidation() {
        PuzzleFeeder feeder = new PuzzleFeeder();
        ArrayList<String> lines = new ArrayList<>();
        lines.add("3 1 2 3 4 5 6 7 0 8 1 2 3 4 5 6 7 8 0");
        lines.add("   ");
        ArrayList<Problem> problems = feeder.getProblems(lines);
        check(problems.size() == 1, "feeder must parse one problem and skip blank lines");
        check(problems.get(0).solvable(), "parsed one-move problem must be solvable");

        ArrayList<String> tooShort = new ArrayList<>();
        tooShort.add("3 1 2 3");
        expectThrows(
                ArrayIndexOutOfBoundsException.class, () -> feeder.getProblems(tooShort));

        ArrayList<String> malformed = new ArrayList<>();
        malformed.add("x 1 2 3");
        expectThrows(NumberFormatException.class, () -> feeder.getProblems(malformed));
        expectThrows(NullPointerException.class, () -> feeder.getProblems(null));
    }

    private static void testOneMoveSearch() {
        PuzzleFeeder feeder = new PuzzleFeeder();
        PuzzleBoard initial = board3(1, 2, 3, 4, 5, 6, 7, 0, 8);
        PuzzleBoard goal = board3(1, 2, 3, 4, 5, 6, 7, 8, 0);
        NPuzzleProblem problem = new NPuzzleProblem(initial, goal, 3);
        AbstractSearcher searcher = feeder.getAStar(HeuristicType.MANHATTAN);
        Deque<Node> path = searcher.search(problem);
        check(path != null && path.size() == 1, "A* must solve a one-move problem optimally");
        check(problem.goal(path.getLast().getState()), "search path must reach goal");
    }

    private static PuzzleBoard board3(int... tiles) {
        return board(3, tiles);
    }

    private static PuzzleBoard board4(int... tiles) {
        return board(4, tiles);
    }

    private static PuzzleBoard board(int size, int[] tiles) {
        byte[] cells = new byte[tiles.length];
        for (int i = 0; i < tiles.length; i++) {
            cells[i] = (byte) tiles[i];
        }
        return new PuzzleBoard(cells, size);
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void expectThrows(Class<? extends Throwable> expected, Runnable action) {
        assertions++;
        try {
            action.run();
        } catch (Throwable actual) {
            if (expected.isInstance(actual)) {
                return;
            }
            throw new AssertionError(
                    "expected " + expected.getSimpleName() + " but got "
                            + actual.getClass().getSimpleName(), actual);
        }
        throw new AssertionError("expected " + expected.getSimpleName() + " to be thrown");
    }
}
