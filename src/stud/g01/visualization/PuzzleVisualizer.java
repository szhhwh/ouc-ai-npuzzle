package stud.g01.visualization;

import stud.g01.problem.npuzzle.PuzzleBoard;
import stud.g01.problem.npuzzle.PuzzleMove;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.util.List;

/**
 * N-Puzzle 解路径的 Swing 动画面板（课程项目第四阶段：解路径的可视化）。
 *
 * <p>界面设计要点（对应报告「可视化界面设计及关键逻辑」）：</p>
 * <ul>
 *   <li><b>盘面区</b>：中部用 {@link GridLayout} 布置 size×size 个 {@link JLabel}，
 *       居中大字号显示将牌数字；空格显示为空白格。右侧附一个缩小版的静态
 *       「目标盘面」对照网格，便于观察当前盘面与目标的差异。</li>
 *   <li><b>高亮反馈</b>：播放到第 k 步时，把「这一步刚发生交换的两个格子」
 *       （steps[k-1] 的空格位 与 steps[k] 的空格位——即执行完前 k-1 步的盘面
 *       的空格位与执行完前 k 步的盘面的空格位，亦即被移将牌的旧位与新位）
 *       涂成醒目的琥珀色，其余将牌浅灰、空格白色，一眼看清每步移动了谁。</li>
 *   <li><b>动画驱动</b>：用 {@link Timer}（Swing 定时器，回调天然在事件分发线程上执行）
 *       按可调间隔推进「步指针」stepIndex 并整体重绘；步指针只在
 *       [0, moves.size()] 内移动，到末步自动停表。</li>
 *   <li><b>控件区</b>：播放/暂停、单步前进、单步后退、重置四个按钮 +
 *       100ms~2000ms 的步间隔滑块（值越小越快）+ 「步 x/y」步号显示 +
 *       最近动作（U/D/L/R，空格移动方向）显示。</li>
 *   <li><b>边界情况</b>：moves 为空（初始即目标的已解实例）时不启动定时器，
 *       面板退化为静态盘面展示。</li>
 * </ul>
 *
 * <p>本类只创建轻量 Swing 组件（JPanel/JLabel 等无头环境下也可构造），
 * 不创建 JFrame 等顶层窗口，因此可在 headless 环境下做组件级单元测试；
 * 窗口的创建由 {@link PuzzleVisualizerMain} 在确认非 headless 后完成。</p>
 */
public class PuzzleVisualizer extends JPanel {

    /**
     * 步间隔的下限（毫秒）
     */
    public static final int MIN_DELAY_MS = 100;
    /**
     * 步间隔的上限（毫秒）
     */
    public static final int MAX_DELAY_MS = 2000;
    /**
     * 默认步间隔（毫秒）
     */
    public static final int DEFAULT_DELAY_MS = 500;

    //三种格子底色：普通将牌 / 空格 / 刚被交换的两个位置（高亮）
    private static final Color TILE_BG = new Color(0xE3E6EF);
    private static final Color BLANK_BG = Color.WHITE;
    private static final Color HIGHLIGHT_BG = new Color(0xFFC04D);

    //解路径三元组（steps 含初始盘面，共 moves.size()+1 个盘面，均为不可变列表）
    private final PuzzleBoard initial;
    private final List<PuzzleMove> moves;
    private final List<PuzzleBoard> steps;

    //当前步指针：已执行的移动步数，取值范围 [0, moves.size()]
    private int stepIndex;

    //动画定时器（javax.swing.Timer，非 java.util.Timer）
    private final Timer timer;

    //主盘面（动画）与目标盘面（静态）的格子标签，行主序
    private final JLabel[] gridLabels;
    private final JLabel[] goalLabels;

    //控件
    private final JButton playButton;
    private final JSlider speedSlider;
    private final JLabel stepLabel;
    private final JLabel moveLabel;

    /**
     * 按课程要求的等价签名构造面板：默认步间隔 500ms，初始处于暂停状态。
     *
     * @param initial 初始盘面
     * @param moves   动作序列（按移动先后次序）
     * @param steps   盘面序列，含初始盘面，长度须为 moves.size()+1
     */
    public PuzzleVisualizer(PuzzleBoard initial, List<PuzzleMove> moves, List<PuzzleBoard> steps) {
        this(initial, moves, steps, DEFAULT_DELAY_MS, false);
    }

