package stud.g01.problem.npuzzle;

/**
 * 模式数据库专用搜索上下文抽象基类。
 *
 * <p>维护直接查表启发值与沿主对角线转置后的对称启发值，支持增量计算后继估值与状态回退。
 */
abstract class PdbContext extends PuzzleSearchContext {
    /** 直接查表估值累加和 */
    int directHeuristic;

    /** 沿主对角线对称转置后的估值累加和 */
    int symmetricHeuristic;

    /** 是否启用对称估值增强 */
    boolean symmetric;

    /**
     * 构造模式数据库搜索上下文。
     *
     * @param initial 初始盘面
     * @param goal 目标盘面数字数组
     */
    PdbContext(PuzzleBoard initial, byte[] goal) {
        super(initial, goal);
    }

    /**
     * 获取当前状态的启发估计值，若开启对称增强则取直接估值与对称估值的较大值。
     *
     * @return 当前状态的启发值
     */
    @Override
    final int heuristic() {
        return symmetric ? Math.max(directHeuristic, symmetricHeuristic) : directHeuristic;
    }

    /** 模式数据库特化的移动操作记录类，保存涉及分组、新旧索引、新旧局部估值以及对称变换信息。 */
    static final class Move extends PuzzleSearchContext.Move {
        /** 正向受影响的模式分组编号 */
        int group;

        /** 对称受影响的模式分组编号 */
        int symmetricGroup;

        /** 正向模式分组移动前的旧表索引 */
        int oldIndex;

        /** 正向模式分组移动后的新表索引 */
        int index;

        /** 对称模式分组移动前的旧表索引 */
        int oldSymmetricIndex;

        /** 对称模式分组移动后的新表索引 */
        int symmetricIndex;

        /** 正向模式分组移动前的旧局部启发值 */
        int oldValue;

        /** 正向模式分组移动后的新局部启发值 */
        int value;

        /** 对称模式分组移动前的旧局部启发值 */
        int oldSymmetricValue;

        /** 对称模式分组移动后的新局部启发值 */
        int symmetricValue;

        /** 移动前的正向总启发值 */
        int oldDirect;

        /** 移动后的正向总启发值 */
        int direct;

        /** 移动前的对称总启发值 */
        int oldSymmetric;

        /** 移动后的对称总启发值 */
        int symmetric;

        /** 估值上限阈值 */
        int limit;
    }

    /**
     * 创建当前上下文专用的移动对象。
     *
     * @return 新建的 Move 对象
     */
    @Override
    final Move newMove() {
        return new Move();
    }

    /**
     * 准备并增量计算候选移动产生的后继状态启发值。
     *
     * @param candidate 候选移动对象
     * @param limit 启发值评估上限阈值
     * @return 预估的后继状态启发值
     */
    @Override
    final int prepareHeuristic(PuzzleSearchContext.Move candidate, int limit) {
        Move move = (Move) candidate;
        move.oldDirect = directHeuristic;
        move.oldSymmetric = symmetricHeuristic;
        move.limit = limit;
        prepareValues(move);
        return symmetric ? Math.max(move.direct, move.symmetric) : move.direct;
    }

    /**
     * 子类实现的具体正向与对称查表索引及局部启发值计算方法。
     *
     * @param move 移动对象
     */
    abstract void prepareValues(Move move);

    /**
     * 子类实现的具体数值更新或回退恢复方法。
     *
     * @param move 移动对象
     * @param undo true 表示回退，false 表示正向更新
     */
    abstract void updateValues(Move move, boolean undo);

    /**
     * 更新或回退启发值状态。
     *
     * @param candidate 移动对象
     * @param undo true 表示回退恢复，false 表示正向更新
     */
    @Override
    final void updateHeuristic(PuzzleSearchContext.Move candidate, boolean undo) {
        Move move = (Move) candidate;
        updateValues(move, undo);
        directHeuristic = undo ? move.oldDirect : move.direct;
        symmetricHeuristic = undo ? move.oldSymmetric : move.symmetric;
    }
}
