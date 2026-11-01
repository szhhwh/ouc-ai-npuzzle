package stud.g01.problem.npuzzle;

import core.problem.Action;
import core.problem.Problem;
import core.problem.State;
import core.solver.queue.Node;
import java.util.Deque;

/** N-Puzzle 数字华容道问题 */
public class NPuzzleProblem extends Problem {

    public NPuzzleProblem(State initialState, State goal) {
        super(initialState, goal);
    }

    public NPuzzleProblem(State initialState, State goal, int size) {
        super(initialState, goal, size);
    }

    /**
     * 取出目标盘面。
     *
     * @return 目标盘面
     */
    public PuzzleBoard goalBoard() {
        return (PuzzleBoard) goal;
    }

    /**
     * 判断问题是否可解，对任意目标盘面都成立。
     *
     * @return 可解返回 true，否则返回 false
     */
    @Override
    public boolean solvable() {
        PuzzleBoard init = (PuzzleBoard) initialState;
        PuzzleBoard goalBoard = (PuzzleBoard) goal;
        int n = init.size();
        if (n <= 4) {
            // Every move flips both full-permutation parity and blank-square colour.
            int initialColour = init.blank() / n + init.blank() % n;
            int goalColour = goalBoard.blank() / n + goalBoard.blank() % n;
            return ((init.permutationParity()
                                    ^ goalBoard.permutationParity()
                                    ^ initialColour
                                    ^ goalColour)
                            & 1)
                    == 0;
        }

        // rank[v] 是数字 v 在目标盘面中的排名
        int[] rank = new int[n * n];
        int r = 0;
        for (byte v : goalBoard.cells()) {
            if (v != 0) rank[v] = r++;
        }

        // 数初始盘面排名序列的逆序数
        byte[] cells = init.cells();
        int inv = 0;
        for (int i = 0; i < cells.length; i++) {
            if (cells[i] == 0) continue;
            for (int j = i + 1; j < cells.length; j++) {
                if (cells[j] != 0 && rank[cells[i]] > rank[cells[j]]) inv++;
            }
        }

        // 初始空格与目标空格的行号之差
        int rowDiff = Math.abs(init.blank() / n - goalBoard.blank() / n);

        return (inv + (n + 1) * rowDiff) % 2 == 0;
    }

    /**
     * 计算一步移动的代价，N-Puzzle 中任何可行移动的代价都是 1。
     *
     * @param state 当前状态
     * @param action 进入当前状态所用的动作
     * @return 动作的代价，恒为 1
     */
    @Override
    public int stepCost(State state, Action action) {
        return action.stepCost();
    }

    /**
     * 判断动作是否可行，即空格朝该方向移动一格后是否还在棋盘内。
     *
     * @param state 当前状态
     * @param action 当前状态下要判断的动作
     * @return 可行返回 true，否则返回 false
     */
    @Override
    public boolean applicable(State state, Action action) {
        PuzzleBoard board = (PuzzleBoard) state;
        int n = board.size();
        int blank = board.blank();

        switch (((PuzzleMove) action).direction()) {
            case UP:
                return blank >= n;
            case DOWN:
                return blank < n * (n - 1);
            case LEFT:
                return blank % n != 0;
            case RIGHT:
                return blank % n != n - 1;
            default:
                return false;
        }
    }

    /**
     * 打印解路径，依次输出初始盘面、每一步的动作和移动后的盘面、总步数。
     *
     * @param path 解路径，按移动先后排列
     */
    @Override
    public void showSolution(Deque<Node> path) {
        System.out.println("初始状态：");
        initialState.draw();

        int step = 0;
        for (Node node : path) {
            System.out.println("第 " + (++step) + " 步，空格移动：" + node.getAction());
            node.getState().draw();
        }
        System.out.println("总步数：" + path.size());
    }
}