    /**
     * 完整构造：可指定初始步间隔与是否加载后自动播放。
     *
     * @param initial  初始盘面
     * @param moves    动作序列（按移动先后次序）
     * @param steps    盘面序列，含初始盘面，长度须为 moves.size()+1
     * @param delayMs  初始步间隔（毫秒），会被夹取到 [100, 2000]
     * @param autoPlay true 表示构造完成后立即自动播放（moves 为空时忽略）
     */
    public PuzzleVisualizer(PuzzleBoard initial, List<PuzzleMove> moves, List<PuzzleBoard> steps,
                            int delayMs, boolean autoPlay) {
        this.initial = initial;
        this.moves = List.copyOf(moves);
        this.steps = List.copyOf(steps);
        //「steps.size() == moves.size()+1 且每步重放一致」由 extractTrace 的
        //提取规则保证（本面板只与 extractTrace 的产物配合使用），不再重复校验

        this.stepIndex = 0;

        //定时器先建：控件区的滑块初始化会引用它
        this.timer = new Timer(clampDelay(delayMs), e -> onTick());

        setLayout(new BorderLayout(8, 8));

        //中部：动画盘面 + 目标盘面对照
        int n = initial.size();
        int cellPx = n <= 3 ? 96 : n == 4 ? 84 : 64;          //主盘面格子边长（像素）
        int fontPt = Math.max(20, 192 / n);                   //字号随阶数自适应
        gridLabels = buildGrid(n, cellPx, fontPt);
        goalLabels = buildGrid(n, Math.max(40, cellPx / 2), Math.max(14, fontPt / 2));

        JPanel boards = new JPanel(new GridLayout(1, 2, 24, 0));
        JPanel mainBoard = new JPanel(new GridLayout(n, n, 4, 4));
        JPanel goalBoard = new JPanel(new GridLayout(n, n, 4, 4));
        for (int i = 0; i < n * n; i++) {
            mainBoard.add(gridLabels[i]);
            goalBoard.add(goalLabels[i]);
        }
        mainBoard.setBorder(BorderFactory.createTitledBorder("当前盘面"));
        goalBoard.setBorder(BorderFactory.createTitledBorder("目标盘面"));
        boards.add(mainBoard);
        boards.add(goalBoard);
        boards.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        add(boards, BorderLayout.CENTER);

        //南部：控件区
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 8));

        playButton = new JButton("播放");
        playButton.setToolTipText("播放 / 暂停动画");
        playButton.addActionListener(e -> playPause());
        controls.add(playButton);

        JButton prevButton = new JButton("上一步");
        prevButton.addActionListener(e -> stepBack());
        controls.add(prevButton);

        JButton nextButton = new JButton("下一步");
        nextButton.addActionListener(e -> stepForward());
        controls.add(nextButton);

        JButton resetButton = new JButton("重置");
        resetButton.setToolTipText("回到初始盘面并暂停");
        resetButton.addActionListener(e -> resetToStart());
        controls.add(resetButton);

        speedSlider = new JSlider(MIN_DELAY_MS, MAX_DELAY_MS, timer.getDelay());
        speedSlider.setPaintTicks(true);
        speedSlider.setMajorTickSpacing((MAX_DELAY_MS - MIN_DELAY_MS) / 4);
        speedSlider.setToolTipText("步间隔（毫秒），值越小动画越快");
        speedSlider.addChangeListener(e -> timer.setDelay(speedSlider.getValue()));
        controls.add(new JLabel("步间隔(ms):"));
        controls.add(speedSlider);

        stepLabel = new JLabel();
        stepLabel.setFont(stepLabel.getFont().deriveFont(Font.BOLD));
        controls.add(stepLabel);

        moveLabel = new JLabel();
        controls.add(moveLabel);

        add(controls, BorderLayout.SOUTH);

        //先完整重绘一次，再按需自动播放
        render();
        if (autoPlay && !this.moves.isEmpty()) {
            playPause();
        }
    }

    /**
     * 建立 size×size 个格子标签数组（行主序），只负责创建与外观初始化；
     * 网格布局与标题边框由调用方完成。
     */
    private JLabel[] buildGrid(int size, int cellPx, int fontPt) {
        JLabel[] labels = new JLabel[size * size];
        for (int i = 0; i < labels.length; i++) {
            JLabel cell = new JLabel("", JLabel.CENTER);
            cell.setPreferredSize(new Dimension(cellPx, cellPx));
            cell.setFont(new Font(Font.SANS_SERIF, Font.BOLD, fontPt));
            cell.setOpaque(true);
            cell.setBackground(BLANK_BG);
            cell.setBorder(BorderFactory.createLineBorder(new Color(0x9AA0B0)));
            labels[i] = cell;
        }
        return labels;
    }

    /**
     * 定时器回调：前进一步；到达末步后自动停止（播放/暂停按钮恢复为「播放」）。
     */
    private void onTick() {
        if (stepIndex < moves.size()) {
            stepForward();
        }
        if (stepIndex >= moves.size()) {
            stopPlaying();
        }
    }

    /**
     * 播放 / 暂停切换。已解实例（空 moves）不播放；播放到末尾后再按播放则从头开始。
     */
    public void playPause() {
        if (timer.isRunning()) {
            stopPlaying();
            return;
        }
        if (moves.isEmpty()) {
            return;   //已解实例：无动画可播
        }
        if (stepIndex >= moves.size()) {
            stepIndex = 0;   //从头重播
            render();
        }
        timer.start();
        playButton.setText("暂停");
    }

    /**
     * 停止动画，按钮恢复「播放」
     */
    private void stopPlaying() {
        timer.stop();
        playButton.setText("播放");
    }

    /**
     * 公开的停止入口：停掉内部 {@link Timer} 并复位按钮文案。
     * Timer 持有非 daemon 的 AWT 事件线程——嵌入本面板做自动化/组件级测试时，
     * 测试结束应调用本方法停止定时器（或显式 System.exit），
     * 否则 JVM 可能因事件线程不退出而挂住。
     */
    public void stop() {
        stopPlaying();
    }

    /**
     * 单步前进：步指针右移一格（至末步则保持不变）
     */
    public void stepForward() {
        if (stepIndex < moves.size()) {
            stepIndex++;
            render();
        }
    }

    /**
     * 单步后退：步指针左移一格（至初始盘面则保持不变）
     */
    public void stepBack() {
        if (stepIndex > 0) {
            stepIndex--;
            render();
        }
    }

    /**
     * 重置：回到初始盘面（步 0）并暂停
     */
    public void resetToStart() {
        stopPlaying();
        stepIndex = 0;
        render();
    }

    /**
     * 设置步间隔（毫秒），同时夹取到 [100, 2000] 并同步滑块位置
     */
    public void setDelay(int delayMs) {
        int clamped = clampDelay(delayMs);
        timer.setDelay(clamped);
        speedSlider.setValue(clamped);
    }

    /**
     * @return 当前步间隔（毫秒）
     */
    public int getDelay() {
        return timer.getDelay();
    }

    /**
     * @return 当前步指针（已执行的移动步数，范围 [0, moves.size()]）
     */
    public int getStepIndex() {
        return stepIndex;
    }

    /**
     * @return 当前展示的盘面（steps[stepIndex]）
     */
    public PuzzleBoard getCurrentBoard() {
        return steps.get(stepIndex);
    }

    /**
     * @return 动作序列（不可变视图）
     */
    public List<PuzzleMove> getMoves() {
        return moves;
    }

    /**
     * @return 盘面序列（不可变视图，含初始盘面）
     */
    public List<PuzzleBoard> getSteps() {
        return steps;
    }

    /**
     * 当前应高亮的两个格子的线性下标（行主序）：
     * 第 stepIndex 步移动中发生交换的两格——
     * 前一盘面（steps[stepIndex-1]）的空格位（将牌到达的地方）与
     * 当前盘面（steps[stepIndex]）的空格位（将牌离开的地方）。
     * stepIndex == 0（尚未移动）时返回空数组。
     *
     * @return 长度为 0 或 2 的线性下标数组
     */
    public int[] highlightedCells() {
        if (stepIndex == 0) {
            return new int[0];
        }
        return new int[]{steps.get(stepIndex - 1).blank(), steps.get(stepIndex).blank()};
    }

    /**
     * 依据当前步指针整体重绘：主盘面格子文字与底色、目标盘面、步号与最近动作。
     */
    private void render() {
        //主盘面：当前步的将牌布局 + 交换高亮
        PuzzleBoard board = steps.get(stepIndex);
        byte[] cells = board.cells();
        int[] highlight = highlightedCells();
        for (int i = 0; i < cells.length; i++) {
            JLabel cell = gridLabels[i];
            cell.setText(cells[i] == 0 ? "" : String.valueOf(cells[i]));
            boolean highlighted = highlight.length == 2 && (i == highlight[0] || i == highlight[1]);
            cell.setBackground(highlighted ? HIGHLIGHT_BG
                    : cells[i] == 0 ? BLANK_BG : TILE_BG);
        }

        //目标盘面（静态）
        byte[] goalCells = steps.get(steps.size() - 1).cells();
        for (int i = 0; i < goalCells.length; i++) {
            goalLabels[i].setText(goalCells[i] == 0 ? "" : String.valueOf(goalCells[i]));
            goalLabels[i].setBackground(goalCells[i] == 0 ? BLANK_BG : TILE_BG);
        }

        //步号与最近动作
        stepLabel.setText("步 " + stepIndex + "/" + moves.size());
        moveLabel.setText("最近动作: " + (stepIndex == 0 ? "—" : moves.get(stepIndex - 1).toString()));
    }

    /**
     * 把步间隔夹取到滑块范围 [MIN_DELAY_MS, MAX_DELAY_MS]
     */
    private static int clampDelay(int delayMs) {
        return Math.max(MIN_DELAY_MS, Math.min(MAX_DELAY_MS, delayMs));
    }
}
