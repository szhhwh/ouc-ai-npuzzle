package stud.g01.problem.npuzzle;

/** N-Puzzle 问题支持的启发函数类型枚举。 */
public enum PuzzleHeuristicType {
    /** 零启发函数，启发值恒为 0，用于退化为 Dijkstra 算法或一致代价搜索。 */
    ZERO,
    /** 不在位数字块数启发函数，统计不在目标位置上的数字块数量，不计空格。 */
    MISPLACED,
    /** 曼哈顿距离和启发函数，计算所有数字块到目标位置的曼哈顿距离之和，不计空格。 */
    MANHATTAN,
    /** 互不相交的模式数据库启发函数。 */
    DISJOINT_PATTERN
}
