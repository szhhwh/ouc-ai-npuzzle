package stud.g01.problem.npuzzle;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Incremental maximum over multiple additive 7-8 component PDBs.
 *
 * <p>Each partition is independently additive. Partitions are combined with max because tiles may
 * overlap between different partitions.
 */
final class ComponentPdbContext extends PdbContext {
    private static final int[][][] PARTITIONS = {
        {{1, 2, 3, 4, 5, 6, 7}, {8, 9, 10, 11, 12, 13, 14, 15}},
        {{1, 2, 5, 6, 9, 10, 13, 14}, {3, 4, 7, 8, 11, 12, 15}}
    };
    private static final Map<String, Tables> BUILT = new ConcurrentHashMap<>();
    private static volatile Tables last;

    static final class Partition {
        final int[][] groups;
        final ComponentPdbTable[] data;
        final int[] tileGroup = new int[16], slot = new int[16], weights = new int[16];
        final int[] transpose;
        final int[] permutation;
        final int[][] crossing = new int[16][16], symmetricCrossing = new int[16][16];
        final boolean symmetric;
        final int maxHeuristic;

        Partition(int[][] groups, PuzzleBoard goal, int partition) {
            this.groups = groups;
            data = new ComponentPdbTable[groups.length];
            transpose = new int[16];
            permutation = new int[16];
            int[] pos = new int[16];
            for (int i = 0; i < 16; i++) {
                transpose[i] = (i % 4) * 4 + i / 4;
                pos[goal.cells()[i]] = i;
            }
            for (int v = 0; v < 16; v++) permutation[v] = goal.cells()[transpose[pos[v]]];
            boolean groupsInvariant = permutation[0] == 0;
            for (int tile = 1; tile < 16 && groupsInvariant; tile++) {
                if (groupIndex(groups, tile) != groupIndex(groups, permutation[tile])) {
                    groupsInvariant = false;
                }
            }
            // A partition invariant under the goal symmetry needs no second table query.
            symmetric = partition == 0 && permutation[0] == 0 && !groupsInvariant;
            for (int g = 0; g < groups.length; g++) {
                data[g] = ComponentPdbTable.load(4, goal.cells(), groups[g], partition * 2 + g);
                for (int j = 0; j < groups[g].length; j++) {
                    int tile = groups[g][j];
                    tileGroup[tile] = g;
                    slot[tile] = j;
                    weights[tile] = ComponentPdbTable.factorial(groups[g].length - j - 1);
                }
            }
            int maximum = 0;
            for (ComponentPdbTable table : data) maximum += table.maxDistance;
            maxHeuristic = maximum;
            for (int tile = 1; tile < 16; tile++) {
                for (int other = 1; other < 16; other++) {
                    if (tileGroup[tile] == tileGroup[other]) {
                        crossing[tile][other] =
                                slot[other] < slot[tile] ? -weights[other] : weights[tile];
                    }
                }
            }
            for (int tile = 1; tile < 16; tile++) {
                for (int other = 1; other < 16; other++) {
                    symmetricCrossing[tile][other] = crossing[tile][permutation[other]];
                }
            }
        }

        private static int groupIndex(int[][] groups, int tile) {
            for (int g = 0; g < groups.length; g++) {
                for (int value : groups[g]) {
                    if (value == tile) return g;
                }
            }
            return -1;
        }
    }

    static final class Tables {
        final PuzzleBoard goal;
        final Partition[] partitions;
        final boolean symmetric;

        Tables(PuzzleBoard goal, int[][][] partitionSet) {
            this.goal = goal;
            partitions = new Partition[partitionSet.length];
            boolean hasSymmetry = false;
            for (int i = 0; i < partitionSet.length; i++) {
                partitions[i] = new Partition(partitionSet[i], goal, i);
                hasSymmetry |= partitions[i].symmetric;
            }
            symmetric = hasSymmetry;
        }
    }

    static Tables tablesFor(PuzzleBoard goal) {
        Tables tables = last;
        if (tables != null && tables.goal == goal) {
            return tables;
        }
        String key = String.valueOf(goal);
        tables = BUILT.computeIfAbsent(key, ignored -> new Tables(goal, PARTITIONS));
        last = tables;
        return tables;
    }

