package stud.problem.pathfinding;

import static core.solver.algorithm.heuristic.HeuristicType.*;
import static stud.problem.pathfinding.Direction.ROOT2;
import static stud.problem.pathfinding.Direction.SCALE;

import core.problem.Action;
import core.problem.State;
import core.solver.algorithm.heuristic.HeuristicType;
import core.solver.algorithm.heuristic.Predictor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;

/** 寻路问题的状态，表示机器人所在的位置 */
public class Position extends State {

    // 机器人所在的行和列
    private final int row;
    private final int col;

    public Position(int row, int col) {
        this.row = row;
        this.col = col;
    }

    @Override
    public void draw() {
        System.out.println(this);
    }

    /**
     * 采用某个动作后到达的下一个状态。
     *
     * @param action 当前状态下可行的动作
     * @return 下一个状态
     */
    @Override
    public State next(Action action) {
        Direction dir = ((Move) action).getDirection();
        int[] offsets = Direction.offset(dir);

        int col = getCol() + offsets[0];
        int row = getRow() + offsets[1];
        return new Position(row, col);
    }

    @Override
    public Iterable<? extends Action> actions() {
        Collection<Move> moves = new ArrayList<>();
        for (Direction d : Direction.EIGHT_DIRECTIONS) moves.add(new Move(d));
        return moves;
    }

    // 启发函数类型到实现方法的映射
    private static final EnumMap<HeuristicType, Predictor> predictors =
            new EnumMap<>(HeuristicType.class);

    static {
        predictors.put(ZERO, (state, goal) -> 0);
        predictors.put(PF_EUCLID, (state, goal) -> ((Position) state).euclid((Position) goal));
        predictors.put(
                PF_MANHATTAN, (state, goal) -> ((Position) state).manhattan((Position) goal));
        predictors.put(PF_GRID, (state, goal) -> ((Position) state).gridDistance((Position) goal));
    }

    public static Predictor predictor(HeuristicType type) {
        return predictors.get(type);
    }

    // 两个点之间的网格距离，尽量走对角线
    private int gridDistance(Position goal) {
        int width = Math.abs(this.col - goal.col);
        int height = Math.abs(this.row - goal.row);

        return Math.abs(width - height) * SCALE + Math.min(width, height) * (int) (SCALE * ROOT2);
    }

    // 两个点之间的曼哈顿距离，再乘以 SCALE
    private int manhattan(Position goal) {
        return (Math.abs(this.row - goal.row) + Math.abs(this.col - goal.col)) * SCALE;
    }

    // 两个点之间的欧几里得距离
    private int euclid(Position goal) {
        int width = this.col - goal.col;
        int height = this.row - goal.row;
        return (int) Math.sqrt(height * height + width * width) * SCALE;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) return true;

        if (obj instanceof Position) {
            Position another = (Position) obj;
            return this.row == another.row && this.col == another.col;
        }
        return false;
    }

    @Override
    public int hashCode() {
        return row << 3 | col;
    }

    @Override
    public String toString() {
        return "(" + row + "," + col + ")";
    }

    public int getRow() {
        return row;
    }

    public int getCol() {
        return col;
    }
}
