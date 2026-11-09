package stud.g01.problem.npuzzle;

import core.solver.algorithm.heuristic.Predictor;

/**
 * N-Puzzle 专用高效搜索上下文抽象基类。
 *
 * <p>维护单份可变棋盘数组、空格位置、行主序紧凑编码以及目标判定逻辑， 支持高效应用移动与撤销移动，消除搜索热循环中的对象分配开销。
 */
abstract class PuzzleSearchContext {
    /** 棋盘格子总数，等于边长的平方 */
    final int cells;

    /** 预计算的各格子合法相邻格子查找表 */
    final int[][] neighbors;

    /** 当前可变盘面数组，0 表示空格 */
    final byte[] board;

    /** 各格子在 64 位行主序编码中的位偏移量查找表 */
    private final int[] shifts;

    /** 目标盘面的 64 位行主序编码 */
    private final long goalCode;

    /** 当前空格所在的线性下标 */
    int blank;

    /** 当前盘面的 64 位行主序编码 */
    long code;

    /**
     * 构造专用搜索上下文。
     *
     * @param initial 初始盘面
     * @param goal 目标盘面的数字数组
     */
    PuzzleSearchContext(PuzzleBoard initial, byte[] goal) {
        int size = initial.size();
        cells = size * size;
        neighbors = PuzzleNeighbors.forSize(size);
        shifts = new int[cells];
        for (int i = 0; i < cells; i++) shifts[i] = 4 * (cells - 1 - i);
        board = initial.cellsInternal().clone();
        blank = initial.blank();
        code = encode(board);
        goalCode = encode(goal);
    }

    /**
     * 根据初始状态、目标状态和预测器创建对应的专用搜索上下文。
     *
     * @param initial 初始盘面
     * @param goal 目标盘面
     * @param predictor 启发式预测器
     * @return 对应的 PuzzleSearchContext 实例
     * @throws UnsupportedOperationException 当棋盘边长不是 3 或 4 时抛出
     */
    static PuzzleSearchContext create(PuzzleBoard initial, PuzzleBoard goal, Predictor predictor) {
        PuzzleBoard.requireSameSize(initial, goal);
        int size = initial.size();
        if (size != 3 && size != 4) {
            throw new UnsupportedOperationException("N-Puzzle 专用搜索仅支持 3 阶与 4 阶");
        }
        return predictor instanceof PuzzleHeuristic heuristic
                ? heuristic.newContext(initial, goal)
                : new Evaluated(initial, goal, predictor);
    }

    /**
     * 将棋盘数组编码为 64 位整型，按行主序，每个格子占 4 位。
     *
     * @param cells 棋盘数组
     * @return 64 位行主序编码
     */
    private static long encode(byte[] cells) {
        long value = 0;
        for (byte tile : cells) value = (value << 4) | tile;
        return value;
    }

    /**
     * 判断当前搜索状态是否已达到目标状态。
     *
     * @return 若当前盘面编码等于目标盘面编码返回 true，否则返回 false
     */
    final boolean isGoal() {
        return code == goalCode;
    }

    /**
     * 获取当前状态的启发估计值。
     *
     * @return 当前状态的启发值
     */
    abstract int heuristic();

    /**
     * 搜索过程中候选移动及状态回退信息的记录类。
     *
     * <p>在搜索递归调用栈的每一层复用，避免在分支展开时频繁实例化移动对象。
     */
    static class Move {
        /** 移动前空格所在位置 */
        int from;

        /** 移动后空格到达位置，即被移动数字块的起始位置 */
        int to;

        /** 被移动的数字块的值 */
        int tile;

        /** 应用该移动后产生的后继状态启发值 */
        int heuristic;

        /** 是否仅为启发值的下界，用于提前剪枝时的标记 */
        boolean lowerBound;

        /** 观测器记录轨迹所用的树结点引用 */
        core.solver.queue.Node traceNode;

        /** 移动前的盘面编码，供回退使用 */
        long oldCode;

        /** 移动后的盘面编码 */
        long code;
    }

    /**
     * 创建一个新的移动信息对象实例。
     *
     * @return 新建的 Move 实例
     */
    abstract Move newMove();

    /**
     * 准备移动候选：填充移动参数并计算后继状态的启发值。
     *
     * @param target 空格的目标位置
     * @param childCode 移动后产生的后继盘面编码
     * @param move 用于存放移动数据的 Move 对象
     * @return 后继状态的启发值
     */
    final int prepare(int target, long childCode, Move move) {
        return prepare(target, childCode, move, Integer.MAX_VALUE);
    }

