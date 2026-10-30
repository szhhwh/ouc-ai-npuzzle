package stud.g01.runner;

import core.problem.State;
import core.solver.algorithm.heuristic.HeuristicType;
import core.solver.algorithm.heuristic.Predictor;
import core.solver.algorithm.searcher.BestFirstSearcher;
import core.solver.queue.EvaluationType;
import core.solver.queue.Frontier;
import core.solver.queue.Node;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import stud.g01.problem.npuzzle.NPuzzleProblem;
import stud.g01.problem.npuzzle.PuzzleBoard;

/** Records the actual course A* and project Frontier without changing either implementation. */
public final class SearchTraceExporter {
    private SearchTraceExporter() {}

    private static final class Entry {
        final int id, g, h;
        final Integer parent;
        final byte[] board;
        String status = "pending";
        Integer reference;

        Entry(int id, Integer parent, PuzzleBoard board, int g, int h) {
            this.id = id;
            this.parent = parent;
            this.board = board.cells().clone();
            this.g = g;
            this.h = h;
        }

        String json() {
            return "{\"id\":"
                    + id
                    + ",\"parent\":"
                    + parent
                    + ",\"board\":"
                    + Arrays.toString(board)
                    + ",\"g\":"
                    + g
                    + ",\"h\":"
                    + h
                    + ",\"f\":"
                    + (g + h)
                    + ",\"status\":\""
                    + status
                    + "\",\"reference\":"
                    + reference
                    + "}";
        }
    }

    private static final class Frame {
        final int current;
        final List<Integer> before;
        final List<Integer> children = new ArrayList<>();
        List<Integer> after = List.of();
        final boolean found;

        Frame(int current, List<Integer> before, boolean found) {
            this.current = current;
            this.before = before;
            this.found = found;
        }

        String json() {
            return "{\"current\":"
                    + current
                    + ",\"openBefore\":"
                    + before
                    + ",\"children\":"
                    + children
                    + ",\"openAfter\":"
                    + after
                    + ",\"found\":"
                    + found
                    + "}";
        }
    }

    /** Observer: all queue operations delegate unchanged to the real project queue. */
    private static final class RecordingFrontier implements Frontier {
        final Frontier delegate;
        final NPuzzleProblem problem;
        final int limit;
        final List<Entry> entries = new ArrayList<>();
        final List<Frame> frames = new ArrayList<>();
        final IdentityHashMap<Node, Entry> ids = new IdentityHashMap<>();
        final Map<State, Node> active = new HashMap<>();
        final Map<State, Integer> closed = new HashMap<>();
        final Map<State, Entry> pending = new HashMap<>();
        Entry current;
        boolean recording = true;

        RecordingFrontier(Frontier delegate, NPuzzleProblem problem, int limit) {
            this.delegate = delegate;
            this.problem = problem;
            this.limit = limit;
        }

        int observe(State state, int h) {
            if (!recording) return h;
            Entry entry =
                    new Entry(
                            entries.size(),
                            current == null ? null : current.id,
                            (PuzzleBoard) state,
                            current == null ? 0 : current.g + 1,
                            h);
            entries.add(entry);
            if (current != null) {
                frames.getLast().children.add(entry.id);
                if (closed.containsKey(state)) {
                    entry.status = "closed-pruned";
                    entry.reference = closed.get(state);
                }
            }
            pending.put(state, entry);
            return h;
        }

        List<Integer> snapshot() {
            Comparator<Node> evaluation = Node.evaluator(EvaluationType.FULL);
            return active.values().stream()
                    .sorted(evaluation.thenComparingInt(n -> ids.get(n).id))
                    .map(n -> ids.get(n).id)
                    .toList();
        }

        void finishFrame() {
            if (recording && !frames.isEmpty()) frames.getLast().after = snapshot();
        }

        @Override
        public Node poll() {
            finishFrame();
            Node winner = delegate.poll();
            if (frames.size() >= limit) recording = false;
            if (!recording || winner == null) return winner;
            List<Integer> before = snapshot();
            current = ids.get(winner);
            if (current == null) throw new IllegalStateException("Unrecorded queue node");
            for (Node candidate : active.values()) {
                if (Node.evaluator(EvaluationType.FULL).compare(candidate, winner) < 0)
                    throw new IllegalStateException("Queue did not return a minimum");
            }
            active.remove(winner.getState());
            boolean found = problem.goal(winner.getState());
            frames.add(new Frame(current.id, before, found));
            if (!found) closed.put(winner.getState(), current.id);
            pending.clear();
            return winner;
        }

