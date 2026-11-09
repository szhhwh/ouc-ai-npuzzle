package stud.g01.problem.npuzzle;

import static stud.g01.problem.npuzzle.RankedPdbEncoding.blankRank;

import java.util.ArrayDeque;
import java.util.Arrays;

/**
 * 基于零代价空格连通分支的模式数据库逆向广度优先搜索构建器。
 *
 * <p>核心算法细节：
 *
 * <ul>
 *   <li>多源逆向搜索：从目标状态出发反向搜索。将所有非模式数字视为空格，所有模式数字处于目标位置、空格处于任意未被占用格子的配置均作为距离为 0 的起始源点；
 *   <li>零代价连通分支折叠：空格在未被模式数字占用的相邻格子之间滑动时不移动任何模式数字，路径代价为 0。
 *       同一连通分支内的所有空格位置具有完全相同的启发式距离，因此只需入队一次即可将整个分支的所有空格序数项批量标记；
 *   <li>模式数字转移：只有当模式数字与连通分支内的相邻格子交换时，才产生代价为 1 的有效转移；
 *   <li>分块无装箱队列：使用分块数组实现的轻量级队列，彻底消除大规模搜索过程中的对象垃圾回收与扩容拷贝开销。
 * </ul>
 */
final class RankedPdbBuilder {
    /** 棋盘总格数，等于边长的平方 */
    private final int cells;

    /** 该模式包含的数字块数量 */
    private final int k;

    /** 空格相对序数所需的二进制位数 */
    private final int blankBits;

    /** 空格相对序数的二进制掩码 */
    private final int blankMask;

    /** 各格子的相邻合法格子列表 */
    private final int[][] neighbors;

    /** 排列数排名编解码器 */
    private final RankedPdbEncoding encoding;

    /** 距离表二维数组，记录空格序数与模式排名对应的最短移动步数 */
    private final byte[][] distances;

    /** 连通分支查找缓存数组，避免对相同占用掩码和空格位置重复执行泛洪填充 */
    private final int[] components;

    /** 广度优先搜索队列 */
    private final IntQueue queue = new IntQueue();

    /**
     * 构造构建器实例并初始化距离表及连通分支缓存。
     *
     * @param size 棋盘边长
     * @param k 模式数字数量
     */
    private RankedPdbBuilder(int size, int k) {
        cells = size * size;
        this.k = k;
        blankBits = Integer.SIZE - Integer.numberOfLeadingZeros(cells - k - 1);
        blankMask = (1 << blankBits) - 1;
        neighbors = PuzzleNeighbors.forSize(size);
        encoding = new RankedPdbEncoding(cells, k);
        int ranks = encoding.rankCount(k);
        distances = new byte[cells - k][ranks];
        for (byte[] block : distances) Arrays.fill(block, (byte) 0xFF);
        components = new int[(1 << cells) * cells];
    }

    /**
     * 为指定目标盘面和模式数字分组构建完整的排名模式数据库。
     *
     * @param size 棋盘边长
     * @param goal 目标盘面数组
     * @param tiles 模式数字块列表
     * @return 构建完成的二维距离表
     */
    static byte[][] build(int size, byte[] goal, int[] tiles) {
        return new RankedPdbBuilder(size, tiles.length).build(goal, tiles);
    }

    /**
     * 执行逆向广度优先搜索构建距离表。
     *
     * @param goal 目标盘面
     * @param tiles 模式数字列表
     * @return 填充完成的最短距离表
     */
    private byte[][] build(byte[] goal, int[] tiles) {
        int[] positions = new int[k];
        int occupied = 0;
        for (int j = 0; j < k; j++) {
            for (int p = 0; p < cells; p++) {
                if (goal[p] == tiles[j]) positions[j] = p;
            }
            occupied |= 1 << positions[j];
        }
        int rank = encoding.rankTuple(positions, k);
        // 多源起点：模式数字全部就位，空格位于任意未占用格子，距离均为 0
        for (int blank = 0; blank < cells; blank++) {
            if ((occupied & (1 << blank)) == 0) enqueue(rank, occupied, blank, 0);
        }
        boolean[] used = new boolean[cells];

        // 逐层 BFS，depth 即模式数字已移动的步数
        for (int depth = 0; !queue.isEmpty(); depth++) {
            int count = queue.size();
            for (int i = 0; i < count; i++) {
                int code = queue.remove();
                rank = code >>> blankBits;
                int blank = encoding.decode(rank, code & blankMask, k, positions, used);
                occupied = 0;
                for (int p : positions) occupied |= 1 << p;
                int reachable = component(occupied, blank);

                // 只有位于空格连通分支内的相邻格子才能与模式数字换位
                for (int j = 0; j < k; j++) {
                    int tilePos = positions[j];
                    for (int destination : neighbors[tilePos]) {
                        if ((reachable & (1 << destination)) == 0) continue;
                        positions[j] = destination;
                        int childRank = encoding.rankTuple(positions, k);
                        positions[j] = tilePos;
                        int childOccupied = occupied ^ (1 << tilePos) ^ (1 << destination);
                        enqueue(childRank, childOccupied, tilePos, depth + 1);
                    }
                }
            }
        }
        return distances;
    }

