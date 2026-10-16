package stud.g01.problem.npuzzle;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

/** Shared file header and I/O; each PDB supplies its own payload layout. */
final class PdbCache {
    private static final Path DIRECTORY = Path.of("resources", "pdb-cache");

    enum Format {
        PRODUCT(0x50444231, 1 << 16, "PDB"), // ASCII "PDB1"
        RANKED(0x52444231, 1 << 20, "RPDB"), // ASCII "RDB1"
        RANKED_MIN(0x52444D31, 1 << 20, "RPDB"), // ASCII "RDM1"
        DIRECT(0x50444432, 1 << 20, "PDB"),
        COMPONENT(0x50444332, 1 << 20, "PDB"),
        COMPONENT_LE(0x50444333, 1 << 20, "PDB");

        final int magic;
        final int bufferSize;
        final String label;

        Format(int magic, int bufferSize, String label) {
            this.magic = magic;
            this.bufferSize = bufferSize;
            this.label = label;
        }
    }

    @FunctionalInterface
    interface Reader<T> {
        T read(DataInputStream in) throws IOException;
    }

    @FunctionalInterface
    interface Writer {
        void write(DataOutputStream out) throws IOException;
    }

    final Path file;
    private final Format format;
    private final int size;
    private final byte[] goalCells;
    private final int[] tiles;

    PdbCache(String filename, Format format, int size, byte[] goalCells, int[] tiles) {
        file = DIRECTORY.resolve(filename);
        this.format = format;
        this.size = size;
        this.goalCells = goalCells;
        this.tiles = tiles;
    }

    /** Missing, stale or truncated caches are rebuilt by the caller. */
    <T> T tryLoad(Reader<T> payload) {
        if (!Files.isRegularFile(file)) return null;
        try (DataInputStream in =
                new DataInputStream(
                        new BufferedInputStream(Files.newInputStream(file), format.bufferSize))) {
            return matchesHeader(in) ? payload.read(in) : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Read-only legacy views let conversion retain only the new representation on heap. */
    ByteBuffer[] mappedBlocks(int blocks, int ranks) {
        try (DataInputStream in = new DataInputStream(Files.newInputStream(file));
                FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            if (!matchesHeader(in) || in.readInt() != blocks || in.readInt() != ranks) return null;
            long offset = 24L + goalCells.length + 4L * tiles.length;
            if (channel.size() != offset + (long) blocks * ranks) return null;
            ByteBuffer[] result = new ByteBuffer[blocks];
            for (int q = 0; q < blocks; q++)
                result[q] =
                        channel.map(
                                FileChannel.MapMode.READ_ONLY, offset + (long) q * ranks, ranks);
            return result;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** A write failure leaves the caller's in-memory table usable. */
    void tryWrite(Writer payload) {
        try {
            Files.createDirectories(DIRECTORY);
            try (DataOutputStream out =
                    new DataOutputStream(
                            new BufferedOutputStream(
                                    Files.newOutputStream(file), format.bufferSize))) {
                out.writeInt(format.magic);
                out.writeInt(size);
                out.writeInt(goalCells.length);
                out.write(goalCells);
                out.writeInt(tiles.length);
                for (int tile : tiles) out.writeInt(tile);
                payload.write(out);
            }
            System.out.println("[" + format.label + "] Cache saved: " + file);
        } catch (IOException e) {
            System.out.println("[" + format.label + "] Cache write failed (" + file + "): " + e);
        }
    }

    private boolean matchesHeader(DataInputStream in) throws IOException {
        if (in.readInt() != format.magic || in.readInt() != size) return false;
        if (in.readInt() != goalCells.length) return false;
        byte[] storedGoal = new byte[goalCells.length];
        in.readFully(storedGoal);
        if (!Arrays.equals(storedGoal, goalCells)) return false;
        if (in.readInt() != tiles.length) return false;
        for (int tile : tiles) {
            if (in.readInt() != tile) return false;
        }
        return true;
    }
}
