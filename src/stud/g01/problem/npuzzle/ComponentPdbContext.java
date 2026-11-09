package stud.g01.problem.npuzzle;

/**
 * 连通分支无损压缩模式数据库的专用增量搜索上下文。
 *
 * <p>维护各模式分组的占用掩码与组内数字排列排名，支持增量计算后继估值与状态回退，适用于 4 阶棋盘。
 */
final class ComponentPdbContext extends PdbContext {
    /** 4 阶棋盘的 7-8 数字分组定义 */
    private static final int[][] GROUPS = {{1, 2, 3, 4, 5, 6, 7}, {8, 9, 10, 11, 12, 13, 14, 15}};

    /** 目标盘面对应的只读查找表与跨行权重转移矩阵。 */
    static final class Tables {
        /** 目标盘面对象 */
        final PuzzleBoard goal;

        /** 两个分组对应的连通分支模式数据库表 */
        final ComponentPdbTable[] data = new ComponentPdbTable[2];

        /** 各数字所属分组编号、在组内的槽位序号以及阶乘基数位权数组 */
        final int[] tileGroup = new int[16], slot = new int[16], weights = new int[16];

        /** 沿主对角线转置的格子下标映射数组与数字对称置换数组 */
        final int[] transpose = new int[16], permutation = new int[16];

        /** 竖向移动跨越格子时的正向与对称排名增量查找表 */
        final int[][] crossing = new int[16][16], symmetricCrossing = new int[16][16];

        /** 是否满足主对角线对称条件 */
        final boolean symmetric;

        /**
         * 为指定目标盘面初始化只读查找表。
         *
         * @param goal 目标盘面
         * @param repository 模式数据库仓库
         */
        Tables(PuzzleBoard goal, PdbRepository repository) {
            this.goal = goal;
            // pos[v] 记录数字 v 在目标盘面中的格子下标
            int[] pos = new int[16];
            for (int i = 0; i < 16; i++) {
                transpose[i] = (i % 4) * 4 + i / 4;
                pos[goal.cellsInternal()[i]] = i;
            }
            // 沿主对角线转置后，数字 v 映射为目标盘面中转置位置上的数字
            for (int v = 0; v < 16; v++) permutation[v] = goal.cellsInternal()[transpose[pos[v]]];
            symmetric = permutation[0] == 0;
            for (int g = 0; g < GROUPS.length; g++) {
                data[g] = ComponentPdbTable.load(4, goal.cellsInternal(), GROUPS[g], repository);
                for (int j = 0; j < GROUPS[g].length; j++) {
                    int tile = GROUPS[g][j];
                    tileGroup[tile] = g;
                    slot[tile] = j;
                    weights[tile] = ComponentPdbTable.factorial(GROUPS[g].length - j - 1);
                }
            }
            // 预计算竖直跨越移动对排列排名的贡献，避免逐格比较
            for (int tile = 1; tile < 16; tile++)
                for (int other = 1; other < 16; other++)
                    if (tileGroup[tile] == tileGroup[other])
                        crossing[tile][other] =
                                slot[other] < slot[tile] ? -weights[other] : weights[tile];
            for (int tile = 1; tile < 16; tile++)
                for (int other = 1; other < 16; other++)
                    symmetricCrossing[tile][other] = crossing[tile][permutation[other]];
        }
    }

    /** 目标盘面对应的预计算查找表集合 */
    private final Tables tables;

    /** 各分组当前正向与对称的格子占用位掩码数组 */
    private final int[] masks = new int[2], masksSym = new int[2];

    /** 各分组当前正向与对称的数字排列排名数组 */
    private final int[] ranks = new int[2], ranksSym = new int[2];

    /** 各分组当前正向与对称的局部单字节启发值数组 */
    private final int[] values = new int[2], valuesSym = new int[2];

    /** 是否开启基于移动的增量查表计算选项 */
    private final boolean incremental;

    /** 非增量模式下用于全量查表的数字位置缓冲区数组 */
    private final int[] fullPositions;

