package stud.g01.problem.npuzzle;

import static stud.g01.problem.npuzzle.RankedPdbEncoding.blankRank;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.BitSet;

/** BFS over zero-cost blank components; each remaining edge moves a pattern tile. */
final class RankedPdbBuilder {
    private final int cells, k;
    private final int blankBits, blankMask;
    private final int[][] neighbors;
    private final RankedPdbEncoding encoding;
    private final byte[][] distances;
    private final BitSet[] visited;
    private final int[] components;
    private final IntQueue queue = new IntQueue();

    private RankedPdbBuilder(int size, int k, boolean minBlank) {
        cells = size * size;
        this.k = k;
        blankBits = Integer.SIZE - Integer.numberOfLeadingZeros(cells - k - 1);
        blankMask = (1 << blankBits) - 1;
        neighbors = PuzzleNeighbors.forSize(size);
        encoding = new RankedPdbEncoding(cells, k);
        int ranks = encoding.rankCount(k);
        distances = new byte[minBlank ? 1 : cells - k][ranks];
        for (byte[] block : distances) Arrays.fill(block, (byte) 0xFF);
        visited = minBlank ? new BitSet[cells - k] : null;
        if (visited != null) {
            for (int q = 0; q < visited.length; q++) visited[q] = new BitSet(ranks);
        }
        // At most 4 MiB for 4 x 4, shared by all ranks with the same occupancy mask.
        components = new int[(1 << cells) * cells];
    }

    static byte[][] build(int size, byte[] goal, int[] tiles, boolean minBlank) {
        return new RankedPdbBuilder(size, tiles.length, minBlank).build(goal, tiles);
    }

    private byte[][] build(byte[] goal, int[] tiles) {
        int[] positions = new int[k];
        int occupied = 0;
        for (int j = 0; j < k; j++) {
            for (int p = 0; p < cells; p++) {
                if (goal[p] == tiles[j]) positions[j] = p;
            }
            occupied |= 1 << positions[j];
        }
        int rank = encoding.rankTuple(positions, k);
        // Every component with the pattern tiles at their goals is a source.
        for (int blank = 0; blank < cells; blank++) {
            if ((occupied & (1 << blank)) == 0) enqueue(rank, occupied, blank, 0);
        }
        boolean[] used = new boolean[cells];
        for (int depth = 0; !queue.isEmpty(); depth++) {
            int count = queue.size();
            for (int i = 0; i < count; i++) {
                int code = queue.remove();
                rank = code >>> blankBits;
                int blank = encoding.decode(rank, code & blankMask, k, positions, used);
                occupied = 0;
                for (int p : positions) occupied |= 1 << p;
                int reachable = component(occupied, blank);
                for (int j = 0; j < k; j++) {
                    int tilePos = positions[j];
                    for (int destination : neighbors[tilePos]) {
                        if ((reachable & (1 << destination)) == 0) continue;
                        positions[j] = destination;
                        int childRank = encoding.rankTuple(positions, k);
                        positions[j] = tilePos;
                        int childOccupied = occupied ^ (1 << tilePos) ^ (1 << destination);
                        enqueue(childRank, childOccupied, tilePos, depth + 1);
                    }
                }
            }
        }
        return distances;
    }

    private void enqueue(int rank, int occupied, int blank, int depth) {
        int q = blankRank(occupied, blank);
        if (visited == null ? distances[q][rank] != (byte) 0xFF : visited[q].get(rank)) return;
        int reachable = component(occupied, blank);
        for (int bits = reachable; bits != 0; bits &= bits - 1) {
            int b = Integer.numberOfTrailingZeros(bits);
            int ordinal = blankRank(occupied, b);
            if (visited == null) distances[ordinal][rank] = (byte) depth;
            else visited[ordinal].set(rank);
        }
        // Layers are processed in order, so the first component fixes the minimum over blanks.
        if (visited != null && distances[0][rank] == (byte) 0xFF) distances[0][rank] = (byte) depth;
        // The largest supported group uses 29 rank bits + 3 blank bits, including the sign bit.
        queue.add((rank << blankBits) | q);
    }

    private int component(int occupied, int blank) {
        int base = occupied * cells;
        int cached = components[base + blank];
        if (cached != 0) return cached;
        int reachable = 1 << blank;
        int pending = reachable;
        while (pending != 0) {
            int p = Integer.numberOfTrailingZeros(pending);
            pending &= pending - 1;
            for (int neighbor : neighbors[p]) {
                int bit = 1 << neighbor;
                if (((occupied | reachable) & bit) != 0) continue;
                reachable |= bit;
                pending |= bit;
            }
        }
        for (int bits = reachable; bits != 0; bits &= bits - 1) {
            components[base + Integer.numberOfTrailingZeros(bits)] = reachable;
        }
        return reachable;
    }

    /** Fixed-size chunks avoid retaining both old and doubled arrays during a large BFS. */
    private static final class IntQueue {
        private static final int CHUNK_SIZE = 1 << 16;
        private final ArrayDeque<int[]> chunks = new ArrayDeque<>();
        private int[] headChunk = new int[CHUNK_SIZE];
        private int[] tailChunk = headChunk;
        private int head, tail, count;

        IntQueue() {
            chunks.addLast(headChunk);
        }

        int size() {
            return count;
        }

        boolean isEmpty() {
            return count == 0;
        }

        void add(int value) {
            if (tail == CHUNK_SIZE) {
                tailChunk = new int[CHUNK_SIZE];
                chunks.addLast(tailChunk);
                tail = 0;
            }
            tailChunk[tail++] = value;
            count++;
        }

        int remove() {
            if (head == CHUNK_SIZE) {
                chunks.removeFirst();
                headChunk = chunks.getFirst();
                head = 0;
            }
            int value = headChunk[head++];
            count--;
            return value;
        }
    }
}
