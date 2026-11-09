import type { Algorithm, Heuristic, HeuristicQuality, NodeStatus } from "@/data/types";

export const HEURISTIC_NAMES: Record<Heuristic, string> = {
  MANHATTAN: "曼哈顿距离",
  MISPLACED: "错位将牌数",
  DISJOINT_PATTERN: "模式数据库",
  ZERO: "零启发",
};

export const ALGORITHM_NOTES: Record<Algorithm, string> = {
  "A*": "按综合代价 f 优先从候选队列选取节点",
  Dijkstra: "按实际步数 g 优先从候选队列选取节点",
  "IDA*": "设定 f 阈值进行深度优先搜索，超界后提高阈值迭代",
  "NIDA*": "面向 N 数码优化的 IDA*，结合阈值迭代与路径剪枝",
};

/** Short tag shown on the tree and in lists. */
export const STATUS_TAGS: Record<NodeStatus, string> = {
  frontier: "保留",
  replaced: "替换",
  "closed-pruned": "重复",
  "frontier-pruned": "队列已有",
  "cycle-pruned": "回路",
  "bound-pruned": "超界",
  "transposition-pruned": "置换表",
};

export const STATUS_REASONS: Record<NodeStatus, string> = {
  frontier: "保留为候选节点",
  replaced: "以更优代价替换重复候选",
  "closed-pruned": "已扩展的重复状态，执行剪枝",
  "frontier-pruned": "队列中已有更优路径，执行剪枝",
  "cycle-pruned": "检测到路径环路，执行剪枝",
  "bound-pruned": "代价值超出当前迭代界限，执行剪枝",
  "transposition-pruned": "置换表记录已有不劣的路径，执行剪枝",
};

export type Direction = "up" | "down" | "left" | "right";
export const DIRECTION_WORDS: Record<Direction, string> = { up: "上移", down: "下移", left: "左移", right: "右移" };
export const DIRECTION_ARROWS: Record<Direction, string> = { up: "↑", down: "↓", left: "←", right: "→" };

export const formatInt = (value: number) => value.toLocaleString("zh-CN");
export const formatMs = (value: number) => value.toLocaleString("zh-CN", { maximumFractionDigits: 2 });
export const formatBytes = (bytes: number) =>
  bytes < 1024 ? `${bytes} B` : bytes < 1024 ** 2 ? `${(bytes / 1024).toFixed(1)} KB` : `${(bytes / 1024 ** 2).toFixed(1)} MB`;

/** Never substitute a computed board heuristic for an estimate absent from the trace. */
export const formatEstimate = (value: number | null, quality: HeuristicQuality) =>
  value === null ? "未计算" : quality === "lower-bound" ? `≥ ${value}（下界）` : String(value);
