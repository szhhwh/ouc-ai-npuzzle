package stud.g01.runner;

import core.problem.State;
import core.solver.algorithm.searcher.SearchObserver;
import core.solver.queue.Node;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import stud.g01.problem.npuzzle.PuzzleBoard;

/** 搜索轨迹收集器，实现 SearchObserver 接口。 负责在搜索算法运行期间记录结点的生成、访问展开、开放列表去重与剪枝修剪等全过程。 */
final class SearchTrace implements SearchObserver {

    /** 搜索轨迹中的结点条目。 */
    static final class Entry {
        /** 结点在轨迹列表中的唯一编号 */
        final int id;

        /** 父结点的编号，根结点为负一 */
        final int parent;

        /** 从初始状态到达当前结点的实际代价值 g */
        final int g;

        /** 启发函数预估到目标状态的代价值 h */
        final int h;

        /** 当前状态对应的棋盘数字排列数组 */
        final byte[] board;

        /** 启发值的评估质量 */
        final HeuristicQuality quality;

        /** 结点当前在搜索过程中的状态描述 */
        String status;

        /** 关联的参考结点编号，用于替换或重复剪枝时记录先前结点 */
        Integer reference;

        /** 结点被访问展开时的搜索帧信息 */
        Frame frame;

        /**
         * 构造轨迹结点条目。
         *
         * @param id 结点编号
         * @param parent 父结点编号
         * @param board 棋盘状态数字数组
         * @param g 实际代价值
         * @param h 启发代价值
         * @param status 结点状态描述
         * @param quality 启发评估质量
         */
        Entry(
                int id,
                int parent,
                byte[] board,
                int g,
                int h,
                String status,
                HeuristicQuality quality) {
            this.id = id;
            this.parent = parent;
            this.board = board;
            this.g = g;
            this.h = h;
            this.status = status;
            this.quality = quality;
        }
    }

    /** 搜索访问展开帧，记录一次结点访问与展开的上下文。 */
    static final class Frame {
        /** 当前被展开的结点编号 */
        final int current;

        /** 当前轮次的代价截断上限，非迭代加深搜索时为负一 */
        final int bound;

        /** 当前所在的迭代轮次编号 */
        final int iteration;

        /** 当前结点是否达成目标状态 */
        final boolean found;

        /** 当前结点展开生成的子结点编号列表 */
        final List<Integer> children = new ArrayList<>();

        /**
         * 构造搜索展开帧。
         *
         * @param current 当前展开的结点编号
         * @param found 是否命中目标状态
         * @param bound 代价截断上限
         * @param iteration 迭代轮次编号
         */
        Frame(int current, boolean found, int bound, int iteration) {
            this.current = current;
            this.found = found;
            this.bound = bound;
            this.iteration = iteration;
        }
    }

    /** 记录搜索过程中生成的全部结点条目 */
    private final List<Entry> nodes = new ArrayList<>();

    /** 记录搜索过程中各结点的访问展开帧 */
    private final List<Frame> frames = new ArrayList<>();

    /** 基于对象同一性建立搜索结点到轨迹结点编号的映射 */
    private final IdentityHashMap<Node, Integer> identities = new IdentityHashMap<>();

    /** 记录当前已被开放列表接纳的状态到搜索结点的映射 */
    private final Map<State, Node> accepted = new HashMap<>();

    /** 记录已关闭状态到对应结点编号的映射 */
    private final Map<State, Integer> closed = new HashMap<>();

    /** 最终命中目标状态的结点编号，未达成时为负一 */
    private int finalGoal = -1;

    /**
     * 追加新的结点条目到轨迹列表，并建立与父结点的子结点索引。
     *
     * @param parent 父结点编号
     * @param board 棋盘状态字节数组
     * @param g 实际代价值
     * @param h 启发代价值
     * @param status 初始状态描述
     * @param quality 启发评估质量
     * @return 新生成的结点条目编号
     */
    private int add(
            int parent, byte[] board, int g, int h, String status, HeuristicQuality quality) {
        int id = nodes.size();
        nodes.add(new Entry(id, parent, board, g, h, status, quality));
        if (parent >= 0) nodes.get(parent).frame.children.add(id);
        return id;
    }

    /**
     * 记录指定结点的访问展开帧。
     *
     * @param id 当前访问的结点编号
     * @param found 是否命中目标状态
     * @param bound 代价截断上限
     * @param iteration 迭代轮次编号
     */
    private void visit(int id, boolean found, int bound, int iteration) {
        Frame frame = new Frame(id, found, bound, iteration);
        nodes.get(id).frame = frame;
        frames.add(frame);
        if (found) finalGoal = id;
    }

