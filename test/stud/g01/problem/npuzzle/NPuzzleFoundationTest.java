package stud.g01.problem.npuzzle;

import core.problem.Action;
import core.problem.Problem;
import core.solver.algorithm.heuristic.HeuristicType;
import core.solver.algorithm.searcher.AbstractSearcher;
import core.solver.queue.Node;
import stud.g01.runner.PuzzleFeeder;

import java.util.ArrayList;
import java.util.Deque;

/**
 * 无第三方依赖的 N-Puzzle 基础回归测试。
 */
public final class NPuzzleFoundationTest {
    private static int assertions;

    private NPuzzleFoundationTest() {
    }

    public static void main(String[] args) {
        testConstructionAndImmutability();
        testInvalidBoards();
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
        PuzzleBoard board = new PuzzleBoard(3, source);
        source[0] = 8;
        check(board.tileAt(0, 0) == 1, "constructor must defensively copy input");

        int[] copy = board.toArray();
        copy[0] = 8;
        check(board.tileAt(0, 0) == 1, "toArray must return a defensive copy");
        check(board.getBlankRow() == 2 && board.getBlankCol() == 1,
                "blank position must be detected");
        expectThrows(IndexOutOfBoundsException.class, () -> board.tileAt(3, 0));
    }

    private static void testInvalidBoards() {
        expectThrows(IllegalArgumentException.class,
                () -> new PuzzleBoard(1, new int[]{0}));
        expectThrows(IllegalArgumentException.class,
                () -> new PuzzleBoard(3, new int[]{0, 1}));
        expectThrows(IllegalArgumentException.class,
                () -> new PuzzleBoard(2, new int[]{0, 1, 1, 3}));
        expectThrows(IllegalArgumentException.class,
                () -> new PuzzleBoard(2, new int[]{0, 1, 2, 4}));
        expectThrows(NullPointerException.class,
                () -> new PuzzleBoard(3, null));
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
        check(!problem.applicable(topLeft, move(PuzzleDirection.UP)),
                "top-left blank cannot move up");
        check(!problem.applicable(topLeft, move(PuzzleDirection.LEFT)),
                "top-left blank cannot move left");
        check(problem.applicable(topLeft, move(PuzzleDirection.RIGHT)),
                "top-left blank can move right");
        check(problem.applicable(topLeft, move(PuzzleDirection.DOWN)),
                "top-left blank can move down");

        int actionCount = 0;
        for (Action ignored : topLeft.actions()) {
            actionCount++;
        }
        check(actionCount == 4, "actions must expose four candidate blank moves");

        PuzzleBoard movedRight = (PuzzleBoard) topLeft.next(move(PuzzleDirection.RIGHT));
        check(movedRight.equals(board3(1, 0, 2, 3, 4, 5, 6, 7, 8)),
                "right move must swap blank and right tile");
        check(topLeft.equals(board3(0, 1, 2, 3, 4, 5, 6, 7, 8)),
                "next must not mutate source board");
        PuzzleBoard restored = (PuzzleBoard) movedRight.next(move(PuzzleDirection.LEFT));
        check(restored.equals(topLeft), "opposite moves must restore original board");
        check(problem.stepCost(movedRight, move(PuzzleDirection.RIGHT)) == 1,
                "each puzzle move must cost one");
        check("(#, N)".equals(move(PuzzleDirection.UP).toString()),
                "up move must use the reference N label");
        check("(#, E)".equals(move(PuzzleDirection.RIGHT).toString()),
                "right move must use the reference E label");
        check("(#, S)".equals(move(PuzzleDirection.DOWN).toString()),
                "down move must use the reference S label");
        check("(#, W)".equals(move(PuzzleDirection.LEFT).toString()),
                "left move must use the reference W label");
        expectThrows(IllegalArgumentException.class,
                () -> topLeft.next(move(PuzzleDirection.UP)));

        PuzzleBoard center = board3(1, 2, 3, 4, 0, 5, 6, 7, 8);
        for (PuzzleDirection direction : PuzzleDirection.values()) {
            check(center.canMove(direction), "center blank must move " + direction);
        }
    }

    private static void testHeuristics() {
        PuzzleBoard goal = board3(1, 2, 3, 4, 5, 6, 7, 8, 0);
        PuzzleBoard state = board3(1, 2, 3, 4, 5, 6, 0, 7, 8);
        check(state.misplacedTiles(goal) == 2, "misplaced must exclude blank");
        check(state.manhattanDistance(goal) == 2, "Manhattan must exclude blank");
        check(goal.misplacedTiles(goal) == 0, "goal misplaced value must be zero");
        check(goal.manhattanDistance(goal) == 0, "goal Manhattan value must be zero");

        int misplaced = PuzzleBoard.predictor(HeuristicType.MISPLACED)
                .heuristics(state, goal);
        int manhattan = PuzzleBoard.predictor(HeuristicType.MANHATTAN)
                .heuristics(state, goal);
        check(misplaced == 2 && manhattan == 2, "predictor mapping must be correct");

        PuzzleBoard arbitraryGoal = board3(1, 2, 3, 4, 5, 6, 0, 7, 8);
        check(goal.manhattanDistance(arbitraryGoal) == 2,
                "heuristics must support a non-canonical goal");
        expectThrows(UnsupportedOperationException.class,
                () -> PuzzleBoard.predictor(HeuristicType.DISJOINT_PATTERN));
    }

    private static void testSolvability() {
        PuzzleBoard goal3 = board3(1, 2, 3, 4, 5, 6, 7, 8, 0);
        check(new NPuzzleProblem(
                board3(1, 2, 3, 4, 5, 6, 7, 0, 8), goal3, 3).solvable(),
                "one-move 3x3 board must be solvable");
        check(!new NPuzzleProblem(
                board3(1, 2, 3, 4, 5, 6, 8, 7, 0), goal3, 3).solvable(),
                "single tile swap in 3x3 board must be unsolvable");

        PuzzleBoard goal4 = new PuzzleBoard(4,
                new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 0});
        PuzzleBoard oneMove4 = new PuzzleBoard(4,
                new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 0, 15});
        PuzzleBoard swapped4 = new PuzzleBoard(4,
                new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 15, 14, 0});
        check(new NPuzzleProblem(oneMove4, goal4, 4).solvable(),
                "one-move 4x4 board must be solvable");
        check(!new NPuzzleProblem(swapped4, goal4, 4).solvable(),
                "single tile swap in 4x4 board must be unsolvable");

        PuzzleBoard arbitraryInitial = board3(1, 2, 3, 4, 5, 6, 7, 8, 0);
        PuzzleBoard arbitraryGoal = board3(1, 2, 3, 4, 5, 6, 7, 0, 8);
        check(new NPuzzleProblem(arbitraryInitial, arbitraryGoal, 3).solvable(),
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
        expectThrows(IllegalArgumentException.class, () -> feeder.getProblems(tooShort));

        ArrayList<String> malformed = new ArrayList<>();
        malformed.add("x 1 2 3");
        expectThrows(IllegalArgumentException.class, () -> feeder.getProblems(malformed));
        expectThrows(IllegalArgumentException.class, () -> feeder.getProblems(null));
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

    private static PuzzleMove move(PuzzleDirection direction) {
        return new PuzzleMove(direction);
    }

    private static PuzzleBoard board3(int... tiles) {
        return new PuzzleBoard(3, tiles);
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
