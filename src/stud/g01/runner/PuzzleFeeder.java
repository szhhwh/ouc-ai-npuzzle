package stud.g01.runner;

import core.problem.Problem;
import core.runner.EngineFeeder;
import core.solver.algorithm.heuristic.HeuristicType;
import core.solver.algorithm.heuristic.Predictor;
import core.solver.queue.EvaluationType;
import core.solver.queue.Frontier;
import core.solver.queue.Node;
import java.util.ArrayList;
import stud.g01.problem.npuzzle.NPuzzleProblem;
import stud.g01.problem.npuzzle.PuzzleBoard;
import stud.queue.ListFrontier;

/** 从课程文本格式构造 N-Puzzle 问题和搜索所需组件。 */
public final class PuzzleFeeder extends EngineFeeder {
    @Override
    public ArrayList<Problem> getProblems(ArrayList<String> problemLines) {
        if (problemLines == null) {
            throw new IllegalArgumentException("problem lines must not be null");
        }
        ArrayList<Problem> problems = new ArrayList<>();
        for (int lineIndex = 0; lineIndex < problemLines.size(); lineIndex++) {
            String line = problemLines.get(lineIndex).trim();
            if (line.isEmpty()) {
                continue;
            }
            problems.add(parseProblem(line, lineIndex + 1));
        }
        return problems;
    }

    @Override
    public Frontier getFrontier(EvaluationType type) {
        if (type == null) {
            throw new IllegalArgumentException("evaluation type must not be null");
        }
        return new ListFrontier(Node.evaluator(type));
    }

    @Override
    public Predictor getPredictor(HeuristicType type) {
        return PuzzleBoard.predictor(type);
    }

    private NPuzzleProblem parseProblem(String line, int lineNumber) {
        String[] fields = line.split("\\s+");
        int size = parseInteger(fields[0], lineNumber, 1);
        if (size < 2) {
            throw new IllegalArgumentException(
                    "line " + lineNumber + ": puzzle size must be at least 2");
        }

        int cellCount;
        try {
            cellCount = Math.multiplyExact(size, size);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(
                    "line " + lineNumber + ": puzzle size is too large: " + size, e);
        }
        int expectedFields = 1 + 2 * cellCount;
        if (fields.length != expectedFields) {
            throw new IllegalArgumentException(
                    "line "
                            + lineNumber
                            + ": expected "
                            + expectedFields
                            + " integers, but got "
                            + fields.length);
        }

        int[] initialTiles = new int[cellCount];
        int[] goalTiles = new int[cellCount];
        for (int i = 0; i < cellCount; i++) {
            initialTiles[i] = parseInteger(fields[i + 1], lineNumber, i + 2);
            goalTiles[i] = parseInteger(fields[cellCount + i + 1], lineNumber, cellCount + i + 2);
        }

        PuzzleBoard initial = new PuzzleBoard(size, initialTiles);
        PuzzleBoard goal = new PuzzleBoard(size, goalTiles);
        return new NPuzzleProblem(initial, goal, size);
    }

    private int parseInteger(String value, int lineNumber, int fieldNumber) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "line "
                            + lineNumber
                            + ", field "
                            + fieldNumber
                            + ": expected an integer, but got '"
                            + value
                            + "'",
                    e);
        }
    }
}