    /**
     * 准备带有估值上限的移动候选：填充移动参数并计算后继状态的启发值。
     *
     * @param target 空格的目标位置
     * @param childCode 移动后产生的后继盘面编码
     * @param move 用于存放移动数据的 Move 对象
     * @param limit 启发值评估上限，用于对称估值或提前剪枝
     * @return 后继状态的启发值
     */
    final int prepare(int target, long childCode, Move move, int limit) {
        move.from = blank;
        move.to = target;
        move.tile = board[target];
        move.oldCode = code;
        move.code = childCode;
        move.lowerBound = false;
        move.heuristic = prepareHeuristic(move, limit);
        return move.heuristic;
    }

    /**
     * 子类实现的具体后继状态启发值计算逻辑。
     *
     * @param move 候选移动对象
     * @param limit 启发值上限
     * @return 预估的后继状态启发值
     */
    abstract int prepareHeuristic(Move move, int limit);

    /**
     * 更新启发状态缓存，前进更新或回退恢复。
     *
     * @param move 移动对象
     * @param undo true 表示回退撤销，false 表示正向应用
     */
    abstract void updateHeuristic(Move move, boolean undo);

    /**
     * 在当前搜索上下文中应用指定移动：更新启发值、修改棋盘数组、更新空格位置及盘面编码。
     *
     * @param move 待应用的移动对象
     */
    final void apply(Move move) {
        updateHeuristic(move, false);
        board[move.from] = (byte) move.tile;
        board[move.to] = 0;
        blank = move.to;
        code = move.code;
    }

    /**
     * 回退撤销指定移动：将棋盘数组、空格位置、盘面编码及启发值恢复至移动前的状态。
     *
     * @param move 待撤销的移动对象
     */
    final void undo(Move move) {
        updateHeuristic(move, true);
        board[move.to] = (byte) move.tile;
        board[move.from] = 0;
        blank = move.from;
        code = move.oldCode;
    }

    /**
     * 计算空格移动到目标格子 target 后产生的新盘面 64 位行主序编码。
     *
     * <p>利用位位移公式直接通过加减算得增量，无需扫描整个数组。
     *
     * @param target 空格的目标位置下标
     * @return 移动后的盘面行主序编码
     */
    final long codeAfterMove(int target) {
        byte tile = board[target];
        return code + ((long) tile << shifts[blank]) - ((long) tile << shifts[target]);
    }

    /** 通用预测器的包装上下文：当启发式未实现专用的增量上下文接口时使用本类做适配。 */
    private static final class Evaluated extends PuzzleSearchContext {
        /** 目标棋盘状态 */
        private final PuzzleBoard goal;

        /** 通用启发预测器 */
        private final Predictor predictor;

        /** 当前盘面启发值 */
        private int value;

        /** 当前不可变棋盘状态对象 */
        private PuzzleBoard current;

        /** 携带不可变 PuzzleBoard 状态引用的移动对象 */
        private static final class EvaluatedMove extends Move {
            /** 移动前的启发值 */
            int previous;

            /** 移动前后的棋盘状态引用 */
            PuzzleBoard parent, child;
        }

        /**
         * 构造通用预测器包装上下文。
         *
         * @param initial 初始盘面
         * @param goal 目标盘面
         * @param predictor 启发式预测器
         */
        Evaluated(PuzzleBoard initial, PuzzleBoard goal, Predictor predictor) {
            super(initial, goal.cellsInternal());
            this.goal = goal;
            this.predictor = predictor;
            current = initial;
            value = predictor.heuristics(initial, goal);
        }

        @Override
        int heuristic() {
            return value;
        }

        @Override
        Move newMove() {
            return new EvaluatedMove();
        }

        @Override
        int prepareHeuristic(Move move, int limit) {
            EvaluatedMove evaluated = (EvaluatedMove) move;
            evaluated.previous = value;
            evaluated.parent = current;
            evaluated.child =
                    (PuzzleBoard) current.next(PuzzleMove.between(move.from, move.to, goal.size()));
            return predictor.heuristics(evaluated.child, goal);
        }

        @Override
        void updateHeuristic(Move move, boolean undo) {
            EvaluatedMove evaluated = (EvaluatedMove) move;
            current = undo ? evaluated.parent : evaluated.child;
            value = undo ? evaluated.previous : move.heuristic;
        }
    }
}