    private final Tables tables;
    private final int[] masks, masksSym;
    private final int[] ranks, ranksSym;
    private final int[] values, valuesSym;
    private final int[] partitionHeuristics, symmetricPartitionHeuristics;
    private final int[] partitionOrder = new int[2];
    private final boolean[] directFirst;

    ComponentPdbContext(PuzzleBoard initial, Tables tables) {
        super(initial);
        this.tables = tables;
        int count = tables.partitions.length;
        masks = new int[count * 2];
        masksSym = new int[count * 2];
        ranks = new int[count * 2];
        ranksSym = new int[count * 2];
        values = new int[count * 2];
        valuesSym = new int[count * 2];
        partitionHeuristics = new int[count];
        symmetricPartitionHeuristics = new int[count];
        directFirst = new boolean[count];
        symmetric = tables.symmetric;

        int[] pos = new int[16], posSym = new int[16];
        for (int i = 0; i < 16; i++) pos[board[i]] = i;

        for (int p = 0; p < tables.partitions.length; p++) {
            Partition partition = tables.partitions[p];
            for (int i = 0; i < 16; i++) {
                posSym[partition.permutation[board[i]]] = partition.transpose[i];
            }
            for (int g = 0; g < partition.groups.length; g++) {
                int index = (p << 1) | g;
                int[] group = partition.groups[g];
                int[] positions = new int[group.length];
                int[] symmetricPositions = new int[group.length];
                for (int j = 0; j < group.length; j++) {
                    int tile = group[j];
                    positions[j] = pos[tile];
                    symmetricPositions[j] = posSym[tile];
                    masks[index] |= 1 << positions[j];
                    masksSym[index] |= 1 << symmetricPositions[j];
                }
                ranks[index] = ComponentPdbTable.permutationRank(positions);
                ranksSym[index] = ComponentPdbTable.permutationRank(symmetricPositions);
                values[index] = partition.data[g].distance(masks[index], ranks[index], blank);
                partitionHeuristics[p] += values[index];
                if (partition.symmetric) {
                    valuesSym[index] =
                            partition.data[g].distance(
                                    masksSym[index], ranksSym[index], partition.transpose[blank]);
                    symmetricPartitionHeuristics[p] += valuesSym[index];
                }
            }
        }
        refreshHeuristics();
    }

    private int rankAfter(
            Partition partition, boolean mirrored, int rank, int tile, int from, int to) {
        if (Math.abs(to - from) == 1) return rank;
        int low = Math.min(from, to);
        int[] contributions =
                mirrored ? partition.symmetricCrossing[tile] : partition.crossing[tile];
        int delta =
                mirrored
                        ? contributions[board[partition.transpose[low + 1]]]
                                + contributions[board[partition.transpose[low + 2]]]
                                + contributions[board[partition.transpose[low + 3]]]
                        : contributions[board[low + 1]]
                                + contributions[board[low + 2]]
                                + contributions[board[low + 3]];
        return rank + (to > from ? delta : -delta);
    }

    @Override
    void prepareValues(Move move) {
        move.ensurePdbCapacity(tables.partitions.length);
        move.pdbPreparedMask = 0;
        move.direct = 0;
        move.symmetric = 0;
        int firstPartition = partitionOrder[0];
        int secondPartition = partitionOrder[1];
        if (preparePartition(move, firstPartition)) return;
        if (Math.max(move.direct, move.symmetric)
                >= tables.partitions[secondPartition].maxHeuristic) {
            return;
        }
        preparePartition(move, secondPartition);
    }

    private boolean preparePartition(Move move, int p) {
        Partition partition = tables.partitions[p];
        move.symmetricGroups[p] = -1;
        if (directFirst[p]) {
            move.direct = Math.max(move.direct, prepareDirect(move, p, partition));
            if (move.direct > move.limit) return true;
            if (partition.symmetric) {
                move.symmetric = Math.max(move.symmetric, prepareSymmetric(move, p, partition));
                if (move.symmetric > move.limit) return true;
            }
        } else {
            if (partition.symmetric) {
                move.symmetric = Math.max(move.symmetric, prepareSymmetric(move, p, partition));
                if (move.symmetric > move.limit) return true;
            }
            move.direct = Math.max(move.direct, prepareDirect(move, p, partition));
            if (move.direct > move.limit) return true;
        }
        move.pdbPreparedMask |= 1 << p;
        return false;
    }

