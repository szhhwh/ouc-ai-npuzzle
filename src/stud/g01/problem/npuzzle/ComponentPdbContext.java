package stud.g01.problem.npuzzle;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Incremental occupancy and tile-order ranks for the lossless component PDB. */
final class ComponentPdbContext extends PdbContext {
    private static final int[][] GROUPS = {{1, 2, 3, 4, 5, 6, 7}, {8, 9, 10, 11, 12, 13, 14, 15}};
    private static final Map<String, Tables> BUILT = new ConcurrentHashMap<>();
    private static volatile Tables last;

    static final class Tables {
        final PuzzleBoard goal;
        final ComponentPdbTable[] data = new ComponentPdbTable[2];
        final int[] tileGroup = new int[16], slot = new int[16], weights = new int[16];
        final int[] transpose = new int[16], permutation = new int[16];
        final int[][] crossing = new int[16][16], symmetricCrossing = new int[16][16];
        final boolean symmetric;

        Tables(PuzzleBoard goal) {
            this.goal = goal;
            int[] pos = new int[16];
            for (int i = 0; i < 16; i++) {
                transpose[i] = (i % 4) * 4 + i / 4;
                pos[goal.cells()[i]] = i;
            }
            for (int v = 0; v < 16; v++) permutation[v] = goal.cells()[transpose[pos[v]]];
            symmetric = permutation[0] == 0;
            for (int g = 0; g < GROUPS.length; g++) {
                data[g] = ComponentPdbTable.load(4, goal.cells(), GROUPS[g], g);
                for (int j = 0; j < GROUPS[g].length; j++) {
                    int tile = GROUPS[g][j];
                    tileGroup[tile] = g;
                    slot[tile] = j;
                    weights[tile] = ComponentPdbTable.factorial(GROUPS[g].length - j - 1);
                }
            }
            for (int tile = 1; tile < 16; tile++)
                for (int other = 1; other < 16; other++)
                    if (tileGroup[tile] == tileGroup[other])
                        crossing[tile][other] =
                                slot[other] < slot[tile] ? -weights[other] : weights[tile];
            for (int tile = 1; tile < 16; tile++)
                for (int other = 1; other < 16; other++)
                    symmetricCrossing[tile][other] = crossing[tile][permutation[other]];
        }
    }

    static Tables tablesFor(PuzzleBoard goal) {
        Tables tables = last;
        if (tables != null && tables.goal == goal) return tables;
        tables = BUILT.computeIfAbsent(goal.toString(), ignored -> new Tables(goal));
        last = tables;
        return tables;
    }

    private final Tables tables;
    private final int[] masks = new int[2], masksSym = new int[2];
    private final int[] ranks = new int[2], ranksSym = new int[2];
    private final int[] values = new int[2], valuesSym = new int[2];
    private final boolean incremental;
    private final int[] fullPositions;
    private final int[][] fullTuples;

    ComponentPdbContext(PuzzleBoard initial, Tables tables) {
        this(initial, tables, PdbOptions.fromSystemProperties());
    }

    ComponentPdbContext(PuzzleBoard initial, Tables tables, PdbOptions options) {
        super(initial, tables.goal.cells());
        this.tables = tables;
        incremental = options.incremental();
        symmetric = options.symmetry() && tables.symmetric;
        fullPositions = incremental ? null : new int[16];
        fullTuples = incremental ? null : new int[][] {new int[7], new int[8]};
        int[] pos = new int[16], posSym = new int[16];
        for (int i = 0; i < 16; i++) {
            pos[board[i]] = i;
            posSym[tables.permutation[board[i]]] = tables.transpose[i];
        }
        for (int g = 0; g < GROUPS.length; g++) {
            int[] p = new int[GROUPS[g].length], ps = new int[p.length];
            for (int j = 0; j < p.length; j++) {
                int tile = GROUPS[g][j];
                p[j] = pos[tile];
                ps[j] = posSym[tile];
                masks[g] |= 1 << p[j];
                masksSym[g] |= 1 << ps[j];
            }
            ranks[g] = ComponentPdbTable.permutationRank(p);
            ranksSym[g] = ComponentPdbTable.permutationRank(ps);
            values[g] = tables.data[g].distance(masks[g], ranks[g], blank);
            directHeuristic += values[g];
            if (symmetric) {
                valuesSym[g] =
                        tables.data[g].distance(masksSym[g], ranksSym[g], tables.transpose[blank]);
                symmetricHeuristic += valuesSym[g];
            }
        }
    }

