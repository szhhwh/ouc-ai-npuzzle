
# N-Puzzle 可视化

`visualization/` 是 React 单页应用，读取 Java 导出的 JSON，提供「解路径回放」与「搜索过程」两个视图。同一条记录同时驱动两个视图，包含完整解路径、生成节点、访问顺序与剪枝记录。首次打开显示导入引导；把 JSON 拖到页面任意位置，或点击顶部「导入」即可导入，可一次导入多个文件。

- **问题记录**：顶部记录按钮打开记录表，显示初始棋盘、名称、算法、启发函数、路径长度、扩展/生成数、耗时、压缩大小与导入时间。支持排序、直接改名（留空时为 `Untitled Problem`）、载入、单条删除和多选批量删除，删除前确认。
- **解路径回放**：棋子按每一步滑动，显示移动的棋子与方向；右侧是指标、逐步移动列表，以及解路径上启发值 h 与实际剩余步数的对比图。可在棋子角标上显示每块棋子对 h 的贡献。
- **搜索过程**：搜索树绘制在可拖动、缩放的画布上。每轮拆成「取出 → 逐个生成后继 → 合并」几个镜头：取出后先为全部后继留出虚线位置，后继从父节点展开并逐个标出保留或剪枝原因，布局变化和镜头移动都连续过渡。叙述卡解释当前一步；计算面板展示所选节点的棋盘、g + h = f、h 的逐块来源、相对父节点的变化与剪枝原因；A* / Dijkstra 显示候选队列与 f 分布，IDA* / NIDA* 显示界限、超界的最小 f 与迭代列表。另有缩略图、镜头跟随、显示剪枝节点和全屏模式。

播放速度从 0.5× 到 1024×，高倍速时树仍逐帧更新，面板改为定时刷新。切换视图、打开记录表或页面隐藏时暂停播放；两个视图各自保留进度，只有切换记录才重置。

| 按键 | 解路径回放 | 搜索过程 |
| --- | --- | --- |
| `1` / `2` | 切换视图 | 切换视图 |
| `Space` | 播放 / 暂停 | 播放 / 暂停 |
| `←` / `→` | 上一步 / 下一步 | 上一步 / 下一步 |
| `Shift` + `←` / `→` | — | 上一轮 / 下一轮 |
| `Home` / `End` | 起点 / 终点 | 起点 / 目标 |
| `F` / `Esc` | — | 进入 / 退出全屏 |
| `P` / `C` / `0` | — | 剪枝节点 / 镜头跟随 / 显示整棵树 |

搜索过程来自实际 Java 搜索，不截断，也不在浏览器重新求解。A* / Dijkstra 保留真实优先队列的选择；f、g 并列时队首显示 Java 实际取出的节点。IDA* / NIDA* 保留每次迭代、界限与实际深度优先访问。重复状态保留独立节点，目标真正取出后才显示最终解路径。镜头节奏只用于回放，不代表运行耗时。

完整记录通过 gzip 压缩后写入浏览器的 IndexedDB（库名 `npuzzle-library`，与旧版页面兼容），刷新后恢复当前记录。记录仅保存在当前浏览器与站点中，清除站点数据会同时清除记录。导入、校验和解压在 Web Worker 中进行。

问题记录表只渲染可见行及少量缓冲行，避免打开时为整个问题库挂载控件和执行逐行布局动画。排序与「选择全部」仍作用于全部记录。

## 开发与构建

