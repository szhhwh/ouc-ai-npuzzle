package stud.g01.problem.npuzzle;

import static stud.g01.problem.npuzzle.RankedPdbEncoding.blankRank;

/** Exact ranked 6-6-3 for ordinary search. */
public final class RankedPdbHeuristic {
    private static final int[][] REGULAR_GROUPS = {
        {1, 2, 5, 6, 9, 10}, {3, 4, 7, 8, 11, 12}, {13, 14, 15}
    };

    private RankedPdbHeuristic() {}

    /** Immutable distances and goal transformations shared by search contexts. */
    static final class Tables {
        final int cells;
        final int[][] groups;
        // Exact tables use [group][blank ordinal][rank].
        final byte[][][] tables;
        final int[] transpose, symPerm;
        final boolean symmetric;
        final RankedPdbEncoding encoding;

        Tables(int size, int[][] groups, byte[][][] tables, byte[] goalCells) {
            cells = size * size;
            this.groups = groups;
            this.tables = tables;
            int maxK = 0;
            for (int[] group : groups) maxK = Math.max(maxK, group.length);
            encoding = new RankedPdbEncoding(cells, maxK);
            transpose = new int[cells];
            for (int i = 0; i < cells; i++) transpose[i] = (i % size) * size + i / size;
            int[] goalPos = new int[cells];
            for (int i = 0; i < cells; i++) goalPos[goalCells[i]] = i;
            symPerm = new int[cells];
            for (int v = 0; v < cells; v++) symPerm[v] = goalCells[transpose[goalPos[v]]];
            symmetric = symPerm[0] == 0;
        }

        int distance(int group, int rank, int occupied, int blank) {
            int q = blankRank(occupied, blank);
            return tables[group][q][rank] & 0xFF;
        }

        int hMax(PuzzleBoard state) {
            int[] pos = new int[cells];
            for (int i = 0; i < cells; i++) pos[state.cells()[i]] = i;
            int[] tuple = new int[encoding.maxTiles];
            int direct = h(pos, state.blank(), tuple, false);
            return symmetric
                    ? Math.max(direct, h(pos, transpose[state.blank()], tuple, true))
                    : direct;
        }

        private int h(int[] pos, int blank, int[] tuple, boolean mirrored) {
            int sum = 0;
            for (int g = 0; g < groups.length; g++) {
                int[] tiles = groups[g];
                int occupied = 0;
                for (int j = 0; j < tiles.length; j++) {
                    int tile = tiles[j];
                    // symPerm is an involution, so it is also its own inverse.
                    tuple[j] = mirrored ? transpose[pos[symPerm[tile]]] : pos[tile];
                    occupied |= 1 << tuple[j];
                }
                sum += distance(g, encoding.rankTuple(tuple, tiles.length), occupied, blank);
            }
            return sum;
        }
    }

    static byte[][] loadOrBuildGroup(int size, byte[] goal, int[] tiles, int group) {
        StringBuilder signature = new StringBuilder(size + "-" + group + "-" + tiles.length);
        for (int tile : tiles) signature.append('-').append(tile);
        PdbCache cache =
                new PdbCache(
                        "ranked-" + signature + ".bin", PdbCache.Format.RANKED, size, goal, tiles);
        int blocks = size * size - tiles.length;
        int ranks = new RankedPdbEncoding(size * size, tiles.length).rankCount(tiles.length);
        byte[][] cached =
                cache.tryLoad(
                        in -> {
                            if (in.readInt() != blocks || in.readInt() != ranks) return null;
                            byte[][] data = new byte[blocks][];
                            for (int q = 0; q < blocks; q++) {
                                data[q] = new byte[ranks];
                                in.readFully(data[q]);
                            }
                            return data;
                        });
        if (cached != null) {
            System.out.println("[RPDB] Cache loaded: " + cache.file);
            return cached;
        }
        long start = System.nanoTime();
        byte[][] data = RankedPdbBuilder.build(size, goal, tiles);
        System.out.printf(
                "[RPDB] Built %s: %,d bytes, %.1f s%n",
                cache.file, (long) blocks * ranks, (System.nanoTime() - start) / 1_000_000_000.0);
        cache.tryWrite(
                out -> {
                    out.writeInt(blocks);
                    out.writeInt(ranks);
                    for (byte[] block : data) out.write(block);
                });
        return data;
    }
}
