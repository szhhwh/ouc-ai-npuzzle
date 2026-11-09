package stud.g01.problem.npuzzle;

/**
 * 模式数据库优化选项配置记录类。
 *
 * <p>在创建搜索器或预测器时捕获当前配置开关。
 *
 * @param incremental 是否启用基于移动的模式数据库增量查表更新优化
 * @param symmetry 是否启用主对角线翻转的对称性估值增强，取直接估值与转置估值的最大值
 */
public record PdbOptions(boolean incremental, boolean symmetry) {

    /**
     * 从系统属性中读取配置并构建 PdbOptions 实例，两项优化默认均为开启状态。
     *
     * @return 包含当前配置的 PdbOptions 实例
     */
    public static PdbOptions fromSystemProperties() {
        return new PdbOptions(
                readBoolean("npuzzle.pdb.incremental"), readBoolean("npuzzle.pdb.symmetry"));
    }

    /**
     * 读取布尔类型的系统属性值。
     *
     * @param name 系统属性名称
     * @return 属性的布尔值
     * @throws IllegalArgumentException 当属性值非 true 或 false 时抛出
     */
    private static boolean readBoolean(String name) {
        String value = System.getProperty(name, "true");
        if ("true".equalsIgnoreCase(value)) return true;
        if ("false".equalsIgnoreCase(value)) return false;
        throw new IllegalArgumentException(name + " must be true or false: " + value);
    }
}
