package stud.g01.runner;

import core.problem.State;
import core.solver.algorithm.searcher.SearchObserver;
import core.solver.queue.Node;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import stud.g01.problem.npuzzle.PuzzleBoard;

/** Collects actual search observations; JSON projection belongs to the writer. */
final class SearchTrace implements SearchObserver {
    static final class Entry {
        final int id, parent, g, h;
        final byte[] board;
        final HeuristicQuality quality;
        String status;
        Integer reference;
        Frame frame;

        Entry(
                int id,
                int parent,
                byte[] board,
                int g,
                int h,
                String status,
                HeuristicQuality quality) {
            this.id = id;
            this.parent = parent;
            this.board = board;
            this.g = g;
            this.h = h;
            this.status = status;
            this.quality = quality;
        }
    }

    static final class Frame {
        final int current, bound, iteration;
        final boolean found;
        final List<Integer> children = new ArrayList<>();

        Frame(int current, boolean found, int bound, int iteration) {
            this.current = current;
            this.found = found;
            this.bound = bound;
            this.iteration = iteration;
        }
    }

    private final List<Entry> nodes = new ArrayList<>();
    private final List<Frame> frames = new ArrayList<>();
    private final IdentityHashMap<Node, Integer> identities = new IdentityHashMap<>();
    private final Map<State, Node> accepted = new HashMap<>();
    private final Map<State, Integer> closed = new HashMap<>();
    private int finalGoal = -1;

    private int add(
            int parent, byte[] board, int g, int h, String status, HeuristicQuality quality) {
        int id = nodes.size();
        nodes.add(new Entry(id, parent, board, g, h, status, quality));
        if (parent >= 0) nodes.get(parent).frame.children.add(id);
        return id;
    }

    private void visit(int id, boolean found, int bound, int iteration) {
        Frame frame = new Frame(id, found, bound, iteration);
        nodes.get(id).frame = frame;
        frames.add(frame);
        if (found) finalGoal = id;
    }

    @Override
    public void iteration(Node root, int bound, int iteration) {
        identities.clear();
        accepted.clear();
        closed.clear();
        identities.put(
                root,
                add(
                        -1,
                        ((PuzzleBoard) root.getState()).cells(),
                        0,
                        root.getHeuristic(),
                        "frontier",
                        HeuristicQuality.EXACT));
        accepted.put(root.getState(), root);
    }

    @Override
    public void visit(Node node, boolean found, int bound, int iteration) {
        int id = identities.get(node);
        visit(id, found, bound, iteration);
        accepted.remove(node.getState());
        if (!found) closed.put(node.getState(), id);
    }

    @Override
    public void generated(Node child, HeuristicQuality quality) {
        identities.put(
                child,
                add(
                        identities.get(child.getParent()),
                        ((PuzzleBoard) child.getState()).cells(),
                        child.getPathCost(),
                        child.getHeuristic(),
                        "frontier",
                        quality));
    }

    @Override
    public void offered(Node node, boolean offered) {
        Entry entry = nodes.get(identities.get(node));
        Node previous = accepted.get(node.getState());
        if (previous != null) {
            entry.status = offered ? "replaced" : "frontier-pruned";
            entry.reference = identities.get(previous);
        }
        if (offered) accepted.put(node.getState(), node);
    }

    @Override
    public void prune(Node node, SearchObserver.Pruning reason) {
        Entry entry = nodes.get(identities.get(node));
        entry.status = status(reason);
        if (reason == SearchObserver.Pruning.CLOSED) entry.reference = closed.get(node.getState());
    }

    private static String status(Pruning reason) {
        return switch (reason) {
            case CLOSED -> "closed-pruned";
            case CYCLE -> "cycle-pruned";
            case BOUND -> "bound-pruned";
            case TRANSPOSITION -> "transposition-pruned";
        };
    }

    List<byte[]> solution() {
        if (finalGoal < 0) throw new IllegalStateException("搜索轨迹缺少目标帧");
        List<byte[]> boards = new ArrayList<>();
        for (int id : solutionIds()) boards.add(nodes.get(id).board);
        return boards;
    }

    List<Integer> solutionIds() {
        List<Integer> result = new ArrayList<>();
        for (int id = finalGoal; id >= 0; id = nodes.get(id).parent) result.add(id);
        java.util.Collections.reverse(result);
        return result;
    }

    List<Entry> entries() {
        return nodes;
    }

    List<Frame> frames() {
        return frames;
    }

    void validateCounts(int generated, int expanded) {
        long roots = nodes.stream().filter(node -> node.parent < 0).count();
        if (generated != nodes.size() - roots
                || expanded != frames.size() - 1
                || frames.isEmpty()
                || !frames.getLast().found) {
            throw new IllegalStateException("搜索计数与完整过程不一致");
        }
    }
}