    /**
     * 迭代搜索开启新一轮迭代时的回调。
     *
     * @param root 当前轮次的根结点
     * @param bound 当前轮次的代价截断上限
     * @param iteration 当前迭代轮次编号
     */
    @Override
    public void iteration(Node root, int bound, int iteration) {
        identities.clear();
        accepted.clear();
        closed.clear();
        identities.put(
                root,
                add(
                        -1,
                        ((PuzzleBoard) root.getState()).cells(),
                        0,
                        root.getHeuristic(),
                        "frontier",
                        HeuristicQuality.EXACT));
        accepted.put(root.getState(), root);
    }

    /**
     * 访问展开某个结点时的回调。
     *
     * @param node 当前被访问展开的搜索结点
     * @param found 是否命中目标状态
     * @param bound 当前轮次的代价截断上限
     * @param iteration 当前迭代轮次编号
     */
    @Override
    public void visit(Node node, boolean found, int bound, int iteration) {
        int id = identities.get(node);
        visit(id, found, bound, iteration);
        accepted.remove(node.getState());
        if (!found) closed.put(node.getState(), id);
    }

    /**
     * 生成新子结点时的回调。
     *
     * @param child 新生成的子结点
     * @param quality 启发值的评估质量
     */
    @Override
    public void generated(Node child, HeuristicQuality quality) {
        identities.put(
                child,
                add(
                        identities.get(child.getParent()),
                        ((PuzzleBoard) child.getState()).cells(),
                        child.getPathCost(),
                        child.getHeuristic(),
                        "frontier",
                        quality));
    }

    /**
     * 结点尝试加入开放列表时的回调。
     *
     * @param node 尝试加入开放列表的结点
     * @param offered 是否成功加入开放列表
     */
    @Override
    public void offered(Node node, boolean offered) {
        Entry entry = nodes.get(identities.get(node));
        Node previous = accepted.get(node.getState());
        if (previous != null) {
            entry.status = offered ? "replaced" : "frontier-pruned";
            entry.reference = identities.get(previous);
        }
        if (offered) accepted.put(node.getState(), node);
    }

    /**
     * 结点被修剪淘汰时的回调。
     *
     * @param node 被修剪淘汰的结点
     * @param reason 修剪的具体原因枚举
     */
    @Override
    public void prune(Node node, SearchObserver.Pruning reason) {
        Entry entry = nodes.get(identities.get(node));
        entry.status = status(reason);
        if (reason == SearchObserver.Pruning.CLOSED) entry.reference = closed.get(node.getState());
    }

    /**
     * 将修剪原因枚举转换为对应的状态字符串标识。
     *
     * @param reason 修剪原因枚举
     * @return 对应的状态描述字符串
     */
    private static String status(Pruning reason) {
        return switch (reason) {
            case CLOSED -> "closed-pruned";
            case CYCLE -> "cycle-pruned";
            case BOUND -> "bound-pruned";
            case TRANSPOSITION -> "transposition-pruned";
        };
    }

    /**
     * 提取从初始状态到目标状态解路径上的所有棋盘状态列表。
     *
     * @return 解路径上的棋盘状态字节数组列表
     * @throws IllegalStateException 当搜索轨迹中未找到目标结点时抛出
     */
    List<byte[]> solution() {
        if (finalGoal < 0) throw new IllegalStateException("搜索轨迹缺少目标帧");
        List<byte[]> boards = new ArrayList<>();
        for (int id : solutionIds()) boards.add(nodes.get(id).board);
        return boards;
    }

    /**
     * 回溯提取从初始根结点到目标结点的编号序列。
     *
     * @return 解路径上的结点编号列表
     */
    List<Integer> solutionIds() {
        List<Integer> result = new ArrayList<>();
        // 沿父指针从目标结点回溯到根结点，再反转为正向顺序
        for (int id = finalGoal; id >= 0; id = nodes.get(id).parent) result.add(id);
        java.util.Collections.reverse(result);
        return result;
    }

    /**
     * 获取所有记录的结点条目列表。
     *
     * @return 结点条目列表
     */
    List<Entry> entries() {
        return nodes;
    }

    /**
     * 获取所有记录的展开帧列表。
     *
     * @return 展开帧列表
     */
    List<Frame> frames() {
        return frames;
    }

    /**
     * 校验搜索算法统计计数与轨迹记录的一致性。
     *
     * @param generated 搜索器统计的生成结点数
     * @param expanded 搜索器统计的展开结点数
     * @throws IllegalStateException 当统计计数与轨迹记录不一致时抛出
     */
    void validateCounts(int generated, int expanded) {
        long roots = nodes.stream().filter(node -> node.parent < 0).count();
        if (generated != nodes.size() - roots
                || expanded != frames.size() - 1
                || frames.isEmpty()
                || !frames.getLast().found) {
            throw new IllegalStateException("搜索计数与完整过程不一致");
        }
    }
}
