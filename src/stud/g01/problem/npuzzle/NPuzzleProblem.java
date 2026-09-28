package stud.g01.problem.npuzzle;

import core.problem.Action;
import core.problem.Problem;
import core.problem.State;
import core.solver.queue.Node;

import java.util.Deque;

/**
 * N-Puzzle 问题定义，包括可解性、动作合法性和解路径输出。
 */
public final class NPuzzleProblem extends Problem {
    public NPuzzleProblem(State initialState, State goal) {
        super(initialState, goal);
    }

    public NPuzzleProblem(State initialState, State goal, int size) {
        super(initialState, goal, size);
    }

    @Override
    public boolean solvable() {
        PuzzleBoard initial = board(initialState);
        PuzzleBoard target = board(goal);
        if (initial.getSize() != target.getSize()) {
            return false;
        }

        int initialParity = parityInvariant(initial);
        int goalParity = parityInvariant(target);
        return initialParity == goalParity;
    }

    @Override
    public int stepCost(State state, Action action) {
        if (!(state instanceof PuzzleBoard) || !(action instanceof PuzzleMove)) {
            throw new IllegalArgumentException("N-Puzzle requires PuzzleBoard and PuzzleMove");
        }
        return action.stepCost();
    }

    @Override
    public boolean applicable(State state, Action action) {
        if (!(state instanceof PuzzleBoard) || !(action instanceof PuzzleMove)) {
            return false;
        }
        PuzzleBoard board = (PuzzleBoard) state;
        PuzzleDirection direction = ((PuzzleMove) action).getDirection();
        return board.canMove(direction);
    }

    @Override
    public void showSolution(Deque<Node> path) {
        board(initialState).draw();
        for (Node node : path) {
            System.out.println("   ↓");
            System.out.println("   ↓-" + node.getAction());
            System.out.println("   ↓");
            node.getState().draw();
        }
    }

    private static PuzzleBoard board(State state) {
        if (!(state instanceof PuzzleBoard)) {
            throw new IllegalArgumentException("N-Puzzle requires PuzzleBoard states");
        }
        return (PuzzleBoard) state;
    }

    private static int parityInvariant(PuzzleBoard board) {
        int inversions = inversionCount(board.toArray());
        if (board.getSize() % 2 == 1) {
            return inversions % 2;
        }
        int blankRowFromBottom = board.getSize() - board.getBlankRow();
        return (inversions + blankRowFromBottom) % 2;
    }

    private static int inversionCount(int[] tiles) {
        int inversions = 0;
        for (int i = 0; i < tiles.length; i++) {
            if (tiles[i] == 0) {
                continue;
            }
            for (int j = i + 1; j < tiles.length; j++) {
                if (tiles[j] != 0 && tiles[i] > tiles[j]) {
                    inversions++;
                }
            }
        }
        return inversions;
    }
}
