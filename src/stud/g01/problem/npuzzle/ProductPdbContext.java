package stud.g01.problem.npuzzle;

/** 乘积编码的搜索上下文，用位权增量维护启发值，适用于 3 阶 */
final class ProductPdbContext extends PdbContext {

    private final DisjointPdbHeuristic.Tables tbl;
    private final int[] tileGroup, tileSlot;
    private final int[][] weight;
    private final int[] transpose, symPerm;
    private final int[] idx, idxSym;

    ProductPdbContext(PuzzleBoard initial, DisjointPdbHeuristic.Tables tables) {
        super(initial);
        tbl = tables;

        weight = new int[tbl.groups.length][];
        for (int g = 0; g < tbl.groups.length; g++) {
            int d = tbl.groups[g].length;
            weight[g] = new int[d];
            for (int j = 0; j < d; j++) {
                weight[g][j] = pow(cells, d - j);
            }
        }
        tileGroup = new int[cells];
        tileSlot = new int[cells];
        for (int g = 0; g < tbl.groups.length; g++) {
            for (int j = 0; j < tbl.groups[g].length; j++) {
                tileGroup[tbl.groups[g][j]] = g;
                tileSlot[tbl.groups[g][j]] = j;
            }
        }
        transpose = tbl.transpose;
        symPerm = tbl.symPerm;

        // 初始编码一次算好，之后全部增量更新
        int[] pos = new int[cells];
        for (int i = 0; i < cells; i++) {
            pos[board[i]] = i;
        }
        idx = new int[tbl.groups.length];
        idxSym = new int[tbl.groups.length];
        // inv 是 symPerm 的逆置换，用来定位对称盘面里各数字块的位置
        int[] inv = new int[cells];
        for (int v = 0; v < cells; v++) {
            inv[symPerm[v]] = v;
        }
        for (int g = 0; g < tbl.groups.length; g++) {
            int[] tiles = tbl.groups[g];
            int e = 0;
            for (int j = 0; j < tiles.length; j++) {
                e += pos[tiles[j]] * weight[g][j];
            }
            idx[g] = e + blank;
            directHeuristic += tbl.tables[g][idx[g]] & 0xFF;

            if (tbl.symmetric) {
                int es = 0;
                for (int j = 0; j < tiles.length; j++) {
                    es += transpose[pos[inv[tiles[j]]]] * weight[g][j];
                }
                idxSym[g] = es + transpose[blank];
                symmetricHeuristic += tbl.tables[g][idxSym[g]] & 0xFF;
            }
        }
        symmetric = tbl.symmetric;
    }

    @Override
    int heuristicAfterMove(int t) {
        int b = blank;
        byte v = board[t];
        int g = tileGroup[v], j = tileSlot[v];
        byte[] tab = tbl.tables[g];
        int nw = idx[g] + (b - t) * (weight[g][j] - 1);
        int hv = directHeuristic + (tab[nw] & 0xFF) - (tab[idx[g]] & 0xFF);
        if (symmetric) {
            int sv = symPerm[v];
            int gs = tileGroup[sv], js = tileSlot[sv];
            byte[] tabS = tbl.tables[gs];
            int nwS = idxSym[gs] + (transpose[b] - transpose[t]) * (weight[gs][js] - 1);
            int hs2 = symmetricHeuristic + (tabS[nwS] & 0xFF) - (tabS[idxSym[gs]] & 0xFF);
            if (hs2 > hv) {
                hv = hs2;
            }
        }
        return hv;
    }

    @Override
    void apply(int t) {
        int b = blank;
        byte v = board[t];
        int g = tileGroup[v], j = tileSlot[v];
        byte[] tab = tbl.tables[g];
        int old = idx[g];
        int nw = old + (b - t) * (weight[g][j] - 1);
        directHeuristic += (tab[nw] & 0xFF) - (tab[old] & 0xFF);
        idx[g] = nw;
        // 其余组的表值不变，只需同步平移编码里的空格维度
        int dbt = t - b;
        for (int gg = 0; gg < idx.length; gg++) {
            if (gg != g) {
                idx[gg] += dbt;
            }
        }

        if (symmetric) {
            int tb = transpose[b], tt = transpose[t];
            byte sv = (byte) symPerm[v];
            int gs = tileGroup[sv], js = tileSlot[sv];
            byte[] tabS = tbl.tables[gs];
            int oldS = idxSym[gs];
            int nwS = oldS + (tb - tt) * (weight[gs][js] - 1);
            symmetricHeuristic += (tabS[nwS] & 0xFF) - (tabS[oldS] & 0xFF);
            idxSym[gs] = nwS;
            int dbs = tt - tb;
            for (int gg = 0; gg < idxSym.length; gg++) {
                if (gg != gs) {
                    idxSym[gg] += dbs;
                }
            }
        }

        swapBlank(t);
    }

    private static int pow(int base, int exp) {
        int r = 1;
        for (int i = 0; i < exp; i++) {
            r *= base;
        }
        return r;
    }
}
