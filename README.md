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

> 🌐 **在线演示**：可直接访问 [在线可视化系统](https://szhhwh.github.io/ouc-ai-npuzzle/) 体验预置样例与拖拽导入回放，无需配置本地环境。

---

## ✨ 核心特性

### ⚡ 核心求解算法与工程优化
- **全算法矩阵覆盖**：原生集成 A*、IDA*、Dijkstra 引擎，并提供针对 N-Puzzle 深度优化的专用引擎 `nida`。
- **阶梯式启发函数（Admissible Heuristics）**：
  - `ZERO`：零估计（不使用启发信息，退化为一致代价搜索 / Dijkstra）
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
  - 自适应响应式布局：棋盘与详情自适应排版缩放，流畅适配宽屏桌面与移动设备。
  - 解路径启发值 $h$ 与真实剩余步数趋势对比图。
  - 棋子角标动态展示各数字对 $h$ 的实际贡献度。
- **搜索空间树推演视图（Search Tree Explorer）**：
  - 基于 D3.js + HTML5 Canvas 的高帧率树状拓扑推演（支持缩放、平移、全屏与鹰眼缩略图）。
  - 分镜式动画演绎：节点出队、后继生成展开、剪枝状态标注（超界 / 重复 / 成环）。
  - 节点计算面板：$f = g + h$ 实时演算公式、棋盘推演差分、候选队列分布。
  - 0.5× 至 1024× 变速播放与智能相机平滑跟随。
- **客户端记录管理（Problem Library）**：
  - 基于 IndexedDB 与 Web Worker 后台并行解压，海量搜索节点秒级加载并本地持久化。
  - 支持打包为独立单文件 HTML，零依赖便捷分发。

---

## 🚀 快速上手

### 1. 运行 Java 求解器

#### 环境要求
- **JDK 25+**
- Maven 3.9+（项目内置 `./mvnw`）

#### 编译与运行
使用内置脚本即可一键编译并求解样例（启发函数省略时默认使用 `MANHATTAN`）：

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

> [!TIP]
> - 问题文件参数支持相对路径，也可直接指定 `resources/` 目录下的文件名。
> - 可通过环境变量 `JAVA_OPTS` 为 JVM 分配参数（如求解四阶问题分配更多内存：`JAVA_OPTS='-Xmx4g' ./scripts/run.sh ...`）。

也可以直接通过 Maven Wrapper 编译并使用 `SearchTester` 运行：
```bash
./mvnw -B -ntp compile
java -cp target/classes core.runner.SearchTester \
  resources/problems-stage1.txt stud.g01.runner.PuzzleFeeder astar MANHATTAN
```

---

### 2. 导出搜索记录并在网页中可视化

#### 步骤一：一键导出解题轨迹与搜索树
使用 `export-visualization.sh` 将求解过程（包含状态扩展、分支生成与剪枝细节）导出为轨迹文件（省略输出路径时默认写入仓库根目录的 `results.json`）：

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

浏览器打开 `http://localhost:5173`，将生成的 `results.json` 拖入页面，即可探索搜索过程与回放解路径。没有记录时，也可点击「加载 3×3 示范样例」直接体验。

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

首次使用 PDB 启发式时会自动构建并本地持久化模式数据库（默认缓存至 `resources/pdb-cache/`）：
- **3 阶 PDB**：3-3-2 分组，乘积编码，大小约 13.8 KB。
- **4 阶通用 PDB**：6-6-3 分组，排名编码，大小约 64 MB。
- **4 阶专用 PDB**：7-8 分组，连通分量编码（专为 `nida` 引擎定制），大小约 482 MB。

可通过 JVM 系统属性指定缓存目录或微调高级优化（默认均为开启）：
```bash
# 自定义 PDB 缓存存储路径
-Dnpuzzle.pdb.cacheDir=/path/to/cache

# 关闭增量查表（回退到全量查表）
-Dnpuzzle.pdb.incremental=false

# 关闭对角线对称性增强（仅使用直接查表值）
-Dnpuzzle.pdb.symmetry=false
```

---

## 🛠️ 开发者指南

### 系统架构概览

本项目采用解耦的分层设计：
- **`src/core/`**：通用图搜索框架与评测基底。定义了抽象问题接口、状态与动作模型、观察者通知协议及标准评测入口。
- **`src/stud/`**：N-Puzzle 特化求解与算法工程实现。包含高效 64 位逆映射位棋盘、专用 NIDA* 引擎、可加模式数据库（PDB）及其增量计算与对称性剪枝策略。
- **`visualization/`**：基于 React 19 + TypeScript + Canvas 的交互式可视化系统。支持解路径动画播放、大规模搜索拓扑树实时演算与客户端本地持久化存储。

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

打包产物位于 `visualization/dist/index.html`，内联了全部样式与脚本，可作为独立单文件在浏览器中离线直接打开运行。

---

## 📁 项目核心结构

```text
.
├── resources/             # 预置数码测试问题样例
├── scripts/               # 编译、运行与轨迹导出脚本
├── src/
│   ├── core/              # 搜索框架通用接口与评测入口
│   └── stud/              # 求解器实现（N-Puzzle 算法与启发式）
├── visualization/         # 交互式可视化前端工程
├── pom.xml                # Maven 构建配置
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
