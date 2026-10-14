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

    /** 预估空格移动到相邻格 target 后的启发值，不修改任何状态。 路径深度和 f 值由搜索器计算。 */
    abstract int heuristicAfterMove(int target);

    /** 空格移动到相邻格 target，同时更新盘面、编码和全部增量数据。 调用前记录原空格位置；回溯时 apply(原空格位置) 恢复移动前的状态。 */
    abstract void apply(int target);

    /** 预估移动后的编码，不修改盘面，供搜索器做路径环检测。 */
    final long codeAfterMove(int target) {
        byte tile = board[target];
        return code + ((long) tile << shifts[blank]) - ((long) tile << shifts[target]);
    }

    /** 后端更新启发值和索引后，用此方法统一完成盘面交换。 */
    final void swapBlank(int target) {
        code = codeAfterMove(target);
        board[blank] = board[target];
        board[target] = 0;
        blank = target;
    }
}
