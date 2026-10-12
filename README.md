# OUC AI N-Puzzle 项目

本项目使用课程提供的 Java 搜索框架求解 N-Puzzle。当前代码保留 `Problem`、`State`、`Action` 等公共接口，学生实现集中在 `src/stud/` 下。

## 环境

- JDK: Eclipse Temurin 25.0.4 LTS

## 目录结构

```text
.
├── resources/              课程测试数据
├── scripts/                统一编译和运行入口
├── src/
│   ├── core/               课程搜索框架
│   └── stud/               学生实现
│       └── g01/           本小组的 N-Puzzle 实现
└── out/                    本地编译产物，不提交
```

## 编译

完整质量门（编译、格式检查和静态分析）：

```bash
./mvnw -B -ntp verify
```

自动格式化学生代码：

```bash
./mvnw spotless:apply
```

也可从任意目录调用 `./scripts/compile.sh` 完成编译。

## 运行

运行测试实例：

```bash
./scripts/run.sh problems-stage1.txt astar MISPLACED,MANHATTAN
```

脚本会先编译，再调用框架入口 `core.runner.SearchTester`。问题文件可省略 `resources/` 前缀，脚本会自动补全。测试实例按阶段存放在 `resources/` 下：`problems-stage1.txt` 为三个 8-Puzzle 实例，`problems-stage2.txt` 为三个 15-Puzzle 实例，`problems-stage3.txt` 为第三阶段的十个实例。

已编译时也可以直接调用框架入口：

```bash
java -cp out core.runner.SearchTester \
  resources/problems-stage1.txt stud.g01.runner.PuzzleFeeder astar MISPLACED,MANHATTAN
```

四个参数依次为：问题文件、`EngineFeeder` 实现类全限定名、搜索引擎、启发函数列表。搜索引擎取值 `astar`、`ida`、`nida`、`dijkstra`；启发函数列表用逗号分隔，取值见 `HeuristicType`，两者可任意搭配。当前实现使用带状态索引和惰性删除的 `PqFrontier`。

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

打开 `visualization/index.html` 可查看路径播放、同题实验图表、PDB 分组示意及独立浏览器 A* 教学轨迹。Java 标准 JSON 导出使用 `bash scripts/export-visualization.sh`，完整参数、安全限制和数据口径见 [可视化说明](visualization/README.md)。内置结果属于注明版本的实验快照，并非自动实时运行仓库代码。

## 开发规则

- 不修改 `Problem`、`State`、`Action` 等课程类的公共接口。
- 从最新 `main` 创建功能分支，不直接在 `main` 开发。
- 提交前至少运行 `./mvnw -B -ntp verify`。
- 不提交 `out/`、`result/`、`.idea/`、`.DS_Store` 或其他本地产物。
- 启发函数和可解性判断必须支持输入中给定的任意目标状态。
