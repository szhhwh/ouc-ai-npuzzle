import { describe, expect, it } from "vitest";
import { compactRecord } from "@/data/compact";
import { ALGORITHM_TRAITS, isPrunedStatus, STATUS_CODE, type LoadedRecord, type RawRecord } from "@/data/types";
import { validateRecord } from "@/data/validate";
import { narrate } from "@/features/search/narration";
import { sceneAt } from "@/replay/model";
import ida from "./fixtures/ida.json";
import nida from "./fixtures/nida.json";

function loaded(raw: RawRecord): LoadedRecord {
  return { ...compactRecord(validateRecord(raw)), id: "test", name: "test" };
}

// A real NIDA*/ZERO trace needs a parity-aligned initial bound of 1 despite h(root) = 0.
const zeroNida: RawRecord = {
  algorithm: "NIDA*", heuristic: "ZERO", timeMs: 0, generated: 3, expanded: 1,
  states: [[1, 2, 3, 4, 5, 6, 7, 0, 8], [1, 2, 3, 4, 5, 6, 7, 8, 0]],
  goal: [1, 2, 3, 4, 5, 6, 7, 8, 0],
  trace: {
    complete: true,
    nodes: [
      { id: 0, parent: null, board: [1, 2, 3, 4, 5, 6, 7, 0, 8], g: 0, h: 0, f: 0, heuristicQuality: "exact", status: "frontier" },
      { id: 1, parent: 0, board: [1, 2, 3, 4, 0, 6, 7, 5, 8], g: 1, h: 0, f: 1, heuristicQuality: "exact", status: "frontier" },
      { id: 2, parent: 0, board: [1, 2, 3, 4, 5, 6, 0, 7, 8], g: 1, h: 0, f: 1, heuristicQuality: "exact", status: "frontier" },
      { id: 3, parent: 0, board: [1, 2, 3, 4, 5, 6, 7, 8, 0], g: 1, h: 0, f: 1, heuristicQuality: "exact", status: "frontier" },
    ],
    frames: [
      { current: 0, children: [1, 2, 3], iteration: 0, bound: 1, found: false },
      { current: 3, children: [], iteration: 0, bound: 1, found: true },
    ],
    solution: [0, 3],
  },
};

describe("trace traits and narration", () => {
  it("classifies every stable status independently from its numeric ordering", () => {
    expect(isPrunedStatus(STATUS_CODE.frontier)).toBe(false);
    expect(isPrunedStatus(STATUS_CODE.replaced)).toBe(false);
    for (const status of ["closed-pruned", "frontier-pruned", "cycle-pruned", "bound-pruned", "transposition-pruned"] as const) {
      expect(isPrunedStatus(STATUS_CODE[status])).toBe(true);
    }
    expect(ALGORITHM_TRAITS.Dijkstra).toMatchObject({ depthFirst: false, pathCostOnly: true });
    expect(ALGORITHM_TRAITS["IDA*"].parityAlignedBound).toBe(false);
    expect(ALGORITHM_TRAITS["NIDA*"].parityAlignedBound).toBe(true);
  });

  it("narrates the recorded initial bound rather than assuming it equals h(root)", () => {
    const record = loaded(zeroNida);
    const narration = narrate(record, sceneAt(record.trace, 0), null);
    expect(narration.title).toContain("初始界限为 1");
    expect(narration.body).toContain("h = 0");
  });

  it("uses recorded restart bounds without equating NIDA* bounds to the minimum exceeded f", () => {
    for (const raw of [...ida.results, ...nida.results] as RawRecord[]) {
      const record = loaded(raw);
      const { trace } = record;
      for (let iteration = 1; iteration < trace.roots.length; iteration++) {
        const scene = sceneAt(trace, trace.shotStart[trace.iterationFirstFrame[iteration]]);
        const narration = narrate(record, scene, null);
        expect(narration.title).toContain(`界限提升至 ${scene.bound}`);
        expect(narration.body).toContain(`将界限提高至 ${scene.bound}`);
        expect(narration.body).not.toContain("最小估值为");
        if (ALGORITHM_TRAITS[record.algorithm].parityAlignedBound) expect(narration.body).toContain("奇偶性对齐");
      }
    }
  });

  it("does not promise a pending DFS child will be visited or has already passed a bound check", () => {
    const record = loaded(zeroNida);
    const scene = sceneAt(record.trace, record.trace.shotStart[0] + 1);
    expect(record.trace.visitFrame[scene.focus]).toBe(-1);
    const narration = narrate(record, scene, null);
    expect(narration.body).toContain("是否访问以随后的搜索帧为准");
    expect(narration.body).not.toContain("未超界限");
  });
});
