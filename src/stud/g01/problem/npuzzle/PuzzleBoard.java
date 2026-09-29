package stud.g01.problem.npuzzle;

import core.problem.Action;
import core.problem.State;
import core.solver.algorithm.heuristic.HeuristicType;
import core.solver.algorithm.heuristic.Predictor;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * N-Puzzle 的不可变棋盘状态，数字 0 表示空格。
 */
public final class PuzzleBoard extends State {
    private static final List<PuzzleMove> ALL_MOVES = List.of(
            new PuzzleMove(PuzzleDirection.UP),
            new PuzzleMove(PuzzleDirection.RIGHT),
            new PuzzleMove(PuzzleDirection.DOWN),
            new PuzzleMove(PuzzleDirection.LEFT)
    );

    private final int size;
    private final int[] tiles;
    private final int blankIndex;

    public PuzzleBoard(int size, int[] tiles) {
        if (size < 2) {
            throw new IllegalArgumentException("puzzle size must be at least 2");
        }
        Objects.requireNonNull(tiles, "tiles must not be null");
        int cellCount;
        try {
            cellCount = Math.multiplyExact(size, size);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("puzzle size is too large: " + size, e);
        }
        if (tiles.length != cellCount) {
            throw new IllegalArgumentException(
                    "expected " + cellCount + " tiles for a " + size + "x" + size
                            + " board, but got " + tiles.length);
        }

        boolean[] seen = new boolean[cellCount];
        int foundBlank = -1;
        for (int i = 0; i < tiles.length; i++) {
            int tile = tiles[i];
            if (tile < 0 || tile >= cellCount) {
                throw new IllegalArgumentException(
                        "tile value out of range at index " + i + ": " + tile);
            }
            if (seen[tile]) {
                throw new IllegalArgumentException("duplicate tile value: " + tile);
            }
            seen[tile] = true;
            if (tile == 0) {
                foundBlank = i;
            }
        }

        this.size = size;
        this.tiles = Arrays.copyOf(tiles, tiles.length);
        this.blankIndex = foundBlank;
    }

    public int getSize() {
        return size;
    }

    public int getBlankRow() {
        return blankIndex / size;
    }

    public int getBlankCol() {
        return blankIndex % size;
    }

    public int tileAt(int row, int col) {
        checkPosition(row, col);
        return tiles[row * size + col];
    }

    public int[] toArray() {
        return Arrays.copyOf(tiles, tiles.length);
    }

    private void checkPosition(int row, int col) {
        if (row < 0 || row >= size || col < 0 || col >= size) {
            throw new IndexOutOfBoundsException(
                    "position outside board: row=" + row + ", col=" + col);
        }
    }

    @Override
    public void draw() {
        System.out.println(this);
    }

    @Override
    public State next(Action action) {
        if (!(action instanceof PuzzleMove)) {
            throw new IllegalArgumentException("N-Puzzle requires a PuzzleMove action");
        }
        PuzzleMove move = (PuzzleMove) action;
        int targetRow = getBlankRow() + move.getDirection().rowOffset();
        int targetCol = getBlankCol() + move.getDirection().colOffset();
        if (!isInside(targetRow, targetCol)) {
            throw new IllegalArgumentException(
                    "illegal move " + move.getDirection() + " from blank position ("
                            + getBlankRow() + "," + getBlankCol() + ")");
        }

        int targetIndex = targetRow * size + targetCol;
        int[] nextTiles = Arrays.copyOf(tiles, tiles.length);
        nextTiles[blankIndex] = nextTiles[targetIndex];
        nextTiles[targetIndex] = 0;
        return new PuzzleBoard(size, nextTiles);
    }

    @Override
    public Iterable<? extends Action> actions() {
        return ALL_MOVES;
    }

    public boolean canMove(PuzzleDirection direction) {
        Objects.requireNonNull(direction, "direction must not be null");
        int row = getBlankRow() + direction.rowOffset();
        int col = getBlankCol() + direction.colOffset();
        return isInside(row, col);
    }

    private boolean isInside(int row, int col) {
        return row >= 0 && row < size && col >= 0 && col < size;
    }

    public int misplacedTiles(PuzzleBoard goal) {
        requireSameSize(goal);
        int misplaced = 0;
        for (int i = 0; i < tiles.length; i++) {
            if (tiles[i] != 0 && tiles[i] != goal.tiles[i]) {
                misplaced++;
            }
        }
        return misplaced;
    }

    public int manhattanDistance(PuzzleBoard goal) {
        requireSameSize(goal);
        int[] goalIndexByTile = new int[tiles.length];
        for (int i = 0; i < goal.tiles.length; i++) {
            goalIndexByTile[goal.tiles[i]] = i;
        }

        int distance = 0;
        for (int i = 0; i < tiles.length; i++) {
            int tile = tiles[i];
            if (tile == 0) {
                continue;
            }
            int goalIndex = goalIndexByTile[tile];
            distance += Math.abs(i / size - goalIndex / size)
                    + Math.abs(i % size - goalIndex % size);
        }
        return distance;
    }

    private void requireSameSize(PuzzleBoard other) {
        Objects.requireNonNull(other, "goal board must not be null");
        if (size != other.size) {
            throw new IllegalArgumentException(
                    "board sizes differ: " + size + " and " + other.size);
        }
    }

    public static Predictor predictor(HeuristicType type) {
        Objects.requireNonNull(type, "heuristic type must not be null");
        switch (type) {
            case MISPLACED:
                return (state, goal) -> asBoard(state).misplacedTiles(asBoard(goal));
            case MANHATTAN:
                return (state, goal) -> asBoard(state).manhattanDistance(asBoard(goal));
            case DISJOINT_PATTERN:
                throw new UnsupportedOperationException(
                        "DISJOINT_PATTERN is not implemented in the foundation module");
            default:
                throw new IllegalArgumentException("unsupported N-Puzzle heuristic: " + type);
        }
    }

    private static PuzzleBoard asBoard(State state) {
        if (!(state instanceof PuzzleBoard)) {
            throw new IllegalArgumentException("expected PuzzleBoard state");
        }
        return (PuzzleBoard) state;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof PuzzleBoard)) {
            return false;
        }
        PuzzleBoard other = (PuzzleBoard) obj;
        return size == other.size && Arrays.equals(tiles, other.tiles);
    }

    @Override
    public int hashCode() {
        return 31 * size + Arrays.hashCode(tiles);
    }

    @Override
    public String toString() {
        int width = Integer.toString(tiles.length - 1).length();
        StringBuilder border = new StringBuilder("+");
        for (int i = 0; i < size; i++) {
            border.append("-".repeat(width + 2)).append('+');
        }

        StringBuilder result = new StringBuilder();
        for (int row = 0; row < size; row++) {
            result.append(border).append(System.lineSeparator()).append('|');
            for (int col = 0; col < size; col++) {
                int tile = tileAt(row, col);
                String value = tile == 0 ? "#" : Integer.toString(tile);
                result.append(' ')
                        .append(" ".repeat(width - value.length()))
                        .append(value)
                        .append(" |");
            }
            result.append(System.lineSeparator());
        }
        return result.append(border).toString();
    }
}
