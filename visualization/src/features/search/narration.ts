import type { LoadedRecord } from "@/data/types";
import { STATUSES } from "@/data/types";
import { DIRECTION_WORDS, formatInt } from "@/lib/labels";
import { moveBetween } from "@/lib/puzzle";
import { boardOf, type Frontier, type Scene } from "@/replay/model";

export interface Narration {
  tag: string;
  title: string;
  body: string;
}

const f = (record: LoadedRecord, id: number) => record.trace.g[id] + record.trace.h[id];

/** One sentence pair per shot, written from what the viewer sees on the tree. */
export function narrate(record: LoadedRecord, scene: Scene, frontier: Frontier | null): Narration {
  const { trace } = record;
  const depthFirst = trace.depthFirst;
  const dijkstra = record.algorithm === "Dijkstra";
  const roundTag = depthFirst ? `第 ${scene.iteration + 1} 次迭代 · 第 ${formatInt(scene.round + 1)} 轮` : `第 ${formatInt(scene.round + 1)} 轮`;
  const id = scene.current;

  switch (scene.phase) {
    case "ready":
      return {
        tag: "准备",
        title: depthFirst ? `从起点开始，界限 = h(起点) = ${trace.h[0]}` : "起点加入候选队列",
        body: dijkstra
          ? `Dijkstra 按已走步数 g 排序：起点 g = 0，h = ${trace.h[0]}。h 仍按所选启发函数记录，不参与候选队列排序。`
          : `f = g + h：g 是已走步数，h 是到目标的估计。起点 g = 0，h = ${trace.h[0]}，所以 f = ${trace.h[0]}。`,
      };
    case "pick": {
      if (depthFirst) {
        const root = trace.parent[id] < 0;
        if (root && scene.iteration > 0) {
          return {
            tag: roundTag,
            title: `界限提高到 ${scene.bound}，从起点重新搜索`,
            body: `上一次迭代中超出界限的最小 f 是 ${scene.bound}。以它为新界限，丢弃旧的搜索树，从起点重新深度优先展开。`,
          };
        }
        return {
          tag: roundTag,
          title: `深度优先访问 #${id}`,
          body: `#${id} 的 f = ${f(record, id)}，不超过本轮界限 ${scene.bound}，继续向下生成它的后继。`,
        };
      }
      const ties = frontier && frontier.ties > 1 ? `有 ${frontier.ties} 个候选的 ${dijkstra ? "g 相同" : "f 和 g 都相同"}，由 Java 优先队列决定先取哪一个。` : "";
      return {
        tag: roundTag,
        title: `取出${dijkstra ? " g" : " f"} 最小的节点 #${id}`,
        body: dijkstra
          ? `候选队列按 g 升序排列，#${id} 的 g = ${trace.g[id]}。${ties}`
          : `候选队列按 f 升序、f 相同再按 g 升序排列。#${id} 的 f = ${f(record, id)}，g = ${trace.g[id]}。${ties}`,
      };
    }
    case "child": {
      const child = scene.focus;
      const move = moveBetween(boardOf(trace, id), boardOf(trace, child), trace.size);
      const status = STATUSES[trace.status[child]];
      const ref = trace.reference[child];
      const sum = dijkstra
        ? `g = ${trace.g[child]}，h = ${trace.h[child]}（估值 f = ${f(record, child)}，按 g 排序）`
        : `f = ${trace.g[child]} + ${trace.h[child]} = ${f(record, child)}`;
      const verdicts: Record<typeof status, string> = {
        frontier: depthFirst ? `${sum}，不超过界限 ${scene.bound}，稍后深入访问。` : `${sum}，加入候选队列。`,
        replaced: `${sum}；候选中的同一状态 #${ref} 代价更高（g = ${trace.g[ref]}），用新节点替换它。`,
        "closed-pruned": `这个状态已在 #${ref} 扩展过，重复出现直接剪枝。`,
        "frontier-pruned": `候选中已有同一状态 #${ref}，且 g = ${trace.g[ref]} 不更差，剪枝。`,
        "cycle-pruned": "这一步回到当前路径上已有的状态（包括立即退回），剪枝。",
        "bound-pruned": `${sum}，超过界限 ${scene.bound}，剪枝；它的 f 会参与决定下一轮界限。`,
        "transposition-pruned": "置换表记录过以更少步数到达这个状态，剪枝。",
      };
      return {
        tag: `${roundTag} · 后继 ${scene.child + 1} / ${scene.childEnd - scene.childStart}`,
        title: `生成 #${child}：${move.tile} 号块${DIRECTION_WORDS[move.direction]}`,
        body: verdicts[status],
      };
    }
    case "merge": {
      let pruned = 0;
      for (let child = scene.childStart; child < scene.childEnd; child++) if (trace.status[child] >= 2) pruned++;
      const kept = scene.childEnd - scene.childStart - pruned;
      const nextIsRoot = scene.next >= 0 && trace.parent[scene.next] < 0;
      const next = scene.next < 0 ? "" : nextIsRoot ? "本次迭代没有找到目标，下一轮将提高界限。" : `下一个${depthFirst ? "访问" : "取出"} #${scene.next}（${dijkstra ? `g = ${trace.g[scene.next]}` : `f = ${f(record, scene.next)}`}）。`;
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
        title: `取出的 #${id} 就是目标：最优解 ${trace.g[id]} 步`,
        body: `黄铜色路线是完整解路径。共扩展 ${formatInt(record.expanded)} 个节点，生成 ${formatInt(record.generated)} 个。`,
      };
  }
}
