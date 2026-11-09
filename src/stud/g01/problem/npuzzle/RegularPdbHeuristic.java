package stud.g01.problem.npuzzle;

/** 4 阶棋盘 6-6-3 分组模式数据库查询实现，采用乘积寻址与共享距离向量。 */
final class RegularPdbHeuristic {
    /** 6-6-3 分组定义 */
    private static final int[][] GROUPS = {{1, 2, 5, 6, 9, 10}, {3, 4, 7, 8, 11, 12}, {13, 14, 15}};

    /** 目标盘面对应的 6-6-3 模式数据库查找表集合。 */
    static final class Tables {
        /** 第一组与第二组 6 块的直接查表模式数据库对象 */
        private final DirectPdbTable first, second;

        /** 第三组 3 块的乘积查表模式数据库字节数组 */
        private final byte[] third;

        /** 沿主对角线转置的格子下标映射数组与数字对称置换数组 */
        private final int[] transpose = new int[16], permutation = new int[16];

        /** 是否满足主对角线对称条件 */
        private final boolean symmetric;

        /** 对称转置下的逆位置编码快速映射查找表数组 */
        private final long[][] mirroredPositions;

        /**
         * 初始化 6-6-3 分组查找表。
         *
         * @param goal 目标盘面
         * @param repository 模式数据库仓库
         */
        Tables(PuzzleBoard goal, PdbRepository repository) {
            first = DirectPdbTable.load(4, goal.cellsInternal(), GROUPS[0], repository);
            second = DirectPdbTable.load(4, goal.cellsInternal(), GROUPS[1], repository);
            third =
                    DisjointPdbHeuristic.loadOrBuildGroup(
                            4, goal.cellsInternal(), GROUPS[2], repository);
            int[] pos = new int[16];
            for (int i = 0; i < 16; i++) {
                transpose[i] = (i % 4) * 4 + i / 4;
                pos[goal.cellsInternal()[i]] = i;
            }
            // 沿主对角线转置后，数字 v 映射为目标盘面中转置位置上的数字
            for (int v = 0; v < 16; v++) permutation[v] = goal.cellsInternal()[transpose[pos[v]]];
            symmetric = permutation[0] == 0;
            mirroredPositions = new long[symmetric ? 4 : 0][];
            // 预计算 16 位位置打包的转置映射，避免热路径逐位抽取
            for (int block = 0; block < mirroredPositions.length; block++) {
                long[] lookup = new long[1 << 16];
                for (int value = 0; value < lookup.length; value++) {
                    long result = 0;
                    for (int j = 0; j < 4; j++) {
                        int p = (value >>> (j * 4)) & 15;
                        result |= (long) transpose[p] << (permutation[block * 4 + j] * 4);
                    }
                    lookup[value] = result;
                }
                mirroredPositions[block] = lookup;
            }
        }

        /**
         * 查表计算当前状态的最大启发值。
         *
         * @param state 当前盘面状态
         * @param useSymmetry 是否启用对称增强
         * @return 启发值
         */
        int hMax(PuzzleBoard state, boolean useSymmetry) {
            long positions = state.positions();
            int direct = h(positions, state.blank());
            if (!useSymmetry || !symmetric) return direct;
            // 按 16 位分块查表，把逆位置编码整体转置为对称态
            long mirrored =
                    mirroredPositions[0][(int) positions & 65535]
                            | mirroredPositions[1][(int) (positions >>> 16) & 65535]
                            | mirroredPositions[2][(int) (positions >>> 32) & 65535]
                            | mirroredPositions[3][(int) (positions >>> 48)];
            return Math.max(direct, h(mirrored, transpose[state.blank()]));
        }

        /**
         * 累加三组的局部启发值。
         *
         * @param positions 逆位置编码
         * @param blank 空格位置下标
         * @return 三组启发值累加总和
         */
        private int h(long positions, int blank) {
            int a = (int) (positions >>> 52) & 15;
            int b = (int) (positions >>> 56) & 15;
            int c = (int) (positions >>> 60);
            // 第三组 3 个数字的位置加空格位置拼成乘积索引
            int index = (a << 12) | (b << 8) | (c << 4) | blank;
            return first.distance(positions, blank)
                    + second.distance(positions, blank)
                    + (third[index] & 255);
        }
    }
}
