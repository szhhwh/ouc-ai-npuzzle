package stud.g01.problem.npuzzle;

import static stud.g01.problem.npuzzle.RankedPdbEncoding.blankRank;
import static stud.g01.problem.npuzzle.RankedPdbEncoding.blankRankOf;
import static stud.g01.problem.npuzzle.RankedPdbEncoding.pack;
import static stud.g01.problem.npuzzle.RankedPdbEncoding.rankOf;

/** ranked 编码的搜索上下文，用排名增量维护启发值，适用于 4 阶 */
final class RankedPdbContext extends PdbContext {

    private final RankedPdbHeuristic.Tables tbl;
    private final int[][] tilesOf;
    private final int[] tileGroup, tileSlot;
    private final int[] transpose, symPerm;

    // 数字块在直链和对称链上的位置，逐步增量维护
    private final int[] pos, posSym;

    // 每组的排名和空格序号，直链与对称链各一套
    private final int[] rG, qG, rS, qS;
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
        qG = new int[gn];
        rS = new int[gn];
        qS = new int[gn];
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
            qG[g] = blankRank(maskG[g], blank);
            directHeuristic += tbl.tables[g][qG[g]][rG[g]] & 0xFF;

            if (symmetric) {
                for (int j = 0; j < k; j++) {
                    ps[j] = posSym[tiles[j]];
                    maskS[g] |= 1 << ps[j];
                }
                rS[g] = tbl.encoding.rankTuple(ps, k);
                qS[g] = blankRank(maskS[g], transpose[blank]);
                symmetricHeuristic += tbl.tables[g][qS[g]][rS[g]] & 0xFF;
            }
        }
    }

    /**
     * 计算某一组在数字块从 t 移到 b 后的新排名和新空格序号。
     *
     * @param posArr 数字块位置数组
     * @param rArr 该组的排名
     * @param maskArr 该组的数字块位置位图
     * @param g 组号
     * @param j 被移动数字块在组内的下标
     * @param t 移动前的位置
     * @param b 移动后的位置
     * @return 打包后的新排名和新空格序号
     */
    private long passiveDelta(int[] posArr, int[] rArr, int[] maskArr, int g, int j, int t, int b) {
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

        int newMask = maskArr[g] ^ ((1 << t) | (1 << b));
        return pack(rArr[g] + dR, blankRank(newMask, t));
    }

    @Override
    int heuristicAfterMove(int t) {
        int b = blank;
        int v = board[t];
        int g = tileGroup[v], j = tileSlot[v];

        long nd = passiveDelta(pos, rG, maskG, g, j, t, b);
        int hv =
                directHeuristic
                        + (tbl.tables[g][blankRankOf(nd)][rankOf(nd)] & 0xFF)
                        - (tbl.tables[g][qG[g]][rG[g]] & 0xFF);

        if (symmetric) {
            int sv = symPerm[v];
            int gs = tileGroup[sv], js = tileSlot[sv];
            int tb = transpose[b], tt = transpose[t];
            long ns = passiveDelta(posSym, rS, maskS, gs, js, tt, tb);
            int hs2 =
                    symmetricHeuristic
                            + (tbl.tables[gs][blankRankOf(ns)][rankOf(ns)] & 0xFF)
                            - (tbl.tables[gs][qS[gs]][rS[gs]] & 0xFF);
            if (hs2 > hv) {
                hv = hs2;
            }
        }
        return hv;
    }

    @Override
    void apply(int t) {
        int b = blank;
        int v = board[t];
        int g = tileGroup[v], j = tileSlot[v];

        // 先算直链上被移动数字块所属组的新编码和表值
        long nd = passiveDelta(pos, rG, maskG, g, j, t, b);
        int nR = rankOf(nd), nQ = blankRankOf(nd);
        directHeuristic += (tbl.tables[g][nQ][nR] & 0xFF) - (tbl.tables[g][qG[g]][rG[g]] & 0xFF);
        rG[g] = nR;
        qG[g] = nQ;
        maskG[g] ^= (1 << t) | (1 << b);

        // 其余组的排名和表值不变，只用位图重算空格序号
        for (int gg = 0; gg < rG.length; gg++) {
            if (gg != g) {
                qG[gg] = blankRank(maskG[gg], t);
            }
        }

        pos[v] = b;

        if (symmetric) {
            int sv = symPerm[v];
            int gs = tileGroup[sv], js = tileSlot[sv];
            int tb = transpose[b], tt = transpose[t];
            long ns = passiveDelta(posSym, rS, maskS, gs, js, tt, tb);
            int nRs = rankOf(ns), nQs = blankRankOf(ns);
            symmetricHeuristic +=
                    (tbl.tables[gs][nQs][nRs] & 0xFF) - (tbl.tables[gs][qS[gs]][rS[gs]] & 0xFF);
            rS[gs] = nRs;
            qS[gs] = nQs;
            maskS[gs] ^= (1 << tt) | (1 << tb);
            for (int gg = 0; gg < rS.length; gg++) {
                if (gg != gs) {
                    qS[gg] = blankRank(maskS[gg], tt);
                }
            }
            posSym[sv] = tb;
        }

        swapBlank(t);
    }
}
