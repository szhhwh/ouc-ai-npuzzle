package stud.g01.experiment;

import core.solver.algorithm.heuristic.HeuristicType;
import core.solver.algorithm.heuristic.Predictor;
import core.solver.queue.Node;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Deque;
import stud.g01.problem.npuzzle.NPuzzleIdaStar;
import stud.g01.problem.npuzzle.NPuzzleProblem;
import stud.g01.problem.npuzzle.PuzzleBoard;
import stud.g01.problem.npuzzle.PuzzleHeuristics;

/** Persistent reference solver; protocol consists of one 16-integer board per line. */
public final class SampleCertifier {
    public static void main(String[] args) throws Exception {
        PrintWriter protocol = new PrintWriter(System.out, true, StandardCharsets.UTF_8);
        System.setOut(System.err);
        byte[] goalCells = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 0};
        PuzzleBoard goal = new PuzzleBoard(goalCells, 4);
        Predictor pdb = PuzzleHeuristics.predictor(HeuristicType.DISJOINT_PATTERN);
        Predictor regularPdb = (state, target) -> pdb.heuristics(state, target);
        NPuzzleIdaStar solver = new NPuzzleIdaStar(regularPdb);
        byte[] warm = goalCells.clone();
        warm[14] = 0;
        warm[15] = 15;
        solver.search(new NPuzzleProblem(new PuzzleBoard(warm, 4), goal, 4));
        protocol.println("READY");
        try (BufferedReader input =
                new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            for (String line; (line = input.readLine()) != null; ) {
                try {
                    String[] parts = line.trim().split("\\s+");
                    if (parts.length != 16) throw new IllegalArgumentException("expected 16 cells");
                    byte[] cells = new byte[16];
                    int seen = 0;
                    for (int i = 0; i < 16; i++) {
                        int value = Integer.parseInt(parts[i]);
                        if (value < 0 || value > 15 || (seen & (1 << value)) != 0)
                            throw new IllegalArgumentException("invalid permutation");
                        seen |= 1 << value;
                        cells[i] = (byte) value;
                    }
                    Deque<Node> path =
                            solver.search(new NPuzzleProblem(new PuzzleBoard(cells, 4), goal, 4));
                    if (path == null) throw new IllegalStateException("no solution");
                    PuzzleBoard previous = new PuzzleBoard(cells, 4);
                    for (Node node : path) {
                        PuzzleBoard next = (PuzzleBoard) node.getState();
                        int a = previous.blank(), b = next.blank();
                        if (Math.abs(a / 4 - b / 4) + Math.abs(a % 4 - b % 4) != 1)
                            throw new IllegalStateException("illegal path");
                        byte[] expected = previous.cells().clone();
                        expected[a] = expected[b];
                        expected[b] = 0;
                        if (!java.util.Arrays.equals(expected, next.cells()))
                            throw new IllegalStateException("illegal path");
                        previous = next;
                    }
                    if (!previous.equals(goal))
                        throw new IllegalStateException("path does not reach goal");
                    protocol.println("OK " + path.size());
                } catch (RuntimeException e) {
                    protocol.println(
                            "ERROR " + e.getClass().getSimpleName() + " " + e.getMessage());
                }
            }
        }
    }
}
