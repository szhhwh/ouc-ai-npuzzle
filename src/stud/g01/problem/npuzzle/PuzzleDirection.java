package stud.g01.problem.npuzzle;

/**
 * N-Puzzle 中空格的移动方向。
 */
public enum PuzzleDirection {
    UP(-1, 0, "N"),
    RIGHT(0, 1, "E"),
    DOWN(1, 0, "S"),
    LEFT(0, -1, "W");

    private final int rowOffset;
    private final int colOffset;
    private final String displayName;

    PuzzleDirection(int rowOffset, int colOffset, String displayName) {
        this.rowOffset = rowOffset;
        this.colOffset = colOffset;
        this.displayName = displayName;
    }

    public int rowOffset() {
        return rowOffset;
    }

    public int colOffset() {
        return colOffset;
    }

    public String displayName() {
        return displayName;
    }
}
