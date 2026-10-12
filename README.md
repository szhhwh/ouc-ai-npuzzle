# OUC AI N-Puzzle 项目

本项目使用课程提供的 Java 搜索框架求解 N-Puzzle。当前代码保留 `Problem`、`State`、`Action` 等公共接口，学生实现集中在 `src/stud/` 下。

## 实验阶段

1. 完成 N-Puzzle 建模，使用错位将牌数和曼哈顿距离进行 A* 搜索。
2. 优化 Open/Closed 表和搜索策略，求解 15-Puzzle 并比较 A* 与 IDA*。
3. 实现 Disjoint Pattern Database 启发式，求解课程给定的困难实例。
4. 展示解路径，完成性能实验、数据分析和研究报告。

## 环境

- JDK: Eclipse Temurin 25.0.4 LTS
- 编码: UTF-8
- 构建方式: Maven Wrapper（仓库锁定 Maven 版本）；测试代码无第三方依赖

仓库中的 `mise.toml` 声明了统一 JDK 版本。已使用 mise 的成员可运行：

```bash
mise install
```

未使用 mise 的成员可手动安装 Temurin 25，然后确认：

```bash
java -version
javac -version
```

### IntelliJ IDEA

1. 打开仓库根目录。
2. 在 `File > Project Structure` 中将 Project SDK 设为 Temurin 25。
3. 确认 `src` 被标记为 Sources Root。
4. 运行配置的 Working directory 必须是仓库根目录。

### VS Code

仓库不提交成员各自的 `.vscode/` 配置。使用 VS Code 的成员需要安装 Java 扩展，选择本机 Temurin 25，并将 `src` 设为源码目录、`out` 设为输出目录。无论使用哪种 IDE，都可以使用仓库中的 `scripts/` 命令编译和运行。

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

首先运行框架自带的寻路问题，确认 Java 环境和搜索框架正常：

```bash
java -cp out core.runner.SearchTester \
  resources/pathfinding.txt PATHFINDING 1 stud.runner.WalkerFeeder
```

运行 N-Puzzle 第一阶段：

```bash
./scripts/run-npuzzle-stage1.sh
```

该脚本会使用 `resources/problems-stage1.txt` 中三个 8-Puzzle 实例，按课程入口完整运行错位将牌数和曼哈顿距离。当前实现使用带状态索引和惰性删除的 `PqFrontier`。`resources/problems.txt` 的第 4 个 15-Puzzle 实例属于后续实验阶段，不由这个脚本运行。

也可以在编译后直接使用课程规定的四个参数：

```bash
java -cp out core.runner.SearchTester \
  resources/problems-stage1.txt NPUZZLE 1 stud.g01.runner.PuzzleFeeder
```

四个参数依次为：问题文件、问题类型、实验阶段、`EngineFeeder` 实现类。

## N-Puzzle 输入格式

每一行表示一个独立问题：

```text
n  <n*n 个初始状态数字>  <n*n 个目标状态数字>
```

`0` 表示空格。例如：

```text
3 5 0 8 4 2 1 7 3 6 1 2 3 4 5 6 7 8 0
```

## 开发规则

- 不修改 `Problem`、`State`、`Action` 等课程类的公共接口。
- 从最新 `main` 创建功能分支，不直接在 `main` 开发。
- 提交前至少运行 `./mvnw -B -ntp verify`。
- 不提交 `out/`、`result/`、`.idea/`、`.DS_Store` 或其他本地产物。
- 启发函数和可解性判断必须支持输入中给定的任意目标状态。

## 当前进度

已完成：

- PathFinding 框架自检
- N-Puzzle 不可变棋盘模型
- 空格的四方向移动
- 3 阶和 4 阶可解性判断
- 错位将牌数和曼哈顿距离
- 课程 N-Puzzle 输入解析
- 控制台解路径输出

待后续分工：

- 更进一步的 Open/Closed 表策略优化
- IDA*
- Disjoint Pattern Database
- 正式性能实验
- 图形化可视化
- 研究报告

## 常见问题

### 找不到 `resources` 中的文件

请确认工作目录是仓库根目录，或直接使用 `scripts/` 下的脚本。

### 程序提示参数不足

`SearchTester` 不能无参启动，需要传入上述四个参数。

### Java 版本错误

项目使用了现代 Java 语法。小组开发和测试统一使用 `mise.toml` 声明的 Temurin 25。
