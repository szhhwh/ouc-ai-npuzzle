import { describe, expect, it } from "vitest";
import { compactRecord } from "@/data/compact";
import type { CompactTrace, RawRecord } from "@/data/types";
import { validateRecord } from "@/data/validate";
import { layoutTree, SLOT } from "@/replay/layout";
import { countersAt, frontierAt, isOpen, nextBoundAt, sceneAt, type Scene } from "@/replay/model";
import astar from "./fixtures/astar.json";
import ida from "./fixtures/ida.json";
import nida from "./fixtures/nida.json";

const records = [...astar.results, ...ida.results, ...nida.results] as unknown as RawRecord[];
const clone = (record: RawRecord): RawRecord => structuredClone(record);

describe("validateRecord", () => {
  it("accepts every Java export fixture", () => {
    for (const record of records) expect(() => validateRecord(record)).not.toThrow();
  });

  it("rejects a solution path with an illegal move", () => {
    const record = clone(records[0]);
    [record.states[2][0], record.states[2][1]] = [record.states[2][1], record.states[2][0]];
    expect(() => validateRecord(record)).toThrow(/不是合法移动|棋盘/);
  });

  it("rejects counts that disagree with the trace", () => {
    const record = clone(records[0]);
    record.generated += 1;
    expect(() => validateRecord(record)).toThrow("生成节点计数");
  });

  it("rejects traces whose frames skip a generated successor", () => {
    const record = clone(records[0]);
    record.trace.frames[1].children.pop();
    expect(() => validateRecord(record)).toThrow();
  });
});

function* everyPick(trace: CompactTrace) {
  for (let round = 0; round < trace.frameCount; round++) yield sceneAt(trace, trace.shotStart[round]);
}

describe("replay model", () => {
  it("walks every shot in order: ready, then pick / children / merge per round, ending on the goal", () => {
    for (const record of records) {
      const { trace } = compactRecord(validateRecord(record));
      let previous: Scene | null = null;
      for (let shot = 0; shot < trace.shotCount; shot++) {
        const scene = sceneAt(trace, shot);
        expect(scene.shot).toBe(shot);
        if (previous) expect(scene.revealedEnd).toBeGreaterThanOrEqual(previous.rangeStart === scene.rangeStart ? previous.revealedEnd : scene.rangeStart);
        if (scene.phase === "child") expect(scene.focus).toBe(scene.childStart + scene.child);
        previous = scene;
      }
      expect(sceneAt(trace, 0).phase).toBe("ready");
      expect(previous!.phase).toBe("goal");
      expect(previous!.current).toBe(trace.solution.at(-1));
    }
  });

  it("final counters equal the Java expanded and generated counts", () => {
    for (const record of records) {
      const details = compactRecord(validateRecord(record));
      const counters = countersAt(details.trace, sceneAt(details.trace, details.trace.shotCount - 1));
      expect(counters.expanded).toBe(details.expanded);
      expect(counters.generated).toBe(details.generated);
    }
  });

  it("A* always picks an open node with the smallest (f, g), matching the Java priority queue", () => {
    for (const record of astar.results as unknown as RawRecord[]) {
      const { trace } = compactRecord(validateRecord(record));
      for (const scene of everyPick(trace)) {
        expect(isOpen(trace, scene, scene.current)).toBe(true);
        // Ties only reorder equal (f, g), so the pick heads the queue exactly when it has the smallest (f, g).
        expect(frontierAt(trace, scene, 1).top[0].id).toBe(scene.current);
      }
    }
  });

  it("IDA* picks open nodes within the bound and the next bound is the smallest f that exceeded it", () => {
    for (const record of [...ida.results, ...nida.results] as unknown as RawRecord[]) {
      const { trace } = compactRecord(validateRecord(record));
      for (const scene of everyPick(trace)) {
        expect(isOpen(trace, scene, scene.current)).toBe(true);
        expect(trace.g[scene.current] + trace.h[scene.current]).toBeLessThanOrEqual(scene.bound);
      }
      for (let iteration = 0; iteration + 1 < trace.roots.length; iteration++) {
        const lastFrame = trace.iterationFirstFrame[iteration + 1] - 1;
        const end = sceneAt(trace, trace.shotStart[lastFrame + 1] - 1);
        expect(end.iteration).toBe(iteration);
        expect(nextBoundAt(trace, end)?.f).toBe(trace.frameBound[lastFrame + 1]);
      }
    }
  });

  it("lays out parents centred over their children with distinct leaf slots", () => {
    for (const record of records) {
      const { trace } = compactRecord(validateRecord(record));
      for (const showPruned of [false, true]) {
        const scene = sceneAt(trace, trace.shotCount - 1);
        const layout = layoutTree(trace, scene, showPruned);
        const byParent = new Map<number, number[]>();
        for (let i = 0; i < layout.count; i++) {
          const id = layout.members[i];
          const parent = trace.parent[id];
          if (parent >= layout.start) byParent.set(parent, [...(byParent.get(parent) ?? []), id]);
        }
        const leaves = new Set<number>();
        for (let i = 0; i < layout.count; i++) {
          const id = layout.members[i];
          const x = layout.x[id - layout.start];
          const children = byParent.get(id);
          if (children) {
            const xs = children.map((child) => layout.x[child - layout.start]);
            expect(x).toBeCloseTo((Math.min(...xs) + Math.max(...xs)) / 2);
          } else {
            expect(x % SLOT).toBe(0);
            expect(leaves.has(x)).toBe(false);
            leaves.add(x);
          }
        }
        // Every accepted node of the final iteration is placed.
        for (let id = scene.rangeStart; id < scene.revealedEnd; id++) {
          if (trace.status[id] < 2 || showPruned) expect(Number.isNaN(layout.x[id - layout.start])).toBe(false);
        }
      }
    }
  });
});
