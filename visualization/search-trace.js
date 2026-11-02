"use strict";
// Independent browser teaching tracer, not the course Java searcher.
function traceAStar(initial, kind = "manhattan", limit = 300) {
  if (
    !Array.isArray(initial) ||
    initial.length !== 9 ||
    new Set(initial).size !== 9 ||
    initial.some((v) => !Number.isInteger(v) || v < 0 || v > 8)
  )
    throw new Error("需要合法的八数码棋盘");
  if (
    !["manhattan", "misplaced"].includes(kind) ||
    !Number.isInteger(limit) ||
    limit < 1 ||
    limit > 1000
  )
    throw new Error("无效跟踪参数");
  let inversions = 0;
  for (let i = 0; i < 9; i++)
    for (let j = i + 1; j < 9; j++)
      if (initial[i] && initial[j] && initial[i] > initial[j]) inversions++;
  if (inversions % 2)
    throw new Error("此棋盘无法到达标准目标（逆序奇偶性不符）");
  const goal = "1,2,3,4,5,6,7,8,0";
  const h = (board) =>
    board.reduce(
      (sum, v, i) =>
        sum +
        (!v
          ? 0
          : kind === "misplaced"
            ? Number(v !== i + 1)
            : Math.abs(Math.floor(i / 3) - Math.floor((v - 1) / 3)) +
              Math.abs((i % 3) - ((v - 1) % 3))),
      0,
    );
  const nodes = [];
  const events = [];
  const open = new Map();
  const closed = new Set();
  let generated = 0,
    expanded = 0;
  function node(board, g, parent) {
    const value = {
      id: nodes.length,
      board,
      g,
      h: h(board),
      parent,
      key: board.join(","),
    };
    value.f = value.g + value.h;
    nodes.push(value);
    return value;
  }
  const root = node(initial.slice(), 0, null);
  open.set(root.key, root);
  const compare = (a, b) => a.f - b.f || a.g - b.g || a.id - b.id;
  while (open.size && events.length < limit) {
    const ranked = [...open.values()].sort(compare);
    const frontierBefore = open.size,
      candidates = ranked.slice(0, 6).map((n) => n.id);
    const current = ranked[0];
    open.delete(current.key);
    const children = [];
    const found = current.key === goal;
    if (!found) {
      closed.add(current.key);
      expanded++;
      const blank = current.board.indexOf(0),
        r = Math.floor(blank / 3),
        c = blank % 3;
      for (const [dr, dc, direction] of [
        [-1, 0, "N"],
        [0, 1, "E"],
        [1, 0, "S"],
        [0, -1, "W"],
      ]) {
        const nr = r + dr,
          nc = c + dc;
        if (nr < 0 || nr > 2 || nc < 0 || nc > 2) continue;
        generated++;
        const board = current.board.slice(),
          target = nr * 3 + nc;
        [board[blank], board[target]] = [board[target], board[blank]];
        const candidate = node(board, current.g + 1, current.id);
        let status = "frontier";
        if (closed.has(candidate.key)) status = "closed-pruned";
        else if (
          open.has(candidate.key) &&
          open.get(candidate.key).g <= candidate.g
        )
          status = "duplicate-pruned";
        else {
          if (open.has(candidate.key)) status = "replaced";
          open.set(candidate.key, candidate);
        }
        children.push({ id: candidate.id, status, direction });
      }
    }
    events.push({
      current: current.id,
      candidates,
      frontierBefore,
      children,
      generated,
      expanded,
      frontier: open.size,
      found,
    });
    if (found) break;
  }
  const last = events.at(-1);
  const path = [];
  if (last?.found)
    for (let id = last.current; id !== null; id = nodes[id].parent)
      path.push(nodes[id].board);
  const chartMax = Math.max(
    1,
    ...events.flatMap((e) => [e.frontier, e.generated, e.expanded]),
  );
  return {
    nodes,
    events,
    path: path.reverse(),
    solved: !!last?.found,
    truncated: !last?.found && open.size > 0,
    kind,
    limit,
    chartMax,
  };
}
if (typeof module !== "undefined") module.exports = { traceAStar };
