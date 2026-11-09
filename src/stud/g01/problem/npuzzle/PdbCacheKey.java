package stud.g01.problem.npuzzle;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.IntStream;

/** Complete identity of one persisted table, independent of its historical filename. */
record PdbCacheKey(
        int size, List<Byte> target, List<Integer> tiles, PdbCache.Format format, int version) {
    static final int CURRENT_VERSION = 2;

    PdbCacheKey {
        target = List.copyOf(target);
        tiles = List.copyOf(tiles);
    }

    static PdbCacheKey of(int size, byte[] target, int[] tiles, PdbCache.Format format) {
        PuzzleBoard board = new PuzzleBoard(target, size);
        boolean[] seen = new boolean[target.length];
        for (int tile : tiles) {
            if (tile <= 0 || tile >= seen.length || seen[tile]) {
                throw new IllegalArgumentException("PDB groups require distinct nonblank tiles");
            }
            seen[tile] = true;
        }
        if (tiles.length == 0 || tiles.length >= target.length) {
            throw new IllegalArgumentException("PDB group must leave room for the blank");
        }
        byte[] cells = board.cellsInternal();
        return new PdbCacheKey(
                size,
                IntStream.range(0, cells.length).mapToObj(i -> cells[i]).toList(),
                IntStream.of(tiles).boxed().toList(),
                format,
                CURRENT_VERSION);
    }

    String filename() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                out.writeInt(version);
                out.writeInt(format.magic);
                out.writeInt(size);
                out.writeInt(target.size());
                for (byte tile : target) out.writeByte(tile);
                out.writeInt(tiles.size());
                for (int tile : tiles) out.writeInt(tile);
            }
            String digest =
                    HexFormat.of()
                            .formatHex(
                                    MessageDigest.getInstance("SHA-256")
                                            .digest(bytes.toByteArray()));
            return format.name().toLowerCase(java.util.Locale.ROOT)
                    + "-v"
                    + version
                    + "-"
                    + digest
                    + ".bin";
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Cannot encode PDB cache identity", e);
        }
    }
}
