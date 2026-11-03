import type { Algorithm, Heuristic, NodeStatus } from "@/data/types";

export const HEURISTIC_NAMES: Record<Heuristic, string> = {
  MANHATTAN: "曼哈顿距离",
  MISPLACED: "错位将牌数",
  DISJOINT_PATTERN: "模式数据库",
  ZERO: "零启发",
};

export const ALGORITHM_NOTES: Record<Algorithm, string> = {
  "A*": "按 f = g + h 从候选队列取节点",
  Dijkstra: "按已走步数 g 从候选队列取节点",
  "IDA*": "在 f 界限内深度优先，超界后提高界限重来",
  "NIDA*": "专用 IDA*：在 f 界限内深度优先，超界后提高界限重来",
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
  replaced: "以更低代价替换重复候选",
  "closed-pruned": "已扩展的重复状态，剪枝",
  "frontier-pruned": "候选中已有更优状态，剪枝",
  "cycle-pruned": "返回当前路径中的状态，剪枝",
  "bound-pruned": "权值超过本轮界限，剪枝",
  "transposition-pruned": "已有代价更低的访问记录，剪枝",
};

export type Direction = "up" | "down" | "left" | "right";
export const DIRECTION_WORDS: Record<Direction, string> = { up: "上移", down: "下移", left: "左移", right: "右移" };
export const DIRECTION_ARROWS: Record<Direction, string> = { up: "↑", down: "↓", left: "←", right: "→" };

export const formatInt = (value: number) => value.toLocaleString("zh-CN");
export const formatMs = (value: number) => value.toLocaleString("zh-CN", { maximumFractionDigits: 2 });
export const formatBytes = (bytes: number) =>
  bytes < 1024 ? `${bytes} B` : bytes < 1024 ** 2 ? `${(bytes / 1024).toFixed(1)} KB` : `${(bytes / 1024 ** 2).toFixed(1)} MB`;
