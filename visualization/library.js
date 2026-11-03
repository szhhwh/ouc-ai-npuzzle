"use strict";
(() => {
  const DEFAULT_NAME = "Untitled Problem";
  const ALGORITHMS = new Set(["A*", "Dijkstra", "IDA*", "NIDA*"]);
  const HEURISTICS = new Set(["MANHATTAN", "MISPLACED", "DISJOINT_PATTERN", "ZERO"]);
  const STATUSES = new Set([
    "frontier", "replaced", "closed-pruned", "frontier-pruned",
    "cycle-pruned", "bound-pruned", "transposition-pruned",
  ]);
  const listeners = new Set();
  const checked = new Set();
  const previews = new Map();
  const previewFailures = new Set();
  let previewLoading = false;
  let databasePromise;
  let metadata = [];
  let current = null;
  let queue = Promise.resolve();
  let selectionEpoch = 0;
  let pending = 0;
  let elements = null;
  let confirmation = null;
  let statusMessage = "";
  let statusError = false;
  let pointerDown = false;
  let renderPending = false;
  let controlsPending = false;

  const nameOf = (name) => typeof name === "string" && name.trim() ? name.trim() : DEFAULT_NAME;
  const integer = (value) => Number.isSafeInteger(value) && value >= 0;
  const equalBoard = (left, right) => left.length === right.length && left.every((tile, i) => tile === right[i]);
  const accepted = (node) => node.status === "frontier" || node.status === "replaced";

  function requireValue(condition, message) {
    if (!condition) throw new Error(message);
  }

  function validateBoard(board, length, label) {
    requireValue(Array.isArray(board) && board.length === length, `${label}的棋盘尺寸不一致。`);
    let seen = 0;
    for (const tile of board) {
      requireValue(Number.isInteger(tile) && tile >= 0 && tile < length && !(seen & (1 << tile)), `${label}必须恰好包含 0 至 ${length - 1}。`);
      seen |= 1 << tile;
    }
  }

  function legalMove(before, after, size) {
    const from = before.indexOf(0);
    const to = after.indexOf(0);
    if (Math.abs(Math.floor(from / size) - Math.floor(to / size)) + Math.abs(from % size - to % size) !== 1) return false;
    for (let i = 0; i < before.length; i++) {
      if (after[i] !== (i === from ? before[to] : i === to ? 0 : before[i])) return false;
    }
    return true;
  }

  // This checks the supplied search history; it never searches for a solution.
  function validateRecord(record) {
    requireValue(record && typeof record === "object" && !Array.isArray(record), "问题记录必须是对象。 ");
    requireValue(ALGORITHMS.has(record.algorithm), "不支持的算法名称。 ");
    requireValue(HEURISTICS.has(record.heuristic), "不支持的启发函数名称。 ");
    requireValue(record.algorithm !== "Dijkstra" || record.heuristic === "ZERO", "Dijkstra 记录必须使用 ZERO 启发函数。 ");
    requireValue(record.name == null || typeof record.name === "string", "问题名称必须是文字。 ");
    requireValue(Array.isArray(record.states) && record.states.length > 0, "记录缺少完整解路径。 ");
    const length = record.states[0]?.length;
    requireValue(length === 9 || length === 16, "只支持 3×3 或 4×4 棋盘。 ");
    const size = Math.sqrt(length);
    validateBoard(record.goal, length, "目标");
    record.states.forEach((board, i) => {
      validateBoard(board, length, `路径第 ${i} 步`);
      if (i) requireValue(legalMove(record.states[i - 1], board, size), `路径第 ${i} 步不是合法移动。`);
    });
    requireValue(equalBoard(record.states.at(-1), record.goal), "解路径未到达记录的目标。 ");
    requireValue(typeof record.timeMs === "number" && Number.isFinite(record.timeMs) && record.timeMs >= 0, "运行时间无效。 ");
    requireValue(integer(record.generated) && integer(record.expanded), "搜索计数必须是非负整数。 ");
    const trace = record.trace;
    requireValue(trace && trace.complete === true && Array.isArray(trace.nodes) && trace.nodes.length > 0 && Array.isArray(trace.frames) && trace.frames.length > 0 && Array.isArray(trace.solution), "记录必须包含 complete=true 的完整搜索过程。请重新使用 Java 导出。 ");
    const { nodes, frames, solution } = trace;
    const roots = [];
    nodes.forEach((node, id) => {
      requireValue(node && node.id === id, `搜索节点 ${id} 的编号不连续。`);
      validateBoard(node.board, length, `搜索节点 ${id}`);
      requireValue(integer(node.g) && integer(node.h) && integer(node.f) && node.f === node.g + node.h, `搜索节点 ${id} 的 g/h/f 无效。`);
      requireValue(STATUSES.has(node.status), `搜索节点 ${id} 的状态无效。`);
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
    requireValue(roots[0] === 0 && record.generated === nodes.length - roots.length, "生成节点计数与完整搜索过程不一致。 ");
    const depthFirst = record.algorithm === "IDA*" || record.algorithm === "NIDA*";
    requireValue(depthFirst || roots.length === 1, "该算法不能包含多个搜索根节点。 ");
    requireValue(record.expanded === frames.length - 1, "扩展节点计数与完整搜索过程不一致。 ");
    const generated = new Uint8Array(nodes.length);
    const visited = new Uint8Array(nodes.length);
    let nextGenerated = 0;
    let iteration = -1;
    let iterationRoot = 0;
    let bound;
    frames.forEach((frame, index) => {
      requireValue(frame && integer(frame.current) && frame.current < nodes.length && Array.isArray(frame.children) && typeof frame.found === "boolean", `搜索帧 ${index} 无效。`);
      const node = nodes[frame.current];
      requireValue(integer(frame.iteration), `搜索帧 ${index} 缺少迭代编号。`);
      if (frame.iteration !== iteration) {
        requireValue(frame.iteration === iteration + 1 && roots[frame.iteration] === frame.current && frame.current === nextGenerated, `搜索帧 ${index} 的迭代根节点或生成顺序不一致。`);
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
      requireValue(frame.found === last && (!last || frame.children.length === 0), "搜索过程必须以唯一的成功帧结束。 ");
      requireValue(equalBoard(node.board, record.goal) === frame.found, `搜索帧 ${index} 的目标标记与棋盘不一致。`);
      const blank = node.board.indexOf(0);
      const legalChildren = Number(blank >= size) + Number(blank < length - size) + Number(blank % size > 0) + Number(blank % size < size - 1);
      requireValue(frame.children.length === (frame.found ? 0 : legalChildren), `搜索帧 ${index} 缺少合法生成分支。`);
      let childPositions = 0;
      for (const id of frame.children) {
        requireValue(integer(id) && id === nextGenerated && id < nodes.length && nodes[id].parent === frame.current && generated[id] === 0, `搜索帧 ${index} 的子节点、顺序或完整性无效。`);
        const position = 1 << nodes[id].board.indexOf(0);
        requireValue(!(childPositions & position), `搜索帧 ${index} 包含重复生成分支。`);
        childPositions |= position;
        generated[id] = 1;
        nextGenerated++;
      }
    });
    requireValue(nextGenerated === nodes.length && iteration + 1 === roots.length, "搜索过程遗漏了生成节点或迭代。 ");
    requireValue(solution.length === record.states.length && solution.length > 0, "解路径与搜索节点路径长度不一致。 ");
    solution.forEach((id, step) => {
      requireValue(integer(id) && id < nodes.length && visited[id] === 1 && equalBoard(nodes[id].board, record.states[step]) && nodes[id].g === step, `解路径第 ${step} 步与搜索过程不一致。`);
      requireValue(nodes[id].parent === (step ? solution[step - 1] : null), `解路径第 ${step} 步的父节点不一致。`);
    });
    requireValue(solution[0] === roots.at(-1) && solution.at(-1) === frames.at(-1).current, "解路径未连接最终迭代与成功帧。 ");
    return { ...record, name: nameOf(record.name) };
  }

  function requestResult(request) {
    return new Promise((resolve, reject) => {
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error || new Error("无法读取本地记录。"));
    });
  }

  function openDatabase() {
    if (databasePromise) return databasePromise;
    databasePromise = new Promise((resolve, reject) => {
      requireValue(typeof indexedDB !== "undefined", "此浏览器不支持 IndexedDB，无法持久保存问题。 ");
      requireValue(typeof CompressionStream !== "undefined" && typeof DecompressionStream !== "undefined", "此浏览器不支持 gzip 压缩存储，请使用新版浏览器。 ");
      const request = indexedDB.open("npuzzle-library", 1);
      request.onupgradeneeded = () => {
        const db = request.result;
        db.createObjectStore("records", { keyPath: "id" });
        db.createObjectStore("payloads", { keyPath: "id" });
        db.createObjectStore("settings", { keyPath: "key" });
      };
      request.onsuccess = () => {
        const db = request.result;
        db.onversionchange = () => {
          db.close();
          databasePromise = undefined;
          setStatus("本地问题库已在其他页面升级，请刷新页面。", true);
        };
        resolve(db);
      };
      request.onerror = () => reject(request.error || new Error("无法打开本地问题库。"));
      request.onblocked = () => setStatus("本地问题库被其他页面占用，请关闭其他页面后重试。", true);
    }).catch((error) => {
      databasePromise = undefined;
      throw error;
    });
    return databasePromise;
  }

  function writeTransaction(db, stores, change) {
    return new Promise((resolve, reject) => {
      const transaction = db.transaction(stores, "readwrite");
      transaction.oncomplete = () => resolve();
      transaction.onabort = () => reject(transaction.error || new Error("本地保存失败，未应用此次修改。"));
      transaction.onerror = () => {};
      try {
        change(transaction);
      } catch (error) {
        transaction.abort();
        reject(error);
      }
    });
  }

  async function loadRecord(db, meta) {
    const payload = await requestResult(db.transaction("payloads").objectStore("payloads").get(meta.id));
    requireValue(payload?.blob instanceof Blob, `“${meta.name}”的压缩数据丢失。`);
    let record;
    try {
      const text = await new Response(payload.blob.stream().pipeThrough(new DecompressionStream("gzip"))).text();
      record = validateRecord(JSON.parse(text));
    } catch (error) {
      throw new Error(`无法解压或读取“${meta.name}”：${error.message}`);
    }
    requireValue(meta.size === Math.sqrt(record.goal.length) && meta.algorithm === record.algorithm && meta.heuristic === record.heuristic && meta.moves === record.states.length - 1 && meta.generated === record.generated && meta.expanded === record.expanded && meta.timeMs === record.timeMs && meta.compressedBytes === payload.blob.size, `“${meta.name}”的本地元数据与压缩记录不一致。`);
    return { ...record, id: meta.id, name: meta.name };
  }

  function setStatus(message, error = false) {
    statusMessage = message;
    statusError = error;
    if (!elements) return;
    for (const element of [elements.status, elements.feedback]) {
      element.textContent = message;
      element.hidden = !message || (element === elements.feedback && !error);
      element.dataset.state = error ? "error" : "success";
    }
  }

  function notify() {
    for (const listener of listeners) {
      try { listener(current); }
      catch (error) { console.error("N-Puzzle selection listener failed", error); }
    }
  }

  function publish(record) {
    const previousId = current?.id;
    if (record) previews.set(record.id, record.states[0]);
    current = record;
    render();
    if (previousId !== current?.id) {
      for (const panel of document.querySelectorAll('[role="tabpanel"]')) panel.dispatchEvent(new CustomEvent("npuzzle:pause"));
    }
    notify();
  }

  function sortMetadata(records) {
    return records.sort((a, b) => a.importedAt - b.importedAt || a.id.localeCompare(b.id));
  }

  const ready = (async () => {
    try {
      const db = await openDatabase();
      const transaction = db.transaction(["records", "settings"]);
      const [stored, setting] = await Promise.all([
        requestResult(transaction.objectStore("records").getAll()),
        requestResult(transaction.objectStore("settings").get("active")),
      ]);
      for (const meta of stored) {
        requireValue(typeof meta.id === "string" && typeof meta.name === "string" && meta.name.trim() && (meta.size === 3 || meta.size === 4) && ALGORITHMS.has(meta.algorithm) && HEURISTICS.has(meta.heuristic) && integer(meta.moves) && integer(meta.generated) && integer(meta.expanded) && Number.isFinite(meta.timeMs) && meta.timeMs >= 0 && Number.isFinite(meta.importedAt) && integer(meta.compressedBytes) && meta.compressedBytes > 0, "本地问题库元数据损坏。请检查浏览器存储。 ");
      }
      metadata = sortMetadata(stored);
      const active = metadata.find((meta) => meta.id === setting?.value) || metadata[0];
      if (active) {
        current = await loadRecord(db, active);
        previews.set(active.id, current.states[0]);
        if (active.id !== setting?.value) await writeTransaction(db, ["settings"], (tx) => tx.objectStore("settings").put({ key: "active", value: active.id }));
      }
      render();
      queueMicrotask(notify);
    } catch (error) {
      setStatus(`无法恢复本地问题库：${error.message}`, true);
      render();
      queueMicrotask(notify);
    }
  })();

  function mutate(action) {
    const focused = elements?.rows.contains(document.activeElement) ? document.activeElement : null;
    const focusId = focused?.closest("tr")?.dataset.id;
    const focusAction = focused?.dataset.action;
    pending++;
    updateControls();
    const result = queue.then(() => ready).then(action).catch((error) => {
      setStatus(error.message || "本地问题库操作失败。", true);
      throw error;
    }).finally(() => {
      pending--;
      updateControls();
      if (!pending && elements?.dialog.open && focusId && focusAction) {
        const row = [...elements.rows.children].find((item) => item.dataset.id === focusId);
        const target = row?.querySelector(`[data-action="${focusAction}"]`) || elements.rows.querySelector('[data-action="load"]') || elements.import;
        target.focus({ preventScroll: true });
      }
    });
    queue = result.catch(() => {});
    return result;
  }

  function importFiles(files) {
    const batch = Array.from(files || []);
    return mutate(async () => {
      requireValue(batch.length > 0, "请选择至少一个 JSON 文件。 ");
      setStatus("正在验证并压缩导入文件…");
      const db = await openDatabase();
      const additions = [];
      const importedAt = Date.now();
      for (const file of batch) {
        let artifact;
        try { artifact = JSON.parse(await file.text()); }
        catch (error) { throw new Error(`无法读取 ${file.name || "JSON 文件"}：${error.message}`); }
        requireValue(artifact?.schemaVersion === 2 && Array.isArray(artifact.results) && artifact.results.length > 0, `${file.name || "文件"} 必须是包含完整 results 的 schemaVersion: 2 Java 导出文件。`);
        for (let index = 0; index < artifact.results.length; index++) {
          let record;
          try { record = validateRecord(artifact.results[index]); }
          catch (error) { throw new Error(`${file.name || "文件"}，记录 ${index + 1}：${error.message}`); }
          const blob = await new Response(new Blob([JSON.stringify(record)]).stream().pipeThrough(new CompressionStream("gzip"))).blob();
          const id = crypto.randomUUID();
          const meta = {
            id, name: record.name, size: Math.sqrt(record.goal.length),
            algorithm: record.algorithm, heuristic: record.heuristic,
            moves: record.states.length - 1, generated: record.generated,
            expanded: record.expanded, timeMs: record.timeMs, importedAt,
            compressedBytes: blob.size,
          };
          additions.push({ meta, blob, record });
        }
      }
      setStatus("正在保存压缩问题库…");
      const first = additions[0];
      await writeTransaction(db, ["records", "payloads", "settings"], (tx) => {
        for (const item of additions) {
          tx.objectStore("records").add(item.meta);
          tx.objectStore("payloads").add({ id: item.meta.id, blob: item.blob });
        }
        tx.objectStore("settings").put({ key: "active", value: first.meta.id });
      });
      for (const item of additions) previews.set(item.meta.id, item.record.states[0]);
      selectionEpoch++;
      metadata = sortMetadata([...metadata, ...additions.map((item) => item.meta)]);
      publish({ ...first.record, id: first.meta.id, name: first.meta.name });
      setStatus(`已导入并压缩保存 ${additions.length} 条完整问题记录。`);
      return first.meta.id;
    });
  }

  function select(id) {
    return mutate(async () => {
      if (current?.id === id) return current;
      const meta = metadata.find((item) => item.id === id);
      requireValue(meta, "此问题记录已不存在。 ");
      const epoch = ++selectionEpoch;
      const db = await openDatabase();
      const record = await loadRecord(db, meta);
      if (epoch !== selectionEpoch) return current;
      await writeTransaction(db, ["settings"], (tx) => tx.objectStore("settings").put({ key: "active", value: id }));
      publish(record);
      setStatus(`已载入“${meta.name}”。`);
      return record;
    });
  }

  function rename(id, name) {
    return mutate(async () => {
      requireValue(typeof name === "string", "问题名称必须是文字。 ");
      const previous = metadata.find((item) => item.id === id);
      requireValue(previous, "此问题记录已不存在。 ");
      const next = { ...previous, name: nameOf(name) };
      if (next.name === previous.name) return;
      const db = await openDatabase();
      await writeTransaction(db, ["records"], (tx) => tx.objectStore("records").put(next));
      metadata = metadata.map((item) => item.id === id ? next : item);
      if (current?.id === id) publish({ ...current, name: next.name });
      else render();
      setStatus(`已重命名为“${next.name}”。`);
    });
  }

  function remove(ids) {
    const requested = [...new Set(typeof ids === "string" ? [ids] : Array.from(ids || []))];
    return mutate(async () => {
      requireValue(requested.length > 0, "请先选择要删除的问题。 ");
      requireValue(requested.every((id) => metadata.some((item) => item.id === id)), "待删除的记录已发生变化，请重新选择。 ");
      const removed = new Set(requested);
      const remaining = metadata.filter((item) => !removed.has(item.id));
      const changeActive = !current || removed.has(current.id);
      const nextMeta = changeActive ? remaining[0] : null;
      const epoch = ++selectionEpoch;
      const db = await openDatabase();
      const nextRecord = nextMeta ? await loadRecord(db, nextMeta) : null;
      requireValue(epoch === selectionEpoch, "所选问题已发生变化，请重试。 ");
      await writeTransaction(db, ["records", "payloads", "settings"], (tx) => {
        for (const id of requested) {
          tx.objectStore("records").delete(id);
          tx.objectStore("payloads").delete(id);
        }
        if (changeActive) tx.objectStore("settings").put({ key: "active", value: nextMeta?.id || null });
      });
      metadata = remaining;
      requested.forEach((id) => { checked.delete(id); previews.delete(id); previewFailures.delete(id); });
      if (changeActive) publish(nextRecord);
      else render();
      setStatus(`已删除 ${requested.length} 条问题记录。`);
    });
  }

  function cell(text, className) {
    const element = document.createElement("td");
    if (text !== undefined) element.textContent = text;
    if (className) element.className = className;
    return element;
  }

  function button(text, label, action) {
    const element = document.createElement("button");
    element.type = "button";
    element.textContent = text;
    element.setAttribute("aria-label", label);
    element.addEventListener("click", action);
    return element;
  }

  function boardPreview(meta) {
    const preview = document.createElement("div");
    preview.className = "record-preview";
    preview.dataset.size = String(meta.size);
    const board = previews.get(meta.id);
    if (!board) {
      preview.classList.add("record-preview-placeholder");
      preview.textContent = previewFailures.has(meta.id) ? "—" : "…";
      preview.setAttribute("aria-label", previewFailures.has(meta.id) ? "无法加载初始棋盘" : "正在加载初始棋盘");
      return preview;
    }
    preview.style.setProperty("--n", meta.size);
    preview.setAttribute("role", "img");
    preview.setAttribute("aria-label", `初始棋盘 ${meta.size}×${meta.size}：${board.map((tile) => tile || "空格").join("、")}`);
    preview.title = "初始棋盘";
    for (const value of board) {
      const tile = document.createElement("span");
      tile.className = value ? "record-preview-tile" : "record-preview-tile blank";
      tile.textContent = value || "";
      tile.setAttribute("aria-hidden", "true");
      preview.append(tile);
    }
    return preview;
  }

  function refreshPreview(id) {
    const meta = metadata.find((item) => item.id === id);
    const row = [...elements.rows.children].find((item) => item.dataset.id === id);
    const previous = row?.querySelector(".record-preview");
    if (meta && previous) previous.replaceWith(boardPreview(meta));
  }

  async function loadPreviews() {
    if (previewLoading) return;
    previewLoading = true;
    try {
      await ready;
      const db = await openDatabase();
      for (const meta of metadata) {
        if (!elements.dialog.open) break;
        if (previews.has(meta.id)) continue;
        previewFailures.delete(meta.id);
        try {
          const payload = await requestResult(db.transaction("payloads").objectStore("payloads").get(meta.id));
          requireValue(payload?.blob instanceof Blob, "压缩数据丢失。");
          const data = await new Response(payload.blob.stream().pipeThrough(new DecompressionStream("gzip"))).json();
          const board = data.states?.[0];
          validateBoard(board, meta.size * meta.size, "初始");
          if (metadata.some((item) => item.id === meta.id)) previews.set(meta.id, board);
        } catch (error) {
          previewFailures.add(meta.id);
          setStatus(`无法加载“${meta.name}”的棋盘预览：${error.message}`, true);
        }
        refreshPreview(meta.id);
      }
    } finally {
      previewLoading = false;
    }
  }

  function render() {
    if (!elements) return;
    if (pointerDown) { renderPending = true; return; }
    const focused = elements.rows.contains(document.activeElement) ? document.activeElement : null;
    const focusedId = focused?.closest("tr")?.dataset.id;
    const focusedAction = focused?.dataset.action;
    const fragment = document.createDocumentFragment();
    for (const meta of metadata) {
      const row = document.createElement("tr");
      const isCurrent = current?.id === meta.id;
      row.className = `record-row${isCurrent ? " record-current" : ""}`;
      row.dataset.id = meta.id;
      if (isCurrent) row.setAttribute("aria-current", "true");
      const selectionCell = cell();
      const checkbox = document.createElement("input");
      checkbox.type = "checkbox";
      checkbox.dataset.action = "check";
      checkbox.checked = checked.has(meta.id);
      checkbox.setAttribute("aria-label", `选择 ${meta.name}`);
      checkbox.addEventListener("change", () => {
        if (checkbox.checked) checked.add(meta.id);
        else checked.delete(meta.id);
        updateControls();
      });
      selectionCell.append(checkbox);
      const identityCell = cell();
      const identity = document.createElement("div");
      identity.className = "record-identity";
      identityCell.append(identity);
      identity.append(boardPreview(meta));
      const input = document.createElement("input");
      input.type = "text";
      input.className = "record-name";
      input.dataset.action = "rename";
      input.value = meta.name;
      input.setAttribute("aria-label", `重命名 ${meta.name}`);
      let editing = false;
      let cancelled = false;
      let keyboardCommit = false;
      input.addEventListener("focus", () => { editing = true; cancelled = false; });
      input.addEventListener("keydown", (event) => {
        if (event.isComposing) return;
        if (event.key === "Escape" && confirmation) {
          event.preventDefault();
          event.stopPropagation();
          cancelDeletion();
          return;
        }
        if (event.key === "Enter") { event.preventDefault(); keyboardCommit = true; input.blur(); }
        else if (event.key === "Escape") {
          event.preventDefault();
          event.stopPropagation();
          cancelled = true;
          input.value = meta.name;
          input.blur();
          row.querySelector('[data-action="load"]').focus();
        }
      });
      input.addEventListener("blur", () => {
        if (!editing) return;
        editing = false;
        const changed = !cancelled && nameOf(input.value) !== meta.name;
        const operation = changed ? rename(meta.id, input.value).catch(() => { input.value = meta.name; }) : Promise.resolve();
        if (!changed) input.value = meta.name;
        if (keyboardCommit) {
          keyboardCommit = false;
          operation.then(() => {
            if (!elements.dialog.open) return;
            const nextRow = [...elements.rows.children].find((item) => item.dataset.id === meta.id);
            nextRow?.querySelector('[data-action="rename"]').focus();
          });
        }
      });
      identity.append(input);
      if (isCurrent) {
        const badge = document.createElement("span");
        badge.className = "record-badge";
        badge.textContent = "当前";
        identity.append(badge);
      }
      const algorithm = cell(meta.algorithm);
      const heuristic = document.createElement("div");
      heuristic.className = "record-meta";
      heuristic.textContent = meta.heuristic;
      algorithm.append(heuristic);
      const actionsCell = cell();
      const actions = document.createElement("div");
      actions.className = "record-actions";
      actionsCell.append(actions);
      const load = button(isCurrent ? "已载入" : "载入", `载入 ${meta.name}`, () => select(meta.id).catch(() => {}));
      load.dataset.action = "load";
      const deletion = button("删除", `删除 ${meta.name}`, () => confirmDeletion([meta.id]));
      deletion.dataset.action = "delete";
      actions.append(load, deletion);
      row.append(selectionCell, identityCell, cell(`${meta.size}×${meta.size}`), algorithm, cell(String(meta.moves)), cell(`${meta.expanded.toLocaleString()} / ${meta.generated.toLocaleString()}`), cell(`${meta.timeMs.toLocaleString(undefined, { maximumFractionDigits: 2 })} ms`), cell(new Date(meta.importedAt).toLocaleString()), actionsCell);
      fragment.append(row);
    }
    elements.rows.replaceChildren(fragment);
    elements.empty.hidden = metadata.length !== 0;
    elements.name.textContent = current?.name || "选择问题";
    elements.count.textContent = String(metadata.length);
    elements.recordCount.textContent = String(metadata.length);
    updateControls();
    if (focusedId && focusedAction && !pending) {
      const row = [...elements.rows.children].find((item) => item.dataset.id === focusedId);
      const target = row?.querySelector(`[data-action="${focusedAction}"]`);
      if (target) target.focus({ preventScroll: true });
      else if (elements.dialog.open) elements.close.focus();
    }
  }

  function updateControls() {
    if (!elements) return;
    if (pointerDown) { controlsPending = true; return; }
    const busy = pending > 0;
    elements.dialog.setAttribute("aria-busy", String(busy));
    elements.import.disabled = busy;
    elements.files.disabled = busy;
    elements.delete.disabled = busy || checked.size === 0;
    elements.selectAll.disabled = busy || metadata.length === 0;
    elements.selectAll.checked = metadata.length > 0 && checked.size === metadata.length;
    elements.selectAll.indeterminate = checked.size > 0 && checked.size < metadata.length;
    elements.selectionCount.textContent = checked.size ? `已选择 ${checked.size} 条` : "未选择";
    elements.apply.disabled = busy || !confirmation;
    for (const input of elements.rows.querySelectorAll("input, button")) input.disabled = busy;
  }

  function confirmDeletion(ids) {
    if (pending) { queue.then(() => confirmDeletion(ids)); return; }
    confirmation = [...ids];
    elements.confirm.hidden = false;
    elements.summary.textContent = `确定删除 ${ids.length} 条问题记录？此操作无法撤销。`;
    updateControls();
    elements.cancel.focus();
  }

  function cancelDeletion() {
    confirmation = null;
    elements.confirm.hidden = true;
    updateControls();
  }

  function open() {
    if (!elements) return;
    for (const panel of document.querySelectorAll('[role="tabpanel"]')) panel.dispatchEvent(new CustomEvent("npuzzle:pause"));
    if (!elements.dialog.open) elements.dialog.showModal();
    const target = metadata.length || elements.import.disabled ? elements.close : elements.import;
    target.focus({ preventScroll: true });
    elements.rows.closest(".library-table-viewport").scrollLeft = 0;
    loadPreviews().catch((error) => setStatus(`无法加载棋盘预览：${error.message}`, true));
  }

  function attach() {
    const byId = (id) => document.getElementById(id);
    elements = {
      open: byId("library-open"), name: byId("current-record-name"), count: byId("library-count"),
      dialog: byId("library-dialog"), close: byId("library-close"), import: byId("library-import"),
      files: byId("library-files"), delete: byId("library-delete"), selectAll: byId("library-select-all"),
      selectionCount: byId("library-selection-count"), recordCount: byId("library-record-count"),
      rows: byId("library-rows"), empty: byId("library-empty"), status: byId("library-status"),
      feedback: byId("library-feedback"), confirm: byId("library-delete-confirm"),
      cancel: byId("library-delete-cancel"), apply: byId("library-delete-apply"), summary: byId("library-delete-summary"),
    };
    if (Object.values(elements).some((element) => !element)) {
      console.error("N-Puzzle library manager markup is incomplete.");
      elements = null;
      return;
    }
    // A blur commit must not remove or disable the destination before its click fires.
    elements.dialog.addEventListener("pointerdown", () => { pointerDown = true; }, true);
    const finishPointer = () => setTimeout(() => {
      pointerDown = false;
      const repaint = renderPending, update = controlsPending;
      renderPending = controlsPending = false;
      if (repaint) render();
      else if (update) updateControls();
    }, 0);
    document.addEventListener("pointerup", finishPointer, true);
    document.addEventListener("pointercancel", finishPointer, true);
    window.addEventListener("blur", finishPointer);
    elements.open.addEventListener("click", open);
    elements.close.addEventListener("click", () => elements.dialog.close());
    elements.dialog.addEventListener("close", () => { cancelDeletion(); elements.open.focus(); });
    elements.dialog.addEventListener("cancel", (event) => {
      if (confirmation) { event.preventDefault(); cancelDeletion(); elements.close.focus(); }
    });
    elements.dialog.addEventListener("keydown", (event) => {
      event.stopPropagation();
      if (event.key === "Escape" && !event.defaultPrevented) {
        event.preventDefault();
        if (confirmation) { cancelDeletion(); elements.close.focus(); }
        else elements.dialog.close();
      }
    });
    elements.import.addEventListener("click", () => elements.files.click());
    elements.files.addEventListener("change", () => {
      const files = Array.from(elements.files.files || []);
      elements.files.value = "";
      if (files.length) importFiles(files).catch(() => {});
    });
    elements.selectAll.addEventListener("change", () => {
      checked.clear();
      if (elements.selectAll.checked) metadata.forEach((meta) => checked.add(meta.id));
      render();
    });
    elements.delete.addEventListener("click", () => confirmDeletion([...checked]));
    elements.cancel.addEventListener("click", () => { cancelDeletion(); elements.delete.focus(); });
    elements.apply.addEventListener("click", () => {
      if (!confirmation) return;
      const ids = [...confirmation];
      cancelDeletion();
      remove(ids).then(() => {
        if (elements.dialog.open) (elements.rows.querySelector('[data-action="load"]') || elements.import).focus();
      }).catch(() => { if (elements.dialog.open) elements.close.focus(); });
    });
    setStatus(statusMessage, statusError);
    render();
  }

  window.NPuzzleLibrary = Object.freeze({
    ready,
    get current() { return current; },
    get records() { return metadata.map((meta) => ({ ...meta })); },
    open,
    subscribe(listener) {
      if (typeof listener !== "function") throw new TypeError("Selection listener must be a function.");
      listeners.add(listener);
      try { listener(current); }
      catch (error) { console.error("N-Puzzle selection listener failed", error); }
      return () => listeners.delete(listener);
    },
    importFiles, select, rename, remove,
  });
  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", attach, { once: true });
  else attach();
})();
