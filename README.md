# OUC AI N-Puzzle Solver & Visualizer

<p align="center">
  <strong>高性能 N-Puzzle（8 数码 / 15 数码）启发式求解器与全流程交互式搜索过程可视化系统</strong>
</p>

<p align="center">
  <a href="https://szhhwh.github.io/ouc-ai-npuzzle/"><img src="https://img.shields.io/badge/Live_Demo-在线体验-2ea44f?style=for-the-badge&logo=githubpages&logoColor=white" alt="Live Demo"></a>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Java-25%2B-ED8B00?style=flat-square&logo=openjdk&logoColor=white" alt="Java 25">
  <img src="https://img.shields.io/badge/React-19-20232A?style=flat-square&logo=react&logoColor=61DAFB" alt="React 19">
  <img src="https://img.shields.io/badge/Vite-6-646CFF?style=flat-square&logo=vite&logoColor=white" alt="Vite 6">
  <img src="https://img.shields.io/badge/Bun-Ready-000000?style=flat-square&logo=bun&logoColor=white" alt="Bun">
  <img src="https://img.shields.io/badge/TailwindCSS-v4-38B2AC?style=flat-square&logo=tailwind-css&logoColor=white" alt="TailwindCSS v4">
  <img src="https://img.shields.io/badge/Code_Style-Spotless_Google_AOSP-00599C?style=flat-square" alt="Spotless">
  <img src="https://img.shields.io/badge/Static_Analysis-SpotBugs-E05D44?style=flat-square" alt="SpotBugs">
</p>

---

## 📖 项目简介

本项目为中国海洋大学（OUC）人工智能课程 N-Puzzle 高效求解系统，采用双模块架构：

1. **高性能后端求解器（Java 25）**：支持 A*、IDA*、Dijkstra 及专用 NIDA* 算法，融合 64 位逆映射位棋盘、可加模式数据库（Disjoint PDB）、增量查表与主对角线对称剪枝等深度优化。
2. **交互式全流程可视化前端（React 19 + Vite）**：纯前端单页架构，提供「解路径回放」与「搜索树推演」双视角，支持平滑镜头追踪、节点计算公式透视与大规模回放记录的客户端持久化。

