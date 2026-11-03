
# N-Puzzle 可视化

打开 `visualization/index.html`，无需前端依赖。顶部切换“路径演示”和“算法过程”：前者在浏览器中运行 A* 并回放解路径，也可导入 Java 解路径 JSON；后者回放真实 Java 搜索轨迹，展示棋盘、g/h/f 与搜索树。

两个视图独立保存进度，切换时暂停离开的视图。支持播放/暂停、单步、进度定位和两种启发函数；算法过程还支持节点检查、剪枝显示与比例切换。点击视图标签后，可直接用左右方向键单步、空格播放/暂停。录屏布局隐藏设置，不生成视频文件。

## 真实 Java 轨迹

`java-search-traces.js` 来自项目 `BestFirstSearcher` 与真实 `PqFrontier`，没有在浏览器重新求解，也没有修改教师搜索框架。导出前与未加观察器的原始搜索核对完整路径、生成数与扩展数。

比较顺序为 f 升序，再 g 升序。两者都相等时展示 Java 队列的真实选择；展示顺序中的编号不是队列的平局规则。

内置 2 步、8 步、21 步实例 × MANHATTAN/MISPLACED。21 步实例只录前 80 次取出，仍实际搜索至结束用于核对；截断不表示无解。不演示 IDA* 或 PDB。

搜索树展示生成关系；重复状态保留独立节点，可选择显示剪枝。目标真正取出时才显示最终解路径，不提前展示未来节点。分镜间隔是讲解节奏，不是运行耗时。

`java-search-traces.js` 是随页面提供的静态轨迹数据；浏览器只读取它，不会重新求解这些轨迹。

## 本地预览

直接打开 `visualization/index.html`；或在仓库根目录运行 `python3 -m http.server 8765 --directory visualization`，访问 http://127.0.0.1:8765/。

前端文件职责：

| 文件 | 职责 |
| --- | --- |
| `index.html` | 两个视图的静态结构与控件 |
| `search-studio.css` | 公共、路径、搜索树样式及响应式布局 |
| `app.js` | 路径视图状态、浏览器 A*、JSON 校验与回放 |
| `search-studio.js` | Java 轨迹分镜、节点检查与搜索树绘制 |
| `navigation.js` | 视图切换、焦点、快捷键、统一暂停与尺寸更新 |
| `java-search-traces.js` | 预录制的 Java 搜索轨迹 |

视图控制器各自封装状态；`navigation.js` 通过面板上的 `npuzzle:pause`、`npuzzle:play`、`npuzzle:step`（`detail` 为步进方向）和 `npuzzle:resize` 事件调用视图。

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

打开 `visualization/index.html` 查看路径与搜索过程。使用 `bash scripts/export-visualization.sh` 导出 Java 求解结果；路径视图导入的是包含 `states` 的单条 JSON 记录，批量导出文件需取 `results` 数组中的一条记录另存后导入。

