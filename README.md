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
| 四阶 `nida` | 7-8，排名编码，对空格位置取最小值 | 549.87 MiB |

四阶普通 PDB 去除了棋子位置重叠的无效索引，启发值与原乘积表一致。专用 PDB 的空格压缩保留可采纳性，IDA* 仍返回最短解，但估值可能比保留空格的表更小，因此扩展节点数可能增加。压缩表仅用于专用 IDA*，普通 A* 继续使用保留空格的一致启发函数。

排名表的建表过程把空格可零代价到达的连通区域合并成一个状态，再按移动分组棋子的次数进行 BFS；压缩模式直接生成最小值表，无须先分配完整的 4.35 GiB 距离表。建表队列使用 32 位状态编码和固定大小的数组块，避免大数组翻倍扩容时的复制与内存峰值。以上大小只计常驻距离数据，首次建表还需要访问位图、BFS 队列和临时缓冲；不能据此把 Java 堆限制设为刚好等于表大小。四阶 7-8 建表仍然是耗时的预处理。

压缩表使用独立的 `ranked-min-*.bin` 文件和格式标识。存在有效的旧版未压缩 7-8 缓存时，会流式读取并转换为压缩表，只需结果表和小块读取缓冲，无须重新建表；没有有效旧缓存时才运行 BFS。旧四阶乘积缓存和旧排名缓存不会自动删除。

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

打开 `visualization/index.html` 可查看解路径播放、实验图表、PDB 分组示意和 A* 教学轨迹。使用 `bash scripts/export-visualization.sh` 导出 Java 求解结果，参数见 [可视化说明](visualization/README.md)。
