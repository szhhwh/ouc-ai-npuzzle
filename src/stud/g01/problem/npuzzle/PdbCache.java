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

/**
 * 带有版本号和校验和的模式数据库磁盘缓存封装类。
 *
 * <p>负责在磁盘上读写二进制数据，通过进程级文件锁和虚拟机内部同步保证多进程与多线程安全， 支持原子写入与完整性校验。
 */
final class PdbCache {
    /** 缓存封装头魔数 */
    private static final int ENVELOPE_MAGIC = 0x50444348;

    /** 文件锁用于协调多进程写入，该映射用于防止同一虚拟机内部对同一路径发生重叠锁异常 */
    private static final Map<Path, Object> PUBLICATION_LOCKS = new ConcurrentHashMap<>();

    /** 模式数据库的二进制存储格式枚举 */
    enum Format {
        /** 乘积编码格式 */
        PRODUCT(0x50444231, 1 << 16, "PDB"),
        /** 排名编码格式 */
        RANKED(0x52444231, 1 << 20, "RPDB"),
        /** 直接查表格式 */
        DIRECT(0x50444432, 1 << 20, "PDB"),
        /** 连通分支小端压缩格式 */
        COMPONENT_LE(0x50444333, 1 << 20, "PDB");

        /** 格式标识魔数 */
        final int magic;

        /** 缓冲流字节大小 */
        final int bufferSize;

        /** 格式简要标识字符串 */
        final String label;

        /**
         * 构造格式枚举常量。
         *
         * @param magic 格式魔数
         * @param bufferSize 缓冲区大小
         * @param label 格式简要标签
         */
        Format(int magic, int bufferSize, String label) {
            this.magic = magic;
            this.bufferSize = bufferSize;
            this.label = label;
        }
    }

    /**
     * 缓存数据读取回调接口。
     *
     * @param <T> 读取反序列化后的目标对象类型
     */
    @FunctionalInterface
    interface Reader<T> {
        /**
         * 从输入流中读取并反序列化缓存数据。
         *
         * @param in 数据输入流
         * @return 反序列化后的对象；若格式错误返回 null
         * @throws IOException 当发生输入输出异常时抛出
         */
        T read(DataInputStream in) throws IOException;
    }

    /** 缓存数据写入回调接口。 */
    @FunctionalInterface
    interface Writer {
        /**
         * 将数据序列化写入输出流。
         *
         * @param out 数据输出流
         * @throws IOException 当发生输入输出异常时抛出
         */
        void write(DataOutputStream out) throws IOException;
    }

    /** 缓存文件路径 */
    final Path file;

    /** 缓存键 */
    final PdbCacheKey key;

    /** 缓存根目录 */
    private final Path directory;

    /**
     * 构造 PdbCache 实例。
     *
     * @param repository 模式数据库仓库
     * @param format 存储格式
     * @param size 棋盘边长
     * @param goalCells 目标盘面数字数组
     * @param tiles 该分组关注的数字列表
     */
    PdbCache(PdbRepository repository, Format format, int size, byte[] goalCells, int[] tiles) {
        key = PdbCacheKey.of(size, goalCells, tiles, format);
        directory = repository.directory();
        file = directory.resolve(key.filename());
    }

    /**
     * 尝试从磁盘加载缓存；若缓存文件不存在、头部不匹配、校验和错误或内容损坏则返回 null。
     *
     * @param <T> 数据类型
     * @param payload 数据读取器
     * @return 成功加载的数据对象；失败返回 null
     */
    <T> T tryLoad(Reader<T> payload) {
        return read(file, payload);
    }

    /**
     * 从指定文件路径读取数据并验证校验和。
     *
     * @param source 缓存文件路径
     * @param payload 读取器
     * @return 读取结果，验证失败返回 null
     */
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
            // 校验完整文件已被读完，尾部多余数据视为损坏
            return in.read() == -1 ? result : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * 尝试将生成的模式数据库数据安全写入磁盘缓存。
     *
     * <p>采用写入临时文件、跨进程文件锁和原子移动替换的策略，确保写入过程的原子性。
     *
     * @param reader 用于在获取锁后二次检查缓存是否已由其它进程生成的读取器
     * @param payload 数据写入器
     */
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
            // 数据落盘后再发布，避免崩溃时留下半写文件
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            // 同一路径在 JVM 内串行化，跨进程则用独立锁文件
            Path lockKey = directory.toRealPath().resolve(file.getFileName());
            synchronized (PUBLICATION_LOCKS.computeIfAbsent(lockKey, ignored -> new Object())) {
                try (FileChannel channel =
                                FileChannel.open(
                                        file.resolveSibling(file.getFileName() + ".lock"),
                                        StandardOpenOption.CREATE,
                                        StandardOpenOption.WRITE);
                        FileLock lock = channel.lock()) {
                    if (!lock.isValid()) throw new IOException("Cache publication lock lost");
                    // 二次检查：其它进程可能已经发布了有效缓存
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

    /**
     * 将缓存头部元数据写入输出流。
     *
     * @param out 数据输出流
     * @throws IOException 当发生输入输出错误时抛出
     */
    private void writeHeader(DataOutputStream out) throws IOException {
        out.writeInt(key.format().magic);
        out.writeInt(key.size());
        out.writeInt(key.target().size());
        for (byte tile : key.target()) out.writeByte(tile);
        out.writeInt(key.tiles().size());
        for (int tile : key.tiles()) out.writeInt(tile);
    }

    /**
     * 校验从输入流中读取的头部元数据是否与当前缓存键完全一致。
     *
     * @param in 数据输入流
     * @return 匹配返回 true，不匹配返回 false
     * @throws IOException 当发生输入输出错误时抛出
     */
    private boolean matchesHeader(DataInputStream in) throws IOException {
        if (in.readInt() != key.format().magic || in.readInt() != key.size()) return false;
        if (in.readInt() != key.target().size()) return false;
        for (byte tile : key.target()) if (in.readByte() != tile) return false;
        if (in.readInt() != key.tiles().size()) return false;
        for (int tile : key.tiles()) if (in.readInt() != tile) return false;
        return true;
    }
}
