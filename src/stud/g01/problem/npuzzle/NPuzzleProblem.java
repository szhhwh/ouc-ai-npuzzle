package stud.g01.problem.npuzzle;

import core.problem.Action;
import core.problem.Problem;
import core.problem.State;
import core.solver.queue.Node;

import java.util.Deque;

/**
 * N-Puzzle 数字华容道问题
 */
public class NPuzzleProblem extends Problem {

    public NPuzzleProblem(State initialState, State goal) {
        super(initialState, goal);
    }

    public NPuzzleProblem(State initialState, State goal, int size) {
        super(initialState, goal, size);
    }

    /**
     * 暴露目标盘面给同包的专用求解器（{@link NPuzzleIdaStar}）：
     * Problem 的 goal 字段是 protected，包外无法直接读取。
     *
     * @return 目标盘面
     */
    public PuzzleBoard goalBoard() {
        return (PuzzleBoard) goal;
    }

    /**
     * 通用可解性判定：相对任意目标盘面（不限于标准目标）的奇偶性检验。
     *
     * 记 inv 为初始盘面（去掉空格）的排名序列的逆序数——把初始盘面行主序序列中的
     * 每个非零值 v 映射为它在目标盘面行主序非零序列中的排名即得该序列；
     * 记 rowDiff 为初始空格行号与目标空格行号（均为 0-based）之差的绝对值。
     *
     * 空格每移动一格，inv + (size+1)*rowDiff 的奇偶性不变，而目标状态自身该量为 0+0，
     * 故它是移动不变量，且：
     *     可解 ⇔ inv + (size+1)*rowDiff 为偶数
     * 即：
     *     奇数阶（3×3 等，size+1 为偶数）⇔ inv 为偶数（经典结论：逆序数奇偶不变）；
     *     偶数阶（4×4 等，size+1 为奇数）⇔ inv + rowDiff 为偶数。
     *
     * 排名序列长度至多 size²-1 ≤ 15，用 O(n²) 的双重循环数逆序数足够。
     */
    @Override
    public boolean solvable() {
        PuzzleBoard init = (PuzzleBoard) initialState;
        PuzzleBoard goalBoard = (PuzzleBoard) goal;
        int n = init.size();

        //rank[v]：值 v 在目标盘面行主序非零序列中的排名（0-based）
        int[] rank = new int[n * n];
        int r = 0;
        for (byte v : goalBoard.cells()) {
            if (v != 0) rank[v] = r++;
        }

        //双重循环统计初始盘面排名序列的逆序数
        byte[] cells = init.cells();
        int inv = 0;
        for (int i = 0; i < cells.length; i++) {
            if (cells[i] == 0) continue;
            for (int j = i + 1; j < cells.length; j++) {
                if (cells[j] != 0 && rank[cells[i]] > rank[cells[j]]) inv++;
            }
        }

        //初始空格行号与目标空格行号之差
        int rowDiff = Math.abs(init.blank() / n - goalBoard.blank() / n);

        return (inv + (n + 1) * rowDiff) % 2 == 0;
    }

    /**
     * N-Puzzle 中任何可行的移动，单步耗散值都是 1
     *
     * @param state   当前状态
     * @param action  进入当前状态所采取的动作
     * @return 动作的耗散值（恒为 1）
     */
    @Override
    public int stepCost(State state, Action action) {
        return action.stepCost();
    }

    /**
     * 动作可行 ⇔ 空格朝 direction 方向移动一格后仍在棋盘内：
     * 空格不在第 0 行才能向上移，不在最后一行才能向下移；
     * 并用列号判断防止行首向左、行尾向右的跨界越界。
     *
     * @param state   当前状态
     * @param action  当前状态下所采用的动作
     * @return true：可用；false：不可用
     */
    @Override
    public boolean applicable(State state, Action action) {
        PuzzleBoard board = (PuzzleBoard) state;
        int n = board.size();
        int blank = board.blank();

        switch (((PuzzleMove) action).direction()) {
            case UP:    return blank >= n;          //空格不在第 0 行
            case DOWN:  return blank < n * (n - 1); //空格不在最后一行
            case LEFT:  return blank % n != 0;      //空格不在第 0 列
            case RIGHT: return blank % n != n - 1;  //空格不在最后一列
            default:    return false;
        }
    }

    /**
     * 解路径的可视化：先打印初始盘面，然后沿解路径逐步打印每步的动作符号与
     * 移动后的新盘面，最后打印总步数。path 为空（初始即目标）时只打印初始盘面与总步数 0。
     *
     * @param path 解路径（按移动先后次序排列）
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