    /** 非增量模式下各分组的数字排列多元组缓冲区 */
    private final int[][] fullTuples;

    /**
     * 构造连通分支模式数据库搜索上下文。
     *
     * @param initial 初始盘面
     * @param tables 预计算好的查找表集合
     * @param options 模式数据库配置选项
     */
    ComponentPdbContext(PuzzleBoard initial, Tables tables, PdbOptions options) {
        super(initial, tables.goal.cellsInternal());
        this.tables = tables;
        incremental = options.incremental();
        symmetric = options.symmetry() && tables.symmetric;
        fullPositions = incremental ? null : new int[16];
        fullTuples = incremental ? null : new int[][] {new int[7], new int[8]};
        int[] pos = new int[16], posSym = new int[16];
        for (int i = 0; i < 16; i++) {
            pos[board[i]] = i;
            posSym[tables.permutation[board[i]]] = tables.transpose[i];
        }
        // 计算各分组初始占用掩码、排列排名与初始启发值
        for (int g = 0; g < GROUPS.length; g++) {
            int[] p = new int[GROUPS[g].length], ps = new int[p.length];
            for (int j = 0; j < p.length; j++) {
                int tile = GROUPS[g][j];
                p[j] = pos[tile];
                ps[j] = posSym[tile];
                masks[g] |= 1 << p[j];
                masksSym[g] |= 1 << ps[j];
            }
            ranks[g] = ComponentPdbTable.permutationRank(p);
            ranksSym[g] = ComponentPdbTable.permutationRank(ps);
            values[g] = tables.data[g].distance(masks[g], ranks[g], blank);
            directHeuristic += values[g];
            if (symmetric) {
                valuesSym[g] =
                        tables.data[g].distance(masksSym[g], ranksSym[g], tables.transpose[blank]);
                symmetricHeuristic += valuesSym[g];
            }
        }
    }

    /**
     * 增量计算数字块由 from 移动到 to 之后的新排列排名。
     *
     * <p>数学原理：行主序中水平移动不改变数字间的相对先后次序，排名不变； 竖直移动必然跨越中间三个格子，由预计算的 crossing 表直接查得差值。
     *
     * @param mirrored 是否为对称网格
     * @param rank 当前排列排名
     * @param tile 被移动的数字块数值
     * @param from 移动前位置
     * @param to 移动后位置
     * @return 移动后的新排列排名
     */
    private int rankAfter(boolean mirrored, int rank, int tile, int from, int to) {
        // 水平移动不改变组内数字的相对次序，排名不变
        if (Math.abs(to - from) == 1) return rank;
        int low = Math.min(from, to);
        int[] contributions = mirrored ? tables.symmetricCrossing[tile] : tables.crossing[tile];
        // 竖直移动跨越中间三格，累加这三格中组内数字的位权贡献
        int delta =
                mirrored
                        ? contributions[board[tables.transpose[low + 1]]]
                                + contributions[board[tables.transpose[low + 2]]]
                                + contributions[board[tables.transpose[low + 3]]]
                        : contributions[board[low + 1]]
                                + contributions[board[low + 2]]
                                + contributions[board[low + 3]];
        return rank + (to > from ? delta : -delta);
    }

    @Override
    void prepareValues(Move move) {
        if (!incremental) {
            prepareFullValues(move);
            return;
        }
        // 先算估值较大的一侧，更早触发界限剪枝
        if (!symmetric || directHeuristic >= symmetricHeuristic) {
            prepareDirect(move);
            // 正向已超限，对称估值仅保留下界标记
            if (move.direct > move.limit) {
                move.symmetric = 0;
                move.lowerBound = symmetric;
                return;
            }
            move.symmetric = symmetricHeuristic;
            if (symmetric) prepareSymmetric(move);
        } else {
            prepareSymmetric(move);
            if (move.symmetric > move.limit) {
                move.direct = 0;
                move.lowerBound = true;
                return;
            }
            prepareDirect(move);
        }
    }

