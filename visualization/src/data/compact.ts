import { ALGORITHM_TRAITS, isPrunedStatus, HEURISTIC_QUALITY_CODE, STATUS_CODE, type CompactTrace, type RawRecord, type RecordDetails } from "./types";

/** Converts a validated record into typed arrays plus the derived indexes the replay reads every frame. */
export function compactRecord(record: RawRecord): RecordDetails {
  const { nodes, frames, solution } = record.trace;
  const cells = record.goal.length;
  const size = Math.sqrt(cells);
  const nodeCount = nodes.length;
  const frameCount = frames.length;
  const depthFirst = ALGORITHM_TRAITS[record.algorithm].depthFirst;

  const boards = new Uint8Array(nodeCount * cells);
  const parent = new Int32Array(nodeCount);
  const g = new Int32Array(nodeCount);
  const h = new Int32Array(nodeCount);
  const heuristicQuality = new Uint8Array(nodeCount);
  const status = new Uint8Array(nodeCount);
  const reference = new Int32Array(nodeCount).fill(-1);
  const replacedBy = new Int32Array(nodeCount).fill(-1);
  const prunedPrefix = new Int32Array(nodeCount + 1);
  const boundMinF = new Int32Array(nodeCount);
  const boundMinId = new Int32Array(nodeCount);
  const roots: number[] = [];

  let minF = -1;
  let minId = -1;
  for (let id = 0; id < nodeCount; id++) {
    const node = nodes[id];
    boards.set(node.board, id * cells);
    parent[id] = node.parent ?? -1;
    g[id] = node.g;
    h[id] = node.h ?? -1;
    heuristicQuality[id] = HEURISTIC_QUALITY_CODE[node.heuristicQuality];
    const code = STATUS_CODE[node.status];
    status[id] = code;
    if (node.reference !== undefined) reference[id] = node.reference;
    if (node.status === "replaced") replacedBy[node.reference!] = id;
    prunedPrefix[id + 1] = prunedPrefix[id] + (isPrunedStatus(code) ? 1 : 0);
    if (node.parent === null) {
      roots.push(id);
      minF = minId = -1;
    }
    if (node.status === "bound-pruned" && node.f !== null && (minF < 0 || node.f < minF)) {
      minF = node.f;
      minId = id;
    }
    boundMinF[id] = minF;
    boundMinId[id] = minId;
  }

  const visitFrame = new Int32Array(nodeCount).fill(-1);
  const frameCurrent = new Int32Array(frameCount);
  const frameChildStart = new Int32Array(frameCount);
  const frameChildCount = new Uint8Array(frameCount);
  const frameIteration = new Int32Array(frameCount);
  const frameBound = new Int32Array(frameCount);
  const iterationFirstFrame = new Int32Array(roots.length);
  const shotStart = new Int32Array(frameCount);
  let nextGenerated = 0;
  let shot = 1;
  frames.forEach((frame, index) => {
    if (index === 0 || frame.iteration !== frames[index - 1].iteration) {
      iterationFirstFrame[frame.iteration] = index;
      nextGenerated++;
    }
    frameCurrent[index] = frame.current;
    visitFrame[frame.current] = index;
    frameChildStart[index] = nextGenerated;
    frameChildCount[index] = frame.children.length;
    nextGenerated += frame.children.length;
    frameIteration[index] = frame.iteration;
    frameBound[index] = frame.bound ?? -1;
    shotStart[index] = shot;
    shot += frame.found ? 2 : frame.children.length + 2;
  });

  const trace: CompactTrace = {
    size,
    cells,
    nodeCount,
    frameCount,
    depthFirst,
    pathCostOnly: ALGORITHM_TRAITS[record.algorithm].pathCostOnly,
    boards,
    parent,
    g,
    h,
    heuristicQuality,
    status,
    reference,
    visitFrame,
    replacedBy,
    frameCurrent,
    frameChildStart,
    frameChildCount,
    frameIteration,
    frameBound,
    roots: Int32Array.from(roots),
    iterationFirstFrame,
    shotStart,
    shotCount: shot,
    prunedPrefix,
    boundMinF,
    boundMinId,
    solution: Int32Array.from(solution),
  };
  return {
    algorithm: record.algorithm,
    heuristic: record.heuristic,
    size,
    goal: record.goal,
    states: record.states,
    timeMs: record.timeMs,
    generated: record.generated,
    expanded: record.expanded,
    source: typeof record.source === "string" ? record.source : undefined,
    sourceRevision: typeof record.sourceRevision === "string" ? record.sourceRevision : undefined,
    timing: typeof record.timing === "string" ? record.timing : undefined,
    sourceFile: typeof record.sourceFile === "string" ? record.sourceFile : undefined,
    exportedAt: typeof record.exportedAt === "string" ? record.exportedAt : undefined,
    environment: typeof record.environment === "string" ? record.environment : undefined,
    trace,
  };
}

export function traceBuffers(trace: CompactTrace): ArrayBuffer[] {
  return Object.values(trace)
    .filter((value): value is Uint8Array | Int32Array => ArrayBuffer.isView(value))
    .map((view) => view.buffer as ArrayBuffer);
}
