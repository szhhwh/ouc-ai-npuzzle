import { ALGORITHMS, HEURISTICS, STATUSES, type RawNode, type RawRecord, type RecordMeta } from "./types";

export const DEFAULT_NAME = "Untitled Problem";

const algorithms = new Set<string>(ALGORITHMS);
const heuristics = new Set<string>(HEURISTICS);
const statuses = new Set<string>(STATUSES);

export const nameOf = (name: unknown) => (typeof name === "string" && name.trim() ? name.trim() : DEFAULT_NAME);
const integer = (value: unknown): value is number => Number.isSafeInteger(value) && (value as number) >= 0;
const equalBoard = (left: ArrayLike<number>, right: ArrayLike<number>) => {
  if (left.length !== right.length) return false;
  for (let i = 0; i < left.length; i++) if (left[i] !== right[i]) return false;
  return true;
};
const accepted = (node: RawNode) => node.status === "frontier" || node.status === "replaced";

export function requireValue(condition: unknown, message: string): asserts condition {
  if (!condition) throw new Error(message);
}

export function validateBoard(board: unknown, length: number, label: string): asserts board is number[] {
  requireValue(Array.isArray(board) && board.length === length, `${label}的棋盘尺寸不一致。`);
  let seen = 0;
  for (const tile of board) {
    requireValue(Number.isInteger(tile) && tile >= 0 && tile < length && !(seen & (1 << tile)), `${label}必须恰好包含 0 至 ${length - 1}。`);
    seen |= 1 << tile;
  }
}

export function legalMove(before: ArrayLike<number>, after: ArrayLike<number>, size: number) {
  const from = Array.prototype.indexOf.call(before, 0);
  const to = Array.prototype.indexOf.call(after, 0);
  if (Math.abs(Math.floor(from / size) - Math.floor(to / size)) + Math.abs((from % size) - (to % size)) !== 1) return false;
  for (let i = 0; i < before.length; i++) {
    if (after[i] !== (i === from ? before[to] : i === to ? 0 : before[i])) return false;
  }
  return true;
}