    /**
     * 全量重新计算两分组的占用掩码与排列排名，用于非增量对比测试。
     *
     * @param move 移动对象
     */
    private void prepareFullValues(Move move) {
        for (int i = 0; i < cells; i++) fullPositions[board[i]] = i;
        fullPositions[0] = move.to;
        fullPositions[move.tile] = move.from;
        // 与增量路径保持一致：先算较大的一侧
        if (!symmetric || directHeuristic >= symmetricHeuristic) {
            move.direct = fullDistance(move.to, false);
            move.lowerBound = symmetric && move.direct > move.limit;
            move.symmetric =
                    symmetric && move.direct <= move.limit
                            ? fullDistance(tables.transpose[move.to], true)
                            : 0;
        } else {
            move.symmetric = fullDistance(tables.transpose[move.to], true);
            move.lowerBound = move.symmetric > move.limit;
            move.direct = move.symmetric <= move.limit ? fullDistance(move.to, false) : 0;
        }
    }

    /**
     * 全量遍历两分组计算启发值总和。
     *
     * @param blank 空格位置
     * @param mirrored 是否为对称网格
     * @return 启发总值
     */
    private int fullDistance(int blank, boolean mirrored) {
        int sum = 0;
        for (int g = 0; g < GROUPS.length; g++) {
            int[] tuple = fullTuples[g];
            int occupied = 0;
            for (int j = 0; j < tuple.length; j++) {
                int tile = GROUPS[g][j];
                tuple[j] =
                        mirrored
                                ? tables.transpose[fullPositions[tables.permutation[tile]]]
                                : fullPositions[tile];
                occupied |= 1 << tuple[j];
            }
            sum +=
                    tables.data[g].distance(
                            occupied, ComponentPdbTable.permutationRank(tuple), blank);
        }
        return sum;
    }

    /**
     * 增量计算正向启发值。
     *
     * @param move 移动对象
     */
    private void prepareDirect(Move move) {
        int g = tables.tileGroup[move.tile];
        move.group = g;
        move.oldIndex = ranks[g];
        move.index = rankAfter(false, ranks[g], move.tile, move.to, move.from);
        // 占用掩码只需异或移出与移入的两个格子
        int occupied = masks[g] ^ (1 << move.from) ^ (1 << move.to);
        move.oldValue = values[g];
        move.value = tables.data[g].distance(occupied, move.index, move.to);
        move.direct = directHeuristic + move.value - move.oldValue;
    }

    /**
     * 增量计算对称启发值。
     *
     * @param move 移动对象
     */
    private void prepareSymmetric(Move move) {
        int tile = tables.permutation[move.tile], gs = tables.tileGroup[tile];
        int from = tables.transpose[move.from], to = tables.transpose[move.to];
        move.symmetricGroup = gs;
        move.oldSymmetricIndex = ranksSym[gs];
        move.symmetricIndex = rankAfter(true, ranksSym[gs], tile, to, from);
        move.oldSymmetricValue = valuesSym[gs];
        int occupied = masksSym[gs] ^ (1 << from) ^ (1 << to);
        move.symmetricValue = tables.data[gs].distance(occupied, move.symmetricIndex, to);
        move.symmetric = symmetricHeuristic + move.symmetricValue - move.oldSymmetricValue;
    }

    @Override
    void updateValues(Move move, boolean undo) {
        if (!incremental) return;
        ranks[move.group] = undo ? move.oldIndex : move.index;
        values[move.group] = undo ? move.oldValue : move.value;
        // 异或翻转占用掩码中移出与移入的两个格子
        masks[move.group] ^= (1 << move.to) | (1 << move.from);
        if (symmetric) {
            int g = move.symmetricGroup;
            ranksSym[g] = undo ? move.oldSymmetricIndex : move.symmetricIndex;
            valuesSym[g] = undo ? move.oldSymmetricValue : move.symmetricValue;
            masksSym[g] ^= (1 << tables.transpose[move.to]) | (1 << tables.transpose[move.from]);
        }
    }
}
