package stud.g01.problem.npuzzle;

import core.problem.Action;
import core.problem.Problem;
import core.problem.State;
import core.solver.queue.Node;
import java.util.Deque;

/**
 * N-Puzzle 数字华容道问题。
 *
 * <p>定义了初始盘面、目标盘面、单步代价、动作可行性判定、可解性检验以及解路径展示。
 */
public class NPuzzleProblem extends Problem {

    /**
     * 构造一个 N-Puzzle 问题实例。
     *
     * @param initialState 初始状态，必须为指定边长的 PuzzleBoard
     * @param goal 目标状态，必须为指定边长的 PuzzleBoard
     * @param size 棋盘边长，如 3 阶为 3，4 阶为 4
     * @throws IllegalArgumentException 当状态不是合法边长的 PuzzleBoard 时抛出
     */
    public NPuzzleProblem(State initialState, State goal, int size) {
        super(requireBoard(initialState, size), requireBoard(goal, size), size);
    }

    /**
     * 校验并转换盘面状态类型。
     *
     * @param state 待检验的状态对象
     * @param size 期望的棋盘边长
     * @return 转换后的 PuzzleBoard 实例
     * @throws IllegalArgumentException 当 state 不是指定边长的 PuzzleBoard 时抛出
     */
    private static PuzzleBoard requireBoard(State state, int size) {
        if (!(state instanceof PuzzleBoard board) || board.size() != size) {
            throw new IllegalArgumentException("Expected a PuzzleBoard of size " + size);
        }
        return board;
    }

    /**
     * 获取目标盘面。
     *
     * @return 目标盘面实例
     */
    public PuzzleBoard goalBoard() {
        return (PuzzleBoard) goal;
    }

    /**
     * 判断当前问题实例是否可解，对任意合法目标盘面均成立。
     *
     * <p>对于 3 阶与 4 阶棋盘，利用预计算的排列奇偶性与空格网格染色奇偶性进行快速判定； 对于其它规模的棋盘，通过计算初始盘面相对于目标排名的逆序数与空格行差进行判定。
     *
     * @return 可解返回 true，无解返回 false
     */
    @Override
    public boolean solvable() {
        PuzzleBoard init = (PuzzleBoard) initialState;
        PuzzleBoard goalBoard = (PuzzleBoard) goal;
        int n = init.size();
        // 3 阶与 4 阶用排列奇偶性与空格网格染色的守恒量常数时间判定
        if (n <= 4) {
            int initialColour = init.blank() / n + init.blank() % n;
            int goalColour = goalBoard.blank() / n + goalBoard.blank() % n;
            // 每次移动同时翻转全排列奇偶性与空格坐标和奇偶性，
            // 因此“排列奇偶性 xor 空格染色奇偶性”是可达性不变量。
            return ((init.permutationParity()
                                    ^ goalBoard.permutationParity()
                                    ^ initialColour
                                    ^ goalColour)
                            & 1)
                    == 0;
        }

        // 更大规模用逆序数加空格行差的经典判据
        int[] rank = new int[n * n];
        int r = 0;
        for (byte v : goalBoard.cellsInternal()) {
            if (v != 0) rank[v] = r++;
        }

        byte[] cells = init.cellsInternal();
        int inv = 0;
        for (int i = 0; i < cells.length; i++) {
            if (cells[i] == 0) continue;
            for (int j = i + 1; j < cells.length; j++) {
                if (cells[j] != 0 && rank[cells[i]] > rank[cells[j]]) inv++;
            }
        }

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
        return state instanceof PuzzleBoard board
                && action instanceof PuzzleMove move
                && board.canMove(move);
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