    private int rankAfter(boolean mirrored, int rank, int tile, int from, int to) {
        if (Math.abs(to - from) == 1) return rank;
        int low = Math.min(from, to);
        // A vertical move crosses exactly three positions in row-major order.
        int[] contributions = mirrored ? tables.symmetricCrossing[tile] : tables.crossing[tile];
        int delta =
                mirrored
                        ? contributions[board[tables.transpose[low + 1]]]
                                + contributions[board[tables.transpose[low + 2]]]
                                + contributions[board[tables.transpose[low + 3]]]
                        : contributions[board[low + 1]]
                                + contributions[board[low + 2]]
                                + contributions[board[low + 3]];
        return rank + (to > from ? delta : -delta);
    }

    @Override
    void prepareValues(Move move) {
        if (!incremental) {
            prepareFullValues(move);
            return;
        }
        // The larger orientation is more likely to reject a candidate on its own.
        if (!symmetric || directHeuristic >= symmetricHeuristic) {
            prepareDirect(move);
            if (move.direct > move.limit) {
                move.symmetric = 0;
                return;
            }
            move.symmetric = symmetricHeuristic;
            if (symmetric) prepareSymmetric(move);
        } else {
            prepareSymmetric(move);
            if (move.symmetric > move.limit) {
                move.direct = 0;
                return;
            }
            prepareDirect(move);
        }
    }

    /** Rebuild all occupancy masks and permutation ranks, without parent query state. */
    private void prepareFullValues(Move move) {
        for (int i = 0; i < cells; i++) fullPositions[board[i]] = i;
        fullPositions[0] = move.to;
        fullPositions[move.tile] = move.from;
        // Keep the same orientation order and bounded-query result as incremental search.
        if (!symmetric || directHeuristic >= symmetricHeuristic) {
            move.direct = fullDistance(move.to, false);
            move.symmetric =
                    symmetric && move.direct <= move.limit
                            ? fullDistance(tables.transpose[move.to], true)
                            : 0;
        } else {
            move.symmetric = fullDistance(tables.transpose[move.to], true);
            move.direct = move.symmetric <= move.limit ? fullDistance(move.to, false) : 0;
        }
    }

    private int fullDistance(int blank, boolean mirrored) {
        int sum = 0;
        for (int g = 0; g < GROUPS.length; g++) {
            int[] tuple = fullTuples[g];
            int occupied = 0;
            for (int j = 0; j < tuple.length; j++) {
                int tile = GROUPS[g][j];
                tuple[j] =
                        mirrored
                                ? tables.transpose[fullPositions[tables.permutation[tile]]]
                                : fullPositions[tile];
                occupied |= 1 << tuple[j];
            }
            sum +=
                    tables.data[g].distance(
                            occupied, ComponentPdbTable.permutationRank(tuple), blank);
        }
        return sum;
    }

    private void prepareDirect(Move move) {
        int g = tables.tileGroup[move.tile];
        move.group = g;
        move.oldIndex = ranks[g];
        move.index = rankAfter(false, ranks[g], move.tile, move.to, move.from);
        int occupied = masks[g] ^ (1 << move.from) ^ (1 << move.to);
        move.oldValue = values[g];
        move.value = tables.data[g].distance(occupied, move.index, move.to);
        move.direct = directHeuristic + move.value - move.oldValue;
    }

    private void prepareSymmetric(Move move) {
        int tile = tables.permutation[move.tile], gs = tables.tileGroup[tile];
        int from = tables.transpose[move.from], to = tables.transpose[move.to];
        move.symmetricGroup = gs;
        move.oldSymmetricIndex = ranksSym[gs];
        move.symmetricIndex = rankAfter(true, ranksSym[gs], tile, to, from);
        move.oldSymmetricValue = valuesSym[gs];
        int occupied = masksSym[gs] ^ (1 << from) ^ (1 << to);
        move.symmetricValue = tables.data[gs].distance(occupied, move.symmetricIndex, to);
        move.symmetric = symmetricHeuristic + move.symmetricValue - move.oldSymmetricValue;
    }

    @Override
    void updateValues(Move move, boolean undo) {
        if (!incremental) return;
        ranks[move.group] = undo ? move.oldIndex : move.index;
        values[move.group] = undo ? move.oldValue : move.value;
        masks[move.group] ^= (1 << move.to) | (1 << move.from);
        if (symmetric) {
            int g = move.symmetricGroup;
            ranksSym[g] = undo ? move.oldSymmetricIndex : move.symmetricIndex;
            valuesSym[g] = undo ? move.oldSymmetricValue : move.symmetricValue;
            masksSym[g] ^= (1 << tables.transpose[move.to]) | (1 << tables.transpose[move.from]);
        }
    }
}
