package stud.g01.problem.npuzzle;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * 基于乘积寻址与距离向量字典压缩的高性能直接查表结构。
 *
 * <p>将每个模式位置多元组映射到一个 16 位的字典索引，字典中存放去重后的 64 位距离向量。 每个向量按 6 位打包各个空格相对序数对应的最短步数，兼顾了快速直接寻址与紧凑的内存占用。
 */
final class DirectPdbTable {
    /** 乘积多元组到距离向量字典的索引映射表 */
    private final short[] indices;

    /** 去重后的 64 位距离向量字典数组 */
    private final long[] vectors;

    /** 该表负责的模式数字块列表 */
    private final int[] tiles;

    /**
     * 私有构造方法。
     *
     * @param indices 字典索引数组
     * @param vectors 距离向量字典
     * @param tiles 模式数字块列表
     */
    private DirectPdbTable(short[] indices, long[] vectors, int[] tiles) {
        this.indices = indices;
        this.vectors = vectors;
        this.tiles = tiles;
    }

    /**
     * 查询指定盘面状态下当前模式分组的启发式距离。
     *
     * @param positions 盘面的逆位置编码
     * @param blank 空格的绝对位置下标
     * @return 启发式移动步数
     */
    int distance(long positions, int blank) {
        int tuple = 0;
        int occupied = 0;
        // 提取本组各数字位置，拼成多元组索引并记录占用掩码
        for (int tile : tiles) {
            int p = (int) (positions >>> (tile * 4)) & 15;
            tuple = (tuple << 4) | p;
            occupied |= 1 << p;
        }
        int q = RankedPdbEncoding.blankRank(occupied, blank);
        // 距离向量中每个空格序数占 6 位
        return (int) (vectors[indices[tuple] & 0xFFFF] >>> (q * 6)) & 63;
    }

    /**
     * 从磁盘缓存加载或从排名表转换构建 DirectPdbTable 实例。
     *
     * @param size 棋盘边长
     * @param goal 目标盘面数字数组
     * @param tiles 模式分组数字块列表
     * @param repository 缓存仓库
     * @return 加载或构建完成的 DirectPdbTable 实例
     */
    static DirectPdbTable load(int size, byte[] goal, int[] tiles, PdbRepository repository) {
        PdbCache cache = new PdbCache(repository, PdbCache.Format.DIRECT, size, goal, tiles);
        int length = 1 << (4 * tiles.length);
        PdbCache.Reader<DirectPdbTable> reader = cacheReader(tiles);
        DirectPdbTable loaded = cache.tryLoad(reader);
        if (loaded != null) return loaded;

        // 先加载底层精确排名表，再为每个多元组建立去重距离向量索引
        byte[][] ranked = RankedPdbHeuristic.loadOrBuildGroup(size, goal, tiles, repository);
        RankedPdbEncoding encoding = new RankedPdbEncoding(size * size, tiles.length);
        int[] positions = new int[tiles.length];
        boolean[] used = new boolean[size * size];
        short[] indices = new short[length];
        long[] vectors = new long[65536];
        Map<Long, Integer> dictionary = new HashMap<>();

        for (int rank = 0; rank < ranked[0].length; rank++) {
            encoding.decode(rank, 0, tiles.length, positions, used);
            int tuple = 0;
            for (int p : positions) tuple = (tuple << 4) | p;
            long vector = 0;
            // 每个空格序数的距离压入 6 位，整行拼成一个 64 位向量
            for (int q = 0; q < ranked.length; q++) {
                int d = ranked[q][rank] & 255;
                if (d >= 63) throw new IllegalStateException("Distance exceeds six-bit vector");
                vector |= (long) d << (q * 6);
            }
            Integer id = dictionary.get(vector);
            if (id == null) {
                id = dictionary.size();
                if (id == vectors.length)
                    throw new IllegalStateException("Dictionary exceeds 16 bits");
                dictionary.put(vector, id);
                vectors[id] = vector;
            }
            indices[tuple] = id.shortValue();
        }

        DirectPdbTable result =
                new DirectPdbTable(indices, Arrays.copyOf(vectors, dictionary.size()), tiles);
        cache.tryWrite(
                reader,
                out -> {
                    out.writeInt(length);
                    out.writeInt(result.vectors.length);
                    for (long v : result.vectors) out.writeLong(v);
                    byte[] buffer = new byte[1 << 16];
                    for (int i = 0; i < length; i += buffer.length / 2) {
                        int n = Math.min(buffer.length / 2, length - i);
                        ByteBuffer.wrap(buffer).asShortBuffer().put(indices, i, n);
                        out.write(buffer, 0, n * 2);
                    }
                });
        return result;
    }

    /**
     * 创建用于反序列化 DirectPdbTable 的缓存读取器。
     *
     * @param tiles 模式数字块列表
     * @return PdbCache.Reader 实例
     */
    static PdbCache.Reader<DirectPdbTable> cacheReader(int[] tiles) {
        int length = 1 << (4 * tiles.length);
        return in -> {
            if (in.readInt() != length) return null;
            int count = in.readInt();
            if (count <= 0 || count > 65536) return null;
            long[] vectors = new long[count];
            for (int i = 0; i < count; i++) vectors[i] = in.readLong();
            short[] indices = new short[length];
            byte[] buffer = new byte[1 << 16];
            for (int i = 0; i < length; i += buffer.length / 2) {
                int n = Math.min(buffer.length / 2, length - i);
                in.readFully(buffer, 0, n * 2);
                ByteBuffer.wrap(buffer).asShortBuffer().get(indices, i, n);
                for (int j = i; j < i + n; j++) {
                    if ((indices[j] & 0xFFFF) >= count) return null;
                }
            }
            return new DirectPdbTable(indices, vectors, tiles);
        };
    }
}
