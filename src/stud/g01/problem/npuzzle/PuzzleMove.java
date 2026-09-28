package stud.g01.problem.npuzzle;

import core.problem.Action;

import java.util.Objects;

/**
 * N-Puzzle 的一次移动，方向表示空格的移动方向。
 */
public final class PuzzleMove extends Action {
    private final PuzzleDirection direction;

    public PuzzleMove(PuzzleDirection direction) {
        this.direction = Objects.requireNonNull(direction, "direction must not be null");
    }

    public PuzzleDirection getDirection() {
        return direction;
    }

    @Override
    public void draw() {
        System.out.println(this);
    }

    @Override
    public int stepCost() {
        return 1;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof PuzzleMove)) {
            return false;
        }
        PuzzleMove other = (PuzzleMove) obj;
        return direction == other.direction;
    }

    @Override
    public int hashCode() {
        return direction.hashCode();
    }

    @Override
    public String toString() {
        return "(#, " + direction.displayName() + ")";
    }
}
