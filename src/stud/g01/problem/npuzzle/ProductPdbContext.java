package stud.g01.problem.npuzzle;

/** 乘积编码的模式数据库专用搜索上下文，利用位权增量维护启发值，适用于 3 阶棋盘。 */
final class ProductPdbContext extends PdbContext {

    /** 目标盘面对应的模式数据库查找表集合 */
    private final DisjointPdbHeuristic.Tables tbl;

    /** 数字所属分组编号、乘积寻址位权、对称分组编号以及对称位权数组 */
    private final int[] tileGroup, tileWeight, mirroredGroup, mirroredWeight;

    /** 沿主对角线转置的格子下标映射表 */
    private final int[] transpose;

    /** 各分组当前正向与对称的查表乘积索引数组以及当前局部估值数组 */
    private final int[] idx, idxSym, values, symmetricValues;

    /** 是否开启增量更新选项 */
    private final boolean incremental;

    /**
     * 构造乘积编码的模式数据库搜索上下文。
     *
     * @param initial 初始盘面
     * @param tables 预计算好的模式数据库表集合
     * @param options 模式数据库配置选项
     */
    ProductPdbContext(PuzzleBoard initial, DisjointPdbHeuristic.Tables tables, PdbOptions options) {
        super(initial, tables.goalCells);
        tbl = tables;
        incremental = options.incremental();
        symmetric = options.symmetry() && tbl.symmetric;

        tileGroup = tbl.tileGroup;
        tileWeight = tbl.tileWeight;
        mirroredGroup = tbl.mirroredGroup;
        mirroredWeight = tbl.mirroredWeight;
        transpose = tbl.transpose;

        long positions = initial.positions();
        idx = new int[tbl.groups.length];
        idxSym = new int[tbl.groups.length];
        values = new int[tbl.groups.length];
        symmetricValues = new int[tbl.groups.length];
        for (int g = 0; g < tbl.groups.length; g++) {
            int[] tiles = tbl.groups[g];
            int e = 0;
            for (int j = 0; j < tiles.length; j++) {
                int position = (int) (positions >>> (tiles[j] * 4)) & 15;
                e += position * tileWeight[tiles[j]];
            }
            // 分组索引 = 组内数字位置加权和 + 空格位置
            idx[g] = e + blank;
            values[g] = tbl.tables[g][idx[g]] & 0xFF;
            directHeuristic += values[g];

            if (symmetric) {
                int es = 0;
                for (int j = 0; j < tiles.length; j++) {
                    int position = (int) (positions >>> (tbl.symPerm[tiles[j]] * 4)) & 15;
                    es += transpose[position] * tileWeight[tiles[j]];
                }
                idxSym[g] = es + transpose[blank];
                symmetricValues[g] = tbl.tables[g][idxSym[g]] & 0xFF;
                symmetricHeuristic += symmetricValues[g];
            }
        }
    }

    @Override
    void prepareValues(Move move) {
        if (!incremental) {
            // 重建后继盘面的逆位置编码后全量查表
            long positions = 0;
            for (int i = 0; i < cells; i++) {
                int position = i == move.from ? move.to : i == move.to ? move.from : i;
                positions |= (long) position << (board[i] * 4);
            }
            move.direct = tbl.hPositions(positions, move.to, false);
            move.symmetric = symmetric ? tbl.hPositions(positions, transpose[move.to], true) : 0;
            return;
        }
        int g = tileGroup[move.tile];
        move.group = g;
        move.oldIndex = idx[g];
        // 空格位移增量与数字位权增量合成新的分组索引
        move.index = idx[g] + (move.from - move.to) * (tileWeight[move.tile] - 1);
        move.oldValue = values[g];
        move.value = tbl.tables[g][move.index] & 0xFF;
        move.direct = directHeuristic + move.value - move.oldValue;
        move.symmetric = symmetricHeuristic;
        if (symmetric) {
            int gs = mirroredGroup[move.tile];
            move.symmetricGroup = gs;
            move.oldSymmetricIndex = idxSym[gs];
            move.symmetricIndex =
                    idxSym[gs]
                            + (transpose[move.from] - transpose[move.to])
                                    * (mirroredWeight[move.tile] - 1);
            move.oldSymmetricValue = symmetricValues[gs];
            move.symmetricValue = tbl.tables[gs][move.symmetricIndex] & 0xFF;
            move.symmetric += move.symmetricValue - move.oldSymmetricValue;
        }
    }

    @Override
    void updateValues(Move move, boolean undo) {
        if (!incremental) return;
        // 未包含被移动数字的分组，其索引只随空格位置整体平移
        int delta = undo ? move.from - move.to : move.to - move.from;
        for (int g = 0; g < idx.length; g++) idx[g] += delta;
        idx[move.group] = undo ? move.oldIndex : move.index;
        values[move.group] = undo ? move.oldValue : move.value;
        if (symmetric) {
            int ds = transpose[move.to] - transpose[move.from];
            if (undo) ds = -ds;
            for (int g = 0; g < idxSym.length; g++) idxSym[g] += ds;
            idxSym[move.symmetricGroup] = undo ? move.oldSymmetricIndex : move.symmetricIndex;
            symmetricValues[move.symmetricGroup] =
                    undo ? move.oldSymmetricValue : move.symmetricValue;
        }
    }
}