/** Checks the supplied search history against the board rules; it never searches for a solution. */
export function validateRecord(input: unknown): RawRecord & { name: string } {
  const record = input as RawRecord;
  requireValue(record && typeof record === "object" && !Array.isArray(record), "记录数据必须是对象。");
  requireValue(algorithms.has(record.algorithm), "不支持的算法名称。");
  requireValue(heuristics.has(record.heuristic), "不支持的启发函数名称。");
  requireValue(record.name == null || typeof record.name === "string", "记录名称必须是字符串。");
  requireValue(Array.isArray(record.states) && record.states.length > 0, "记录缺少完整解路径数据。");
  const length = record.states[0]?.length;
  requireValue(length === 9 || length === 16, "只支持 3×3 或 4×4 棋盘。");
  const size = Math.sqrt(length);
  validateBoard(record.goal, length, "目标");
  record.states.forEach((board, i) => {
    validateBoard(board, length, `路径第 ${i} 步`);
    if (i) requireValue(legalMove(record.states[i - 1], board, size), `路径第 ${i} 步不是合法移动。`);
  });
  requireValue(equalBoard(record.states.at(-1)!, record.goal), "解路径未到达记录的目标。");
  requireValue(typeof record.timeMs === "number" && Number.isFinite(record.timeMs) && record.timeMs >= 0, "运行时间无效。");
  requireValue(integer(record.generated) && integer(record.expanded), "搜索计数必须是非负整数。");
  const trace = record.trace;
  requireValue(
    trace && trace.complete === true && Array.isArray(trace.nodes) && trace.nodes.length > 0 && Array.isArray(trace.frames) && trace.frames.length > 0 && Array.isArray(trace.solution),
    "记录缺少完整搜索树数据，请提供包含完整搜索过程的记录文件。",
  );
  const { nodes, frames, solution } = trace;
  const roots: number[] = [];
  nodes.forEach((node, id) => {
    requireValue(node && node.id === id, `搜索节点 ${id} 的编号不连续。`);
    validateBoard(node.board, length, `搜索节点 ${id}`);
    requireValue(integer(node.g) && integer(node.h) && integer(node.f) && node.f === node.g + node.h, `搜索节点 ${id} 的 g/h/f 无效。`);
    requireValue(statuses.has(node.status), `搜索节点 ${id} 的状态无效。`);
    if (node.parent === null) {
      roots.push(id);
      requireValue(node.g === 0 && node.status === "frontier" && equalBoard(node.board, record.states[0]), `搜索根节点 ${id} 与初始棋盘不一致。`);
    } else {
      requireValue(integer(node.parent) && node.parent < id, `搜索节点 ${id} 的父节点无效。`);
      const parent = nodes[node.parent];
      requireValue(accepted(parent) && node.g === parent.g + 1 && legalMove(parent.board, node.board, size), `搜索节点 ${id} 与父节点不构成合法移动。`);
    }
    if (node.reference !== undefined) {
      requireValue(integer(node.reference) && node.reference < id && equalBoard(nodes[node.reference].board, node.board), `搜索节点 ${id} 的引用无效。`);
    }
    requireValue(node.status !== "replaced" || node.reference !== undefined, `替换节点 ${id} 缺少引用。`);
  });
  requireValue(roots[0] === 0 && record.generated === nodes.length - roots.length, "生成节点计数与完整搜索过程不一致。");
  const depthFirst = record.algorithm === "IDA*" || record.algorithm === "NIDA*";
  requireValue(depthFirst || roots.length === 1, "当前算法应只包含单个初始根节点。");
  requireValue(record.expanded === frames.length - 1, "扩展节点计数与完整搜索过程不一致。");
  const generated = new Uint8Array(nodes.length);
  const visited = new Uint8Array(nodes.length);
  let nextGenerated = 0;
  let iteration = -1;
  let iterationRoot = 0;
  let bound: number | undefined;
  frames.forEach((frame, index) => {
    requireValue(
      frame && integer(frame.current) && frame.current < nodes.length && Array.isArray(frame.children) && typeof frame.found === "boolean",
      `搜索帧 ${index} 无效。`,
    );
    const node = nodes[frame.current];
    requireValue(integer(frame.iteration), `搜索帧 ${index} 缺少迭代编号。`);
    if (frame.iteration !== iteration) {
      requireValue(
        frame.iteration === iteration + 1 && roots[frame.iteration] === frame.current && frame.current === nextGenerated,
        `搜索帧 ${index} 的迭代根节点或生成顺序不一致。`,
      );
      iteration = frame.iteration;
      iterationRoot = frame.current;
      generated[frame.current] = 1;
      nextGenerated++;
      if (depthFirst) requireValue(integer(frame.bound) && (bound === undefined || frame.bound > bound), `搜索帧 ${index} 的迭代阈值无效。`);
      bound = frame.bound;
    }
    requireValue(generated[frame.current] === 1 && visited[frame.current] === 0 && accepted(node), `搜索帧 ${index} 引用了未生成、重复扩展或已剪枝节点。`);
    requireValue(!depthFirst || frame.bound === bound, `搜索帧 ${index} 的迭代阈值不一致。`);
    requireValue(frame.current >= iterationRoot, `搜索帧 ${index} 引用了其他迭代的节点。`);
    if (frame.bound !== undefined) requireValue(integer(frame.bound), `搜索帧 ${index} 的阈值无效。`);
    visited[frame.current] = 1;
    const last = index === frames.length - 1;
    requireValue(frame.found === last && (!last || frame.children.length === 0), "搜索过程必须以唯一的成功帧结束。");
    requireValue(equalBoard(node.board, record.goal) === frame.found, `搜索帧 ${index} 的目标标记与棋盘不一致。`);
    const blank = node.board.indexOf(0);
    const legalChildren = Number(blank >= size) + Number(blank < length - size) + Number(blank % size > 0) + Number(blank % size < size - 1);
    requireValue(frame.children.length === (frame.found ? 0 : legalChildren), `搜索帧 ${index} 缺少合法生成分支。`);
    let childPositions = 0;
    for (const id of frame.children) {
      requireValue(
        integer(id) && id === nextGenerated && id < nodes.length && nodes[id].parent === frame.current && generated[id] === 0,
        `搜索帧 ${index} 的子节点、顺序或完整性无效。`,
      );
      const position = 1 << nodes[id].board.indexOf(0);
      requireValue(!(childPositions & position), `搜索帧 ${index} 包含重复生成分支。`);
      childPositions |= position;
      generated[id] = 1;
      nextGenerated++;
    }
  });
  requireValue(nextGenerated === nodes.length && iteration + 1 === roots.length, "搜索过程遗漏了生成节点或迭代。");
  requireValue(solution.length === record.states.length && solution.length > 0, "解路径与搜索节点路径长度不一致。");
  solution.forEach((id, step) => {
    requireValue(
      integer(id) && id < nodes.length && visited[id] === 1 && equalBoard(nodes[id].board, record.states[step]) && nodes[id].g === step,
      `解路径第 ${step} 步与搜索过程不一致。`,
    );
    requireValue(nodes[id].parent === (step ? solution[step - 1] : null), `解路径第 ${step} 步的父节点不一致。`);
  });
  requireValue(solution[0] === roots.at(-1) && solution.at(-1) === frames.at(-1)!.current, "解路径未连接最终迭代与成功帧。");
  return { ...record, name: nameOf(record.name) };
}

export function isValidMeta(meta: RecordMeta) {
  return (
    typeof meta.id === "string" &&
    typeof meta.name === "string" &&
    meta.name.trim() !== "" &&
    (meta.size === 3 || meta.size === 4) &&
    algorithms.has(meta.algorithm) &&
    heuristics.has(meta.heuristic) &&
    integer(meta.moves) &&
    integer(meta.generated) &&
    integer(meta.expanded) &&
    Number.isFinite(meta.timeMs) &&
    meta.timeMs >= 0 &&
    Number.isFinite(meta.importedAt) &&
    integer(meta.compressedBytes) &&
    meta.compressedBytes > 0
  );
}
