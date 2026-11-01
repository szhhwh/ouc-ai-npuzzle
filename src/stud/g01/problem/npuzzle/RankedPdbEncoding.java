package stud.g01.problem.npuzzle;

import java.util.Arrays;

/** Partial permutation ranks and blank ordinals used by ranked PDBs. */
final class RankedPdbEncoding {
    private final int cells;
    final int maxTiles;
    private final int[][] permutations;

    RankedPdbEncoding(int cells, int maxTiles) {
        this.cells = cells;
        this.maxTiles = maxTiles;
        permutations = new int[cells + 1][];
        for (int n = 0; n <= cells; n++) {
            permutations[n] = new int[Math.min(n, maxTiles) + 1];
            permutations[n][0] = 1;
            for (int k = 1; k < permutations[n].length; k++) {
                permutations[n][k] = permutations[n][k - 1] * (n - k + 1);
            }
        }
    }

    /** P(cells, k): number of ordered placements of k distinct tiles. */
    int rankCount(int k) {
        return permutations[cells][k];
    }

    /** Place value of a tuple slot, also used by incremental rank updates. */
    int weight(int k, int slot) {
        return permutations[cells - 1 - slot][k - 1 - slot];
    }

    int rankTuple(int[] positions, int k) {
        int rank = 0, occupied = 0;
        for (int i = 0; i < k; i++) {
            int below = Integer.bitCount(occupied & ((1 << positions[i]) - 1));
            rank += (positions[i] - below) * weight(k, i);
            occupied |= 1 << positions[i];
        }
        return rank;
    }

    /** Blank ordinal among positions not occupied by this group. */
    static int blankRank(int occupied, int blank) {
        return blank - Integer.bitCount(occupied & ((1 << blank) - 1));
    }

    /** Decode tile positions into reusable buffers and return the blank position. */
    int decode(int rank, int blankRank, int k, int[] positions, boolean[] used) {
        Arrays.fill(used, false);
        for (int i = 0; i < k; i++) {
            int weight = weight(k, i);
            int ordinal = rank / weight;
            rank %= weight;
            for (int pos = 0; pos < cells; pos++) {
                if (!used[pos] && ordinal-- == 0) {
                    positions[i] = pos;
                    used[pos] = true;
                    break;
                }
            }
        }
        for (int pos = 0; pos < cells; pos++) {
            if (!used[pos] && blankRank-- == 0) return pos;
        }
        return -1;
    }
}
