package stud.g01.problem.npuzzle;

/** ranked 编码的搜索上下文，用排名增量维护启发值，适用于 4 阶 */
final class RankedPdbContext extends PdbContext {

    private final RankedPdbHeuristic.Tables tbl;
    private final int[][] tilesOf;
    private final int[] tileGroup, tileSlot;
    private final int[] transpose, symPerm;

    // 数字块在直链和对称链上的位置，逐步增量维护
    private final int[] pos, posSym;

    // Per-group ranks and cached distances, for both orientations.
    private final int[] rG, rS, values, symmetricValues;
    // 每组的数字块位置位图，bit i 为 1 表示该组有数字块在格子 i
    private final int[] maskG, maskS;

    RankedPdbContext(PuzzleBoard initial, RankedPdbHeuristic.Tables tables) {
        super(initial);
        tbl = tables;

        int gn = tbl.groups.length;
        tilesOf = tbl.groups;
        tileGroup = new int[cells];
        tileSlot = new int[cells];
        for (int g = 0; g < gn; g++) {
            for (int j = 0; j < tbl.groups[g].length; j++) {
                tileGroup[tbl.groups[g][j]] = g;
                tileSlot[tbl.groups[g][j]] = j;
            }
        }
        transpose = tbl.transpose;
        symPerm = tbl.symPerm;

        pos = new int[cells];
        for (int i = 0; i < cells; i++) {
            pos[board[i]] = i;
        }

        rG = new int[gn];
        values = new int[gn];
        rS = new int[gn];
        symmetricValues = new int[gn];
        maskG = new int[gn];
        maskS = new int[gn];

        symmetric = tbl.symmetric;
        posSym = new int[cells];
        if (symmetric) {
            for (int i = 0; i < cells; i++) {
                posSym[symPerm[board[transpose[i]]]] = i;
            }
        }

        // 初始编码一次算好，之后全部增量更新
        int[] p = new int[tbl.encoding.maxTiles];
        int[] ps = new int[tbl.encoding.maxTiles];
        for (int g = 0; g < gn; g++) {
            int[] tiles = tilesOf[g];
            int k = tiles.length;
            for (int j = 0; j < k; j++) {
                p[j] = pos[tiles[j]];
                maskG[g] |= 1 << p[j];
            }
            rG[g] = tbl.encoding.rankTuple(p, k);
            values[g] = tbl.distance(g, rG[g], maskG[g], blank);
            directHeuristic += values[g];

            if (symmetric) {
                for (int j = 0; j < k; j++) {
                    ps[j] = posSym[tiles[j]];
                    maskS[g] |= 1 << ps[j];
                }
                rS[g] = tbl.encoding.rankTuple(ps, k);
                symmetricValues[g] = tbl.distance(g, rS[g], maskS[g], transpose[blank]);
                symmetricHeuristic += symmetricValues[g];
            }
        }
    }

    /** Update only the rank terms affected by the moving tile. */
    private int rankAfterMove(int[] posArr, int[] rArr, int g, int j, int t, int b) {
        int k = tilesOf[g].length;
        int[] tiles = tilesOf[g];
        int belowT = 0, belowB = 0, dR = 0;
        boolean up = b > t;
        for (int i = 0; i < k; i++) {
            if (i == j) continue;
            int pi = posArr[tiles[i]];
            if (i < j) {
                if (pi < t) belowT++;
                if (pi < b) belowB++;
            } else if (up ? (pi > t && pi < b) : (pi > b && pi < t)) {
                // 其他数字块落在 (t, b) 区间内时，其前方更小计数变化 1，对应的排名分量反向变化一个位权
                dR += (up ? 1 : -1) * tbl.encoding.weight(k, i);
            }
        }
        int dAj = (b - belowB) - (t - belowT);
        dR += dAj * tbl.encoding.weight(k, j);

        return rArr[g] + dR;
    }

    @Override
    void prepareValues(Move move) {
        int g = tileGroup[move.tile], j = tileSlot[move.tile];
        move.group = g;
        move.oldIndex = rG[g];
        move.index = rankAfterMove(pos, rG, g, j, move.to, move.from);
        move.oldValue = values[g];
        int newMask = maskG[g] ^ ((1 << move.to) | (1 << move.from));
        move.value = tbl.distance(g, move.index, newMask, move.to);
        move.direct = directHeuristic + move.value - move.oldValue;
        move.symmetric = symmetricHeuristic;
        if (symmetric) {
            int v = symPerm[move.tile];
            int gs = tileGroup[v], js = tileSlot[v];
            int from = transpose[move.from], to = transpose[move.to];
            move.symmetricGroup = gs;
            move.oldSymmetricIndex = rS[gs];
            move.symmetricIndex = rankAfterMove(posSym, rS, gs, js, to, from);
            move.oldSymmetricValue = symmetricValues[gs];
            int newSymmetricMask = maskS[gs] ^ ((1 << to) | (1 << from));
            move.symmetricValue = tbl.distance(gs, move.symmetricIndex, newSymmetricMask, to);
            move.symmetric += move.symmetricValue - move.oldSymmetricValue;
        }
    }

    @Override
    void updateValues(Move move, boolean undo) {
        rG[move.group] = undo ? move.oldIndex : move.index;
        values[move.group] = undo ? move.oldValue : move.value;
        maskG[move.group] ^= (1 << move.to) | (1 << move.from);
        pos[move.tile] = undo ? move.to : move.from;
        if (symmetric) {
            rS[move.symmetricGroup] = undo ? move.oldSymmetricIndex : move.symmetricIndex;
            symmetricValues[move.symmetricGroup] =
                    undo ? move.oldSymmetricValue : move.symmetricValue;
            maskS[move.symmetricGroup] ^= (1 << transpose[move.to]) | (1 << transpose[move.from]);
            posSym[symPerm[move.tile]] = transpose[undo ? move.to : move.from];
        }
    }
}