> 🌐 **在线演示体验**：无需安装本地环境，直接访问 [GitHub Pages 在线可视化系统](https://szhhwh.github.io/ouc-ai-npuzzle/) 即可体验预置样例与拖拽导入回放。

---

## ✨ 核心特性

### ⚡ 核心求解算法与工程优化
- **全算法矩阵覆盖**：原生集成 A*、IDA*、Dijkstra 引擎，并提供针对 N-Puzzle 深度优化的专用引擎 `nida`。
- **阶梯式启发函数（Admissible Heuristics）**：
  - `ZERO`：零估计（退化为一致代价搜索 / Dijkstra）
  - `MISPLACED`：不在位将牌数启发式
  - `MANHATTAN`：基于预计算距离表的常数级曼哈顿距离
  - `DISJOINT_PATTERN`：可加模式数据库（PDB），支持 8 数码（3-3-2 分组）与 15 数码（6-6-3 排名编码 / 7-8 连通分量编码）
- **高阶剪枝与底层加速**：
  - **64 位逆映射位棋盘**：单 `long` 整数紧凑编码盘面各数值的位置，状态判等为单条 CPU 指令，滑动转移仅需位异或。
  - **增量式 PDB 查表**：避免全盘重新编码，仅计算滑动棋子引起的模式位移差值。
  - **主对角线对称增强**：在对角线目标下利用 $\max(h_{\text{direct}}, h_{\text{mirrored}})$ 无损大幅削减搜索展开节点。
  - **延时清理优先队列**：Open 表基于二叉堆与哈希表结合，实现 $\mathcal{O}(1)$ 状态查重与去重。

### 🎨 现代化交互式搜索可视化
- **解路径回放视图（Path Replay）**：
  - 平滑滑块移动与方向指示。
  - 解路径启发值 $h$ 与真实剩余步数趋势对比图。
  - 棋子角标动态展示各数字对 $h$ 的实际贡献度。
- **搜索空间树推演视图（Search Tree Explorer）**：
  - 基于 D3.js + HTML5 Canvas 的高帧率树状拓扑推演（支持缩放、平移、全屏与鹰眼缩略图）。
  - 分镜式动画演绎：节点出队、后继生成展开、剪枝状态标注（超界 / 重复 / 成环）。
  - 节点计算面板：$f = g + h$ 实时演算公式、棋盘推演差分、候选队列分布。
  - 0.5× 至 1024× 变速播放与智能相机平滑跟随。
- **客户端高性能存储（Problem Library）**：
  - 基于 IndexedDB 与 Web Worker 后台并行 gzip 解压，大规模搜索过程瞬时加载，刷新后状态持久化保留。
  - 导出单文件静态分发（支持离线直接双击打开）。

---

## 🚀 快速上手

### 1. 运行 Java 求解器

#### 环境要求
- **JDK 25+**（推荐 Temurin 25）
- Maven 3.9+（项目内置 `./mvnw`）

#### 编译与运行
使用内置脚本一键编译并求解样例：

```bash
# 语法：./scripts/run.sh <问题文件> <搜索引擎> [启发函数列表]
./scripts/run.sh resources/problems-stage1.txt astar MANHATTAN
```

**常用运行命令示例**：
```bash
# 1. 使用 A* 搭配曼哈顿距离求解三阶样例
./scripts/run.sh resources/problems-stage1.txt astar MANHATTAN

# 2. 对比多种启发函数（以逗号分隔）
./scripts/run.sh resources/problems-stage1.txt astar MISPLACED,MANHATTAN

# 3. 使用专用 NIDA* 引擎配合模式数据库求解四阶高难度样例
./scripts/run.sh resources/problems-stage3.txt nida DISJOINT_PATTERN
```

也可以直接通过 Maven Wrapper 编译并使用 `SearchTester` 运行：
```bash
./mvnw -B -ntp compile
java -cp target/classes core.runner.SearchTester \
  resources/problems-stage1.txt stud.g01.runner.PuzzleFeeder astar MANHATTAN
```

---

### 2. 导出搜索记录并在网页中可视化

#### 步骤一：一键导出解题轨迹与搜索树
使用 `export-visualization.sh` 将求解过程（包含出队、分支生成与剪枝细节）导出为标准化 JSON 轨迹文件：

```bash
# 导出三阶问题在 A* 下的搜索过程至 results.json
./scripts/export-visualization.sh resources/problems-stage1.txt results.json astar MANHATTAN

# 导出四阶 PDB 求解过程（需追加 --allow-pdb）
./scripts/export-visualization.sh resources/problems-stage3.txt results.json nida DISJOINT_PATTERN --allow-pdb
```

#### 步骤二：启动可视化前端
前端项目位于 `visualization/` 目录，由 [Bun](https://bun.sh) 驱动：

```bash
cd visualization
bun install
bun run dev
```

浏览器打开 `http://localhost:5173`，将刚刚生成的 `results.json` 拖入页面任意位置，即可立即进行可视化探索与回放！

---

## 🎮 可视化系统快捷键

| 按键 | 解路径回放视图 | 搜索树过程视图 |
| :--- | :--- | :--- |
| `1` / `2` | 切换到路径视图 / 搜索视图 | 切换到路径视图 / 搜索视图 |
| `Space` | 播放 / 暂停 | 播放 / 暂停 |
| `←` / `→` | 上一步 / 下一步 | 上一步 / 下一步（单步微调） |
| `Shift` + `←` / `→` | — | 上一轮 / 下一轮（按扩展轮次跳转） |
| `Home` / `End` | 跳转至起点 / 终点 | 跳转至搜索起点 / 找到目标时刻 |
| `F` / `Esc` | — | 进入 / 退出全屏展示 |
| `C` | — | 开关镜头自动跟随当前焦点 |
| `P` | — | 开关剪枝节点显示 |
| `0` | — | 缩放重置，自适应展示整棵搜索树 |

---

## ⚙️ 算法引擎与启发式参数配置

### 支持的引擎与启发函数矩阵

| 搜索引擎 | 标识符 | 描述 |
| :--- | :--- | :--- |
| **A\*** | `astar` | 经典图搜索 A* 算法，采用优先队列与延迟清理管理 Open 表 |
| **IDA\*** | `ida` | 迭代加深 A* 算法，线性空间开销，适合大规模深层搜索 |
| **NIDA\*** | `nida` | **N-Puzzle 专用超高速 IDA\***，集成状态逆映射与增量式状态推进 |
| **Dijkstra** | `dijkstra` | 一致代价基准搜索，队列仅按实际步数 $g$ 排序 |

| 启发函数 | 标识符 | 适用问题 | 特点与说明 |
| :--- | :--- | :--- | :--- |
| **Zero** | `ZERO` | 任意阶数 | 零估计，提供搜索算法的下界对照基准 |
| **Misplaced** | `MISPLACED` | 任意阶数 | 统计不在目标位置的非零棋子数，耗时低但剪枝力较弱 |
| **Manhattan** | `MANHATTAN` | 任意阶数 | 计算所有数字到目标格的曼哈顿距离和，通过预置表实现常数查表 |
| **Disjoint PDB** | `DISJOINT_PATTERN` | 3 阶 / 4 阶 | 可加模式数据库，建表后剪枝能力极强 |

### 模式数据库（PDB）规格与 JVM 优化参数

系统在首次使用 PDB 时会自动触发 0-1 BFS 离线建表并建立本地缓存：
- **3 阶 PDB**：3-3-2 分组，乘积编码，仅占用 13.8 KB。
- **4 阶通用 PDB**：6-6-3 分组，排名编码（保留空格信息），净数据约 110 MiB。
- **4 阶专用 PDB**：7-8 分组，连通分量编码，为 `nida` 引擎量身定制，内存占用约 482 MiB。

可通过 JVM 系统属性微调高级优化（默认均为开启）：
```bash
# 关闭增量查表（回退到全量查表）
-Dnpuzzle.pdb.incremental=false

# 关闭对角线对称性增强（仅使用直接查表值）
-Dnpuzzle.pdb.symmetry=false
```

---

## 🛠️ 开发者指南

### 后端 Java 工程规范

项目配置了完整的格式规范检查与静态分析：

```bash
# 编译并执行 Spotless 格式校验与 SpotBugs 静态检查
./mvnw -B -ntp verify

# 自动修复并格式化代码（遵循 Google Java Format AOSP 规范）
./mvnw spotless:apply
```

### 前端构建与测试

前端基于现代 Web 技术栈构建，支持类型检查与单元测试：

```bash
cd visualization

# 运行自动化测试（回放模型逻辑与数据校验）
bun run test

# 生产环境打包（输出单文件离线 HTML 至 dist/index.html）
bun run build
```

打包产物 `visualization/dist/index.html` 内联了所有脚本、样式及字体，**无需起任何本地 Web 服务器，直接双击该 HTML 即可在任何现代浏览器中运行**。同时该页面已通过 GitHub Actions 自动化部署至 GitHub Pages。

---

## 📁 项目核心结构

```text
.
├── resources/             # 预置数码测试问题样例文件
├── scripts/               # 快速编译、运行与可视化轨迹导出脚本
├── src/
│   ├── core/              # 搜索通用框架抽象接口与评测入口
│   └── stud/              # 核心求解实现（N-Puzzle 求解器、PDB 与优化算法）
├── visualization/         # React 19 + TypeScript 可视化前端工程
├── pom.xml                # Maven 工程配置（JDK 25 / Spotless / SpotBugs）
└── README.md              # 项目主文档
```

---

## 📝 输入文件格式规范

问题描述文件每一行为一个独立测试用例，采用空格分隔：
```text
<n>  <n*n 个初始状态数字>  <n*n 个目标状态数字>
```
其中 `0` 代表空格。例如一个 3 阶（8 数码）标准测试实例：
```text
3  5 0 8 4 2 1 7 3 6  1 2 3 4 5 6 7 8 0
```
