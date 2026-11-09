package stud.g01.problem.npuzzle;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import java.util.Arrays;

/**
 * 连通分支无损压缩模式数据库结构。
 *
 * <p>核心算法原理：
 *
 * <ul>
 *   <li>零代价连通分支几何预计算：处于同一空闲格子连通分支内的空格，其滑动代价为 0，因此共享完全一致的启发式距离向量；
 *   <li>调色板向量去重压缩：对于给定的数字块占用掩码，所有可能的数字块排列对应的距离向量种类通常较少。 因此将唯一的距离向量存入调色板，而排列排名则仅存储紧凑位宽的调色板索引， 从而将
 *       7-8 分组的完整模式数据库内存压缩至可常驻内存的级别。
 * </ul>
 */
final class ComponentPdbTable {
    /** 静态预计算的几何连通分支查找表：COMPONENTS[occupied][blank] 为指定占用下空格所属的连通分支编号 */
    private static final byte[][] COMPONENTS = geometry();

    /** 模式数字个数 */
    final int k;

    /** 排列总数 */
    final int permutations;

    /** 各占用掩码下的位紧凑调色板索引数据，按 long 数组存储 */
    private final long[][] codes = new long[1 << 16][];

    /** 各占用掩码下的去重距离向量调色板 */
    private final long[][] palettes = new long[1 << 16][];

    /** 各占用掩码下调色板索引所占的二进制位数 */
    private final byte[] widths = new byte[1 << 16];

    /**
     * 构造 ComponentPdbTable 实例。
     *
     * @param k 模式包含的数字块数量
     */
    private ComponentPdbTable(int k) {
        this.k = k;
        permutations = factorial(k);
    }

    /**
     * 查询指定占用掩码、数字排列排名和空格位置下的精确启发式距离。
     *
     * @param occupied 模式数字块占用的格子位掩码
     * @param permutation 模式数字块在占用格子中的排列排名
     * @param blank 空格在棋盘上的线性下标
     * @return 启发式移动步数
     */
    int distance(int occupied, int permutation, int blank) {
        int width = widths[occupied];
        int bit = permutation * width;
        long[] words = codes[occupied];
        int word = bit >>> 6, shift = bit & 63;
        long id = words[word] >>> shift;
        // 变长位域可能跨越 long 边界，需要拼入下一单元的低位
        if (shift + width > 64) id |= words[word + 1] << (64 - shift);
        id &= (1L << width) - 1;
        int component = COMPONENTS[occupied][blank];
        return (int) (palettes[occupied][(int) id] >>> (component * 8)) & 255;
    }

    /**
     * 计算整数 k 的阶乘。
     *
     * @param k 非负整数
     * @return k 的阶乘
     */
    static int factorial(int k) {
        int result = 1;
        for (int i = 2; i <= k; i++) result *= i;
        return result;
    }

    /**
     * 计算给定位置序列的阶乘进制全排列排名。
     *
     * @param positions 数字块位置数组
     * @return 范围内的排列排名
     */
    static int permutationRank(int[] positions) {
        int result = 0;
        // remaining 记录当前排列中尚未使用的符号集合
        int remaining = 0;
        for (int p : positions) remaining |= 1 << p;
        // 阶乘进制：当前符号在剩余集合中的相对序数乘以剩余位数的阶乘
        for (int p : positions) {
            result +=
                    Integer.bitCount(remaining & ((1 << p) - 1))
                            * factorial(Integer.bitCount(remaining) - 1);
            remaining ^= 1 << p;
        }
        return result;
    }

