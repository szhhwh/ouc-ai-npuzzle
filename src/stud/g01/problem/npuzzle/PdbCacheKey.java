package stud.g01.problem.npuzzle;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.IntStream;

/**
 * 模式数据库磁盘缓存的唯一标识键。
 *
 * <p>完整记录了棋盘规模、目标盘面、分组数字、存储格式以及版本号，确保磁盘持久化文件名与内容哈希完全绑定。
 *
 * @param size 棋盘边长
 * @param target 目标盘面各格子数字列表
 * @param tiles 该分组所关注的非空格数字列表
 * @param format 缓存文件存储格式
 * @param version 缓存格式版本号
 */
record PdbCacheKey(
        int size, List<Byte> target, List<Integer> tiles, PdbCache.Format format, int version) {
    /** 当前缓存实现版本号 */
    static final int CURRENT_VERSION = 2;

    PdbCacheKey {
        target = List.copyOf(target);
        tiles = List.copyOf(tiles);
    }

    /**
     * 校验参数并构造 PdbCacheKey 实例。
     *
     * @param size 棋盘边长
     * @param target 目标盘面数组
     * @param tiles 分组数字列表
     * @param format 存储格式
     * @return 对应的 PdbCacheKey 实例
     * @throws IllegalArgumentException 当分组数字包含空格、有重复或超出盘面容量时抛出
     */
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

    /**
     * 根据缓存键的各属性计算哈希指纹，生成标准化缓存文件名。
     *
     * @return 包含格式、版本号以及哈希摘要的标准文件名
     */
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
