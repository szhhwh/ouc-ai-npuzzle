package stud.g01.problem.npuzzle;

/** Ranked PDB 分组表的磁盘缓存与构建入口，供 6-6-3 等精确分组共用。 */
public final class RankedPdbHeuristic {
    private RankedPdbHeuristic() {}

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
