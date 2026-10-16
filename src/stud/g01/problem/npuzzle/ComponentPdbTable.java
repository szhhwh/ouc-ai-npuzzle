package stud.g01.problem.npuzzle;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import java.util.Arrays;

/**
 * Lossless PDB: blanks in the same zero-cost component share one distance. For each occupancy, tile
 * permutations index a bit-packed palette of vectors.
 */
final class ComponentPdbTable {
    private static final byte[][] COMPONENTS = geometry();
    final int k, permutations;
    private final long[][] codes = new long[1 << 16][];
    private final long[][] palettes = new long[1 << 16][];
    private final byte[] widths = new byte[1 << 16];

    private ComponentPdbTable(int k) {
        this.k = k;
        permutations = factorial(k);
    }

    int distance(int occupied, int permutation, int blank) {
        int width = widths[occupied];
        int bit = permutation * width;
        long[] words = codes[occupied];
        int word = bit >>> 6, shift = bit & 63;
        long id = words[word] >>> shift;
        if (shift + width > 64) id |= words[word + 1] << (64 - shift);
        id &= (1L << width) - 1;
        int component = COMPONENTS[occupied][blank];
        return (int) (palettes[occupied][(int) id] >>> (component * 8)) & 255;
    }

    static int factorial(int k) {
        int result = 1;
        for (int i = 2; i <= k; i++) result *= i;
        return result;
    }

    static int permutationRank(int[] positions) {
        int result = 0;
        // Later tile positions are the unused symbols in this permutation.
        int remaining = 0;
        for (int p : positions) remaining |= 1 << p;
        for (int p : positions) {
            result +=
                    Integer.bitCount(remaining & ((1 << p) - 1))
                            * factorial(Integer.bitCount(remaining) - 1);
            remaining ^= 1 << p;
        }
        return result;
    }

