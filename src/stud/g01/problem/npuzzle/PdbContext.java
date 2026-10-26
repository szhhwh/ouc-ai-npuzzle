package stud.g01.problem.npuzzle;

/** 一次搜索使用的增量 PDB 状态，管理盘面、编码和启发值。 */
abstract class PdbContext {

    final int cells;
    final int[][] neighbors;
    final byte[] board;
    private final long[] shifts;
    int blank;
    long code;

    int directHeuristic, symmetricHeuristic;
    boolean symmetric;

    PdbContext(PuzzleBoard initial) {
        int size = initial.size();
        cells = size * size;
        neighbors = PuzzleNeighbors.forSize(size);
        shifts = new long[cells];
        for (int i = 0; i < cells; i++) {
            shifts[i] = 4L * (cells - 1 - i);
        }
        board = initial.cells().clone();
        blank = initial.blank();
        for (byte tile : board) {
            code = (code << 4) | tile;
        }
    }

    /** 当前启发值，取直接查询和对称查询的较大值。 */
    final int heuristic() {
        return symmetric ? Math.max(directHeuristic, symmetricHeuristic) : directHeuristic;
    }

    /** 分组覆盖全部数字块，直接查询为 0 时所有数字块已就位。 */
    final boolean isGoal() {
        return directHeuristic == 0;
    }

    /** Reused per depth and neighbor: both the prepared child and its undo data. */
    static final class Move {
        int from, to, tile, group, symmetricGroup;
        int oldIndex, index, oldSymmetricIndex, symmetricIndex;
        int oldValue, value, oldSymmetricValue, symmetricValue;
        int oldDirect, direct, oldSymmetric, symmetric, heuristic, limit;
        int pdbPreparedMask;
        long oldCode, code;
        int[] groups, oldIndices, indices, oldValues, values;
        int[] symmetricGroups, oldSymmetricIndices, symmetricIndices;
        int[] oldSymmetricValues, symmetricValues;

        void ensurePdbCapacity(int count) {
            if (groups != null && groups.length == count) return;
            groups = new int[count];
            oldIndices = new int[count];
            indices = new int[count];
            oldValues = new int[count];
            values = new int[count];
            symmetricGroups = new int[count];
            oldSymmetricIndices = new int[count];
            symmetricIndices = new int[count];
            oldSymmetricValues = new int[count];
            symmetricValues = new int[count];
        }
    }

    /** Prepare once, after cycle pruning; applying and undoing never query the PDB. */
    final int prepare(int target, long childCode, Move move) {
        return prepare(target, childCode, move, Integer.MAX_VALUE);
    }

    final int prepare(int target, long childCode, Move move, int limit) {
        move.from = blank;
        move.to = target;
        move.tile = board[target];
        move.oldCode = code;
        move.code = childCode;
        move.oldDirect = directHeuristic;
        move.oldSymmetric = symmetricHeuristic;
        move.limit = limit;
        prepareValues(move);
        move.heuristic = symmetric ? Math.max(move.direct, move.symmetric) : move.direct;
        return move.heuristic;
    }

    abstract void prepareValues(Move move);

    abstract void updateValues(Move move, boolean undo);

    final void apply(Move move) {
        updateValues(move, false);
        board[move.from] = (byte) move.tile;
        board[move.to] = 0;
        blank = move.to;
        code = move.code;
        directHeuristic = move.direct;
        symmetricHeuristic = move.symmetric;
    }

    final void undo(Move move) {
        updateValues(move, true);
        board[move.to] = (byte) move.tile;
        board[move.from] = 0;
        blank = move.from;
        code = move.oldCode;
        directHeuristic = move.oldDirect;
        symmetricHeuristic = move.oldSymmetric;
    }

    /** 预估移动后的编码，不修改盘面，供搜索器做路径环检测。 */
    final long codeAfterMove(int target) {
        byte tile = board[target];
        return code + ((long) tile << shifts[blank]) - ((long) tile << shifts[target]);
    }
}
