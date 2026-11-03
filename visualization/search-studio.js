(() => {
  "use strict";
  const $ = (id) => document.getElementById(id), panel = $("tab-studio");
  const reasons = {
    frontier: "保留的候选节点", replaced: "以更低代价替换重复候选",
    "closed-pruned": "已扩展的重复状态，剪枝", "frontier-pruned": "候选中已有更优状态，剪枝",
    "cycle-pruned": "返回当前路径中的状态，剪枝", "bound-pruned": "权值超过本轮界限，剪枝",
    "transposition-pruned": "已有代价更低的访问记录，剪枝",
  };
  let record = null, trace = null, nodes = [], shots = [], position = 0, timer = null;
  let inspected = null, fit = false, oldBoard = null, previousPosition = -1;
  let completedRound = -1, visible = new Set(), open = new Set(), coords = new Map();
  let treeList = [], treeWidth = 0, treeHeight = 0, scale = 1, currentId = null, nextId = null, solution = new Set();
  const viewport = $("tree-viewport"), svg = $("search-tree");
  function stop() {
    clearTimeout(timer);
    timer = null;
    $("play-studio").textContent = "▶ 播放算法";
  }
  function accept(id) {
    const node = nodes[id];
    if (node.status === "replaced") open.delete(node.reference);
    if (!node.status.endsWith("-pruned")) open.add(id);
  }
  function apply(frame) {
    if (nodes[frame.current].parent === null) {
      if (record.algorithm.includes("IDA")) open.clear();
      visible.add(frame.current);
    }
    open.delete(frame.current);
    for (const id of frame.children) { visible.add(id); accept(id); }
  }
  function before(round) {
    if (round <= completedRound) {
      visible = new Set([trace.frames[0].current]);
      open = new Set(visible);
      completedRound = -1;
    }
    while (completedRound < round - 1) apply(trace.frames[++completedRound]);
  }
  function load(next) {
    const changed = record?.id !== next?.id;
    record = next;
    $("studio-record").textContent = record ? `${record.name} · ${record.algorithm}` : "尚未选择记录";
    if (!changed && next) return;
    stop();
    trace = record?.trace || null;
    nodes = trace?.nodes || [];
    shots = [];
    position = 0;
    inspected = null;
    oldBoard = null;
    previousPosition = -1;
    completedRound = -1;
    visible = new Set(); open = new Set(); coords = new Map(); treeList = [];
    $("focus-board").replaceChildren();
    $("focus-board").classList.toggle("is-empty", !trace);
    svg.replaceChildren();
    svg.toggleAttribute("hidden", !trace);
    $("tree-empty").hidden = !!trace;
    for (const id of ["play-studio", "previous", "next-studio", "restart", "timeline", "speed-studio", "pruned", "fit-tree", "actual-tree"]) $(id).disabled = !trace;
    if (trace) {
      shots.push({ phase: "ready", round: 0 });
      trace.frames.forEach((frame, round) => {
        shots.push({ phase: "pick", round });
        if (frame.found) shots.push({ phase: "goal", round });
        else {
          frame.children.forEach((_, child) => shots.push({ phase: "child", round, child }));
          shots.push({ phase: "merge", round });
        }
      });
      visible.add(trace.frames[0].current); open.add(trace.frames[0].current);
      $("timeline").max = shots.length - 1;
      render();
    } else {
      $("shot-tag").textContent = "—";
      $("headline").textContent = "等待导入求解记录";
      $("explanation-studio").textContent = "在「问题记录」中选择后，查看完整搜索过程。";
      $("inspect-title").textContent = "节点 —";
      for (const id of ["cost-g", "cost-h", "cost-f", "node-status"]) $(id).textContent = "—";
      $("position").textContent = "— / —";
      $("timeline").max = $("timeline").value = 0;
      const empty = document.createElement("span");
      empty.className = "empty-board-label"; empty.textContent = "暂无节点";
      $("focus-board").append(empty);
    }
  }
  function board(values) {
    const el = $("focus-board"), n = Math.sqrt(values.length);
    el.style.setProperty("--n", n);
    if (!el.children.length) for (let value = 0; value < values.length; value++) {
      const tile = document.createElement("span");
      tile.className = `focus-tile ${value ? "" : "blank"}`;
      tile.dataset.value = value; tile.textContent = value || ""; el.append(tile);
    }
    const changes = oldBoard ? values.filter((v, i) => v !== oldBoard[i]).length : 0;
    el.classList.toggle("instant", changes !== 2);
    for (const tile of el.children) {
      const index = values.indexOf(Number(tile.dataset.value)), cell = el.clientWidth / n;
      tile.style.transform = `translate(${index % n * cell}px,${Math.floor(index / n) * cell}px)`;
    }
    el.setAttribute("aria-label", `棋盘 ${values.join(" ")}`);
    oldBoard = values;
  }
  function render() {
    if (!trace || panel.hidden) return;
    const shot = shots[position], frame = trace.frames[shot.round], current = nodes[frame.current];
    before(shot.round);
    const shown = new Set(visible), pending = new Set(open);
    if (current.parent === null) {
      shown.add(current.id);
      if (record.algorithm.includes("IDA")) pending.clear();
      pending.add(current.id);
    }
    const revealed = shot.phase === "child" ? frame.children.slice(0, shot.child + 1) : shot.phase === "merge" ? frame.children : [];
    if (["child", "merge", "goal"].includes(shot.phase)) pending.delete(current.id);
    for (const id of revealed) {
      shown.add(id);
      const node = nodes[id];
      if (node.status === "replaced") pending.delete(node.reference);
      if (!node.status.endsWith("-pruned")) pending.add(id);
    }
    const focus = nodes[inspected] || (shot.phase === "child" ? nodes[revealed.at(-1)] : current);
    const depthFirst = record.algorithm.includes("IDA");
    const titles = {
      ready: "起点进入搜索", pick: depthFirst ? `访问节点 #${current.id}` : `从候选中取出节点 #${current.id}`,
      child: `生成后继 #${focus.id}`, merge: depthFirst ? "继续深度优先搜索" : "更新候选，进入下一轮",
      goal: `找到目标 · ${current.g} 步`,
    };
    const words = {
      ready: "f = g + h：已走代价 + 剩余估计。",
      pick: depthFirst ? `按 Java 搜索顺序访问；本轮界限 ${frame.bound ?? "—"}。` : "按 f 升序、再按 g 升序选择；平局展示 Java 队列本次选择。",
      child: reasons[focus.status] || focus.status,
      merge: `本轮生成 ${frame.children.length} 个节点，剪枝 ${frame.children.filter((id) => nodes[id].status.endsWith("-pruned")).length} 个。`,
      goal: "搜索完成，金色连线标出完整解路径。",
    };
    $("headline").textContent = titles[shot.phase];
    $("explanation-studio").textContent = words[shot.phase];
    $("shot-tag").textContent = `${depthFirst ? `迭代 ${frame.iteration + 1} · ` : ""}第 ${shot.round + 1} 轮`;
    $("inspect-title").textContent = `节点 #${focus.id}`;
    $("cost-g").textContent = focus.g; $("cost-h").textContent = focus.h; $("cost-f").textContent = focus.f;
    $("node-status").textContent = reasons[focus.status] || focus.status;
    board(focus.board);
    currentId = current.id;
    nextId = shot.phase === "merge" ? trace.frames[shot.round + 1]?.current : null;
    solution = new Set(shot.phase === "goal" ? trace.solution : []);
    layout(shown, pending);
    $("position").textContent = `${position + 1} / ${shots.length}`;
    $("timeline").value = position;
    $("previous").disabled = $("restart").disabled = position === 0;
    $("next-studio").disabled = position === shots.length - 1;
  }
  let pendingNodes = new Set();
  function layout(shown, pending) {
    treeList = [...shown].map((id) => nodes[id]).filter((node) => $("pruned").checked || !node.status.endsWith("-pruned"));
    const byParent = new Map();
    for (const node of treeList) {
      if (!byParent.has(node.parent)) byParent.set(node.parent, []);
      byParent.get(node.parent).push(node);
    }
    coords = new Map();
    let leaf = 0, maxDepth = 0;
    const stack = (byParent.get(null) || []).map((node) => [node, false]).reverse();
    while (stack.length) {
      const [node, done] = stack.pop(), children = byParent.get(node.id) || [];
      if (!done && children.length) {
        stack.push([node, true]);
        for (let i = children.length - 1; i >= 0; i--) stack.push([children[i], false]);
      } else {
        coords.set(node.id, { x: children.length ? children.reduce((sum, child) => sum + coords.get(child.id).x, 0) / children.length : 60 + leaf++ * 116, y: 70 + node.g * 148 });
        maxDepth = Math.max(maxDepth, node.g);
      }
    }
    const naturalWidth = Math.max(120, leaf * 116);
    treeWidth = Math.max(viewport.clientWidth, naturalWidth); treeHeight = Math.max(viewport.clientHeight, 144 + maxDepth * 148);
    const offset = (treeWidth - naturalWidth) / 2;
    for (const point of coords.values()) point.x += offset;
    scale = fit ? Math.max(0.15, Math.min(1, viewport.clientWidth / treeWidth)) : 1;
    pendingNodes = pending;
    svg.setAttribute("viewBox", `0 0 ${treeWidth} ${treeHeight}`);
    svg.setAttribute("width", treeWidth * scale); svg.setAttribute("height", treeHeight * scale);
    if (previousPosition !== position) {
      const point = coords.get(currentId);
      if (point) viewport.scrollTo({ left: Math.max(0, point.x * scale - viewport.clientWidth / 2), top: Math.max(0, point.y * scale - viewport.clientHeight / 2), behavior: "instant" });
    }
    previousPosition = position;
    drawTree();
  }
  function drawTree() {
    if (!trace || panel.hidden) return;
    const left = viewport.scrollLeft / scale - 130, right = (viewport.scrollLeft + viewport.clientWidth) / scale + 130;
    const top = viewport.scrollTop / scale - 150, bottom = (viewport.scrollTop + viewport.clientHeight) / scale + 150;
    const onScreen = (p) => p.x >= left && p.x <= right && p.y >= top && p.y <= bottom;
    const links = [], groups = [], n = Math.sqrt(record.states[0].length), cell = 60 / n;
    for (const node of treeList) {
      const point = coords.get(node.id), parent = coords.get(node.parent);
      if (parent && Math.max(point.x, parent.x) >= left && Math.min(point.x, parent.x) <= right && Math.max(point.y, parent.y) >= top && Math.min(point.y, parent.y) <= bottom)
        links.push(`<path class="tree-link ${solution.has(node.id) && solution.has(node.parent) ? "solution" : ""}" d="M${parent.x},${parent.y + 54} L${point.x},${point.y - 54}"/>`);
      if (!onScreen(point)) continue;
      const cells = node.board.map((value, index) => `<rect class="state-tile ${value ? "" : "empty"}" x="${-30 + index % n * cell}" y="${-30 + Math.floor(index / n) * cell}" width="${cell - 2}" height="${cell - 2}" rx="2"/>${value ? `<text class="state-value" text-anchor="middle" x="${-30 + (index % n + 0.5) * cell - 1}" y="${-30 + (Math.floor(index / n) + 0.7) * cell}" style="font-size:${n === 4 ? 9 : 12}px">${value}</text>` : ""}`).join("");
      groups.push(`<g class="tree-node ${pendingNodes.has(node.id) ? "open" : ""} ${node.id === currentId ? "active" : ""} ${node.status.endsWith("-pruned") ? "pruned" : ""} ${solution.has(node.id) ? "solution" : ""} ${inspected === node.id ? "inspect" : ""}" transform="translate(${point.x},${point.y})" data-node="${node.id}" tabindex="0" role="button" aria-label="节点 ${node.id}，g ${node.g} h ${node.h} f ${node.f}"><rect class="state-card" x="-47" y="-54" width="94" height="108" rx="8"/><text class="state-id" text-anchor="middle" y="-39">#${node.id}${node.id === nextId ? " · 下一个" : ""}</text>${cells}<text class="state-cost" text-anchor="middle" y="44">g ${node.g} · h ${node.h} · f ${node.f}</text></g>`);
    }
    svg.innerHTML = links.join("") + groups.join("");
  }
  function jump(value) {
    if (!trace) return;
    stop(); position = Math.max(0, Math.min(shots.length - 1, value)); inspected = null; render();
  }
  function tick() {
    if (panel.hidden || position >= shots.length - 1) return stop();
    timer = setTimeout(() => { position++; inspected = null; render(); tick(); }, Number($("speed-studio").value));
  }
  function play() {
    if (!trace || panel.hidden) return;
    if (timer) return stop();
    if (position === shots.length - 1) position = 0;
    inspected = null; $("play-studio").textContent = "Ⅱ 暂停"; render(); tick();
  }
  $("play-studio").onclick = play;
  $("previous").onclick = () => jump(position - 1);
  $("next-studio").onclick = () => jump(position + 1);
  $("restart").onclick = () => jump(0);
  $("timeline").oninput = () => jump(Number($("timeline").value));
  $("speed-studio").onchange = () => { if (timer) { clearTimeout(timer); tick(); } };
  $("pruned").onchange = render;
  for (const id of ["fit-tree", "actual-tree"]) $(id).onclick = () => {
    fit = id === "fit-tree"; $("fit-tree").classList.toggle("active", fit); $("actual-tree").classList.toggle("active", !fit); render();
  };
  panel.addEventListener("click", (event) => { const target = event.target.closest("[data-node]"); if (target) { inspected = Number(target.dataset.node); render(); } });
  panel.addEventListener("keydown", (event) => {
    if (event.target.matches(".tree-node") && ["Enter", " "].includes(event.key)) {
      event.preventDefault(); event.stopPropagation(); inspected = Number(event.target.dataset.node); render();
      panel.querySelector(`[data-node="${inspected}"]`)?.focus({ preventScroll: true });
    }
  });
  let scrollFrame = null;
  viewport.addEventListener("scroll", () => { if (scrollFrame === null) scrollFrame = requestAnimationFrame(() => { scrollFrame = null; drawTree(); }); });
  let drag = null;
  viewport.addEventListener("pointerdown", (event) => {
    if (event.button !== 0 || event.target.closest(".tree-node")) return;
    drag = { x: event.clientX, y: event.clientY, left: viewport.scrollLeft, top: viewport.scrollTop };
    viewport.setPointerCapture(event.pointerId);
  });
  viewport.addEventListener("pointermove", (event) => { if (drag) { viewport.scrollLeft = drag.left - event.clientX + drag.x; viewport.scrollTop = drag.top - event.clientY + drag.y; } });
  for (const type of ["pointerup", "pointercancel", "lostpointercapture"]) viewport.addEventListener(type, () => { drag = null; });
  panel.addEventListener("npuzzle:pause", stop);
  panel.addEventListener("npuzzle:play", play);
  panel.addEventListener("npuzzle:step", (event) => jump(position + event.detail));
  panel.addEventListener("npuzzle:resize", render);
  window.NPuzzleLibrary.subscribe(load);
})();
