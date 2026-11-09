import { fOf, hOf, qualityOf } from "@/data/trace-values";
import type { LoadedRecord } from "@/data/types";
import { ALGORITHM_TRAITS, isPrunedStatus, STATUSES } from "@/data/types";
import { DIRECTION_WORDS, formatEstimate, formatInt } from "@/lib/labels";
import { moveBetween } from "@/lib/puzzle";
import { boardOf, type Frontier, type Scene } from "@/replay/model";

export interface Narration {
  tag: string;
  title: string;
  body: string;
}

const estimate = (record: LoadedRecord, id: number, kind: "h" | "f") => {
  const value = kind === "h" ? hOf(record.trace, id) : fOf(record.trace, id);
  const quality = qualityOf(record.trace, id);
  return `${quality === "exact" && value !== null ? "= " : ""}${formatEstimate(value, quality)}`;
};
const f = (record: LoadedRecord, id: number) => estimate(record, id, "f");

/** One sentence pair per shot, written from what the viewer sees on the tree. */
export function narrate(record: LoadedRecord, scene: Scene, frontier: Frontier | null): Narration {
  const { trace } = record;
  const depthFirst = trace.depthFirst;
  const dijkstra = trace.pathCostOnly;
  const traits = ALGORITHM_TRAITS[record.algorithm];
  const roundTag = depthFirst ? `第 ${scene.iteration + 1} 次迭代 · 第 ${formatInt(scene.round + 1)} 轮` : `第 ${formatInt(scene.round + 1)} 轮`;
  const id = scene.current;

  switch (scene.phase) {
    case "ready":
      return {
        tag: "准备",
        title: depthFirst ? `从起点开始搜索，初始界限为 ${scene.bound}` : "起点加入候选队列",
        body: dijkstra
          ? `起点已走步数 g = 0，记录的启发估计 h ${estimate(record, 0, "h")}。`
          : `起点已走步数 g = 0，记录的启发估计 h ${estimate(record, 0, "h")}，综合代价 f ${f(record, 0)}。`,
      };
    case "pick": {
      if (depthFirst) {
        const root = trace.parent[id] < 0;
        if (root && scene.iteration > 0) {
          return {
            tag: roundTag,
            title: `界限提升至 ${scene.bound}，从起点重新搜索`,
            body: traits.parityAlignedBound
              ? `按记录将界限提高至 ${scene.bound}（与解长奇偶性对齐），从起点重新深度优先搜索。`
              : `按记录将界限提高至 ${scene.bound}，从起点重新深度优先搜索。`,
          };
        }
        return {
          tag: roundTag,
          title: `深度优先访问节点 #${id}`,
          body: `节点 #${id} 的估值 f ${f(record, id)}，当前界限为 ${scene.bound}，按记录检查目标并继续搜索。`,
        };
      }
      const ties = frontier && frontier.ties > 1 ? `存在 ${frontier.ties} 个候选节点的 ${dijkstra ? "g 相同" : "f 与 g 均相同"}，按队列既定规则取出。` : "";
      return {
        tag: roundTag,
        title: `取出${dijkstra ? " g" : " f"} 最小的节点 #${id}`,
        body: dijkstra
          ? `候选队列按步数 g 升序排列，节点 #${id} 的 g = ${trace.g[id]}。${ties}`
          : `候选队列按估值 f 优先、f 相同时按 g 升序排列。节点 #${id} 的 f ${f(record, id)}，g = ${trace.g[id]}。${ties}`,
      };
    }
    case "child": {
      const child = scene.focus;
      const move = moveBetween(boardOf(trace, id), boardOf(trace, child), trace.size);
      const status = STATUSES[trace.status[child]];
      const ref = trace.reference[child];
      const childF = f(record, child);
      const verdicts: Record<typeof status, string> = {
        frontier: depthFirst
          ? `综合代价 f ${childF}，记录中保留此分支；是否访问以随后的搜索帧为准。`
          : dijkstra
            ? `已走步数 g = ${trace.g[child]}，加入候选队列。`
            : `综合代价 f ${childF}，加入候选队列。`,
        replaced: `候选队列中已有同状态节点 #${ref}，当前路径代价更优，其 g = ${trace.g[child]}，更新替换。`,
        "closed-pruned": `该状态已在节点 #${ref} 扩展过，判定为重复状态并剪枝。`,
        "frontier-pruned": `候选队列中已存在相同状态 #${ref} 且代价不劣，其 g = ${trace.g[ref]}，执行剪枝。`,
        "cycle-pruned": "移动回退至当前搜索路径已有状态，检测到回路，执行剪枝。",
        "bound-pruned": `综合代价 f ${childF} 超出界限 ${scene.bound}，执行剪枝；其 f 值将参与决定下一轮迭代界限。`,
        "transposition-pruned": "置换表记录已有不劣的步数到达该状态，执行剪枝。",
      };
      return {
        tag: `${roundTag} · 后继 ${scene.child + 1} / ${scene.childEnd - scene.childStart}`,
        title: `生成 #${child}：${move.tile} 号块${DIRECTION_WORDS[move.direction]}`,
        body: `${verdicts[status]}${qualityOf(trace, child) === "unevaluated" ? "h 与 f 未计算。" : ""}`,
      };
    }
    case "merge": {
      let pruned = 0;
      for (let child = scene.childStart; child < scene.childEnd; child++) if (isPrunedStatus(trace.status[child])) pruned++;
      const kept = scene.childEnd - scene.childStart - pruned;
      const nextIsRoot = scene.next >= 0 && trace.parent[scene.next] < 0;
      const next = scene.next < 0 ? "" : nextIsRoot ? "本轮迭代未找到目标，下一轮将提高界限继续搜索。" : `下一个${depthFirst ? "访问" : "取出"}节点 #${scene.next}，${dijkstra ? `g = ${trace.g[scene.next]}` : `f ${f(record, scene.next)}`}。`;
      return {
        tag: roundTag,
        title: `本轮保留 ${kept} 个，剪枝 ${pruned} 个`,
        body: depthFirst
          ? `${next}`
          : `候选队列现有 ${formatInt(frontier?.size ?? 0)} 个节点。${next}`,
      };
    }
    case "goal":
      return {
        tag: "完成",
        title: `节点 #${id} 到达目标状态：最优解共 ${trace.g[id]} 步`,
        body: `高亮路线为完整解路径。累计扩展 ${formatInt(record.expanded)} 个节点，生成 ${formatInt(record.generated)} 个节点。`,
      };
  }
}
