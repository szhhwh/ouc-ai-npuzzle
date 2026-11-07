package stud.problem.pathfinding;

public enum GridType {
    EMPTY('0', 1), // 空地
    GRASS('*', 5), // 草地，代价为空地的 5 倍
    WALL('+', Integer.MAX_VALUE); // 石墙，无法通过

    private final char symbol;
    private final int magnify;

    GridType(char symbol, int magnify) {
        this.symbol = symbol;
        this.magnify = magnify;
    }

    public char symbol() {
        return symbol;
    }

    public int magnify() {
        return magnify;
    }

    @Override
    public String toString() {
        return symbol + "";
    }
}
