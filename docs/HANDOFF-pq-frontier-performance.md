# PqFrontier 与第一阶段性能测试交接

## 本分支范围

- 用最小堆和 `State` 哈希索引实现通用 `stud.queue.PqFrontier`。
- 同一状态只保留估值更好的结点；使用惰性删除避免 `PriorityQueue.remove` 的 O(n) 扫描。
- `PuzzleFeeder` 已从线性 `ListFrontier` 切换到 `PqFrontier`。
- 新增独立的 `resources/problems-stage1.txt`（仅三个 8-Puzzle）。第一阶段脚本不再误跑 `resources/problems.txt` 中第 4 个 15-Puzzle 实例；该实例的错位将牌数搜索会耗尽默认 2GB 堆。
- 新增 26 个 Frontier 回归断言，并接入 Maven `verify` 和 `scripts/test.sh`。
- 新增第一阶段性能测试：每组预热 3 次后取 7 次测量中位数，输入路径显式指定；路径长度与课程三个 8-Puzzle 实例的已知最优值 21、31、31 自动核对。

## 验证命令

```bash
./mvnw -B -ntp verify
./scripts/benchmark-stage1.sh
./scripts/benchmark-stage1.sh --compare-list
```

`--compare-list` 仅额外测试第一个实例，防止线性队列在两个 31 步实例上造成过长等待。

## 本机实测结果

测试时间：2026-10-01。单位为毫秒；每组先预热 3 次，再取 7 次测量的中位数。机器负载仍会影响耗时，节点数和路径长度才是稳定回归指标。

| Frontier | 启发式 | 实例 | 路径长度 | 中位数 (ms) | 生成节点 | 扩展节点 |
|---|---|---:|---:|---:|---:|---:|
| PqFrontier | MISPLACED | 1 | 21 | 4.467 | 15,677 | 5,794 |
| PqFrontier | MISPLACED | 2 | 31 | 112.240 | 385,076 | 143,848 |
| PqFrontier | MISPLACED | 3 | 31 | 93.514 | 385,076 | 143,848 |
| PqFrontier | MANHATTAN | 1 | 21 | 1.046 | 2,489 | 931 |
| PqFrontier | MANHATTAN | 2 | 31 | 13.073 | 55,925 | 21,197 |
| PqFrontier | MANHATTAN | 3 | 31 | 10.218 | 55,925 | 21,197 |
| ListFrontier | MISPLACED | 1 | 21 | 67.658 | 15,677 | 5,794 |
| ListFrontier | MANHATTAN | 1 | 21 | 1.697 | 2,489 | 931 |

较小实例中固定开销占比高，耗时也受硬件和运行负载影响，不适合作为硬性性能门槛。基准已接入 Maven `verify`，但只校验路径长度和结点计数，不校验毫秒数；CI 仍然只校验功能、格式和静态分析。

## 后续协作边界

- 原有 `src/stud/g01/queue/PqFrontier.java` 是一份未接入且方法恒返回 `false` 的危险占位实现，本分支已将其删除；实际入口使用通用的 `src/stud/queue/PqFrontier.java`。
- 本分支没有修改教师框架 `src/core/`，也没有实现 IDA*、PDB 或第二阶段 Closed 表策略。
- 若后续更改结点评估器或 Open/Closed 策略，请继续保留“更优重复状态替换”和最优路径长度回归。
