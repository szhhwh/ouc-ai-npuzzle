package stud.queue;

import core.solver.queue.EvaluationType;
import core.solver.queue.Node;
import java.util.Comparator;
import stud.g01.problem.npuzzle.PuzzleBoard;

/** 无第三方依赖的 PqFrontier 回归测试。 */
public final class PqFrontierTest {
    private static final Comparator<Node> BY_FULL = Node.evaluator(EvaluationType.FULL);
    private static int assertions;

    private PqFrontierTest() {}

    public static void main(String[] args) {
        testPriorityOrder();
        testDuplicateDiscardAndReplace();
        testLazyDeletion();
        testClearAndNullValidation();
        System.out.println("PqFrontier 全部通过：" + assertions + " 个断言");
    }

    private static void testPriorityOrder() {
        PqFrontier frontier = new PqFrontier(BY_FULL);
        Node expensive = node(board(1, 2, 0, 3), 4, 3);
        Node cheapest = node(board(1, 0, 2, 3), 1, 1);
        Node middle = node(board(0, 1, 2, 3), 2, 2);

        check(frontier.offer(expensive), "new state must be inserted");
        check(frontier.offer(cheapest), "second state must be inserted");
        check(frontier.offer(middle), "third state must be inserted");
        check(frontier.size() == 3, "size must count live states");
        check(frontier.poll() == cheapest, "poll must return the lowest evaluation first");
        check(frontier.poll() == middle, "poll must preserve priority order");
        check(frontier.poll() == expensive, "poll must return the final node");
        check(frontier.poll() == null && frontier.isEmpty(), "empty frontier must poll null");
    }

    private static void testDuplicateDiscardAndReplace() {
        PqFrontier frontier = new PqFrontier(BY_FULL);
        PuzzleBoard state = board(1, 2, 3, 0);
        Node original = node(state, 4, 3);
        Node worse = node(board(1, 2, 3, 0), 6, 3);
        Node better = node(board(1, 2, 3, 0), 1, 2);

        check(frontier.offer(original), "original node must be inserted");
        check(frontier.contains(worse), "contains must compare states, not node identity");
        check(!frontier.offer(worse), "worse duplicate must be discarded");
        check(frontier.size() == 1, "discarded duplicate must not change live size");
        check(frontier.offer(better), "better duplicate must replace old node");
        check(frontier.size() == 1, "replacement must keep one live state");
        check(frontier.poll() == better, "replacement must be the live node");
    }

    private static void testLazyDeletion() {
        PqFrontier frontier = new PqFrontier(BY_FULL);
        Node stale = node(board(1, 2, 3, 0), 8, 2);
        Node replacement = node(board(1, 2, 3, 0), 2, 1);
        Node other = node(board(1, 2, 0, 3), 4, 2);

        frontier.offer(stale);
        frontier.offer(replacement);
        frontier.offer(other);
        check(frontier.size() == 2, "stale heap entry must not count as a live state");
        check(frontier.poll() == replacement, "better replacement must be returned first");
        check(frontier.size() == 1, "poll must remove exactly one live state");
        check(frontier.poll() == other, "stale heap entry must be skipped");
        check(frontier.size() == 0, "drained frontier must have zero live states");
        check(frontier.poll() == null, "stale-only heap must drain to null");
    }

    private static void testClearAndNullValidation() {
        PqFrontier frontier = new PqFrontier(BY_FULL);
        Node node = node(board(1, 2, 3, 0), 1, 0);
        frontier.offer(node);
        frontier.clear();
        check(frontier.isEmpty() && frontier.size() == 0, "clear must reset heap and index");
        check(!frontier.contains(node), "cleared state must not remain indexed");
        expectThrows(NullPointerException.class, () -> frontier.offer(null));
        expectThrows(NullPointerException.class, () -> frontier.contains(null));
        expectThrows(NullPointerException.class, () -> new PqFrontier(null));
    }

    private static Node node(PuzzleBoard state, int pathCost, int heuristic) {
        return new Node(state, null, null, pathCost, heuristic);
    }

    private static PuzzleBoard board(int... tiles) {
        byte[] cells = new byte[tiles.length];
        for (int i = 0; i < tiles.length; i++) {
            cells[i] = (byte) tiles[i];
        }
        return new PuzzleBoard(cells, 2);
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
                    "expected "
                            + expected.getSimpleName()
                            + " but got "
                            + actual.getClass().getSimpleName(),
                    actual);
        }
        throw new AssertionError("expected " + expected.getSimpleName() + " to be thrown");
    }
}
