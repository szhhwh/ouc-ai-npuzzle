package stud.g01.problem.npuzzle;

import core.solver.algorithm.heuristic.Predictor;

/** 专用搜索的盘面、目标判定和可回退估值。 */
abstract class PuzzleSearchContext {
    final int cells;
    final int[][] neighbors;
    final byte[] board;
    private final int[] shifts;
    private final long goalCode;
    int blank;
    long code;

    PuzzleSearchContext(PuzzleBoard initial, byte[] goal) {
        int size = initial.size();
        cells = size * size;
        neighbors = PuzzleNeighbors.forSize(size);
        shifts = new int[cells];
        for (int i = 0; i < cells; i++) shifts[i] = 4 * (cells - 1 - i);
        board = initial.cells().clone();
        blank = initial.blank();
        code = encode(board);
        goalCode = encode(goal);
    }

    static PuzzleSearchContext create(PuzzleBoard initial, PuzzleBoard goal, Predictor predictor) {
        int size = initial.size();
        if (size != 3 && size != 4) {
            throw new UnsupportedOperationException("N-Puzzle 专用搜索仅支持 3/4 阶");
        }
        return predictor instanceof PuzzleHeuristic heuristic
                ? heuristic.newContext(initial, goal)
                : new Evaluated(initial, goal, predictor);
    }

    private static long encode(byte[] cells) {
        long value = 0;
        for (byte tile : cells) value = (value << 4) | tile;
        return value;
    }

    final boolean isGoal() {
        return code == goalCode;
    }

    abstract int heuristic();

    /** 每层复用候选移动及其估值回退数据。 */
    static class Move {
        int from, to, tile, traceId, heuristic;
        long oldCode, code;
    }

    abstract Move newMove();

    final int prepare(int target, long childCode, Move move) {
        return prepare(target, childCode, move, Integer.MAX_VALUE);
    }

    final int prepare(int target, long childCode, Move move, int limit) {
        move.from = blank;
        move.to = target;
        move.tile = board[target];
        move.oldCode = code;
        move.code = childCode;
        move.heuristic = prepareHeuristic(move, limit);
        return move.heuristic;
    }

    abstract int prepareHeuristic(Move move, int limit);

    abstract void updateHeuristic(Move move, boolean undo);

    final void apply(Move move) {
        updateHeuristic(move, false);
        board[move.from] = (byte) move.tile;
        board[move.to] = 0;
        blank = move.to;
        code = move.code;
    }

    final void undo(Move move) {
        updateHeuristic(move, true);
        board[move.to] = (byte) move.tile;
        board[move.from] = 0;
        blank = move.from;
        code = move.oldCode;
    }

    final long codeAfterMove(int target) {
        byte tile = board[target];
        return code + ((long) tile << shifts[blank]) - ((long) tile << shifts[target]);
    }

    private static final class Evaluated extends PuzzleSearchContext {
        private final PuzzleBoard goal;
        private final Predictor predictor;
        private int value;

        private static final class EvaluatedMove extends Move {
            int previous;
        }

        Evaluated(PuzzleBoard initial, PuzzleBoard goal, Predictor predictor) {
            super(initial, goal.cells());
            this.goal = goal;
            this.predictor = predictor;
            value = predictor.heuristics(initial, goal);
        }

        @Override
        int heuristic() {
            return value;
        }

        @Override
        Move newMove() {
            return new EvaluatedMove();
        }

        @Override
        int prepareHeuristic(Move move, int limit) {
            ((EvaluatedMove) move).previous = value;
            board[move.from] = (byte) move.tile;
            board[move.to] = 0;
            int h = predictor.heuristics(new PuzzleBoard(board, goal.size()), goal);
            board[move.to] = (byte) move.tile;
            board[move.from] = 0;
            return h;
        }

        @Override
        void updateHeuristic(Move move, boolean undo) {
            value = undo ? ((EvaluatedMove) move).previous : move.heuristic;
        }
    }
}
