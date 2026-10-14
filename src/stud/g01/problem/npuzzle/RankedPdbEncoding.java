package stud.g01.problem.npuzzle;

import java.util.Arrays;

/** Partial permutation ranks and blank ordinals used by ranked PDBs. */
final class RankedPdbEncoding {
    private static final int BLANK_BITS = 6;
    private static final long BLANK_MASK = (1L << BLANK_BITS) - 1;

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
        int rank = 0;
        for (int i = 0; i < k; i++) {
            int below = 0;
            for (int j = 0; j < i; j++) {
                if (positions[j] < positions[i]) below++;
            }
            rank += (positions[i] - below) * weight(k, i);
        }
        return rank;
    }

    /** Blank ordinal among positions not occupied by this group. */
    static int blankRank(int[] positions, int k, int blank) {
        int below = 0;
        for (int i = 0; i < k; i++) {
            if (positions[i] < blank) below++;
        }
        return blank - below;
    }

    /** The same ordinal, computed from an incrementally maintained bitmap. */
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

    static long pack(int rank, int blankRank) {
        return ((long) rank << BLANK_BITS) | blankRank;
    }

    static int rankOf(long code) {
        return (int) (code >>> BLANK_BITS);
    }

    static int blankRankOf(long code) {
        return (int) (code & BLANK_MASK);
    }
}