    static ComponentPdbTable load(int size, byte[] goal, int[] tiles, int group) {
        StringBuilder signature = new StringBuilder(size + "-" + group + "-" + tiles.length);
        for (int tile : tiles) signature.append('-').append(tile);
        PdbCache cache =
                new PdbCache(
                        "component-le-" + signature + ".bin",
                        PdbCache.Format.COMPONENT_LE,
                        size,
                        goal,
                        tiles);
        ComponentPdbTable loaded = readCache(cache, tiles.length, ByteOrder.LITTLE_ENDIAN);
        if (loaded != null) return loaded;
        loaded =
                readCache(
                        new PdbCache(
                                "component-" + signature + ".bin",
                                PdbCache.Format.COMPONENT,
                                size,
                                goal,
                                tiles),
                        tiles.length,
                        ByteOrder.BIG_ENDIAN);
        if (loaded == null) {
            RankedPdbEncoding encoding = new RankedPdbEncoding(16, tiles.length);
            PdbCache legacy =
                    new PdbCache(
                            "ranked-" + signature + ".bin",
                            PdbCache.Format.RANKED,
                            size,
                            goal,
                            tiles);
            ByteBuffer[] blocks =
                    legacy.mappedBlocks(16 - tiles.length, encoding.rankCount(tiles.length));
            if (blocks == null) {
                byte[][] built =
                        RankedPdbHeuristic.loadOrBuildGroup(size, goal, tiles, group, false);
                blocks = new ByteBuffer[built.length];
                for (int i = 0; i < blocks.length; i++) blocks[i] = ByteBuffer.wrap(built[i]);
            }
            loaded = fromBlocks(tiles.length, blocks);
        }
        ComponentPdbTable table = loaded;
        cache.tryWrite(
                out -> {
                    out.writeInt(table.permutations);
                    byte[] buffer = new byte[1 << 16];
                    LongBuffer longs =
                            ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN).asLongBuffer();
                    for (int mask = 0; mask < (1 << 16); mask++) {
                        if (Integer.bitCount(mask) != tiles.length) continue;
                        out.writeInt(table.palettes[mask].length);
                        out.writeInt(table.widths[mask]);
                        for (long[] values :
                                new long[][] {table.palettes[mask], table.codes[mask]}) {
                            for (int offset = 0;
                                    offset < values.length;
                                    offset += buffer.length / 8) {
                                int count = Math.min(buffer.length / 8, values.length - offset);
                                longs.clear();
                                longs.put(values, offset, count);
                                out.write(buffer, 0, count * 8);
                            }
                        }
                    }
                });
        return table;
    }

    private static ComponentPdbTable readCache(PdbCache cache, int k, ByteOrder order) {
        return cache.tryLoad(
                in -> {
                    if (in.readInt() != factorial(k)) return null;
                    ComponentPdbTable table = new ComponentPdbTable(k);
                    byte[] buffer = new byte[1 << 16];
                    LongBuffer longs = ByteBuffer.wrap(buffer).order(order).asLongBuffer();
                    for (int mask = 0; mask < (1 << 16); mask++) {
                        if (Integer.bitCount(mask) != k) continue;
                        int count = in.readInt();
                        int width = in.readInt();
                        if (count <= 0 || width != 32 - Integer.numberOfLeadingZeros(count - 1))
                            return null;
                        table.widths[mask] = (byte) width;
                        table.palettes[mask] = new long[count];
                        readLongs(in, table.palettes[mask], buffer, longs);
                        int words = Math.max(1, (table.permutations * width + 63) >>> 6);
                        table.codes[mask] = new long[words];
                        readLongs(in, table.codes[mask], buffer, longs);
                    }
                    return table;
                });
    }

    private static void readLongs(
            DataInputStream in, long[] result, byte[] buffer, LongBuffer longs) throws IOException {
        for (int offset = 0; offset < result.length; offset += buffer.length / 8) {
            int count = Math.min(buffer.length / 8, result.length - offset);
            in.readFully(buffer, 0, count * 8);
            longs.clear();
            longs.get(result, offset, count);
        }
    }

    static ComponentPdbTable fromBlocks(int k, ByteBuffer[] blocks) {
        ComponentPdbTable result = new ComponentPdbTable(k);
        RankedPdbEncoding encoding = new RankedPdbEncoding(16, k);
        int[] positions = new int[k], ids = new int[result.permutations];
        int[] representatives = new int[8], ordinals = new int[8];
        // Reused primitive dictionary avoids boxing hundreds of millions of vectors.
        long[] keys = new long[1 << 16], vectors = new long[result.permutations];
        int[] values = new int[keys.length], touched = new int[keys.length];
        for (int mask = 0; mask < (1 << 16); mask++) {
            if (Integer.bitCount(mask) != k) continue;
            int t = 0;
            for (int bits = mask; bits != 0; bits &= bits - 1)
                positions[t++] = Integer.numberOfTrailingZeros(bits);
            int components = 0;
            for (int blank = 0; blank < 16; blank++) {
                if ((mask & (1 << blank)) != 0) continue;
                int c = COMPONENTS[mask][blank];
                representatives[c] = blank;
                components = Math.max(components, c + 1);
            }
            for (int c = 0; c < components; c++)
                ordinals[c] = RankedPdbEncoding.blankRank(mask, representatives[c]);
            int count = 0, p = 0;
            do {
                int rank = encoding.rankTuple(positions, k);
                long vector = 0;
                for (int c = 0; c < components; c++)
                    vector |= (long) (blocks[ordinals[c]].get(rank) & 255) << (8 * c);
                long key = vector + 1;
                int slot = (int) ((key * 0x9E3779B97F4A7C15L) >>> 48);
                while (keys[slot] != 0 && keys[slot] != key) slot = (slot + 1) & (keys.length - 1);
                if (keys[slot] == 0) {
                    keys[slot] = key;
                    touched[count] = slot;
                    values[slot] = count;
                    vectors[count++] = vector;
                }
                ids[p++] = values[slot];
            } while (nextPermutation(positions));
            for (int i = 0; i < count; i++) keys[touched[i]] = 0;
            int width = 32 - Integer.numberOfLeadingZeros(count - 1);
            long[] words = new long[Math.max(1, (result.permutations * width + 63) >>> 6)];
            for (int i = 0; i < ids.length; i++) {
                int bit = i * width, word = bit >>> 6, shift = bit & 63;
                words[word] |= (long) ids[i] << shift;
                if (shift + width > 64) words[word + 1] |= (long) ids[i] >>> (64 - shift);
            }
            result.widths[mask] = (byte) width;
            result.codes[mask] = words;
            result.palettes[mask] = Arrays.copyOf(vectors, count);
        }
        return result;
    }

    private static boolean nextPermutation(int[] positions) {
        int i = positions.length - 2;
        while (i >= 0 && positions[i] >= positions[i + 1]) i--;
        if (i < 0) return false;
        int j = positions.length - 1;
        while (positions[j] <= positions[i]) j--;
        int value = positions[i];
        positions[i] = positions[j];
        positions[j] = value;
        for (int a = i + 1, b = positions.length - 1; a < b; a++, b--) {
            value = positions[a];
            positions[a] = positions[b];
            positions[b] = value;
        }
        return true;
    }

    private static byte[][] geometry() {
        byte[][] result = new byte[1 << 16][];
        int[][] neighbors = PuzzleNeighbors.forSize(4);
        for (int occupied = 0; occupied < result.length; occupied++) {
            byte[] components = new byte[16];
            result[occupied] = components;
            int visited = occupied, c = 0;
            for (int b = 0; b < 16; b++) {
                if ((visited & (1 << b)) != 0) continue;
                int pending = 1 << b;
                visited |= pending;
                while (pending != 0) {
                    int p = Integer.numberOfTrailingZeros(pending);
                    pending &= pending - 1;
                    components[p] = (byte) c;
                    for (int n : neighbors[p]) {
                        if ((visited & (1 << n)) != 0) continue;
                        visited |= 1 << n;
                        pending |= 1 << n;
                    }
                }
                c++;
            }
        }
        return result;
    }
}
