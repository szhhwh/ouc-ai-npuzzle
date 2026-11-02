/* Coursework result interchange: no dependencies and no network requests. */
"use strict";
function parsePathText(text) {
  const results = [];
  let boards = [],
    rows = [],
    width = 0;
  const flush = (metadata) => {
    if (rows.length) throw new Error("文本包含不完整棋盘");
    if (!boards.length) return;
    results.push(validate({ states: boards, ...metadata }));
    boards = [];
    width = 0;
  };
  for (const line of text.replace(/\r/g, "").split("\n")) {
    const total = line.match(/总步数[：:]\s*(\d+)/);
    if (total && boards.length - 1 !== Number(total[1]))
      throw new Error("总步数与棋盘序列不一致");
    if (/初始状态/.test(line) && boards.length) flush({});
    const summary = line.match(
      /启发函数[：:]\s*([A-Z_]+).*?解路径长度[：:]\s*(\d+).*?执行了\s*([\d.]+)s.*?生成了\s*(\d+).*?扩展了\s*(\d+)/,
    );
    if (summary) {
      if (!boards.length || boards.length - 1 !== Number(summary[2]))
        throw new Error("摘要步数与棋盘序列不一致");
      const engine = line.match(/搜索引擎[：:]\s*(astar|ida|nida|dijkstra)\b/i);
      const algorithm = engine
        ? { astar: "A*", ida: "IDA*", nida: "NIDA*", dijkstra: "Dijkstra" }[
            engine[1].toLowerCase()
          ]
        : undefined;
      flush({
        algorithm,
        heuristic: algorithm === "Dijkstra" ? "ZERO" : summary[1],
        timeMs: Number(summary[3]) * 1000,
        generated: Number(summary[4]),
        expanded: Number(summary[5]),
        timing: "CPU 秒转毫秒（原始文本）",
      });
      continue;
    }
    const tokens = line.replace(/\|/g, " ").trim().split(/\s+/);
    if (
      ![3, 4].includes(tokens.length) ||
      !tokens.every((t) => /^(?:\d+|#)$/.test(t))
    )
      continue;
    if (!width) width = tokens.length;
    if (tokens.length !== width) throw new Error("同一路径棋盘尺寸不一致");
    rows.push(tokens.map((t) => (t === "#" ? 0 : Number(t))));
    if (rows.length === width) {
      boards.push(rows.flat());
      rows = [];
    }
  }
  flush({});
  if (!results.length) throw new Error("没有识别到 3×3 或 4×4 解路径棋盘");
  return results;
}
function parseResults(text, filename) {
  if (/^\s*[\[{]/.test(text)) {
    const data = JSON.parse(text);
    const records = Array.isArray(data) ? data : data.results || [data];
    if (!Array.isArray(records) || !records.length || records.length > 200)
      throw new Error("每批需要 1–200 条结果");
    return records.map((record) => validate(record));
  }
  return parsePathText(text);
}
const importedResults = [];
$("import").textContent = "＋ 导入解路径 JSON / TXT（可多选）";
document.querySelector(".intro-note p").textContent =
  "一个棋盘，一条搜索路径。用动画回放，用数据解释。";
function showImported(index) {
  const record = importedResults[index];
  load(record.data, record.label);
  updateStudy();
  const data = record.data;
  $("metrics-note").textContent =
    `路径合法且与声明目标一致（若提供）。计时：${typeof data.timing === "string" ? data.timing : "未提供"}；环境：${typeof data.environment === "string" ? data.environment : "未提供"}；预热 ${Number.isSafeInteger(data.warmupRuns) ? data.warmupRuns : "—"} 轮，测量 ${Number.isSafeInteger(data.measuredRuns) ? data.measuredRuns : "—"} 轮。`;
}
function updateStudy() {
  const list = $("moves");
  list.replaceChildren();
  states.forEach((board, i) => {
    const button = document.createElement("button");
    if (!i) button.textContent = "0 · 起点";
    else {
      const a = states[i - 1].indexOf(0),
        b = board.indexOf(0),
        delta = b - a;
      const direction =
        delta === -size
          ? "N ↑"
          : delta === size
            ? "S ↓"
            : delta === 1
              ? "E →"
              : "W ←";
      button.textContent = `${i} · ${direction}`;
    }
    button.onclick = () => jump(i);
    list.append(button);
  });
  renderPatterns();
}
function renderPatterns() {
  const root = $("patterns");
  root.replaceChildren();
  const groups =
    size === 3
      ? [
          [1, 2, 3],
          [4, 5, 6],
          [7, 8],
        ]
      : $("pattern-kind").value === "ranked"
        ? [
            [1, 2, 3, 4, 5, 6, 7],
            [8, 9, 10, 11, 12, 13, 14, 15],
          ]
        : [
            [1, 2, 5, 6, 9, 10],
            [3, 4, 7, 8, 11, 12],
            [13, 14, 15],
          ];
  for (const group of groups) {
    const section = document.createElement("section");
    const label = document.createElement("p");
    label.textContent = `{ ${group.join(", ")} }`;
    const board = document.createElement("div");
    board.className = "pattern-board";
    board.style.setProperty("--n", size);
    for (const value of states[step] || []) {
      const cell = document.createElement("span");
      cell.textContent =
        value === 0 ? "□" : group.includes(value) ? value : "·";
      cell.className = group.includes(value) ? "included" : "";
      board.append(cell);
    }
    section.append(label, board);
    root.append(section);
  }
}
function renderComparison() {
  const table = $("comparison-body");
  table.replaceChildren();
  const selected = importedResults[Number($("result-list").value)];
  const sameProblem = (data) =>
    selected &&
    data.goal &&
    selected.data.goal &&
    JSON.stringify(data.states[0]) ===
      JSON.stringify(selected.data.states[0]) &&
    JSON.stringify(data.goal) === JSON.stringify(selected.data.goal);
  const comparable = importedResults.filter((record) =>
    sameProblem(record.data),
  );
  $("comparison-note").textContent = comparable.length
    ? "仅列出与当前导入结果初始状态、声明目标完全相同的记录。"
    : "请导入至少一条包含 goal 的 JSON 结果，再选择该结果。";
  for (const { data, label } of comparable) {
    const row = document.createElement("tr");
    for (const value of [
      label,
      data.algorithm || "未提供",
      data.heuristic || "未提供",
      data.states.length - 1,
      data.timeMs ?? "—",
      data.generated ?? "—",
      data.expanded ?? "—",
    ]) {
      const cell = document.createElement("td");
      cell.textContent = value;
      row.append(cell);
    }
    table.append(row);
  }
  renderCharts(comparable);
}
const charts = document.createElement("div");
charts.id = "charts";
charts.className = "charts";
$("comparison-body").parentElement.parentElement.after(charts);
function renderCharts(records) {
  charts.replaceChildren();
  for (const [key, title] of [
    ["expanded", "扩展节点"],
    ["timeMs", "求解耗时 / ms"],
  ]) {
    const section = document.createElement("section");
    const heading = document.createElement("h3");
    heading.textContent = title;
    section.append(heading);
    // Time bars share a scale only with explicit matching measurement provenance.
    const reference = records[0]?.data;
    const signature = (data) =>
      JSON.stringify([
        data.source,
        data.environment,
        data.timing,
        data.warmupRuns,
        data.measuredRuns,
      ]);
    const timeCompatible =
      reference &&
      reference.source === "Java" &&
      reference.environment &&
      reference.timing === "wall-median" &&
      Number.isSafeInteger(reference.warmupRuns) &&
      Number.isSafeInteger(reference.measuredRuns) &&
      records.every(({ data }) => signature(data) === signature(reference));
    if (key === "timeMs" && !timeCompatible) {
      const note = document.createElement("p");
      note.textContent =
        "耗时图需要相同 Java 环境、墙钟中位数计时及预热/测量轮数。";
      section.append(note);
      charts.append(section);
      continue;
    }
    const values = records.filter(({ data }) => Number.isFinite(data[key]));
    const max = Math.max(1, ...values.map(({ data }) => data[key]));
    for (const { data, label } of values) {
      const row = document.createElement("div");
      row.className = "chart-row";
      const caption = document.createElement("span");
      caption.textContent = `${label} / ${data.algorithm || "?"} / ${data.heuristic || "?"} · ${Number(data[key]).toLocaleString()}`;
      const track = document.createElement("div");
      track.className = "bar-track";
      const fill = document.createElement("div");
      fill.className = "bar-fill";
      fill.style.width = `${(data[key] / max) * 100}%`;
      track.append(fill);
      row.append(caption, track);
      section.append(row);
    }
    charts.append(section);
  }
}
function downloadFile(name, content, type) {
  const url = URL.createObjectURL(new Blob([content], { type }));
  const link = document.createElement("a");
  link.href = url;
  link.download = name;
  link.hidden = true;
  // Attach before activation for browsers that do not download detached anchors.
  document.body.append(link);
  link.click();
  link.remove();
  // Give the browser time to consume the Blob before releasing its URL.
  setTimeout(() => URL.revokeObjectURL(url), 60000);
}
function csvCell(value) {
  const text = String(value ?? "");
  return (
    '"' +
    (/^[=+\-@\t\r]/.test(text) ? "'" : "") +
    text.replaceAll('"', '""') +
    '"'
  );
}
function exportCsv(records) {
  const keys = [
    "name",
    "algorithm",
    "heuristic",
    "length",
    "timeMs",
    "generated",
    "expanded",
    "source",
    "environment",
    "timing",
    "warmupRuns",
    "measuredRuns",
    "sourceRevision",
  ];
  return (
    "\uFEFF" +
    [
      keys.map(csvCell).join(","),
      ...records.map(({ data, label }) =>
        keys
          .map((key) =>
            csvCell(
              key === "name"
                ? label
                : key === "length"
                  ? data.states.length - 1
                  : data[key],
            ),
          )
          .join(","),
      ),
    ].join("\r\n")
  );
}
const downloads = document.createElement("div");
downloads.className = "moves";
for (const [label, handler] of [
  [
    "下载全部结果 JSON",
    () =>
      downloadFile(
        "npuzzle-results.json",
        JSON.stringify(
          { schemaVersion: 1, results: importedResults.map((r) => r.data) },
          null,
          2,
        ),
        "application/json",
      ),
  ],
  [
    "下载实验记录 CSV",
    () =>
      downloadFile(
        "npuzzle-experiments.csv",
        exportCsv(importedResults),
        "text/csv;charset=utf-8",
      ),
  ],
]) {
  const button = document.createElement("button");
  button.textContent = label;
  button.onclick = handler;
  downloads.append(button);
}
charts.after(downloads);
$("result-list").onchange = (e) => {
  showImported(Number(e.target.value));
  renderComparison();
};
$("pattern-kind").onchange = renderPatterns;
$("file").multiple = true;
$("file").accept = ".json,.txt,application/json,text/plain";
$("file").onchange = async (e) => {
  const pending = [];
  try {
    for (const file of e.target.files) {
      if (file.size > 5_000_000) throw new Error("单个文件请小于 5 MB");
      for (const [i, data] of parseResults(
        await file.text(),
        file.name,
      ).entries())
        pending.push({ data, label: `${file.name} · ${i + 1}` });
    }
    if (importedResults.length + pending.length > 200)
      throw new Error("最多保留 200 条结果，请刷新页面后分批查看");
    for (const record of pending) {
      const option = document.createElement("option");
      option.value = importedResults.length;
      option.textContent = record.label;
      importedResults.push(record);
      $("result-list").append(option);
    }
    if (pending.length) {
      $("result-list").disabled = false;
      $("result-list").value = importedResults.length - pending.length;
      showImported(Number($("result-list").value));
      renderComparison();
      $("message").textContent =
        `已验证并导入 ${pending.length} 条路径。文件仅在本机读取，不上传。`;
    }
  } catch (error) {
    $("message").textContent = `导入失败：${error.message}`;
  }
  e.target.value = "";
};
if (window.NPuzzleExamples) {
  for (const [i, data] of window.NPuzzleExamples.results.entries()) {
    validate(data);
    const record = {
      data,
      label: `Java / ${data.name || i + 1} / ${data.heuristic || "?"}`,
    };
    const option = document.createElement("option");
    option.value = importedResults.length;
    option.textContent = record.label;
    importedResults.push(record);
    $("result-list").append(option);
  }
  $("result-list").disabled = false;
  $("result-list").value = "0";
  showImported(0);
  renderComparison();
}
