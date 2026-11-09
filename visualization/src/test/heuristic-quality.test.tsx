import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import { compactRecord, traceBuffers } from "@/data/compact";
import { fOf, hOf, qualityOf } from "@/data/trace-values";
import { HEURISTIC_QUALITY_CODE, type LoadedRecord, type RawRecord } from "@/data/types";
import { validateRecord } from "@/data/validate";
import { Inspector } from "@/features/search/Inspector";
import { narrate } from "@/features/search/narration";
import { HeuristicChart } from "@/features/path/HeuristicChart";
import { boardOf, frontierAt, sceneAt } from "@/replay/model";
import nida from "./fixtures/nida4.json";

const raw = nida.results[0] as RawRecord;
const record: LoadedRecord = { ...compactRecord(validateRecord(raw)), id: "quality", name: "quality" };
const { trace } = record;
const unobserved = raw.trace.nodes.find((node) => node.heuristicQuality === "unevaluated")!;
const lower = raw.trace.nodes.find((node) => node.heuristicQuality === "lower-bound")!;
const scene = sceneAt(trace, trace.shotCount - 1);

describe("schemaVersion 3 estimate quality", () => {
  it("keeps unobserved values absent in compact arrays and transfers the quality buffer", () => {
    expect(unobserved.h).toBeNull();
    expect(unobserved.f).toBeNull();
    expect(trace.h[unobserved.id]).toBe(-1);
    expect(hOf(trace, unobserved.id)).toBeNull();
    expect(fOf(trace, unobserved.id)).toBeNull();
    expect(qualityOf(trace, unobserved.id)).toBe("unevaluated");
    expect(trace.heuristicQuality).toBeInstanceOf(Uint8Array);
    expect(traceBuffers(trace)).toContain(trace.heuristicQuality.buffer);
    expect(hOf(trace, lower.id)).toBe(lower.h);
    expect(fOf(trace, lower.id)).toBe(lower.f);
    expect(trace.heuristicQuality[lower.id]).toBe(HEURISTIC_QUALITY_CODE["lower-bound"]);
  });

  it("requires explicit quality and consistent nullable h/f values", () => {
    for (const change of [
      { heuristicQuality: undefined },
      { heuristicQuality: "unknown" },
      { heuristicQuality: "unevaluated", h: 0, f: 0 },
      { heuristicQuality: "exact", h: null, f: null },
      { heuristicQuality: "lower-bound", h: null, f: null },
      { heuristicQuality: "exact", h: 0, f: 10 },
    ]) {
      const damaged = structuredClone(raw);
      Object.assign(damaged.trace.nodes[0], change);
      expect(() => validateRecord(damaged)).toThrow();
    }
  });

  it("renders unevaluated nodes as 未计算 without computing board contributions", () => {
    const html = renderToStaticMarkup(<Inspector record={record} scene={scene} inspected={unobserved.id} onInspect={() => {}} onLocate={() => {}} fast />);
    expect(html).toContain("未计算");
    expect(html).not.toContain("整体组合估值");
    expect(html).not.toContain("h = -1");
    // Viewing an unobserved node does not modify its compact data.
    expect(hOf(trace, unobserved.id)).toBeNull();
    expect(boardOf(trace, unobserved.id)).toEqual(Uint8Array.from(unobserved.board));
  });

  it("labels recorded lower bounds without presenting a completed heuristic breakdown", () => {
    const html = renderToStaticMarkup(<Inspector record={record} scene={scene} inspected={lower.id} onInspect={() => {}} onLocate={() => {}} fast />);
    expect(html).toContain("下界");
    expect(html).toContain("并非完整启发值");
    expect(html).not.toContain("整体组合估值");
    const parent = lower.parent!;
    const round = trace.visitFrame[parent];
    const shot = trace.shotStart[round] + lower.id - trace.frameChildStart[round] + 1;
    expect(narrate(record, sceneAt(trace, shot), null).body).toContain("下界");
  });

  it("leaves gaps in the path chart for missing observations and labels the active value", () => {
    const html = renderToStaticMarkup(<HeuristicChart hs={[3, null, 1]} qualities={["exact", "unevaluated", "lower-bound"]} step={1} onSeek={() => {}} />);
    expect(html).toContain("未计算");
    const line = html.match(/<path d="([^"]*)"/)!;
    expect(line[1]).toContain("M");
    expect(line[1]).not.toContain("L");
    expect(html).not.toContain("NaN");
  });

  it("keeps Dijkstra priority based on g when h/f were not observed", () => {
    const copy = structuredClone(raw);
    copy.algorithm = "Dijkstra";
    for (const node of copy.trace.nodes) {
      node.h = node.f = null;
      node.heuristicQuality = "unevaluated";
    }
    const { trace: dijkstra } = compactRecord(validateRecord(copy));
    const frontier = frontierAt(dijkstra, sceneAt(dijkstra, 0), 3);
    expect(frontier.top[0]).toMatchObject({ id: 0, g: 0, f: null, priority: 0 });
    expect(frontier.histogram).toEqual([{ value: 0, count: 1 }]);
  });
});
