package stud.g01.problem.npuzzle;

import core.solver.algorithm.heuristic.Predictor;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 可加（分离/不相交）模式数据库启发函数 —— Additive Disjoint Pattern Database（PDB）。
 *
 * <p>【additive PDB 原理】</p>
 * <p>把盘面上所有非空格将牌按"将牌值"划分成若干互不相交的组（4 阶 6-6-3 三组：
 * {@code {1,2,5,6,9,10}}、{@code {3,4,7,8,11,12}}、{@code {13,14,15}}；3 阶 3-3-2 三组：
 * {@code {1,2,3}}、{@code {4,5,6}}、{@code {7,8}}）。分组相对于 goal 盘面进行：组内各将牌
 * 的值在 goal 盘面中的位置天然给出该组的目标布局。对每一组单独考察一个"抽象问题"：
 * 只保留该组将牌与空格的真实位置，其余将牌全部视作无差别的"无关将牌"（don't care）。
 * 从目标配置的抽象投影出发做一次完全的倒向搜索，即可离线求得：任何抽象配置
 * （组内各将牌位置 + 空格位置）要把组内将牌全部挪回目标位置，至少需要多少次
 * "组内将牌的移动"。查询时把真实状态的各组投影分别查表后求和，即得启发值。</p>
 *
 * <p>【为什么空格移动不计代价，就能保证各组之和可加（admissible）】</p>
 * <p>真实盘面任何一步移动，相当于空格与某个相邻将牌 v 交换——v 属于且只属于某一组 G。
 * 把这步移动投影到各组：对 G 而言是"组内将牌动了一次"（抽象代价 1）；对其它组而言，
 * 只是空格从一个格子挪到另一个格子、组内将牌纹丝不动（抽象代价 0）。于是一条真实解
 * 路径的代价（= 步数）恰好等于它在各组抽象空间中投影代价之和。因此对任一状态 s：
 * 各组表值 h_G(s) ≤ 最优解路径中 G 组将牌的移动次数；对全部组求和即得
 * Σ h_G(s) ≤ 最优解长，可采纳性得证。反之，若某组的表把空格移动也计为 1
 * （即普通非可加 PDB 的计法），各组之和会对空格的开销重复计费，可能超过真实解长
 * 而失去可采纳性——这正是 additive PDB 必须让空格"免费"的原因。</p>
 *
 * <p>【为什么查询要携带空格位置——以及它带来的一致性（consistency）】</p>
 * <p>空格位置进入抽象状态编码后，"空格与无关将牌交换"成为抽象空间中一条 <b>0 代价</b>
 * 边，且双向对称：于是对不含被移动将牌的组，移动前后的两个抽象状态互达且代价为 0，
 * 表值严格相等；对含被移动将牌的组，两状态之间是一条 1 代价边，表值至多变化 1。
 * 故任一单步真实移动满足 |h(s) − h(s′)| ≤ 1，启发函数是一致的，f 值沿路径单调不降，
 * IDA* 与 A* 均可正确剪枝。若不把空格编入状态（Korf-Felner 原始 5-5-5 表的做法），
 * 表是对空格位置取 min 后的 weaker 版本，一致性论证也不再逐字成立。</p>
 *
 * <p>【为什么用多源起点】</p>
 * <p>抽象目标不应把空格钉死在 goal 盘面的空格位。真实解的终点空格位置固然固定，
 * 但抽象空间里"组内将牌全部就位、空格在任意其余格子"的配置彼此之间只差 0 代价的
 * 空格移动；真实终点在组 G 的投影只是这个集合中的一个成员。倒向 BFS 从该集合的
 * <b>全部</b>成员（4 阶 6 将牌组为 16 − 6 = 10 个）同时以 dist = 0 出发，求得的表值是
 * "到集合的距离" ≤ "到真实投影的距离"，只会更小或相等，绝不破坏可采纳性；
 * 同时它也是该编码下能得到的最大表值（再抬高就不可采纳了）。</p>
 *
 * <p>【0-1 BFS】</p>
 * <p>抽象空间中同时存在 0 代价边（空格与无关将牌交换）与 1 代价边（空格与组内将牌
 * 交换），故用双端队列的 0-1 BFS：改进后的邻态若经 0 代价边到达则 addFirst，
 * 经 1 代价边到达则 addLast，保证队首到队尾距离单调，每个状态至多被 settle 一次。</p>
 *
 * <p>【对称增强（symmetry lookup，Korf &amp; Felner 2002）】</p>
 * <p>盘面转置 τ（沿主对角线翻转，UP↔LEFT、DOWN↔RIGHT）是状态图的自同构；
 * 再配以按 goal 导出的将牌值置换 π（{@code π(v) = goal[τ(pos_goal(v))]}），
 * 复合映射 T(s) = π(τ(s)) 对<b>任意</b> goal 都保持 goal 不变（π = G∘τ∘G⁻¹
 * 的共轭构造保证）。但实现还要求 <b>π(0) = 0</b>——等价于 goal 的空格位于
 * 主对角线上（标准目标满足）：此时 T 把空格映到空格，对称态的空格位置恰为
 * τ(blank)，查表可用；否则对称查询定错空格维度、表值不再可采纳，自动禁用
 * 增强（退化为直接查表 h(s)）。在启用条件下 d(s, goal) = d(T(s), goal)，
 * h(T(s)) 同样是 s 的可采纳下界；取 h'(s) = max(h(s), h(T(s))) 仍可采纳且
 * 一致（|h'(s)−h'(s')| ≤ 1 对 max 成立），而强度显著提升——同一张表免费
 * 查两次，无需重建任何 PDB。</p>
 *
 * <p>【复杂度与内存】</p>
 * <ul>
 *   <li>编码：乘积进制，各维取值 0..size²−1。4 阶 6 将牌组（含空格 7 维）→
 *       16⁷ = 268,435,456 项 byte（256 MB）×2 组，3 将牌组 16⁴ = 65,536 项；
 *       3 阶 3 将牌组 9⁴ = 6,561 项、2 将牌组 9³ = 729 项。4 阶三组合计约
 *       512 MB，需 JVM 堆 ≥ 1.5 GB（含构建期 BitSet）。</li>
 *   <li>有效状态（将牌位置互异）：4 阶每组 16×15×14×13×12×11×10 = 57,657,600 个，
 *       构建是每状态 O(1) 出队的 0-1 BFS，每组数秒（一次性，此后走磁盘缓存）；
 *       查询每次 3 次查表，O(1)。</li>
 *   <li>磁盘缓存：构建完成后写入 {@code resources/pdb-cache/pdb-{size}-{组号}.bin}，
 *       文件头含 magic、盘面规模、goal 盘面原文、分组签名；任一项不符即判定
 *       缓存失效并重建。goal 改变后旧缓存自然被判废。</li>
 *   <li>进程内缓存：以 "size + goal 内容" 为 key 的 ConcurrentHashMap（另加单槽
 *       引用 memo 加速热路径），同一 goal 的重复调用直接命中，绝不重复 BFS。
 *       注意：BUILT 不做淘汰，问题文件中每个<b>不同</b>的 4 阶 goal 都会常驻一套
 *       512 MB 的表——多 goal 场景需按 goal 数放大 -Xmx。</li>
 *   <li>与教师框架的集成：SearchTester 第三阶段（step=3）走
 *       {@code getAStar(DISJOINT_PATTERN)}（BestFirstSearcher），A* 的
 *       frontier/explored 会叠加在 512 MB 表之上，跑接近最难实例时需加大
 *       堆（建议 -Xmx4g 起）；IDA*（{@code getIdaStar}）空间 O(d)，2 GB 即可。</li>
 * </ul>
 */
public final class DisjointPdbHeuristic {

    /** 已构建 PDB 表的进程内缓存：key = 盘面规模 + "#" + goal 盘面内容（toString 逐值唯一） */
    private static final Map<String, Tables> BUILT = new ConcurrentHashMap<>();

    /**
     * 单槽 memo：上一次查询的 (goal 引用, 表)。同一次搜索中 goal 实例固定不变，
     * 逐结点的启发估值先做 O(1) 引用比较即可命中，免去每次重建字符串 key
     * （实测热路径上 key 构建约占 1/4 运行时间）。goal 变化时回退到 BUILT 查找。
     * goal 与表封装为单个不可变持有者发布，保证并发读到的二者必定配对。
     */
    private static final class Memo {
        final PuzzleBoard goal;
        final Tables tables;
        Memo(PuzzleBoard goal, Tables tables) { this.goal = goal; this.tables = tables; }
    }
    private static volatile Memo lastMemo;

    /** 磁盘缓存目录（相对当前工作目录） */
    private static final Path CACHE_DIR = Paths.get("resources", "pdb-cache");

    /** 缓存文件头魔数：ASCII "PDB1" */
    private static final int MAGIC = 0x50444231;

    /**
     * 各盘面规模的分组定义（按将牌值分组；null 表示该规模不支持，查询回退曼哈顿距离）。
     * 4 阶取 6-6-3（Korf-Felner 经典划分：左两列 / 右两列 / 底行），
     * 每组 6 将牌 + 空格共 7 维乘积进制编码 → 16⁷ = 256 MB byte 表 ×2 组，
     * 强度显著高于 5-5-5（组内将牌更多、交互建模更充分）；
     * 3 阶沿用 3-3-2（全空间仅 9⁴，构建毫秒级）。
     */
    private static final Map<Integer, int[][]> GROUPINGS = Map.of(
            4, new int[][]{{1, 2, 5, 6, 9, 10}, {3, 4, 7, 8, 11, 12}, {13, 14, 15}},
            3, new int[][]{{1, 2, 3}, {4, 5, 6}, {7, 8}}
    );

    private DisjointPdbHeuristic() {
    }

    /**
     * 获得可加模式数据库启发函数（带对称增强）。
     *
     * <p>返回的 Predictor 对每次查询按 goal 盘面懒构建/命中 PDB 表缓存，
     * 估值 = max(直接查表和, 对称态查表和)。重复以同一 goal 调用不会触发重建。</p>
     *
     * @return (state, goal) -> max(h(s), h(T(s)))，见类注释【对称增强】
     */
    public static Predictor predictor() {
        return (state, goal) -> tablesFor((PuzzleBoard) goal).hMax((PuzzleBoard) state);
    }

    /**
     * 按 goal 盘面取（或懒构建）该 goal 对应的整套 PDB 表。
     * 优先走单槽 memo（引用比较，同一次搜索 O(1) 命中），
     * 失配再以盘面规模与 goal 盘面内容为 key 查/建 BUILT 缓存。
     *
     * <p>包内可见：供 {@link NPuzzleIdaStar}（增量查询的专用 IDA*）获取同一套表。</p>
     */
    static Tables tablesFor(PuzzleBoard goal) {
        Memo m = lastMemo;
        if (m != null && m.goal == goal) {
            return m.tables;
        }
        String key = goal.size() + "#" + goal;
        Tables t = BUILT.computeIfAbsent(key, k -> Tables.build(goal));
        lastMemo = new Memo(goal, t);
        return t;
    }

    // ==================================================================
    //  一套 PDB 表（某个 goal 盘面对应的全部组）
    // ==================================================================

    /**
     * 某一 goal 盘面对应的全部分组距离表，以及查询逻辑。
     * 所有字段在构建后只读，线程安全。
     *
     * <p>类与字段为包内可见：{@link NPuzzleIdaStar} 直接读取分组、位权与对称表，
     * 实现逐步移动的 O(1) 增量查询（见其类注释）。</p>
     */
    static final class Tables {

        /** 盘面边长 */
        final int size;
        /** 格子总数 size² */
        final int cells;
        /** 分组定义；null 表示该规模无分组（不支持），h 回退整盘曼哈顿距离 */
        final int[][] groups;
        /** groups 中每组对应的距离表 */
        final byte[][] tables;
        /** goal 盘面原文（构造对称置换与磁盘缓存头校验用） */
        final byte[] goalCells;
        /** goalPos[v]：将牌 v 在 goal 盘面上的线性下标（导出对称置换 π 用） */
        private final int[] goalPos;

        /** transpose[i]：格子 i 沿主对角线翻转后的线性下标（对合） */
        final int[] transpose;
        /** symPerm[v]：将牌值置换 π，π(v) = goal[transpose(pos_goal(v))] */
        final int[] symPerm;
        /** 对称增强是否可用：π(0) == 0（即 goal 空格位于主对角线上）时为 true */
        final boolean symmetric;

        private Tables(int size, int[][] groups, byte[][] tables, byte[] goalCells) {
            this.size = size;
            this.cells = size * size;
            this.groups = groups;
            this.tables = tables;
            this.goalCells = goalCells;
            this.goalPos = new int[cells];
            for (int i = 0; i < cells; i++) {
                goalPos[goalCells[i]] = i;
            }

            //导出对称变换：转置表 τ 与值置换 π。
            //注意：π(τ(goal)) == goal 对任意 goal 恒成立（π = G∘τ∘G⁻¹ 的共轭构造
            //保证），不构成守卫；对称增强真正的先行条件是 π(0) == 0，等价于
            //goal 的空格位于主对角线上——此时 T(s) = π(τ(s)) 才把空格映到空格，
            //对称态的空格位置恰为 τ(blank)。若 goal 空格不在对角线（π(0)≠0），
            //对称查询无法用 τ(blank) 定位空格，查得的表值不再是 s 的下界，
            //必须禁用增强。标准目标（空格在右下角）满足该条件。
            this.transpose = new int[cells];
            for (int i = 0; i < cells; i++) {
                transpose[i] = (i % size) * size + i / size;
            }
            this.symPerm = new int[cells];
            for (int v = 0; v < cells; v++) {
                symPerm[v] = goalCells[transpose[goalPos[v]]];
            }
            this.symmetric = symPerm[0] == 0;
        }

        /**
         * 为 goal 盘面构建整套表：逐组"磁盘缓存命中 → 否则 0-1 BFS 构建 → 写盘"。
         */
        static Tables build(PuzzleBoard goal) {
            int size = goal.size();
            int[][] groups = GROUPINGS.get(size);
            if (groups == null) {
                // PDB 分组只为课程涉及的 3/4 阶定义
                throw new UnsupportedOperationException(size + " 阶未定义 PDB 分组（仅支持 3/4 阶）");
            }
            byte[][] ts = new byte[groups.length][];
            for (int g = 0; g < groups.length; g++) {
                ts[g] = loadOrBuildGroup(size, goal.cells(), groups[g], g);
            }
            return new Tables(size, groups, ts, goal.cells().clone());
        }

        /**
         * 直接查询：state 各组投影查表求和。
         */
        int h(PuzzleBoard state) {
            return hCells(state.cells(), state.blank());
        }

        /**
         * 对称增强查询：h'(s) = max(h(s), h(T(s)))，T = π∘τ 见类注释。
         * goal 不满足对称条件（π(0)≠0）时退化为直接查询 h(s)。
         */
        int hMax(PuzzleBoard state) {
            int direct = h(state);
            if (!symmetric) {
                return direct;
            }

            //构造对称态 T(s)：t[i] = π(s[τ(i)])；空格位置随之转置
            byte[] c = state.cells();
            byte[] t = new byte[cells];
            for (int i = 0; i < cells; i++) {
                t[i] = (byte) symPerm[c[transpose[i]]];
            }
            int mirrored = hCells(t, transpose[state.blank()]);
            return Math.max(direct, mirrored);
        }

        /**
         * 查表核心：给定盘面数组与空格下标，各组投影查表求和。
         * 直接接收数组而非 PuzzleBoard，使对称态查询无需构造完整对象（热路径）。
         * 有效状态（各将牌位置互异）的投影必然已被 0-1 BFS 覆盖——
         * BFS 从目标抽象配置出发覆盖整个连通抽象空间。
         *
         * <p>包内可见：{@link NPuzzleIdaStar} 的自检模式（-Dnpida.verify）
         * 以全量查询核对增量维护的启发值。</p>
         */
        int hCells(byte[] c, int blank) {
            //一次线性扫描建 pos[v]（将牌 v 的当前位置），供各组复用
            int[] pos = new int[cells];
            for (int i = 0; i < cells; i++) {
                pos[c[i]] = i;
            }

            int sum = 0;
            for (int g = 0; g < groups.length; g++) {
                int[] tiles = groups[g];
                //Horner 法构造乘积进制下标：最高位是 tiles[0] 的位置，最低位是空格位置
                int idx = pos[tiles[0]];
                for (int j = 1; j < tiles.length; j++) {
                    idx = idx * cells + pos[tiles[j]];
                }
                idx = idx * cells + blank;
                sum += tables[g][idx] & 0xFF;
            }
            return sum;
        }
    }

    // ==================================================================
    //  单组距离表的磁盘缓存与 0-1 BFS 构建
    // ==================================================================

    /**
     * 取某一组的距离表：磁盘缓存校验通过则读入，否则 0-1 BFS 构建并写盘。
     *
     * @param size   盘面边长
     * @param goalCells goal 盘面（行主序，0 = 空格）
     * @param tiles  该组的将牌值
     * @param gi     组号（用于缓存文件命名）
     */
    private static byte[] loadOrBuildGroup(int size, byte[] goalCells, int[] tiles, int gi) {
        Path file = CACHE_DIR.resolve("pdb-" + size + "-" + gi + ".bin");
        byte[] cached = tryLoad(file, size, goalCells, tiles);
        if (cached != null) {
            System.out.println("[PDB] " + file + " 缓存命中（" + cached.length + " 项）");
            return cached;
        }
        long t0 = System.currentTimeMillis();
        byte[] built = buildGroupTable(size, goalCells, tiles);
        System.out.println("[PDB] " + file + " 缓存未命中或校验失败，0-1 BFS 重建完成："
                + built.length + " 项，耗时 " + (System.currentTimeMillis() - t0) + " ms");
        tryWrite(file, size, goalCells, tiles, built);
        return built;
    }

    /**
     * 尝试从缓存文件读入某组距离表；文件不存在、任一头字段不符或 CRC 不符均返回 null。
     *
     * <p>文件布局（DataOutputStream 大端序）：
     * magic(int)、size(int)、goalLen(int)、goal 原文(goalLen 字节)、
     * 组将牌数(int)、各将牌值(int×n)、数据长度(int)、距离数据(len 字节)。
     * 头部用 goal 盘面与分组的原文做校验——goal 或分组改变即判缓存失效。</p>
     */
    private static byte[] tryLoad(Path file, int size, byte[] goalCells, int[] tiles) {
        if (!Files.isRegularFile(file)) return null;
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(file), 1 << 16))) {
            if (in.readInt() != MAGIC) return null;
            if (in.readInt() != size) return null;
            int goalLen = in.readInt();
            if (goalLen != goalCells.length) return null;
            byte[] g = new byte[goalLen];
            in.readFully(g);
            if (!Arrays.equals(g, goalCells)) return null;   //goal 改变 → 缓存失效
            int n = in.readInt();
            if (n != tiles.length) return null;              //分组签名不符 → 缓存失效
            for (int tile : tiles) {
                if (in.readInt() != tile) return null;
            }
            int len = in.readInt();
            if (len != tableLength(size, tiles.length)) return null;
            byte[] data = new byte[len];
            in.readFully(data);
            return data;
        } catch (IOException | RuntimeException e) {
            return null;   //读半截文件、被截断等任何异常都视为缓存无效
        }
    }

    /**
     * 把某组距离表写入缓存文件。
     * 写盘失败不影响正确性：仅打印告警，表仍在内存中可用。
     */
    private static void tryWrite(Path file, int size, byte[] goalCells, int[] tiles, byte[] data) {
        try {
            Files.createDirectories(CACHE_DIR);
            try (DataOutputStream out = new DataOutputStream(
                    new BufferedOutputStream(Files.newOutputStream(file), 1 << 16))) {
                out.writeInt(MAGIC);
                out.writeInt(size);
                out.writeInt(goalCells.length);
                out.write(goalCells);
                out.writeInt(tiles.length);
                for (int tile : tiles) out.writeInt(tile);
                out.writeInt(data.length);
                out.write(data);
            }
            System.out.println("[PDB] 已写缓存 " + file + "（" + data.length + " 字节）");
        } catch (IOException e) {
            System.out.println("[PDB] 缓存写入失败（" + file + "）：" + e
                    + "；不影响本次运行，表保留在内存中");
        }
    }

    /**
     * 对某一组做倒向多源 0-1 BFS，构建该组距离表。
     *
     * <p>抽象状态编码（乘积进制，共 D+1 维，D = 组内将牌数）：
     * {@code idx = p0·cells^D + p1·cells^(D-1) + ... + p(D-1)·cells + blank}，
     * 其中 p_j 是组内第 j 个将牌的线性位置，blank 是空格位置。
     * 未访问项保持 0xFF。</p>
     *
     * <p>转移：空格与四邻交换。交换对象是组内将牌 → 代价 1，同时该将牌数字与空格
     * 数字互换；交换对象是无关将牌 → 仅空格数字变化，代价 0；越界方向跳过。
     * 下标更新用位权直接增量计算，避免每次转移整体重编码。</p>
     *
     * @param size      盘面边长
     * @param goalCells goal 盘面
     * @param tiles     组内将牌值
     */
    private static byte[] buildGroupTable(int size, byte[] goalCells, int[] tiles) {
        final int cells = size * size;
        final int D = tiles.length;

        //w[j]：第 j 个将牌数字的位权 cells^(D-j)（空格数字位权恒为 1）
        final int[] w = new int[D];
        for (int j = 0; j < D; j++) {
            w[j] = pow(cells, D - j);
        }
        final int total = pow(cells, D + 1);
        byte[] dist = new byte[total];
        Arrays.fill(dist, (byte) 0xFF);   //0xFF = 未访问

        //tp[j]：组内第 j 个将牌在 goal 盘面上的位置（组的目标布局）
        final int[] tp = new int[D];
        for (int i = 0; i < cells; i++) {
            for (int j = 0; j < D; j++) {
                if (goalCells[i] == tiles[j]) tp[j] = i;
            }
        }

        //各格子的邻接表（预计算，热循环内免做边界判断）
        final int[][] nbr = neighbors(size);

        //多源起点：组内将牌全部就位 + 空格在任意其余格子，全部 dist=0 入队
        IntDeque deque = new IntDeque(1 << 12);
        int tilesIdx = 0;
        for (int j = 0; j < D; j++) {
            tilesIdx += tp[j] * w[j];
        }
        for (int b = 0; b < cells; b++) {
            boolean occupied = false;
            for (int p : tp) {
                if (p == b) { occupied = true; break; }
            }
            if (occupied) continue;   //空格不能与组内将牌同格
            dist[tilesIdx + b] = 0;
            deque.addLast(tilesIdx + b);
        }

        //0-1 BFS 主体（settled 位集防止陈旧队列项被重复扩展）
        BitSet settled = new BitSet(total);
        int[] p = new int[D];   //出队状态的各将牌位置（复用，避免热循环分配）

        while (!deque.isEmpty()) {
            int idx = deque.pollFirst();
            if (settled.get(idx)) continue;
            settled.set(idx);

            int d = dist[idx] & 0xFF;
            int b = idx % cells;          //空格位置（最低位）
            int rest = idx / cells;
            for (int j = D - 1; j >= 0; j--) {   //由低位到高位解出各将牌位置
                p[j] = rest % cells;
                rest /= cells;
            }

            for (int t : nbr[b]) {        //空格与四邻 t 交换
                //t 处是否是组内将牌？是则记其下标 ti
                int ti = -1;
                for (int j = 0; j < D; j++) {
                    if (p[j] == t) { ti = j; break; }
                }
                int nIdx, cost;
                if (ti >= 0) {
                    //组内将牌 ti 从 t 移到 b，空格从 b 移到 t：
                    //idx' = idx - t·w[ti] + b·w[ti] - b + t = idx + (b-t)·(w[ti]-1)
                    nIdx = idx + (b - t) * (w[ti] - 1);
                    cost = 1;
                } else {
                    //无关将牌：抽象上只动空格，代价 0
                    nIdx = idx - b + t;
                    cost = 0;
                }
                int nd = d + cost;
                if ((dist[nIdx] & 0xFF) > nd) {
                    dist[nIdx] = (byte) nd;
                    if (cost == 0) deque.addFirst(nIdx);   //0 代价：队首
                    else deque.addLast(nIdx);              //1 代价：队尾
                }
            }
        }
        return dist;
    }

    /** base 的非负整数次幂（编码规模很小，循环相乘足够） */
    private static int pow(int base, int exp) {
        int r = 1;
        for (int i = 0; i < exp; i++) r *= base;
        return r;
    }

    /** 某组距离表的项数：cells^(组内将牌数+1) */
    private static int tableLength(int size, int tiles) {
        return pow(size * size, tiles + 1);
    }

    /** 预计算每个格子的四邻下标（棋盘内），供 BFS 热循环直接遍历 */
    private static int[][] neighbors(int size) {
        int cells = size * size;
        int[][] nbr = new int[cells][];
        int[] buf = new int[4];
        for (int i = 0; i < cells; i++) {
            int r = i / size, c = i % size, n = 0;
            if (r > 0) buf[n++] = i - size;          //上
            if (r < size - 1) buf[n++] = i + size;   //下
            if (c > 0) buf[n++] = i - 1;             //左
            if (c < size - 1) buf[n++] = i + 1;      //右
            nbr[i] = Arrays.copyOf(buf, n);
        }
        return nbr;
    }

    // ==================================================================
    //  原生 int 双端队列（0-1 BFS 专用，避免 Integer 装箱的分配与 GC 压力）
    // ==================================================================

    /** 可增长的循环数组 int 双端队列 */
    private static final class IntDeque {
        private int[] buf;     //循环缓冲
        private int head = 0;  //队头下标
        private int len = 0;   //当前元素个数

        IntDeque(int expectedCapacity) {
            int cap = Math.max(16, Integer.highestOneBit(expectedCapacity - 1) << 1);
            buf = new int[cap];
        }

        boolean isEmpty() {
            return len == 0;
        }

        void addFirst(int v) {
            ensureCapacity();
            head = (head - 1 + buf.length) % buf.length;
            buf[head] = v;
            len++;
        }

        void addLast(int v) {
            ensureCapacity();
            buf[(head + len) % buf.length] = v;
            len++;
        }

        int pollFirst() {
            int v = buf[head];
            head = (head + 1) % buf.length;
            len--;
            return v;
        }

        private void ensureCapacity() {
            if (len < buf.length) return;
            int[] nb = new int[buf.length << 1];
            for (int i = 0; i < len; i++) {
                nb[i] = buf[(head + i) % buf.length];
            }
            buf = nb;
            head = 0;
        }
    }
}
