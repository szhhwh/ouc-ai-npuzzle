package stud.g01.problem.npuzzle;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * 模式数据库仓库管理类。
 *
 * <p>统一管理 PDB 表的内存懒加载缓存以及磁盘存储目录。 实例化仓库对象时不会立即读取磁盘文件或构建距离表，而是在具体查询对应目标盘面时按需懒加载并缓存。
 */
public final class PdbRepository {
    /** 缓存存储目录路径 */
    private final Path directory;

    /** 3 阶乘积编码 PDB 表缓存 */
    private final GoalTables<DisjointPdbHeuristic.Tables> product;

    /** 4 阶常规查表 PDB 表缓存 */
    private final GoalTables<RegularPdbHeuristic.Tables> regular;

    /** 4 阶连通分支压缩 PDB 表缓存 */
    private final GoalTables<ComponentPdbContext.Tables> component;

    /**
     * 构造指定存储目录的 PdbRepository 实例。
     *
     * @param directory 磁盘缓存目录路径
     * @throws NullPointerException 当 directory 为 null 时抛出
     */
    public PdbRepository(Path directory) {
        this.directory =
                Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
        product = new GoalTables<>(goal -> DisjointPdbHeuristic.Tables.build(goal, this));
        regular = new GoalTables<>(goal -> new RegularPdbHeuristic.Tables(goal, this));
        component = new GoalTables<>(goal -> new ComponentPdbContext.Tables(goal, this));
    }

    /**
     * 获取缓存目录路径。
     *
     * @return 缓存目录绝对路径
     */
    public Path directory() {
        return directory;
    }

    /**
     * 获取全局默认的模式数据库仓库单例。
     *
     * <p>默认目录由系统属性 npuzzle.pdb.cacheDir 指定，缺省为 resources/pdb-cache。
     *
     * @return 全局默认 PdbRepository 实例
     */
    public static PdbRepository defaultRepository() {
        return DefaultHolder.INSTANCE;
    }

    /** 默认仓库单例持有类 */
    private static final class DefaultHolder {
        static final PdbRepository INSTANCE =
                new PdbRepository(
                        Path.of(System.getProperty("npuzzle.pdb.cacheDir", "resources/pdb-cache")));
    }

    /**
     * 获取 3 阶乘积编码模式数据库表。
     *
     * @param goal 目标盘面
     * @return 对应的 DisjointPdbHeuristic.Tables 实例
     */
    DisjointPdbHeuristic.Tables productTables(PuzzleBoard goal) {
        requireSize(goal, 3);
        return product.get(goal);
    }

    /**
     * 获取 4 阶常规查表模式数据库表。
     *
     * @param goal 目标盘面
     * @return 对应的 RegularPdbHeuristic.Tables 实例
     */
    RegularPdbHeuristic.Tables regularTables(PuzzleBoard goal) {
        requireSize(goal, 4);
        return regular.get(goal);
    }

    /**
     * 获取 4 阶连通分支压缩模式数据库表。
     *
     * @param goal 目标盘面
     * @return 对应的 ComponentPdbContext.Tables 实例
     */
    ComponentPdbContext.Tables componentTables(PuzzleBoard goal) {
        requireSize(goal, 4);
        return component.get(goal);
    }

    /**
     * 校验目标盘面的边长是否满足指定规格。
     *
     * @param goal 目标盘面
     * @param size 期望的边长
     * @throws NullPointerException 当 goal 为 null 时抛出
     * @throws UnsupportedOperationException 当边长不符时抛出
     */
    private static void requireSize(PuzzleBoard goal, int size) {
        Objects.requireNonNull(goal, "goal");
        if (goal.size() != size) {
            throw new UnsupportedOperationException("This PDB strategy requires size " + size);
        }
    }

    /**
     * 按目标盘面缓存距离表的线程安全泛型容器。
     *
     * @param <T> 表结构类型
     */
    private static final class GoalTables<T> {
        /** 目标盘面到距离表的并发哈希映射缓存 */
        private final Map<PuzzleBoard, T> built = new ConcurrentHashMap<>();

        /** 距离表构建函数 */
        private final Function<PuzzleBoard, T> build;

        /** 最近一次访问命中的目标盘面与表结构单元素快表 */
        private volatile Entry<T> last;

        /** 单目标盘面与对应表结构的缓存条目记录类 */
        private record Entry<T>(PuzzleBoard goal, T tables) {}

        /**
         * 构造目标盘面距离表缓存容器。
         *
         * @param build 距离表构建函数
         */
        GoalTables(Function<PuzzleBoard, T> build) {
            this.build = build;
        }

        /**
         * 获取指定目标盘面对应的 PDB 表实例。
         *
         * @param goal 目标盘面
         * @return 对应的表实例
         */
        T get(PuzzleBoard goal) {
            // 单元素快表优先，避免搜索热路径反复查并发映射
            Entry<T> memo = last;
            if (memo != null && memo.goal == goal) return memo.tables;
            T tables = built.computeIfAbsent(goal, build);
            last = new Entry<>(goal, tables);
            return tables;
        }
    }
}
