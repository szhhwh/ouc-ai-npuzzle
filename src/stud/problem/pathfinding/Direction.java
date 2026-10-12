package stud.problem.pathfinding;

import java.util.EnumMap;
import java.util.List;

/** 地图中可以移动的8个方向，及其箭头符号 */
public enum Direction {
    N('↑'), // 北
    NE('↗'), // 东北
    E('→'), // 东
    SE('↘'), // 东南
    S('↓'), // 南
    SW('↙'), // 西南
    W('←'), // 西
    NW('↖'); // 西北

    private final char symbol;

    /**
     * @param symbol 该方向对应的箭头符号
     */
    Direction(char symbol) {
        this.symbol = symbol;
    }

    public char symbol() {
        return symbol;
    }

    public static final int SCALE = 10; // 单元格的边长
    public static final double ROOT2 = 1.4; // 2的平方根

    /** 四方向和八方向两种可用的方向集合 */
    public static final List<Direction> FOUR_DIRECTIONS =
            List.of(Direction.N, Direction.E, Direction.S, Direction.W);

    public static final List<Direction> EIGHT_DIRECTIONS = List.of(Direction.values());

    /** 某个方向的移动代价，四方向取 SCALE，斜向取对角线长度 */
    public static int cost(Direction dir) {
        return FOUR_DIRECTIONS.contains(dir) ? SCALE : (int) (SCALE * ROOT2);
    }

    // 各方向的坐标位移量，分别是列增量和行增量
    private static final EnumMap<Direction, int[]> DIRECTION_OFFSET =
            new EnumMap<>(Direction.class);

    static {
        DIRECTION_OFFSET.put(N, new int[] {0, -1});
        DIRECTION_OFFSET.put(NE, new int[] {1, -1});
        DIRECTION_OFFSET.put(E, new int[] {1, 0});
        DIRECTION_OFFSET.put(SE, new int[] {1, 1});
        DIRECTION_OFFSET.put(S, new int[] {0, 1});
        DIRECTION_OFFSET.put(SW, new int[] {-1, 1});
        DIRECTION_OFFSET.put(W, new int[] {-1, 0});
        DIRECTION_OFFSET.put(NW, new int[] {-1, -1});
    }

    public static int[] offset(Direction dir) {
        return DIRECTION_OFFSET.get(dir);
    }
}
