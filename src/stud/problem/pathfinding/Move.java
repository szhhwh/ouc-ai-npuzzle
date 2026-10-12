package stud.problem.pathfinding;

import core.problem.Action;

/** 寻路的移动动作，可以是四个方向或八个方向 */
public class Move extends Action {

    private final Direction direction;

    public Move(Direction direction) {
        this.direction = direction;
    }

    public Direction getDirection() {
        return direction;
    }

    @Override
    public void draw() {
        System.out.println(this);
    }

    @Override
    public int stepCost() {
        return Direction.cost(direction);
    }

    @Override
    public String toString() {
        return direction.name();
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) return true;

        if (obj instanceof Move) {
            Move another = (Move) obj;
            return this.direction.equals(another.direction);
        }
        return false;
    }

    /** equals 只比较 direction，hashCode 也要用 direction 计算 */
    @Override
    public int hashCode() {
        return direction.hashCode();
    }
}
