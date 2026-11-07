package stud.problem.pathfinding;

import core.problem.Action;
import core.problem.Problem;
import core.problem.State;
import core.solver.queue.Node;
import java.util.Deque;

/** 寻路问题 */
public class PathFinding extends Problem {

    // 地图
    GridType[][] grids;

    public PathFinding(State initialState, State goal, int size) {
        super(initialState, goal, size);
        grids = new GridType[size][size];
    }

    public void setGrids(GridType[][] grids) {
        for (int i = 0; i < size; i++) {
            System.arraycopy(grids[i], 0, this.grids[i], 0, size);
        }
    }

    /**
     * 寻路问题是否有解。只有搜索才能判断，这里固定返回 true。
     *
     * @return 恒为 true
     */
    @Override
    public boolean solvable() {
        return true;
    }

    /**
     * 计算一步移动的代价，草地和平地的代价不同。
     *
     * @param state 当前状态
     * @param action 进入当前状态所用的动作
     * @return 考虑地面类型后的代价
     */
    @Override
    public int stepCost(State state, Action action) {
        Position position = (Position) state;
        GridType type = grids[position.getRow() - 1][position.getCol() - 1];
        return action.stepCost() * type.magnify();
    }

    @Override
    public boolean applicable(State state, Action action) {
        int[] offsets = Direction.offset(((Move) action).getDirection());
        int row = ((Position) state).getRow() + offsets[1];
        int col = ((Position) state).getCol() + offsets[0];
        return row > 0
                && row <= size
                && col > 0
                && col <= size
                && grids[row - 1][col - 1] != GridType.WALL;
    }

    @Override
    public void showSolution(Deque<Node> path) {
        System.out.println(initialState + "→" + goal);
        // 把地图转成字符数组
        char[][] grids = new char[size][];
        for (int i = 0; i < size; i++) {
            grids[i] = new char[size];
            for (int j = 0; j < size; j++) {
                grids[i][j] = this.grids[i][j].symbol();
            }
        }

        // 标出起点和终点
        int row = ((Position) initialState).getRow();
        int col = ((Position) initialState).getCol();
        grids[row - 1][col - 1] = '@';
        row = ((Position) goal).getRow();
        col = ((Position) goal).getCol();
        grids[row - 1][col - 1] = '√';

        // 把解路径上的方向符号写进字符数组
        for (Node node : path) {
            Position p = (Position) node.getParent().getState();
            Move move = (Move) node.getAction();
            Direction d = move.getDirection();
            grids[p.getRow() - 1][p.getCol() - 1] = d.symbol();
        }

        drawGrid(grids);
    }

    private void drawGrid(char[][] grids) {
        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                System.out.print(grids[i][j] + " ");
            }
            System.out.println();
        }
    }
}
