package stud.g01.problem.npuzzle;

import static stud.g01.problem.npuzzle.RankedPdbEncoding.blankRank;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Exact ranked 6-6-3 for ordinary search; blank-minimized 7-8 for specialized IDA*. */
public final class RankedPdbHeuristic {
    private static final int[][] REGULAR_GROUPS = {
        {1, 2, 5, 6, 9, 10}, {3, 4, 7, 8, 11, 12}, {13, 14, 15}
    };
    private static final int[][] LARGE_GROUPS = {
        {1, 2, 3, 4, 5, 6, 7}, {8, 9, 10, 11, 12, 13, 14, 15}
    };
    private static final Map<String, Tables> BUILT = new ConcurrentHashMap<>();
    private static volatile Memo lastMemo;

    private record Memo(PuzzleBoard goal, boolean minBlank, Tables tables) {}

    private RankedPdbHeuristic() {}

    static Tables tablesFor(PuzzleBoard goal) {
        return tablesFor(goal, true);
    }

    static Tables regularTablesFor(PuzzleBoard goal) {
        return tablesFor(goal, false);
    }

    private static Tables tablesFor(PuzzleBoard goal, boolean minBlank) {
        Memo memo = lastMemo;
        if (memo != null && memo.goal == goal && memo.minBlank == minBlank) return memo.tables;
        if (goal.size() != 4) {
            throw new UnsupportedOperationException("Ranked PDBs require a 4 x 4 board");
        }
        String key = minBlank + "#" + goal;
        Tables tables = BUILT.computeIfAbsent(key, k -> Tables.build(goal, minBlank));
        lastMemo = new Memo(goal, minBlank, tables);
        return tables;
    }

    /** Immutable distances and goal transformations shared by search contexts. */
    static final class Tables {
        final int cells;
        final int[][] groups;
        // Exact tables use [group][blank ordinal][rank]; compressed tables have one block.
        final byte[][][] tables;
        final boolean minBlank;
        final int[] transpose, symPerm;
        final boolean symmetric;
        final RankedPdbEncoding encoding;

        Tables(int size, int[][] groups, byte[][][] tables, byte[] goalCells, boolean minBlank) {
            cells = size * size;
            this.groups = groups;
            this.tables = tables;
            this.minBlank = minBlank;
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

        private static Tables build(PuzzleBoard goal, boolean minBlank) {
            int[][] groups = minBlank ? LARGE_GROUPS : REGULAR_GROUPS;
            byte[][][] data = new byte[groups.length][][];
            for (int g = 0; g < groups.length; g++) {
                data[g] = loadOrBuildGroup(goal.size(), goal.cells(), groups[g], g, minBlank);
            }
            return new Tables(goal.size(), groups, data, goal.cells(), minBlank);
        }

        int distance(int group, int rank, int occupied, int blank) {
            int q = minBlank ? 0 : blankRank(occupied, blank);
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

    static byte[][] loadOrBuildGroup(
            int size, byte[] goal, int[] tiles, int group, boolean minBlank) {
        String prefix = minBlank ? "ranked-min-" : "ranked-";
        StringBuilder signature = new StringBuilder(size + "-" + group + "-" + tiles.length);
        for (int tile : tiles) signature.append('-').append(tile);
        PdbCache cache =
                new PdbCache(
                        prefix + signature + ".bin",
                        minBlank ? PdbCache.Format.RANKED_MIN : PdbCache.Format.RANKED,
                        size,
                        goal,
                        tiles);
        int blocks = minBlank ? 1 : size * size - tiles.length;
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
        byte[][] converted =
                minBlank
                        ? compressLegacy(
                                new PdbCache(
                                        "ranked-" + signature + ".bin",
                                        PdbCache.Format.RANKED,
                                        size,
                                        goal,
                                        tiles),
                                size * size - tiles.length,
                                ranks)
                        : null;
        byte[][] data =
                converted != null ? converted : RankedPdbBuilder.build(size, goal, tiles, minBlank);
        System.out.printf(
                "[RPDB] %s %s: %,d bytes, %.1f s%n",
                converted == null ? "Built" : "Converted",
                cache.file,
                (long) blocks * ranks,
                (System.nanoTime() - start) / 1_000_000_000.0);
        cache.tryWrite(
                out -> {
                    out.writeInt(blocks);
                    out.writeInt(ranks);
                    for (byte[] block : data) out.write(block);
                });
        return data;
    }

    /** Upgrade a valid old table with only the result and a small streaming buffer in memory. */
    private static byte[][] compressLegacy(PdbCache legacy, int blocks, int ranks) {
        return legacy.tryLoad(
                in -> {
                    if (in.readInt() != blocks || in.readInt() != ranks) return null;
                    byte[] minimum = new byte[ranks];
                    in.readFully(minimum);
                    byte[] buffer = new byte[Math.min(ranks, 1 << 20)];
                    for (int q = 1; q < blocks; q++) {
                        for (int offset = 0; offset < ranks; offset += buffer.length) {
                            int length = Math.min(buffer.length, ranks - offset);
                            in.readFully(buffer, 0, length);
                            for (int i = 0; i < length; i++) {
                                int value = buffer[i] & 0xFF;
                                if (value < (minimum[offset + i] & 0xFF))
                                    minimum[offset + i] = buffer[i];
                            }
                        }
                    }
                    return new byte[][] {minimum};
                });
    }
}
