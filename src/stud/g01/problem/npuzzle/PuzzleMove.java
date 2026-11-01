package stud.g01.problem.npuzzle;

import core.problem.Action;

/**
 * N-Puzzle 的移动动作，表示空格（0）朝某个方向移动一格，即与该方向相邻的数字块交换位置。 例如 UP 表示空格与其正上方的数字块互换。
 *
 * <p>四个方向各提供一个不可变单例，避免 {@code actions()} 每次调用都重新分配对象。
 */
public class PuzzleMove extends Action {

    /** 空格移动的方向 */
    public enum Direction {
        UP,
        DOWN,
        LEFT,
        RIGHT
    }

    /** 四个方向的动作单例 */
    public static final PuzzleMove UP = new PuzzleMove(Direction.UP);

    public static final PuzzleMove DOWN = new PuzzleMove(Direction.DOWN);
    public static final PuzzleMove LEFT = new PuzzleMove(Direction.LEFT);
    public static final PuzzleMove RIGHT = new PuzzleMove(Direction.RIGHT);

    private final Direction direction;

    /**
     * 私有构造，保证每个方向的动作全局只有一份实例
     *
     * @param direction 移动方向
     */
    private PuzzleMove(Direction direction) {
        this.direction = direction;
    }

    /**
     * @return 移动方向
     */
    public Direction direction() {
        return direction;
    }

    /** 在控制台打印方向符号 */
    @Override
    public void draw() {
        System.out.println(this);
    }

    /**
     * 每移动一步的代价恒为 1
     *
     * @return 单步代价 1
     */
    @Override
    public int stepCost() {
        return 1;
    }

    /**
     * @return 方向符号：U/D/L/R
     */
    @Override
    public String toString() {
        switch (direction) {
            case UP:
                return "U";
            case DOWN:
                return "D";
            case LEFT:
                return "L";
            default:
                return "R";
        }
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj instanceof PuzzleMove) {
            return this.direction == ((PuzzleMove) obj).direction;
        }
        return false;
    }

    @Override
    public int hashCode() {
        return direction.hashCode();
    }
}