    /**
     * 将新状态加入队列并批量更新所属连通分支内所有空格项的距离。
     *
     * @param rank 模式数字排列排名
     * @param occupied 模式数字占用格子的位掩码
     * @param blank 空格位置
     * @param depth 移动步数深度
     */
    private void enqueue(int rank, int occupied, int blank, int depth) {
        int q = blankRank(occupied, blank);
        if (distances[q][rank] != (byte) 0xFF) return;
        int reachable = component(occupied, blank);
        // 同一连通分支内空格滑动代价为 0，所有空格序数共享同一距离
        for (int bits = reachable; bits != 0; bits &= bits - 1) {
            int b = Integer.numberOfTrailingZeros(bits);
            int ordinal = blankRank(occupied, b);
            distances[ordinal][rank] = (byte) depth;
        }
        queue.add((rank << blankBits) | q);
    }

    /**
     * 计算并缓存空格在未被占用的空闲格子中能够连通到达的格子掩码。
     *
     * @param occupied 模式数字占用的格子位掩码
     * @param blank 空格起始位置
     * @return 包含所有可达空闲格子的位掩码
     */
    private int component(int occupied, int blank) {
        int base = occupied * cells;
        int cached = components[base + blank];
        if (cached != 0) return cached;
        int reachable = 1 << blank;
        int pending = reachable;
        // 泛洪填充所有与空格连通的未占用格子
        while (pending != 0) {
            int p = Integer.numberOfTrailingZeros(pending);
            pending &= pending - 1;
            for (int neighbor : neighbors[p]) {
                int bit = 1 << neighbor;
                if (((occupied | reachable) & bit) != 0) continue;
                reachable |= bit;
                pending |= bit;
            }
        }
        // 同一连通分支内每个空格位置共享该掩码
        for (int bits = reachable; bits != 0; bits &= bits - 1) {
            components[base + Integer.numberOfTrailingZeros(bits)] = reachable;
        }
        return reachable;
    }

    /**
     * 固定块大小的分块无装箱基本类型整型队列。
     *
     * <p>避免在大规模广度优先搜索中频繁扩容数组导致的双倍内存占用与频繁对象垃圾回收。
     */
    private static final class IntQueue {
        /** 单个分块数组的大小 */
        private static final int CHUNK_SIZE = 1 << 16;

        /** 分块数组双端队列容器 */
        private final ArrayDeque<int[]> chunks = new ArrayDeque<>();

        /** 当前读取的队首分块数组 */
        private int[] headChunk = new int[CHUNK_SIZE];

        /** 当前写入的队尾分块数组 */
        private int[] tailChunk = headChunk;

        /** 队首下标、队尾下标以及队列元素总数 */
        private int head, tail, count;

        /** 构造空的分块无装箱队列。 */
        IntQueue() {
            chunks.addLast(headChunk);
        }

        /**
         * 获取队列中当前包含的元素个数。
         *
         * @return 元素数量
         */
        int size() {
            return count;
        }

        /**
         * 判断队列是否为空。
         *
         * @return 为空返回 true，否则返回 false
         */
        boolean isEmpty() {
            return count == 0;
        }

        /**
         * 向队尾添加一个整数元素。
         *
         * @param value 待入队的整数
         */
        void add(int value) {
            if (tail == CHUNK_SIZE) {
                tailChunk = new int[CHUNK_SIZE];
                chunks.addLast(tailChunk);
                tail = 0;
            }
            tailChunk[tail++] = value;
            count++;
        }

        /**
         * 从队首弹出一个整数元素。
         *
         * @return 出队的整数
         */
        int remove() {
            if (head == CHUNK_SIZE) {
                chunks.removeFirst();
                headChunk = chunks.getFirst();
                head = 0;
            }
            int value = headChunk[head++];
            count--;
            return value;
        }
    }
}
