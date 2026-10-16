package stud.g01.problem.npuzzle;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Product-addressed dictionary of exact blank-distance vectors. Invalid product tuples cost two
 * bytes, rather than a complete vector of sixteen distances.
 */
final class DirectPdbTable {
    private final short[] indices;
    private final long[] vectors;
    private final int[] tiles;

    private DirectPdbTable(short[] indices, long[] vectors, int[] tiles) {
        this.indices = indices;
        this.vectors = vectors;
        this.tiles = tiles;
    }

    int distance(long positions, int blank) {
        int tuple = 0, occupied = 0;
        for (int tile : tiles) {
            int p = (int) (positions >>> (tile * 4)) & 15;
            tuple = (tuple << 4) | p;
            occupied |= 1 << p;
        }
        int q = RankedPdbEncoding.blankRank(occupied, blank);
        return (int) (vectors[indices[tuple] & 0xFFFF] >>> (q * 6)) & 63;
    }

    static DirectPdbTable load(int size, byte[] goal, int[] tiles, int group) {
        StringBuilder signature = new StringBuilder(size + "-" + group + "-" + tiles.length);
        for (int tile : tiles) signature.append('-').append(tile);
        PdbCache cache =
                new PdbCache(
                        "direct-" + signature + ".bin", PdbCache.Format.DIRECT, size, goal, tiles);
        int length = 1 << (4 * tiles.length);
        DirectPdbTable loaded =
                cache.tryLoad(
                        in -> {
                            if (in.readInt() != length) return null;
                            int count = in.readInt();
                            if (count <= 0 || count > 65536) return null;
                            long[] vectors = new long[count];
                            for (int i = 0; i < count; i++) vectors[i] = in.readLong();
                            short[] indices = new short[length];
                            byte[] buffer = new byte[1 << 16];
                            for (int i = 0; i < length; i += buffer.length / 2) {
                                int n = Math.min(buffer.length / 2, length - i);
                                in.readFully(buffer, 0, n * 2);
                                ByteBuffer.wrap(buffer).asShortBuffer().get(indices, i, n);
                            }
                            return new DirectPdbTable(indices, vectors, tiles);
                        });
        if (loaded != null) return loaded;
        byte[][] ranked = RankedPdbHeuristic.loadOrBuildGroup(size, goal, tiles, group, false);
        RankedPdbEncoding encoding = new RankedPdbEncoding(size * size, tiles.length);
        int[] positions = new int[tiles.length];
        boolean[] used = new boolean[size * size];
        short[] indices = new short[length];
        long[] vectors = new long[65536];
        Map<Long, Integer> dictionary = new HashMap<>();
        for (int rank = 0; rank < ranked[0].length; rank++) {
            encoding.decode(rank, 0, tiles.length, positions, used);
            int tuple = 0;
            for (int p : positions) tuple = (tuple << 4) | p;
            long vector = 0;
            for (int q = 0; q < ranked.length; q++) {
                int d = ranked[q][rank] & 255;
                if (d >= 63) throw new IllegalStateException("Distance exceeds six-bit vector");
                vector |= (long) d << (q * 6);
            }
            Integer id = dictionary.get(vector);
            if (id == null) {
                id = dictionary.size();
                if (id == vectors.length)
                    throw new IllegalStateException("Dictionary exceeds 16 bits");
                dictionary.put(vector, id);
                vectors[id] = vector;
            }
            indices[tuple] = id.shortValue();
        }
        DirectPdbTable result =
                new DirectPdbTable(indices, Arrays.copyOf(vectors, dictionary.size()), tiles);
        cache.tryWrite(
                out -> {
                    out.writeInt(length);
                    out.writeInt(result.vectors.length);
                    for (long v : result.vectors) out.writeLong(v);
                    byte[] buffer = new byte[1 << 16];
                    for (int i = 0; i < length; i += buffer.length / 2) {
                        int n = Math.min(buffer.length / 2, length - i);
                        ByteBuffer.wrap(buffer).asShortBuffer().put(indices, i, n);
                        out.write(buffer, 0, n * 2);
                    }
                });
        return result;
    }
}
