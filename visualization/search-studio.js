/* Replay observations from the real Java queue; no browser search or invented tie-break. */
(() => {
  "use strict";
  const $ = (id) => document.getElementById(id);
  const panel = $("tab-studio");
  const data = window.NPuzzleJavaTraces;
  let trace,
    nodes,
    shots,
    root,
    position = 0,
    timer,
    inspected = null,
    fit = false;
  function stop() {
    clearTimeout(timer);
    timer = null;
    $("play-studio").textContent = "▶ 播放算法";
  }
  function load() {
    stop();
    trace = data.traces.find(
      (t) =>
        t.preset === Number($("preset").value) &&
        t.heuristic === $("heuristic").value,
    );
    nodes = new Map(trace.nodes.map((n) => [n.id, n]));
    root = trace.nodes.find((node) => node.parent === null);
    shots = [{ phase: "ready", round: -1 }];
    trace.frames.forEach((frame, round) => {
      for (const phase of ["f", "g", "pick"]) shots.push({ phase, round });
      if (frame.found) shots.push({ phase: "goal", round });
      else {
        frame.children.forEach((_, child) =>
          shots.push({ phase: "child", round, child }),
        );
        shots.push({ phase: "merge", round });
      }
    });
    position = 0;
    inspected = null;
    $("timeline").max = shots.length - 1;
    previousTree.clear();
    previousTreeStep = -1;
    oldBoard = null;
    render();
  }
  function render() {
    if (panel.hidden) return;
    const shot = shots[position];
    const frame = trace.frames[shot.round];
    const phase = shot.phase;
    const current = frame ? nodes.get(frame.current) : root;
    let openIds = !frame
      ? [root.id]
      : phase === "merge"
        ? frame.openAfter
        : frame.openBefore;
    if (frame && ["child", "goal"].includes(phase)) {
      openIds = frame.openBefore.filter((id) => id !== frame.current);
      if (phase === "child") {
        for (const id of frame.children.slice(0, shot.child + 1)) {
          const node = nodes.get(id);
          if (node.status === "replaced")
            openIds = openIds.filter((old) => old !== node.reference);
          if (node.status === "frontier" || node.status === "replaced")
            openIds.push(id);
        }
      }
    }
    let minF = Infinity;
    let minG = Infinity;
    for (const id of openIds) {
      const node = nodes.get(id);
      if (node.f < minF) {
        minF = node.f;
        minG = node.g;
      } else if (node.f === minF) minG = Math.min(minG, node.g);
    }
    const titles = {
      ready: "起点进入 Open",
      f: `先找最小 f = ${minF}`,
      g: `同 f 时，再选较小 g = ${minG}`,
      pick: `取出节点 #${current.id}`,
      child: "计算后继权值",
      merge: "更新 Open，开始下一轮",
      goal: `找到目标 · ${current.g} 步`,
    };
    const words = {
      ready: "f = g + h：已走代价 + 剩余估计。",
      f: "比较 Open 中所有候选的权值。",
      g: "权值相同，按项目规则比较已走代价。",
      pick: "从整个 Open 中选择，不限于刚生成的后继。",
      child: "逐个估值；重复状态按项目规则剪枝。",
      merge: "保留可用候选，再从全局比较权值。",
      goal: "金色连线标出最终解路径。",
    };
    if (
      phase === "pick" &&
      frame.openBefore.filter((id) => {
        const node = nodes.get(id);
        return node.f === current.f && node.g === current.g;
      }).length > 1
    )
      words.pick = "f、g 均相同：展示 Java 队列本次选择。";
    if (position === shots.length - 1 && trace.truncated) {
      titles.merge = "前 80 轮录制结束";
      words.merge = "前 80 轮演示结束。";
    }
    $("headline").textContent = titles[phase];
    $("explanation-studio").textContent = words[phase];
    $("shot-tag").textContent = frame ? `第 ${shot.round + 1} 轮` : "起点";

    const revealed =
      phase === "child"
        ? frame.children.slice(0, shot.child + 1)
        : phase === "merge"
          ? frame.children
          : [];
    const focus =
      nodes.get(inspected) ||
      (phase === "child" ? nodes.get(revealed.at(-1)) : current);
    $("inspect-title").textContent = `节点 #${focus.id}`;
    $("cost-g").textContent = focus.g;
    $("cost-h").textContent = focus.h;
    $("cost-f").textContent = focus.f;
    board(focus.board);

    const visible = new Set([root.id]);
    for (let round = 0; round < shot.round; round++)
      for (const id of trace.frames[round].children) visible.add(id);
    for (const id of revealed) visible.add(id);
    drawTree(visible, new Set(openIds), current, phase === "goal");
    $("position").textContent = `${position + 1} / ${shots.length}`;
    $("timeline").value = position;
    $("previous").disabled = position === 0;
    $("next-studio").disabled = position === shots.length - 1;
  }
  let oldBoard;
  function board(values) {
    const el = $("focus-board");
    if (!el.children.length)
      for (let v = 0; v < 9; v++) {
        const tile = document.createElement("span");
        tile.className = `focus-tile ${v ? "" : "blank"}`;
        tile.dataset.value = v;
        tile.textContent = v || "";
        el.append(tile);
      }
    const changes = oldBoard
      ? values.filter((v, i) => v !== oldBoard[i]).length
      : 9;
    el.classList.toggle("instant", changes !== 2);
    void el.offsetWidth;
    [...el.children].forEach((tile) => {
      const i = values.indexOf(Number(tile.dataset.value)),
        cell = el.clientWidth / 3;
      tile.style.transform = `translate(${(i % 3) * cell}px,${Math.floor(i / 3) * cell}px)`;
    });
    oldBoard = values;
  }
  let previousTree = new Set(),
    previousTreeStep = -1;
  function drawTree(visible, open, current, goal) {
    const list = [...visible]
      .map((id) => nodes.get(id))
      .filter((n) => $("pruned").checked || !n.status.endsWith("pruned"));
    const byParent = new Map();
    list.forEach((n) => {
      if (!byParent.has(n.parent)) byParent.set(n.parent, []);
      byParent.get(n.parent).push(n);
    });
    let leaf = 0;
    const coords = new Map();
    function walk(n) {
      const kids = byParent.get(n.id) || [];
      kids.forEach(walk);
      coords.set(n.id, {
        x: kids.length
          ? kids.reduce((s, k) => s + coords.get(k.id).x, 0) / kids.length
          : 54 + leaf++ * 108,
        y: 64 + n.g * 140,
      });
    }
    walk(list.find((n) => n.parent === null));
    const viewport = $("tree-viewport"),
      naturalWidth = leaf * 108,
      width = Math.max(viewport.clientWidth, naturalWidth),
      height = Math.max(140, ...list.map((n) => 64 + n.g * 140 + 64));
    if (width > naturalWidth)
      coords.forEach((p) => (p.x += (width - naturalWidth) / 2));
    const scale = fit
        ? Math.max(0.8, Math.min(1, viewport.clientWidth / width))
        : 1,
      svg = $("search-tree");
    svg.setAttribute("viewBox", `0 0 ${width} ${height}`);
    svg.setAttribute("width", width * scale);
    svg.setAttribute("height", height * scale);
    const solution = new Set(goal ? trace.solution : []);
    svg.innerHTML =
      list
        .map((n) => {
          const p = coords.get(n.parent),
            c = coords.get(n.id);
          return p
            ? `<path class="tree-link ${solution.has(n.id) && solution.has(n.parent) ? "solution" : ""}" d="M${p.x},${p.y + 54} L${c.x},${c.y - 54}"/>`
            : "";
        })
        .join("") +
      list
        .map((n) => {
          const p = coords.get(n.id);
          const cells = n.board
            .map(
              (v, i) =>
                `<rect class="state-tile ${v ? "" : "empty"}" x="${-29 + (i % 3) * 20}" y="${-30 + Math.floor(i / 3) * 20}" width="18" height="18" rx="2"/>${v ? `<text class="state-value" text-anchor="middle" x="${-20 + (i % 3) * 20}" y="${-17 + Math.floor(i / 3) * 20}">${v}</text>` : ""}`,
            )
            .join("");
          return `<g class="tree-node ${!previousTree.has(n.id) ? "new-node" : ""} ${open.has(n.id) ? "open" : ""} ${n.id === current.id ? "active" : ""} ${n.status.endsWith("pruned") ? "pruned" : ""} ${solution.has(n.id) ? "solution" : ""} ${inspected === n.id ? "inspect" : ""}" transform="translate(${p.x},${p.y})" data-node="${n.id}" tabindex="0" role="button" aria-label="节点 ${n.id}，棋盘 ${n.board.join(" ")}，g ${n.g} h ${n.h} f ${n.f}"><title>#${n.id} · ${n.board.join(" ")} · g ${n.g} + h ${n.h} = f ${n.f}</title><rect class="state-card" x="-44" y="-54" width="88" height="108" rx="8"/><text class="state-id" text-anchor="middle" y="-39">#${n.id}</text>${cells}<text class="state-cost" text-anchor="middle" y="44">g ${n.g} · h ${n.h} · f ${n.f}</text></g>`;
        })
        .join("");
    previousTree = new Set(list.map((n) => n.id));
    if (previousTreeStep !== position && coords.has(current.id)) {
      const p = coords.get(current.id);
      viewport.scrollTo({
        left: Math.max(0, p.x * scale - viewport.clientWidth / 2),
        top: Math.max(0, p.y * scale - viewport.clientHeight / 2),
        behavior: "instant",
      });
    }
    previousTreeStep = position;
  }
  function jump(value) {
    stop();
    position = Math.max(0, Math.min(shots.length - 1, value));
    inspected = null;
    render();
  }
  function tick() {
    if (position >= shots.length - 1) {
      stop();
      return;
    }
    timer = setTimeout(
      () => {
        position++;
        inspected = null;
        render();
        tick();
      },
      Number($("speed-studio").value),
    );
  }
  function play() {
    if (timer) {
      stop();
      return;
    }
    if (position === shots.length - 1) position = 0;
    inspected = null;
    $("play-studio").textContent = "Ⅱ 暂停";
    render();
    tick();
  }
  $("play-studio").onclick = play;
  $("previous").onclick = () => jump(position - 1);
  $("next-studio").onclick = () => jump(position + 1);
  $("restart").onclick = () => jump(0);
  $("timeline").oninput = () => jump(Number($("timeline").value));
  $("preset").onchange = load;
  $("heuristic").onchange = load;
  $("speed-studio").onchange = () => {
    if (timer) {
      clearTimeout(timer);
      tick();
    }
  };
  $("pruned").onchange = render;
  for (const id of ["fit-tree", "actual-tree"]) {
    $(id).onclick = () => {
      fit = id === "fit-tree";
      $("fit-tree").classList.toggle("active", fit);
      $("actual-tree").classList.toggle("active", !fit);
      render();
    };
  }
  panel.addEventListener("click", (event) => {
    const target = event.target.closest("[data-node]");
    if (target) {
      inspected = Number(target.dataset.node);
      render();
    }
  });
  panel.addEventListener("keydown", (event) => {
    if (
      event.target.matches(".tree-node") &&
      ["Enter", " "].includes(event.key)
    ) {
      event.preventDefault();
      event.stopPropagation();
      inspected = Number(event.target.dataset.node);
      render();
      panel
        .querySelector(`[data-node="${inspected}"]`)
        .focus({ preventScroll: true });
    }
  });
  panel.addEventListener("npuzzle:pause", stop);
  panel.addEventListener("npuzzle:play", play);
  panel.addEventListener("npuzzle:step", (event) =>
    jump(position + event.detail),
  );
  panel.addEventListener("npuzzle:resize", render);
  load();
})();
