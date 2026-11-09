package stud.g01.problem.npuzzle;

import core.problem.Action;

/**
 * N-Puzzle 的移动动作，表示空格朝某个方向移动一格，即与该方向相邻的数字块交换位置。 例如 UP 表示空格与其正上方的数字块互换。
 *
 * <p>四个方向各提供一个不可变单例，避免调用 actions 方法时重复分配对象。
 */
public class PuzzleMove extends Action {

    /** 空格移动的方向枚举 */
    public enum Direction {
        UP,
        DOWN,
        LEFT,
        RIGHT
    }

    /** 向上移动空格的动作单例 */
    public static final PuzzleMove UP = new PuzzleMove(Direction.UP);

    /** 向下移动空格的动作单例 */
    public static final PuzzleMove DOWN = new PuzzleMove(Direction.DOWN);

    /** 向左移动空格的动作单例 */
    public static final PuzzleMove LEFT = new PuzzleMove(Direction.LEFT);

    /** 向右移动空格的动作单例 */
    public static final PuzzleMove RIGHT = new PuzzleMove(Direction.RIGHT);

    /** 当前动作对应的移动方向 */
    private final Direction direction;

    /**
     * 私有构造方法，保证每个方向的动作全局只有一份实例。
     *
     * @param direction 移动方向
     */
    private PuzzleMove(Direction direction) {
        this.direction = direction;
    }

    /**
     * 根据空格移动前后的两个相邻位置下标，推导出对应的移动动作。
     *
     * @param from 空格移动前的线性下标
     * @param to 空格移动后的线性下标
     * @param size 棋盘边长
     * @return 对应的移动动作
     */
    static PuzzleMove between(int from, int to, int size) {
        if (to == from - size) return UP;
        if (to == from + size) return DOWN;
        if (to == from - 1) return LEFT;
        return RIGHT;
    }

    /**
     * 获取移动方向。
     *
     * @return 移动方向枚举值
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
     * 每移动一步的代价恒为 1。
     *
     * @return 单步代价 1
     */
    @Override
    public int stepCost() {
        return 1;
    }

    /**
     * 返回方向对应的英文字母符号。
     *
     * @return 方向符号 U 或 D 或 L 或 R
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

    /**
     * 判断当前移动动作是否与目标对象相等。
     *
     * @param obj 待比较的目标对象
     * @return 若同为 PuzzleMove 且移动方向相同返回 true，否则返回 false
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj instanceof PuzzleMove) {
            return this.direction == ((PuzzleMove) obj).direction;
        }
        return false;
    }

    /**
     * 计算当前移动动作的哈希码。
     *
     * @return 移动方向枚举的哈希值
     */
    @Override
    public int hashCode() {
        return direction.hashCode();
    }
}