    private int partitionScore(int partition) {
        return Math.max(partitionHeuristics[partition], symmetricPartitionHeuristics[partition]);
    }

    private int prepareDirect(Move move, int p, Partition partition) {
        int g = partition.tileGroup[move.tile];
        int index = (p << 1) | g;
        move.groups[p] = g;
        move.oldIndices[p] = ranks[index];
        move.indices[p] = rankAfter(partition, false, ranks[index], move.tile, move.to, move.from);
        int occupied = masks[index] ^ (1 << move.from) ^ (1 << move.to);
        move.oldValues[p] = values[index];
        move.values[p] = partition.data[g].distance(occupied, move.indices[p], move.to);
        return partitionHeuristics[p] + move.values[p] - move.oldValues[p];
    }

    private int prepareSymmetric(Move move, int p, Partition partition) {
        int tile = partition.permutation[move.tile];
        int g = partition.tileGroup[tile];
        int index = (p << 1) | g;
        int from = partition.transpose[move.from];
        int to = partition.transpose[move.to];
        move.symmetricGroups[p] = g;
        move.oldSymmetricIndices[p] = ranksSym[index];
        move.symmetricIndices[p] = rankAfter(partition, true, ranksSym[index], tile, to, from);
        int occupied = masksSym[index] ^ (1 << from) ^ (1 << to);
        move.oldSymmetricValues[p] = valuesSym[index];
        move.symmetricValues[p] =
                partition.data[g].distance(occupied, move.symmetricIndices[p], to);
        return symmetricPartitionHeuristics[p]
                + move.symmetricValues[p]
                - move.oldSymmetricValues[p];
    }

    @Override
    void updateValues(Move move, boolean undo) {
        for (int p = 0; p < tables.partitions.length; p++) {
            Partition partition = tables.partitions[p];
            if ((move.pdbPreparedMask & (1 << p)) == 0) preparePartitionForApply(move, p);
            int g = move.groups[p];
            int index = (p << 1) | g;
            ranks[index] = undo ? move.oldIndices[p] : move.indices[p];
            values[index] = undo ? move.oldValues[p] : move.values[p];
            masks[index] ^= (1 << move.to) | (1 << move.from);
            partitionHeuristics[p] +=
                    undo ? move.oldValues[p] - move.values[p] : move.values[p] - move.oldValues[p];

            int gs = move.symmetricGroups[p];
            if (gs >= 0) {
                int symmetricIndex = (p << 1) | gs;
                ranksSym[symmetricIndex] =
                        undo ? move.oldSymmetricIndices[p] : move.symmetricIndices[p];
                valuesSym[symmetricIndex] =
                        undo ? move.oldSymmetricValues[p] : move.symmetricValues[p];
                masksSym[symmetricIndex] ^=
                        (1 << partition.transpose[move.to]) | (1 << partition.transpose[move.from]);
                symmetricPartitionHeuristics[p] +=
                        undo
                                ? move.oldSymmetricValues[p] - move.symmetricValues[p]
                                : move.symmetricValues[p] - move.oldSymmetricValues[p];
            }
        }
        refreshHeuristics();
        move.direct = directHeuristic;
        move.symmetric = symmetricHeuristic;
    }

    private void preparePartitionForApply(Move move, int p) {
        Partition partition = tables.partitions[p];
        prepareDirect(move, p, partition);
        if (partition.symmetric) prepareSymmetric(move, p, partition);
        move.pdbPreparedMask |= 1 << p;
    }

    private void refreshHeuristics() {
        directHeuristic = 0;
        symmetricHeuristic = 0;
        for (int value : partitionHeuristics) directHeuristic = Math.max(directHeuristic, value);
        for (int value : symmetricPartitionHeuristics)
            symmetricHeuristic = Math.max(symmetricHeuristic, value);
        partitionOrder[0] = partitionScore(0) >= partitionScore(1) ? 0 : 1;
        partitionOrder[1] = partitionOrder[0] ^ 1;
        for (int p = 0; p < tables.partitions.length; p++)
            directFirst[p] = partitionHeuristics[p] >= symmetricPartitionHeuristics[p];
    }
}
