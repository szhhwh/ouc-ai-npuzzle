package stud.g01.problem.npuzzle;

/** Exact 6-6-3 queries with product addresses and shared distance vectors. */
final class RegularPdbHeuristic {
    private static final int[][] GROUPS = {{1, 2, 5, 6, 9, 10}, {3, 4, 7, 8, 11, 12}, {13, 14, 15}};

    static final class Tables {
        private final DirectPdbTable first, second;
        private final byte[] third;
        private final int[] transpose = new int[16], permutation = new int[16];
        private final boolean symmetric;
        private final long[][] mirroredPositions;

        Tables(PuzzleBoard goal, PdbRepository repository) {
            first = DirectPdbTable.load(4, goal.cellsInternal(), GROUPS[0], repository);
            second = DirectPdbTable.load(4, goal.cellsInternal(), GROUPS[1], repository);
            third =
                    DisjointPdbHeuristic.loadOrBuildGroup(
                            4, goal.cellsInternal(), GROUPS[2], repository);
            int[] pos = new int[16];
            for (int i = 0; i < 16; i++) {
                transpose[i] = (i % 4) * 4 + i / 4;
                pos[goal.cellsInternal()[i]] = i;
            }
            for (int v = 0; v < 16; v++) permutation[v] = goal.cellsInternal()[transpose[pos[v]]];
            symmetric = permutation[0] == 0;
            mirroredPositions = new long[symmetric ? 4 : 0][];
            for (int block = 0; block < mirroredPositions.length; block++) {
                long[] lookup = new long[1 << 16];
                for (int value = 0; value < lookup.length; value++) {
                    long result = 0;
                    for (int j = 0; j < 4; j++) {
                        int p = (value >>> (j * 4)) & 15;
                        result |= (long) transpose[p] << (permutation[block * 4 + j] * 4);
                    }
                    lookup[value] = result;
                }
                mirroredPositions[block] = lookup;
            }
        }

        int hMax(PuzzleBoard state, boolean useSymmetry) {
            long positions = state.positions();
            int direct = h(positions, state.blank());
            if (!useSymmetry || !symmetric) return direct;
            long mirrored =
                    mirroredPositions[0][(int) positions & 65535]
                            | mirroredPositions[1][(int) (positions >>> 16) & 65535]
                            | mirroredPositions[2][(int) (positions >>> 32) & 65535]
                            | mirroredPositions[3][(int) (positions >>> 48)];
            return Math.max(direct, h(mirrored, transpose[state.blank()]));
        }

        private int h(long positions, int blank) {
            // Tiles 13,14,15 occupy the high three nibbles of the inverse board.
            int a = (int) (positions >>> 52) & 15;
            int b = (int) (positions >>> 56) & 15;
            int c = (int) (positions >>> 60);
            int index = (a << 12) | (b << 8) | (c << 4) | blank;
            return first.distance(positions, blank)
                    + second.distance(positions, blank)
                    + (third[index] & 255);
        }
    }
}
