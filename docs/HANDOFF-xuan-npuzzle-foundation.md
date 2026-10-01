# N-Puzzle 基础模块交接报告

## 交接概要

- 开发分支：`feature/xuan-npuzzle-foundation`
- 开发范围：README、统一运行脚本、PuzzleBoard、N-Puzzle 基础建模和回归测试
- JDK：Eclipse Temurin 25.0.4 LTS
- 实现原则：保留教师框架的公共接口，不修改 `core` 包
- 当前状态：基础建模和测试完成，性能优化、IDA* 和 Pattern Database 待后续实现

## 已完成内容

### 项目使用说明

`README.md` 已补充：

- 实验阶段和目录结构
- JDK 与 IDE 配置
- 编译、测试和运行命令
- N-Puzzle 输入格式
- 开发规则和当前进度
- 常见环境与启动问题

### 运行脚本

| 脚本 | 用途 |
| --- | --- |
| `scripts/compile.sh` | 使用 UTF-8 从零编译全部正式源码 |
| `scripts/test.sh` | 编译并运行 N-Puzzle 基础回归测试 |
| `scripts/run-pathfinding.sh` | 运行教师框架自带的 PathFinding 回归自检 |
| `scripts/run-npuzzle-stage1.sh` | 按课程入口运行 N-Puzzle 第一阶段 |

脚本会自动定位仓库根目录，可以从其他目录调用。

### 棋盘和动作模型

#### `PuzzleDirection`

定义 `UP`、`RIGHT`、`DOWN`、`LEFT` 四个内部枚举值及行列偏移量。方向统一表示空格的移动方向，对外路径文本按教师参考格式输出 `N`、`E`、`S`、`W`。

#### `PuzzleMove`

- 继承 `core.problem.Action`
- 单步代价固定为 1
- 实现动作输出、判等和哈希值

#### `PuzzleBoard`

- 使用一维 `int[]` 保存棋盘，`0` 表示空格
- 状态完全不可变，构造和输出数组都使用防御性复制
- 验证数字长度、范围和唯一性
- 实现 `draw()`、`next()`、`actions()`、`equals()` 和 `hashCode()`
- 支持错位将牌数和曼哈顿距离
- 两个启发函数都忽略空格，并相对传入的目标状态计算

`actions()` 按教师 PathFinding 示例的模式返回四种候选动作，实际合法性由 `NPuzzleProblem.applicable()` 过滤。

### N-Puzzle 问题建模

`NPuzzleProblem` 已实现：

- 奇数阶可解性判断
- 偶数阶可解性判断
- 任意初始状态与任意目标状态的可达性比较
- 移动边界检查
- 单步代价
- 控制台解路径输出

可解性规则：

- 奇数阶比较初始和目标排列的逆序数奇偶性。
- 偶数阶比较“逆序数 + 空格距底部行号”的奇偶性。
- 逆序数计算忽略空格。

### 输入与搜索连接

`PuzzleFeeder` 已实现：

- 按 `n + n² 个初始数字 + n² 个目标数字` 解析每行
- 忽略空行
- 检查字段数量和整数格式
- 构造 `PuzzleBoard` 和 `NPuzzleProblem`
- 将 `MISPLACED` 和 `MANHATTAN` 连接到启发函数
- 使用现有 `ListFrontier` 运行基线 A*

`DISJOINT_PATTERN` 当前会明确抛出“未实现”异常，不会返回错误的默认值。

## 验证结果

### 自动化测试

```bash
./scripts/test.sh
```

结果：

```text
编译成功
全部通过：46 个断言
```

测试覆盖：

- 3×3 和 4×4 棋盘
- 无效棋盘输入
- 不可变性
- `equals()` 与 `hashCode()` 一致性
- 四方向移动和边界
- 奇数阶和偶数阶可解性
- 任意目标状态
- 错位将牌数和曼哈顿距离
- 输入解析与错误报告
- A* 单步求解集成测试

### 框架回归测试

`scripts/run-pathfinding.sh` 已通过，原 PathFinding 功能未被破坏。

### 课程 N-Puzzle 数据

在现有 `ListFrontier` 上，前三个 3×3 课程实例的最优解长度为：

```text
21
31
31
```

与课程参考结果一致。

## 已知限制

### Frontier 性能

该项已由 `feature/pq-frontier-performance` 完成：`PuzzleFeeder` 使用
`src/stud/queue/PqFrontier.java`，支持按 `Node.evaluator(type)` 排序、按状态快速查重以及更优路径替换。
原先 `src/stud/g01/queue/PqFrontier.java` 的空占位实现已经删除，避免误接入后静默返回无解。

### 未实现的后续模块

- IDA*
- Disjoint Pattern Database
- Pattern Database 生成与序列化
- 正式性能实验
- 图形化可视化
- 完整研究报告

## 后续成员的接入点

### Frontier/A* 负责人

主要使用：

- `PuzzleBoard.equals()`
- `PuzzleBoard.hashCode()`
- `PuzzleFeeder.getFrontier()`
- `Node.evaluator(EvaluationType)`

不应修改棋盘判等语义。

### IDA* 负责人

可以直接使用：

- `Problem.root(predictor)`
- `Problem.childNodes(node, predictor)`
- `PuzzleBoard` 的不可变状态
- `PuzzleBoard.predictor()`

### Pattern Database 负责人

建议将实现放在：

```text
src/stud/g01/problem/npuzzle/pattern/
```

完成后在 `PuzzleBoard.predictor(DISJOINT_PATTERN)` 或独立 Predictor 工厂中接入，并替换当前的明确未实现异常。

### 报告与测试负责人

可以直接记录 `SearchTester` 输出的：

- 解路径长度
- 执行时间
- 生成结点数
- 扩展结点数

性能结果需在同一台机器、同一 JDK、同一输入顺序下进行比较。

## 建议合并前检查

```bash
git diff --check
./scripts/test.sh
./scripts/run-pathfinding.sh
```

然后由至少一名其他成员检查：

- 空格方向语义是否与可视化模块一致
- 奇偶阶可解性公式
- `equals()` 和 `hashCode()` 是否适合 Open/Closed 表
- 启发函数是否始终忽略空格
- 解路径输出是否满足后续可视化的输入需求
