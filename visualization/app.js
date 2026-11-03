(() => {
  "use strict";
  const $ = (id) => document.getElementById(id);
  const panel = $("tab-overview"), board = $("board");
  const names = { MANHATTAN: "曼哈顿距离", MISPLACED: "错位将牌数", DISJOINT_PATTERN: "模式数据库", ZERO: "零启发" };
  let record = null, states = [], step = 0, timer = null, size = 3;
  const tiles = new Map();
  function stop() {
    clearTimeout(timer);
    timer = null;
    $("play").innerHTML = "▶ <span>播放路径</span>";
  }
  function render() {
    if (!states.length || panel.hidden || board.clientWidth <= 28) return;
    const current = states[step];
    board.style.setProperty("--n", size);
    const stride = (board.clientWidth - 28 - (size - 1) * 7) / size + 7;
    const moved = step ? states[step - 1][current.indexOf(0)] : 0;
    current.forEach((value, index) => {
      if (!value) return;
      let tile = tiles.get(value);
      if (!tile) {
        tile = document.createElement("div");
        tile.className = "tile";
        tile.textContent = value;
        tiles.set(value, tile);
        board.append(tile);
      }
      tile.style.transform = `translate(${index % size * stride}px, ${Math.floor(index / size) * stride}px)`;
      tile.classList.toggle("moved", value === moved);
    });
    board.setAttribute("aria-label", `第 ${step} 步，棋盘 ${current.join(" ")}`);
    $("step").textContent = String(step).padStart(2, "0");
    $("total").textContent = states.length - 1;
    $("progress").max = states.length - 1;
    $("progress").value = step;
    $("prev").disabled = step === 0;
    $("reset").disabled = step === 0;
    $("next").disabled = step === states.length - 1;
  }
  function advance() {
    if (panel.hidden || !states.length) return stop();
    step++;
    render();
    if (step === states.length - 1) stop();
    else timer = setTimeout(advance, Number($("speed").value));
  }
  function play() {
    if (panel.hidden || states.length < 2) return;
    if (timer) return stop();
    if (step === states.length - 1) step = 0;
    render();
    $("play").innerHTML = "Ⅱ <span>暂停播放</span>";
    timer = setTimeout(advance, Number($("speed").value));
  }
  function jump(value) {
    if (!states.length) return;
    stop();
    step = Math.max(0, Math.min(states.length - 1, value));
    render();
  }
  function load(next) {
    const changed = record?.id !== next?.id;
    record = next;
    $("record-title").textContent = record?.name || "尚未导入记录";
    if (!changed && next) return;
    stop();
    states = record?.states || [];
    step = 0;
    tiles.clear();
    board.replaceChildren();
    board.classList.toggle("is-empty", !record);
    for (const id of ["play", "prev", "next", "reset", "progress", "speed"]) $(id).disabled = !record;
    $("algorithm-name").textContent = record?.algorithm || "—";
    $("heuristic-name").textContent = record ? names[record.heuristic] || record.heuristic : "—";
    $("source").textContent = record ? "Java · 完整记录" : "—";
    $("record-hint").hidden = !!record;
    for (const id of ["length", "time", "generated", "expanded"]) $(id).textContent = "—";
    if (record) {
      size = Math.sqrt(states[0].length);
      $("goal-size").textContent = `${size} × ${size}`;
      $("puzzle-tag").textContent = `${size * size - 1}-PUZZLE`;
      $("length").innerHTML = `${states.length - 1}<small>步</small>`;
      $("time").innerHTML = `${record.timeMs.toLocaleString(undefined, { maximumFractionDigits: 2 })}<small>ms</small>`;
      $("generated").textContent = record.generated.toLocaleString();
      $("expanded").textContent = record.expanded.toLocaleString();
      $("play").disabled = states.length < 2;
      render();
    } else {
      const placeholder = document.createElement("span");
      placeholder.className = "empty-board-label";
      placeholder.textContent = "尚未导入记录";
      board.append(placeholder);
      board.setAttribute("aria-label", "数码棋盘，尚未导入记录");
      $("step").textContent = $("total").textContent = "—";
      $("progress").max = $("progress").value = 0;
      $("goal-size").textContent = "—";
      $("puzzle-tag").textContent = "— PUZZLE";
    }
  }
  $("play").onclick = play;
  $("prev").onclick = () => jump(step - 1);
  $("next").onclick = () => jump(step + 1);
  $("reset").onclick = () => jump(0);
  $("progress").oninput = (event) => jump(Number(event.target.value));
  $("speed").onchange = () => {
    if (timer) {
      clearTimeout(timer);
      timer = setTimeout(advance, Number($("speed").value));
    }
  };
  panel.addEventListener("npuzzle:pause", stop);
  panel.addEventListener("npuzzle:play", play);
  panel.addEventListener("npuzzle:step", (event) => jump(step + event.detail));
  panel.addEventListener("npuzzle:resize", render);
  window.NPuzzleLibrary.subscribe(load);
})();
