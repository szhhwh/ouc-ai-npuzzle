package stud.g01.problem.npuzzle;

/** 乘积编码的搜索上下文，用位权增量维护启发值，适用于 3 阶 */
final class ProductPdbContext extends PdbContext {

    private final DisjointPdbHeuristic.Tables tbl;
    private final int[] tileGroup, tileWeight, mirroredGroup, mirroredWeight;
    private final int[] transpose;
    private final int[] idx, idxSym, values, symmetricValues;
    private final boolean incremental;

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

        // 初始编码一次算好，之后全部增量更新
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
            idx[g] = e + blank;
            values[g] = tbl.tables[g][idx[g]] & 0xFF;
            directHeuristic += values[g];

            if (symmetric) {
                int es = 0;
                for (int j = 0; j < tiles.length; j++) {
                    // A diagonal reflection is its own inverse.
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
            // Rebuild the child's complete inverse encoding and query every group.
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
