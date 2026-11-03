package stud.g01.runner;

import core.problem.State;
import core.solver.algorithm.heuristic.Predictor;
import core.solver.queue.EvaluationType;
import core.solver.queue.Frontier;
import core.solver.queue.Node;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import stud.g01.problem.npuzzle.NPuzzleIdaStar;
import stud.g01.problem.npuzzle.PuzzleBoard;
import stud.queue.PqFrontier;
import stud.solver.IdAStar;

/** Export-only observations; the search engines retain their own ordering and counters. */
final class SearchTrace implements IdAStar.Observer, NPuzzleIdaStar.Observer {
    private static final class Entry {
        final int id, parent, g, h;
        final byte[] board;
        String status;
        Integer reference;
        Frame frame;

        Entry(int id, int parent, byte[] board, int g, int h, String status) {
            this.id = id;
            this.parent = parent;
            this.board = board;
            this.g = g;
            this.h = h;
            this.status = status;
        }
    }

    private static final class Frame {
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
    private int finalGoal = -1;

    @Override
    public int root(byte[] board, int h, int bound, int iteration) {
        return add(-1, board.clone(), 0, h, "frontier");
    }

    private int add(int parent, byte[] board, int g, int h, String status) {
        int id = nodes.size();
        nodes.add(new Entry(id, parent, board, g, h, status));
        if (parent >= 0) nodes.get(parent).frame.children.add(id);
        return id;
    }

    @Override
    public int child(int parent, byte[] board, int blank, int target, int g, int h, String status) {
        byte[] copy = board.clone();
        copy[blank] = copy[target];
        copy[target] = 0;
        return add(parent, copy, g, h, status);
    }

    @Override
    public void visit(int id, boolean found, int bound, int iteration) {
        Frame frame = new Frame(id, found, bound, iteration);
        nodes.get(id).frame = frame;
        frames.add(frame);
        if (found) finalGoal = id;
    }

    @Override
    public void prune(int id, String status) {
        nodes.get(id).status = status;
    }

    @Override
    public void iteration(Node root, int bound, int iteration) {
        identities.clear();
        identities.put(
                root,
                root(
                        ((PuzzleBoard) root.getState()).cells(),
                        root.getHeuristic(),
                        bound,
                        iteration));
    }

    @Override
    public void visit(Node node, boolean found, int bound, int iteration) {
        visit(identities.get(node), found, bound, iteration);
    }

    @Override
    public void generated(Node parent, List<Node> children, Predictor predictor, State goal) {
        int parentId = identities.get(parent);
        for (Node child : children) {
            int h = predictor.heuristics(child.getState(), goal);
            identities.put(
                    child,
                    add(
                            parentId,
                            ((PuzzleBoard) child.getState()).cells(),
                            child.getPathCost(),
                            h,
                            "frontier"));
        }
    }

    @Override
    public void prune(Node node, String status) {
        prune(identities.get(node), status);
    }

    /** The teacher searcher calls the predictor for every child before offering any child. */
    final class BestFirstObservation implements Frontier, Predictor {
        private final Predictor predictor;
        private final PuzzleBoard goal;
        private final Comparator<Node> comparator;
        private final Frontier frontier;
        private final Map<State, Node> accepted = new HashMap<>();
        private final Map<State, Integer> closed = new HashMap<>();
        private final IdentityHashMap<State, Integer> generated = new IdentityHashMap<>();
        private Node current;
        private int rootId;

        BestFirstObservation(Predictor predictor, PuzzleBoard goal, EvaluationType evaluation) {
            this.predictor = predictor;
            this.goal = goal;
            comparator = Node.evaluator(evaluation);
            frontier = new PqFrontier(comparator);
        }

        @Override
        public int heuristics(State state, State target) {
            int h = predictor.heuristics(state, target);
            int id;
            if (current == null) {
                id = root(((PuzzleBoard) state).cells(), h, -1, 0);
                rootId = id;
            } else {
                Integer reference = closed.get(state);
                id =
                        add(
                                identities.get(current),
                                ((PuzzleBoard) state).cells(),
                                current.getPathCost() + 1,
                                h,
                                reference == null ? "frontier" : "closed-pruned");
                nodes.get(id).reference = reference;
            }
            generated.put(state, id);
            return h;
        }

        @Override
        public boolean offer(Node node) {
            int id = node.getParent() == null ? rootId : generated.get(node.getState());
            identities.put(node, id);
            Node previous = accepted.get(node.getState());
            boolean offered = frontier.offer(node);
            if (previous != null) {
                Entry entry = nodes.get(id);
                entry.status = offered ? "replaced" : "frontier-pruned";
                entry.reference = identities.get(previous);
            }
            if (offered) accepted.put(node.getState(), node);
            return offered;
        }

        @Override
        public Node poll() {
            current = frontier.poll();
            if (current != null) {
                accepted.remove(current.getState());
                int id = identities.get(current);
                boolean found = current.getState().equals(goal);
                visit(id, found, -1, 0);
                if (!found) closed.put(current.getState(), id);
                generated.clear();
            }
            return current;
        }

        @Override
        public void clear() {
            frontier.clear();
            accepted.clear();
            closed.clear();
            generated.clear();
            current = null;
        }

        @Override
        public int size() {
            return frontier.size();
        }

        @Override
        public boolean isEmpty() {
            return frontier.isEmpty();
        }

        @Override
        public boolean contains(Node node) {
            return frontier.contains(node);
        }
    }

    List<byte[]> solution() {
        if (finalGoal < 0) throw new IllegalStateException("搜索轨迹缺少目标帧");
        List<byte[]> boards = new ArrayList<>();
        for (int id : solutionIds()) boards.add(nodes.get(id).board);
        return boards;
    }

    private List<Integer> solutionIds() {
        List<Integer> result = new ArrayList<>();
        for (int id = finalGoal; id >= 0; id = nodes.get(id).parent) result.add(id);
        java.util.Collections.reverse(result);
        return result;
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

    void writeJson(Writer out) throws IOException {
        out.write("{\"complete\":true,\"nodes\":[");
        for (Entry node : nodes) {
            if (node.id > 0) out.write(',');
            out.write(
                    "{\"id\":"
                            + node.id
                            + ",\"parent\":"
                            + (node.parent < 0 ? "null" : node.parent)
                            + ",\"board\":"
                            + java.util.Arrays.toString(node.board)
                            + ",\"g\":"
                            + node.g
                            + ",\"h\":"
                            + node.h
                            + ",\"f\":"
                            + (node.g + node.h)
                            + ",\"status\":"
                            + SolutionExporter.quote(node.status));
            if (node.reference != null) out.write(",\"reference\":" + node.reference);
            out.write('}');
        }
        out.write("],\"frames\":[");
        boolean first = true;
        for (Frame frame : frames) {
            if (!first) out.write(',');
            first = false;
            out.write(
                    "{\"current\":"
                            + frame.current
                            + ",\"children\":"
                            + frame.children
                            + ",\"found\":"
                            + frame.found
                            + ",\"iteration\":"
                            + frame.iteration);
            if (frame.bound >= 0) out.write(",\"bound\":" + frame.bound);
            out.write('}');
        }
        out.write("],\"solution\":" + solutionIds() + "}");
    }
}
