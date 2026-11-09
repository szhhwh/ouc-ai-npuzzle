package stud.g01.problem.npuzzle;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.CRC32C;
import java.util.zip.CheckedInputStream;
import java.util.zip.CheckedOutputStream;

/** Versioned, checksummed cache envelopes with complete typed identities. */
final class PdbCache {
    private static final int ENVELOPE_MAGIC = 0x50444348; // "PDCH"

    // File locks coordinate processes; this guard also avoids overlapping locks within one JVM.
    private static final Map<Path, Object> PUBLICATION_LOCKS = new ConcurrentHashMap<>();

    enum Format {
        PRODUCT(0x50444231, 1 << 16, "PDB"), // ASCII "PDB1"
        RANKED(0x52444231, 1 << 20, "RPDB"), // ASCII "RDB1"
        DIRECT(0x50444432, 1 << 20, "PDB"),
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
    final PdbCacheKey key;
    private final Path directory;

    PdbCache(PdbRepository repository, Format format, int size, byte[] goalCells, int[] tiles) {
        key = PdbCacheKey.of(size, goalCells, tiles, format);
        directory = repository.directory();
        file = directory.resolve(key.filename());
    }

    /** Missing, mismatched, corrupt or truncated caches are rebuilt by the caller. */
    <T> T tryLoad(Reader<T> payload) {
        return read(file, payload);
    }

    private <T> T read(Path source, Reader<T> payload) {
        if (!Files.isRegularFile(source)) return null;
        CRC32C checksum = new CRC32C();
        try (DataInputStream in =
                new DataInputStream(
                        new CheckedInputStream(
                                new BufferedInputStream(
                                        Files.newInputStream(source), key.format().bufferSize),
                                checksum))) {
            if (in.readInt() != ENVELOPE_MAGIC || in.readInt() != key.version()) return null;
            if (!matchesHeader(in)) return null;
            T result = payload.read(in);
            if (result == null) return null;
            long actual = checksum.getValue();
            if (in.readLong() != actual) return null;
            return in.read() == -1 ? result : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Revalidate under a per-key lock; atomically publish only when no valid cache exists. */
    void tryWrite(Reader<?> reader, Writer payload) {
        Path temporary = null;
        try {
            Files.createDirectories(directory);
            temporary = Files.createTempFile(directory, ".pdb-", ".tmp");
            CRC32C checksum = new CRC32C();
            try (DataOutputStream out =
                    new DataOutputStream(
                            new CheckedOutputStream(
                                    new BufferedOutputStream(
                                            Files.newOutputStream(temporary),
                                            key.format().bufferSize),
                                    checksum))) {
                out.writeInt(ENVELOPE_MAGIC);
                out.writeInt(key.version());
                writeHeader(out);
                payload.write(out);
                long value = checksum.getValue();
                out.writeLong(value);
            }
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            // Resolve directory aliases after it exists, so threads using a symlink and its
            // target synchronize before requesting the same process-wide file lock.
            Path lockKey = directory.toRealPath().resolve(file.getFileName());
            synchronized (PUBLICATION_LOCKS.computeIfAbsent(lockKey, ignored -> new Object())) {
                // Keep the sidecar inode stable: deleting it would let writers lock different
                // files.
                try (FileChannel channel =
                                FileChannel.open(
                                        file.resolveSibling(file.getFileName() + ".lock"),
                                        StandardOpenOption.CREATE,
                                        StandardOpenOption.WRITE);
                        FileLock lock = channel.lock()) {
                    if (!lock.isValid()) throw new IOException("Cache publication lock lost");
                    if (read(file, reader) != null) return;
                    Files.move(
                            temporary,
                            file,
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                    System.out.println("[" + key.format().label + "] Cache saved: " + file);
                }
            }
        } catch (IOException
                | UnsupportedOperationException
                | SecurityException
                | OverlappingFileLockException e) {
            System.out.println(
                    "[" + key.format().label + "] Cache write failed (" + file + "): " + e);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException | SecurityException e) {
                    System.out.println(
                            "[" + key.format().label + "] Temporary cache cleanup failed: " + e);
                }
            }
        }
    }

    private void writeHeader(DataOutputStream out) throws IOException {
        out.writeInt(key.format().magic);
        out.writeInt(key.size());
        out.writeInt(key.target().size());
        for (byte tile : key.target()) out.writeByte(tile);
        out.writeInt(key.tiles().size());
        for (int tile : key.tiles()) out.writeInt(tile);
    }

    private boolean matchesHeader(DataInputStream in) throws IOException {
        if (in.readInt() != key.format().magic || in.readInt() != key.size()) return false;
        if (in.readInt() != key.target().size()) return false;
        for (byte tile : key.target()) if (in.readByte() != tile) return false;
        if (in.readInt() != key.tiles().size()) return false;
        for (int tile : key.tiles()) if (in.readInt() != tile) return false;
        return true;
    }
}
