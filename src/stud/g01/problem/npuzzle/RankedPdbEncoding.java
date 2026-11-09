package stud.g01.problem.npuzzle;

import java.util.Arrays;

/**
 * 基于排列数计数的模式数据库组合排名与解码编解码器。
 *
 * <p>将棋盘上 k 个特定数字块所在的位置多元组一一对应地映射为紧凑整数下标， 消除无效状态对应的存储空间；同时维护组外空格的相对序数。
 */
final class RankedPdbEncoding {
    /** 棋盘总格数，等于边长的平方 */
    private final int cells;

    /** 该编码器支持的最大分组数字块数量 */
    final int maxTiles;

    /** 排列数预计算查找表 */
    private final int[][] permutations;

    /**
     * 构造组合编码器并预计算排列数表。
     *
     * @param cells 棋盘总格数
     * @param maxTiles 最大分组数字数
     */
    RankedPdbEncoding(int cells, int maxTiles) {
        this.cells = cells;
        this.maxTiles = maxTiles;
        permutations = new int[cells + 1][];
        // permutations[n][k] = n * (n-1) * ... * (n-k+1)
        for (int n = 0; n <= cells; n++) {
            permutations[n] = new int[Math.min(n, maxTiles) + 1];
            permutations[n][0] = 1;
            for (int k = 1; k < permutations[n].length; k++) {
                permutations[n][k] = permutations[n][k - 1] * (n - k + 1);
            }
        }
    }

    /**
     * 计算从 cells 个格子中选取 k 个不同格子放置不同数字的总排列数。
     *
     * @param k 分组数字个数
     * @return 状态总排列数
     */
    int rankCount(int k) {
        return permutations[cells][k];
    }

    /**
     * 计算多元组中指定槽位对应的变进制基数位权。
     *
     * @param k 分组数字总数
     * @param slot 当前数字的槽位下标，从 0 到 k - 1
     * @return 当前槽位的位权
     */
    int weight(int k, int slot) {
        return permutations[cells - 1 - slot][k - 1 - slot];
    }

    /**
     * 将 k 个数字块所在的位置数组 positions 编码为一个唯一的整数排名。
     *
     * <p>采用 Lehmer 码思想：对于第 i 个数字块，统计在其位置之前已被前面的数字占用的格子数 below， 得到该数字在剩余可用格子中的相对序数，再乘以对应的位权累加。
     *
     * @param positions 包含 k 个数字块线性位置下标的数组
     * @param k 数字块数量
     * @return 唯一排名整数
     */
    int rankTuple(int[] positions, int k) {
        int rank = 0;
        int occupied = 0;
        // Lehmer 码：位置减去前面已占用的格子数，得到剩余可用格子中的相对序数
        for (int i = 0; i < k; i++) {
            int below = Integer.bitCount(occupied & ((1 << positions[i]) - 1));
            rank += (positions[i] - below) * weight(k, i);
            occupied |= 1 << positions[i];
        }
        return rank;
    }

    /**
     * 计算空格在未被组内数字占用的剩余空格子中的相对序数。
     *
     * @param occupied 组内数字占用格子的位掩码
     * @param blank 空格在棋盘上的绝对线性下标
     * @return 空格在未占用格子集合中的 0 基序数
     */
    static int blankRank(int occupied, int blank) {
        // 空格绝对下标减去其前面已被组内数字占用的格子数
        return blank - Integer.bitCount(occupied & ((1 << blank) - 1));
    }

    /**
     * 将整数排名和空格相对序数解码还原为各数字块的实际位置数组及空格绝对位置。
     *
     * @param rank 数字块排列的整数排名
     * @param blankRank 空格的相对序数
     * @param k 分组数字个数
     * @param positions 存放还原后数字块位置的数组
     * @param used 辅助布尔数组，记录各格子是否被占用
     * @return 解码出的空格在棋盘上的绝对线性位置下标
     */
    int decode(int rank, int blankRank, int k, int[] positions, boolean[] used) {
        Arrays.fill(used, false);
        for (int i = 0; i < k; i++) {
            int weight = weight(k, i);
            int ordinal = rank / weight;
            rank %= weight;
            // 线性扫描第 ordinal 个未占用格子
            for (int pos = 0; pos < cells; pos++) {
                if (!used[pos] && ordinal-- == 0) {
                    positions[i] = pos;
                    used[pos] = true;
                    break;
                }
            }
        }
        // 剩余未占用格子中第 blankRank 个就是空格位置
        for (int pos = 0; pos < cells; pos++) {
            if (!used[pos] && blankRank-- == 0) return pos;
        }
        return -1;
    }
}
