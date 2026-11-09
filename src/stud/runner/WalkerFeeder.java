package stud.runner;

import core.problem.Problem;
import core.runner.EngineFeeder;
import core.solver.algorithm.heuristic.Predictor;
import core.solver.queue.EvaluationType;
import core.solver.queue.Frontier;
import core.solver.queue.Node;
import java.util.ArrayList;
import stud.problem.pathfinding.GridType;
import stud.problem.pathfinding.PathFinding;
import stud.problem.pathfinding.PathHeuristicType;
import stud.problem.pathfinding.Position;
import stud.queue.ListFrontier;

/** 寻路问题的 EngineFeeder。 */
public class WalkerFeeder extends EngineFeeder<PathHeuristicType> {
    public WalkerFeeder() {
        super(PathHeuristicType.class);
    }

    @Override
    public ArrayList<Problem> getProblems(ArrayList<String> problemLines) {
        // 第一行是地图规模
        int size = Integer.parseInt(problemLines.get(0));
        GridType[][] map = getMap(problemLines, size);

        ArrayList<Problem> problems = new ArrayList<>();
        int lineNo = size + 1;
        while (lineNo < problemLines.size()) {
            PathFinding problem = getPathFinding(problemLines.get(lineNo), size);
            problem.setGrids(map);
            problems.add(problem);
            lineNo++;
        }

        return problems;
    }

    /**
     * 解析一行输入，生成一个寻路问题实例。
     *
     * @param problemLine 问题所在的行
     * @param size 地图规模
     * @return 寻路问题实例
     */
    private PathFinding getPathFinding(String problemLine, int size) {
        String[] cells = problemLine.split(" ");
        // 前两个数是起点，后两个数是终点
        int row = Integer.parseInt(cells[0]);
        int col = Integer.parseInt(cells[1]);
        Position initialState = new Position(row, col);
        row = Integer.parseInt(cells[2]);
        col = Integer.parseInt(cells[3]);
        Position goal = new Position(row, col);

        return new PathFinding(initialState, goal, size);
    }

    /**
     * 解析输入中的地图部分。
     *
     * @param problemLines 输入文本的所有行
     * @param size 地图规模
     * @return 地图
     */
    private GridType[][] getMap(ArrayList<String> problemLines, int size) {
        GridType[][] map = new GridType[size][];
        for (int i = 0; i < size; i++) {
            map[i] = new GridType[size];
            String[] cells = problemLines.get(i + 1).split(" ");
            for (int j = 0; j < size; j++) {
                int cellType = Integer.parseInt(cells[j]);
                map[i][j] = GridType.values()[cellType];
            }
        }
        return map;
    }

    @Override
    public Frontier getFrontier(EvaluationType type) {
        return new ListFrontier(Node.evaluator(type));
    }

    /**
     * 按类型取启发函数。
     *
     * @param type 启发函数类型
     * @return 启发函数
     */
    @Override
    public Predictor getPredictor(PathHeuristicType type) {
        return Position.predictor(type);
    }
}
