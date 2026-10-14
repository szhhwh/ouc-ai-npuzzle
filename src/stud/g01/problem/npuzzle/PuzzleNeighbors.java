package stud.g01.problem.npuzzle;

import java.util.Arrays;

/** Board adjacency shared by PDB builders and incremental search. */
final class PuzzleNeighbors {
    private PuzzleNeighbors() {}

    /** Keep the UP, DOWN, LEFT, RIGHT order used by the search. */
    static int[][] forSize(int size) {
        int[][] neighbors = new int[size * size][];
        int[] buffer = new int[4];
        for (int i = 0; i < neighbors.length; i++) {
            int row = i / size, col = i % size, count = 0;
            if (row > 0) buffer[count++] = i - size;
            if (row < size - 1) buffer[count++] = i + size;
            if (col > 0) buffer[count++] = i - 1;
            if (col < size - 1) buffer[count++] = i + 1;
            neighbors[i] = Arrays.copyOf(buffer, count);
        }
        return neighbors;
    }
}