需要 [Bun](https://bun.sh)。在 `visualization/` 下：

```bash
bun install
bun run dev        # 开发服务器
bun run test       # 校验与回放模型测试
bun run build      # 类型检查并输出单文件 dist/index.html
```

`dist/index.html` 内联了全部脚本、样式与字体，可以直接双击打开，也由 `.github/workflows/pages.yml` 发布到 GitHub Pages。

| 目录 | 职责 |
| --- | --- |
| `src/data/` | 导入校验、紧凑类型化数组、Worker 与 IndexedDB 记录库 |
| `src/replay/` | 镜头序列、节点状态、候选队列与树布局，均为纯函数 |
| `src/features/library/` | 问题记录表 |
| `src/features/path/` | 解路径回放 |
| `src/features/search/` | 搜索过程视图；`tree-renderer.ts` 负责画布绘制、动画与镜头 |
| `src/app/` | 顶栏、导入引导、视图切换与全屏状态 |
| `src/components/ui/` | shadcn/ui 组件 |

# OUC AI N-Puzzle 项目

基于课程提供的 Java 搜索框架实现 N-Puzzle 求解，支持 A*、IDA* 和模式数据库（PDB）启发函数。

## 环境

- JDK 25

## 目录结构

```text
.
├── resources/              问题实例与 PDB 缓存
├── scripts/                编译、运行和导出脚本
├── src/
│   ├── algs4/              算法工具库
│   ├── core/               课程搜索框架
│   └── stud/               学生实现，N-Puzzle 位于 g01/
└── visualization/          网页可视化
```

## 编译

使用 Maven Wrapper 编译，并运行格式检查与静态分析：

```bash
./mvnw -B -ntp verify
```

自动格式化学生代码：

```bash
./mvnw spotless:apply
```

也可运行 `./scripts/compile.sh`，将编译结果输出到 `out/`。Maven 的编译结果位于 `target/classes/`。

## 运行

运行问题实例：

```bash
./scripts/run.sh problems-stage1.txt astar MISPLACED,MANHATTAN
```

脚本会先编译，再调用 `core.runner.SearchTester`。问题文件可省略 `resources/` 前缀。课程实例存放在 `resources/problems-stage1.txt`、`problems-stage2.txt` 和 `problems-stage3.txt` 中。

使用 Maven 编译后，也可直接运行：

```bash
java -cp target/classes core.runner.SearchTester \
  resources/problems-stage1.txt stud.g01.runner.PuzzleFeeder astar MISPLACED,MANHATTAN
```

四个参数依次为：问题文件、`EngineFeeder` 实现类全限定名、搜索引擎、启发函数列表。

- 搜索引擎：`astar`、`ida`、`nida`、`dijkstra`。
- N-Puzzle 启发函数：`MISPLACED`（错位数）、`MANHATTAN`（曼哈顿距离）、`DISJOINT_PATTERN`（PDB），多个值用逗号分隔。
- `nida` 配合 PDB 使用专用 IDA*；配合其他启发函数使用通用 IDA*。

PDB 支持三阶和四阶棋盘，首次使用时建表，缓存存放在 `resources/pdb-cache/`。

| 棋盘与引擎 | 分组与存储 | 距离表净大小 |
| --- | --- | --- |
| 三阶 | 3-3-2，乘积编码 | 13,851 字节 |
| 四阶 `astar/ida` | 6-6-3，排名编码，保留空格位置 | 110.01 MiB |
| 四阶 `nida` | 7-8，连通分量编码 | 约 482 MiB |

四阶普通 PDB 去除了棋子位置重叠的无效索引，启发值与原乘积表一致。专用 PDB 把空格可零代价互达的位置合并为同一个自由连通分量，同时保留不同分量的距离。该压缩是无损的，IDA* 仍返回最短解。

建表过程把空格可零代价到达的连通区域合并成一个状态，再按移动分组棋子的次数进行 BFS。完整排名表按空格位置保留全部距离，Component 表再将每个占用模式下各连通分量的距离向量去重，并用变宽位压缩保存排列到向量的映射。建表队列使用 32 位状态编码和固定大小的数组块，避免大数组翻倍扩容时的复制与内存峰值。以上大小只计常驻距离数据，首次建表还需要访问位图、BFS 队列和临时缓冲；不能据此把 Java 堆限制设为刚好等于表大小。四阶 7-8 建表仍然是耗时的预处理。

四阶专用表只读取当前的 `component-le-*.bin`。缓存缺失或校验失败时直接运行 BFS 建表并写入新缓存，不再读取旧版 `component-*.bin` 或 `ranked-*.bin`。

专用 IDA* 在查表前排除立即反向移动和路径环，超界候选只更新下一轮阈值。候选移动缓存新索引、距离和父状态，执行与撤销不再重复计算排名或查询 PDB。生成节点数仍按所有合法移动计数，便于与原实现对照。

## N-Puzzle 输入格式

每一行表示一个独立问题：

```text
n  <n*n 个初始状态数字>  <n*n 个目标状态数字>
```

`0` 表示空格。例如：

```text
3 5 0 8 4 2 1 7 3 6 1 2 3 4 5 6 7 8 0
```

## 网页可视化与实验导出

一次命令导出问题文件中每个实例的完整解路径和完整搜索过程：

```bash
./scripts/export-visualization.sh resources/problems-stage1.txt results.json astar MANHATTAN
```

参数依次为输入文件、输出 `.json`、引擎、启发函数列表；全部省略时使用上述默认值。引擎支持 `astar`、`ida`、`nida`、`dijkstra`。可用逗号指定多个启发函数，每个实例与启发函数组合生成一条独立记录，每条记录只执行一次实际搜索。Dijkstra 使用 `ZERO`。使用 `DISJOINT_PATTERN` 时在末尾传入 `--allow-pdb`；PDB 的首次建表成本与普通命令相同。

在网页「问题记录」中直接导入整个输出文件，无需拆分批量结果。格式为 `{"schemaVersion":2,"results":[...]}`：每条结果包含 `states`（包括起点和终点）、`goal`、算法与启发函数、`timeMs`、`generated`、`expanded`，以及 `trace` 的完整节点、扩展帧与解节点编号。每次 IDA* 迭代均保留一个根节点，生成计数不包含这些根节点。导入时检查棋盘、合法移动、节点父子关系、帧计数和解路径一致性；缺少完整过程的文件无法导入，需要重新导出。

导出的耗时包含过程记录与本次搜索内的 PDB 初始化开销，不作为重复测量的基准性能。完整过程的文件大小与搜索规模相关，导出不设截断上限；输出文件已存在时拒绝覆盖。