        @Override
        public boolean offer(Node node) {
            boolean accepted = delegate.offer(node);
            if (!recording) return accepted;
            Entry entry = pending.get(node.getState());
            if (entry == null || entry.g != node.getPathCost() || entry.h != node.getHeuristic())
                throw new IllegalStateException("Prediction/queue observation mismatch");
            ids.put(node, entry);
            Node old = active.get(node.getState());
            if (old != null) entry.reference = ids.get(old).id;
            entry.status = accepted ? (old == null ? "frontier" : "replaced") : "duplicate-pruned";
            if (accepted) active.put(node.getState(), node);
            return accepted;
        }

        @Override
        public void clear() {
            delegate.clear();
            entries.clear();
            frames.clear();
            ids.clear();
            active.clear();
            closed.clear();
            pending.clear();
            current = null;
            recording = true;
        }

        @Override
        public int size() {
            return delegate.size();
        }

        @Override
        public boolean isEmpty() {
            return delegate.isEmpty();
        }

        @Override
        public boolean contains(Node node) {
            return delegate.contains(node);
        }
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 1)
            throw new IllegalArgumentException("Usage: SearchTraceExporter new-output.js");
        String revision = System.getProperty("npuzzle.revision", "unknown");
        if (!revision.matches("[0-9a-f]{40}(-dirty)?|unknown"))
            throw new IllegalArgumentException("Invalid revision");
        int[][] starts = {
            {1, 2, 3, 4, 5, 6, 0, 7, 8}, {1, 3, 6, 5, 0, 2, 4, 7, 8}, {5, 0, 8, 4, 2, 1, 7, 3, 6}
        };
        String[] names = {"入门 · 2 步", "分支探索 · 8 步", "课程实例 · 21 步"};
        List<String> traces = new ArrayList<>();
        PuzzleFeeder feeder = new PuzzleFeeder();
        for (int i = 0; i < starts.length; i++) {
            byte[] initial = new byte[9];
            for (int j = 0; j < 9; j++) initial[j] = (byte) starts[i][j];
            NPuzzleProblem problem =
                    new NPuzzleProblem(
                            new PuzzleBoard(initial, 3),
                            new PuzzleBoard(new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 0}, 3),
                            3);
            for (HeuristicType kind : List.of(HeuristicType.MANHATTAN, HeuristicType.MISPLACED)) {
                RecordingFrontier observer =
                        new RecordingFrontier(feeder.getFrontier(EvaluationType.FULL), problem, 80);
                Predictor predictor = feeder.getPredictor(kind);
                BestFirstSearcher recorded =
                        new BestFirstSearcher(
                                observer,
                                (state, goal) ->
                                        observer.observe(state, predictor.heuristics(state, goal)));
                Deque<Node> result = recorded.search(problem);
                observer.finishFrame();
                var reference = feeder.getAStar(kind);
                Deque<Node> baseline = reference.search(problem);
                if (result == null
                        || baseline == null
                        || result.size() != baseline.size()
                        || recorded.nodesGenerated() != reference.nodesGenerated()
                        || recorded.nodesExpanded() != reference.nodesExpanded()
                        || !result.stream()
                                .map(n -> n.getState().toString())
                                .toList()
                                .equals(
                                        baseline.stream()
                                                .map(n -> n.getState().toString())
                                                .toList()))
                    throw new IllegalStateException("Observer changed the actual search result");
                boolean found = observer.frames.getLast().found;
                List<Integer> solution = new ArrayList<>();
                if (found) {
                    Integer id = observer.frames.getLast().current;
                    while (id != null) {
                        solution.addFirst(id);
                        id = observer.entries.get(id).parent;
                    }
                }
                String nodes =
                        String.join(",", observer.entries.stream().map(Entry::json).toList());
                String frames =
                        String.join(",", observer.frames.stream().map(Frame::json).toList());
                traces.add(
                        "{\"preset\":"
                                + i
                                + ",\"name\":\""
                                + names[i]
                                + "\",\"heuristic\":\""
                                + kind
                                + "\",\"nodes\":["
                                + nodes
                                + "],\"frames\":["
                                + frames
                                + "],\"solution\":"
                                + solution
                                + ",\"truncated\":"
                                + !found
                                + ",\"limit\":80,\"referenceLength\":"
                                + result.size()
                                + "}");
                System.out.println(
                        "Verified actual Java trace: "
                                + names[i]
                                + " / "
                                + kind
                                + " / "
                                + observer.frames.size()
                                + " frames");
            }
        }
        String json =
                "{\"schemaVersion\":1,\"source\":\"Java BestFirstSearcher + project PqFrontier\",\"sourceRevision\":\""
                        + revision
                        + "\",\"verifiedAgainstUninstrumented\":true,\"traces\":["
                        + String.join(",", traces)
                        + "]}";
        Files.writeString(
                Path.of(args[0]),
                "window.NPuzzleJavaTraces = " + json + ";\n",
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW);
    }
}