    /**
     * 从磁盘加载或基于精确排名表构建连通分支 PDB 表。
     *
     * @param size 棋盘边长
     * @param goal 目标盘面数组
     * @param tiles 模式数字列表
     * @param repository 缓存仓库
     * @return ComponentPdbTable 实例
     */
    static ComponentPdbTable load(int size, byte[] goal, int[] tiles, PdbRepository repository) {
        PdbCache cache = new PdbCache(repository, PdbCache.Format.COMPONENT_LE, size, goal, tiles);
        PdbCache.Reader<ComponentPdbTable> reader =
                cacheReader(tiles.length, ByteOrder.LITTLE_ENDIAN);
        ComponentPdbTable loaded = cache.tryLoad(reader);
        if (loaded != null) return loaded;
        // 未命中缓存时先构建精确排名表，再压缩为连通分支表
        loaded = fromExactTables(tiles.length, RankedPdbBuilder.build(size, goal, tiles));
        ComponentPdbTable table = loaded;
        cache.tryWrite(
                reader,
                out -> {
                    out.writeInt(table.permutations);
                    byte[] buffer = new byte[1 << 16];
                    LongBuffer longs =
                            ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN).asLongBuffer();
                    for (int mask = 0; mask < (1 << 16); mask++) {
                        // 只存储恰好占用 k 个格子的合法掩码
                        if (Integer.bitCount(mask) != tiles.length) continue;
                        out.writeInt(table.palettes[mask].length);
                        out.writeInt(table.widths[mask]);
                        // 依次流式写出调色板向量与紧凑索引数组
                        for (long[] values :
                                new long[][] {table.palettes[mask], table.codes[mask]}) {
                            for (int offset = 0;
                                    offset < values.length;
                                    offset += buffer.length / 8) {
                                int count = Math.min(buffer.length / 8, values.length - offset);
                                longs.clear();
                                longs.put(values, offset, count);
                                out.write(buffer, 0, count * 8);
                            }
                        }
                    }
                });
        return table;
    }

    /**
     * 创建二进制缓存读取器。
     *
     * @param k 模式数字数量
     * @param order 字节序
     * @return PdbCache.Reader 实例
     */
    static PdbCache.Reader<ComponentPdbTable> cacheReader(int k, ByteOrder order) {
        return in -> {
            if (in.readInt() != factorial(k)) return null;
            ComponentPdbTable table = new ComponentPdbTable(k);
            byte[] buffer = new byte[1 << 16];
            LongBuffer longs = ByteBuffer.wrap(buffer).order(order).asLongBuffer();
            for (int mask = 0; mask < (1 << 16); mask++) {
                // 只处理恰好占用 k 个格子的合法掩码
                if (Integer.bitCount(mask) != k) continue;
                int count = in.readInt();
                int width = in.readInt();
                // 校验向量数量与位宽的一致性
                if (count <= 0
                        || count > table.permutations
                        || width != 32 - Integer.numberOfLeadingZeros(count - 1)) return null;
                table.widths[mask] = (byte) width;
                table.palettes[mask] = new long[count];
                readLongs(in, table.palettes[mask], buffer, longs);
                int words = Math.max(1, (table.permutations * width + 63) >>> 6);
                table.codes[mask] = new long[words];
                readLongs(in, table.codes[mask], buffer, longs);
            }
            return table;
        };
    }

    /**
     * 批量从输入流中读取 long 数组。
     *
     * @param in 数据输入流
     * @param result 目标数组
     * @param buffer 字节缓冲数组
     * @param longs 缓冲长整型视图
     * @throws IOException 当发生输入输出异常时抛出
     */
    private static void readLongs(
            DataInputStream in, long[] result, byte[] buffer, LongBuffer longs) throws IOException {
        for (int offset = 0; offset < result.length; offset += buffer.length / 8) {
            int count = Math.min(buffer.length / 8, result.length - offset);
            in.readFully(buffer, 0, count * 8);
            longs.clear();
            longs.get(result, offset, count);
        }
    }

    /**
     * 从精确基础距离表中提炼压缩出调色板与位紧凑索引表。
     *
     * @param k 模式数字数量
     * @param blocks 原始基础距离块
     * @return 压缩构建后的 ComponentPdbTable 实例
     */
    static ComponentPdbTable fromExactTables(int k, byte[][] blocks) {
        ComponentPdbTable result = new ComponentPdbTable(k);
        RankedPdbEncoding encoding = new RankedPdbEncoding(16, k);
        int[] positions = new int[k], ids = new int[result.permutations];
        int[] representatives = new int[8], ordinals = new int[8];
        // 基本类型哈希表，用于距离向量去重
        long[] keys = new long[1 << 16], vectors = new long[result.permutations];
        int[] values = new int[keys.length], touched = new int[keys.length];
        for (int mask = 0; mask < (1 << 16); mask++) {
            // 只有恰好占用 k 个格子的掩码才是合法占用
            if (Integer.bitCount(mask) != k) continue;
            int t = 0;
            for (int bits = mask; bits != 0; bits &= bits - 1)
                positions[t++] = Integer.numberOfTrailingZeros(bits);
            // 每个连通分支取一个代表空格位置，同分支内距离向量相同
            int components = 0;
            for (int blank = 0; blank < 16; blank++) {
                if ((mask & (1 << blank)) != 0) continue;
                int c = COMPONENTS[mask][blank];
                representatives[c] = blank;
                components = Math.max(components, c + 1);
            }
            for (int c = 0; c < components; c++)
                ordinals[c] = RankedPdbEncoding.blankRank(mask, representatives[c]);
            int count = 0, p = 0;
            // 枚举该占用掩码下的所有数字排列，把各连通分支距离拼成向量并去重
            do {
                int rank = encoding.rankTuple(positions, k);
                long vector = 0;
                for (int c = 0; c < components; c++)
                    vector |= (long) (blocks[ordinals[c]][rank] & 255) << (8 * c);
                long key = vector + 1;
                int slot = (int) ((key * 0x9E3779B97F4A7C15L) >>> 48);
                while (keys[slot] != 0 && keys[slot] != key) slot = (slot + 1) & (keys.length - 1);
                if (keys[slot] == 0) {
                    keys[slot] = key;
                    touched[count] = slot;
                    values[slot] = count;
                    vectors[count++] = vector;
                }
                ids[p++] = values[slot];
            } while (nextPermutation(positions));
            for (int i = 0; i < count; i++) keys[touched[i]] = 0;
            // 用最小位宽存调色板编号，再把编号流式打包进 long 数组
            int width = 32 - Integer.numberOfLeadingZeros(count - 1);
            long[] words = new long[Math.max(1, (result.permutations * width + 63) >>> 6)];
            for (int i = 0; i < ids.length; i++) {
                int bit = i * width, word = bit >>> 6, shift = bit & 63;
                words[word] |= (long) ids[i] << shift;
                if (shift + width > 64) words[word + 1] |= (long) ids[i] >>> (64 - shift);
            }
            result.widths[mask] = (byte) width;
            result.codes[mask] = words;
            result.palettes[mask] = Arrays.copyOf(vectors, count);
        }
        return result;
    }

    /**
     * 生成下一个字典序排列。
     *
     * @param positions 位置排列数组
     * @return 若存在下一个排列返回 true，若已是最大排列返回 false
     */
    private static boolean nextPermutation(int[] positions) {
        // 从右向左找到第一处升序对，再从右侧找比它大的元素交换，最后翻转后缀
        int i = positions.length - 2;
        while (i >= 0 && positions[i] >= positions[i + 1]) i--;
        if (i < 0) return false;
        int j = positions.length - 1;
        while (positions[j] <= positions[i]) j--;
        int value = positions[i];
        positions[i] = positions[j];
        positions[j] = value;
        for (int a = i + 1, b = positions.length - 1; a < b; a++, b--) {
            value = positions[a];
            positions[a] = positions[b];
            positions[b] = value;
        }
        return true;
    }

    /**
     * 预计算 4x4 网格上所有占用掩码下的空格连通分支几何划分。
     *
     * @return 二维数组 result[occupiedMask][blankPosition]，存放连通分支编号
     */
    private static byte[][] geometry() {
        byte[][] result = new byte[1 << 16][];
        int[][] neighbors = PuzzleNeighbors.forSize(4);
        for (int occupied = 0; occupied < result.length; occupied++) {
            byte[] components = new byte[16];
            result[occupied] = components;
            // 已被占用的格子视作已访问，余下格子做泛洪填充划分连通分支
            int visited = occupied;
            int c = 0;
            for (int b = 0; b < 16; b++) {
                if ((visited & (1 << b)) != 0) continue;
                int pending = 1 << b;
                visited |= pending;
                while (pending != 0) {
                    int p = Integer.numberOfTrailingZeros(pending);
                    pending &= pending - 1;
                    components[p] = (byte) c;
                    for (int n : neighbors[p]) {
                        if ((visited & (1 << n)) != 0) continue;
                        visited |= 1 << n;
                        pending |= 1 << n;
                    }
                }
                c++;
            }
        }
        return result;
    }
}
