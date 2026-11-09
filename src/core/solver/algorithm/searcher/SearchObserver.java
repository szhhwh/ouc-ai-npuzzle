package core.solver.algorithm.searcher;

import core.solver.queue.Node;

/**
 * Optional synchronous observations of actual search decisions. Callbacks must not mutate nodes or
 * search state. No event objects are created, and engines skip callbacks when no observer is set.
 */
public interface SearchObserver {
    enum Pruning {
        CLOSED, CYCLE, BOUND, TRANSPOSITION
    }

    enum HeuristicQuality { EXACT, LOWER_BOUND, UNEVALUATED }

    /** A fresh root occurrence, including each iterative-deepening restart; bound -1 means none. */
    default void iteration(Node root, int bound, int iteration) {}

    /** A node selected for expansion, or the goal selected for return. */
    default void visit(Node node, boolean found, int bound, int iteration) {}

    /** A generated occurrence; only observed estimates have a meaningful numeric heuristic. */
    default void generated(Node child, HeuristicQuality quality) {}

    /** The actual result of offering a generated child to the frontier. */
    default void offered(Node node, boolean accepted) {}

    default void prune(Node node, Pruning reason) {}
}
