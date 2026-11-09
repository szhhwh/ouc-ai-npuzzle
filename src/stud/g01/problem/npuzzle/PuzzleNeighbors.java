package stud.g01.problem.npuzzle;

import java.util.Arrays;

/**
 * 棋盘各格子相邻关系的预计算工具类。
 *
 * <p>为指定规模的棋盘预先计算每个格子的合法邻接格子下标，供模式数据库构建与专用搜索快速索引。
 */
final class PuzzleNeighbors {
    /** 私有构造方法，防止工具类被实例化。 */
    private PuzzleNeighbors() {}

    /**
     * 为指定边长的棋盘生成全空格相邻关系查找表。
     *
     * <p>邻接格子的存放顺序严格保持 UP、DOWN、LEFT、RIGHT，与搜索算法的遍历顺序一致。
     *
     * @param size 棋盘边长
     * @return 二维数组 neighbors，其中 neighbors[i] 为格子 i 的所有合法相邻格子下标列表
     */
    static int[][] forSize(int size) {
        int[][] neighbors = new int[size * size][];
        int[] buffer = new int[4];
        for (int i = 0; i < neighbors.length; i++) {
            int row = i / size, col = i % size, count = 0;
            // 顺序固定为 UP、DOWN、LEFT、RIGHT
            if (row > 0) buffer[count++] = i - size;
            if (row < size - 1) buffer[count++] = i + size;
            if (col > 0) buffer[count++] = i - 1;
            if (col < size - 1) buffer[count++] = i + 1;
            neighbors[i] = Arrays.copyOf(buffer, count);
        }
        return neighbors;
    }
}
