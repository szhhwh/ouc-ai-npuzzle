import NumberFlow from "@number-flow/react";
import { ChevronDown, LocateFixed, X } from "lucide-react";
import { AnimatePresence, motion } from "motion/react";
import { memo, useState } from "react";
import { PuzzleBoard } from "@/components/puzzle-board";
import { Button } from "@/components/ui/button";
import { STATUSES, type LoadedRecord } from "@/data/types";
import { DIRECTION_ARROWS, DIRECTION_WORDS, STATUS_REASONS, STATUS_TAGS } from "@/lib/labels";
import { heuristicTerms, moveBetween } from "@/lib/puzzle";
import { cn } from "@/lib/utils";
import { boardOf, nodeState, pathTo, type NodeState, type Scene } from "@/replay/model";

const STATE_LABELS: Record<Exclude<NodeState, "pruned">, { label: string; tone: string }> = {
  pending: { label: "待生成", tone: "bg-muted text-ink-soft" },
  current: { label: "扩展中", tone: "bg-ink text-primary-foreground" },
  expanded: { label: "已扩展", tone: "bg-muted text-ink" },
  open: { label: "候选", tone: "bg-sea-wash text-sea" },
  superseded: { label: "已失效", tone: "bg-muted text-ink-soft" },
};


interface InspectorProps {
  record: LoadedRecord;
  scene: Scene;
  /** Node chosen on the tree, or null to follow the replay. */
  inspected: number | null;
  onInspect: (id: number | null) => void;
  onLocate: (id: number) => void;
  fast: boolean;
}

/** One node's board, costs and heuristic estimate, plus why it was kept or cut. */
export const Inspector = memo(function Inspector({ record, scene, inspected, onInspect, onLocate, fast }: InspectorProps) {
  const { trace } = record;
  const [calculationOpen, setCalculationOpen] = useState(true);
  const id = inspected ?? scene.focus;
  const board = boardOf(trace, id);
  const parent = trace.parent[id];
  const g = trace.g[id];
  const h = trace.h[id];
  const f = g + h;
  const state = nodeState(trace, scene, id);
  const status = STATUSES[trace.status[id]];
  const terms = heuristicTerms(record.heuristic, board, record.goal, trace.size);
  const move = parent >= 0 ? moveBetween(boardOf(trace, parent), board, trace.size) : null;
  const dh = parent >= 0 ? h - trace.h[parent] : 0;
  const reference = trace.reference[id];
  const replacedBy = trace.replacedBy[id];
  const path = pathTo(trace, id);
  const isGoal = scene.goal && id === scene.current;
  const badge = isGoal
    ? { label: "目标", tone: "bg-brass text-primary-foreground" }
    : state === "pruned"
      ? { label: STATUS_TAGS[status], tone: "bg-signal-wash text-signal" }
      : STATE_LABELS[state];
  const overBound = scene.bound >= 0 && f > scene.bound;

  return (
    <section aria-label="节点计算" className="flex flex-col gap-4">
      <header className="flex items-center gap-2">
        <span className="text-[12px] font-medium text-ink-soft">{inspected === null ? "当前步骤" : "已选节点"}</span>
        <span className="type-data text-[13px] font-semibold text-ink">#{id}</span>
        <AnimatePresence mode="popLayout" initial={false}>
          <motion.span
            key={badge.label}
            className={cn("rounded-full px-2 py-0.5 text-[11px] font-semibold", badge.tone)}
            initial={{ opacity: 0, scale: 0.85 }}
            animate={{ opacity: 1, scale: 1 }}
            exit={{ opacity: 0, scale: 0.85 }}
            transition={{ duration: 0.16 }}
          >
            {badge.label}
          </motion.span>
        </AnimatePresence>
        <div className="ml-auto flex items-center gap-0.5">
          <Button variant="ghost" size="icon-sm" onClick={() => onLocate(id)} aria-label={`在搜索树中定位 #${id}`} title="在树上定位">
            <LocateFixed />
          </Button>
          {inspected !== null && (
            <Button variant="ghost" size="icon-sm" onClick={() => onInspect(null)} aria-label="回到当前步骤" title="回到当前步骤 · Esc">
              <X />
            </Button>
          )}
        </div>
      </header>

      <PuzzleBoard
        board={board}
        size={trace.size}
        movedTile={move?.tile}
        terms={calculationOpen ? terms : null}
        duration={fast ? 0 : 0.3}
        className="mx-auto w-full max-w-[168px]"
      />

      <div className="type-data flex items-baseline justify-center gap-2 border-y border-rule py-3 text-[17px] font-semibold">
        <span title="已走步数"><span className="mr-1 text-[12px] font-medium text-ink-soft">g</span><NumberFlow value={g} animated={!fast} /></span>
        <span className="font-normal text-ink-soft">+</span>
        <span className="text-magenta" title={record.heuristic === "ZERO" ? "零估计" : "剩余估计"}><span className="mr-1 text-[12px] font-medium">h</span><NumberFlow value={h} animated={!fast} /></span>
        <span className="font-normal text-ink-soft">=</span>
        <span title={record.algorithm === "Dijkstra" ? "估算总代价 f" : "综合代价 f"}><span className="mr-1 text-[12px] font-medium text-ink-soft">f</span><NumberFlow value={f} animated={!fast} /></span>
      </div>

      {scene.bound >= 0 && <BoundMeter f={f} bound={scene.bound} over={overBound} />}

      {move && (
        <p className="text-[12px] leading-relaxed text-ink-soft">
          由{" "}
          <button type="button" className="type-data font-semibold text-sea underline-offset-2 hover:underline" onClick={() => onInspect(parent)}>
            #{parent}
          </button>{" "}
          把 <b className="font-semibold text-ink">{move.tile}</b> 号块{DIRECTION_WORDS[move.direction]}。
        </p>
      )}

      {(state === "pruned" || state === "superseded" || status === "replaced" || reference >= 0) && (
        <p className={cn("text-[12px] leading-relaxed", state === "pruned" ? "text-signal" : "text-ink-soft")}>
          {state === "pruned"
            ? STATUS_REASONS[status]
            : state === "superseded"
              ? trace.depthFirst ? "属于上一轮迭代的历史节点，新一轮已重新开始。" : "已被更低代价的同状态节点替代。"
              : status === "replaced" && state === "open"
                ? "以更优代价替换了候选队列中的同状态节点，等待取出。"
                : null}
          {reference >= 0 && (
            <>
              {" "}相同状态：
              <button type="button" className="type-data font-semibold text-sea underline-offset-2 hover:underline" onClick={() => onInspect(reference)}>
                #{reference}
              </button>
            </>
          )}
          {replacedBy >= 0 && replacedBy < scene.revealedEnd && (
            <>
              {" "}后续被节点
              <button type="button" className="type-data mx-0.5 font-semibold text-sea underline-offset-2 hover:underline" onClick={() => onInspect(replacedBy)}>
                #{replacedBy}
              </button>
              以更优代价替换。
            </>
          )}
        </p>
      )}

      <details className="group border-t border-rule" open={calculationOpen} onToggle={(event) => setCalculationOpen(event.currentTarget.open)}>
        <summary className="flex cursor-pointer list-none items-center justify-between gap-2 py-3 text-[12px] font-medium text-ink-soft [&::-webkit-details-marker]:hidden">
          {record.heuristic === "ZERO" ? "零估计" : "剩余估计"}
          <ChevronDown className="size-3.5 transition-transform group-open:rotate-180 motion-reduce:transition-none" />
        </summary>
        <div className="flex flex-col gap-3 pb-3">
          <HeuristicBreakdown record={record} board={board} terms={terms} h={h} />
          {move && (
            <p className="type-data text-[11px] leading-relaxed text-ink-soft">
              g +1，h {dh > 0 ? `+${dh}` : dh === 0 ? "不变" : `−${-dh}`}，f {dh + 1 === 0 ? "不变" : dh + 1 > 0 ? `+${dh + 1}` : `−${-(dh + 1)}`}。
            </p>
          )}
        </div>
      </details>

      <details className="group -mt-4 border-t border-rule" open>
        <summary className="flex cursor-pointer list-none items-center justify-between gap-2 py-3 text-[12px] font-medium text-ink-soft [&::-webkit-details-marker]:hidden">
          <span>到达此节点的移动路径 · {g} 步</span>
          <ChevronDown className="size-3.5 shrink-0 transition-transform group-open:rotate-180 motion-reduce:transition-none" />
        </summary>
        <div className="flex flex-wrap gap-1 pb-3">
          {path.length === 1 ? (
            <span className="text-[12px] text-ink-soft">—</span>
          ) : (
            path.slice(1).map((node, step) => {
              const each = moveBetween(boardOf(trace, path[step]), boardOf(trace, node), trace.size);
              return (
                <button
                  key={node}
                  type="button"
                  onClick={() => onInspect(node)}
                  title={`#${node}：${each.tile} 号块${DIRECTION_WORDS[each.direction]}`}
                  className={cn(
                    "type-data grid h-6 min-w-6 place-items-center rounded-md px-1 text-[11px] transition-colors",
                    node === id ? "bg-ink text-primary-foreground" : "bg-muted text-ink hover:bg-sea-wash",
                  )}
                >
                  {each.tile}
                  {DIRECTION_ARROWS[each.direction]}
                </button>
              );
            })
          )}
        </div>
      </details>
    </section>
  );
});


/** IDA*: where this node's f sits against the iteration's threshold. */
function BoundMeter({ f, bound, over }: { f: number; bound: number; over: boolean }) {
  const scale = Math.max(bound + 4, f + 1);
  return (
    <div className="flex flex-col gap-1.5">
      <div className="flex items-center justify-between text-[11px]">
        <span className="text-ink-soft">本次迭代界限 {bound}</span>
        <span className={cn("font-semibold", over ? "text-signal" : "text-sea")}>{over ? `f = ${f} > ${bound} 超出界限` : `f = ${f} ≤ ${bound} 未超界限`}</span>
      </div>
      <div className="relative h-2 rounded-full bg-muted">
        <motion.div
          className={cn("absolute inset-y-0 left-0 rounded-full", over ? "bg-signal" : "bg-sea")}
          initial={false}
          animate={{ width: `${(Math.min(f, scale) / scale) * 100}%` }}
          transition={{ duration: 0.3 }}
        />
        <div className="absolute -inset-y-1 w-0.5 rounded bg-ink" style={{ left: `${(bound / scale) * 100}%` }} />
      </div>
    </div>
  );
}

function HeuristicBreakdown({ record, board, terms, h }: { record: LoadedRecord; board: ArrayLike<number>; terms: number[] | null; h: number }) {
  if (record.heuristic === "ZERO") {
    return <p className="text-[12px] leading-relaxed text-ink-soft">零启发函数恒返回 0，搜索过程退化为 Dijkstra 算法。</p>;
  }
  if (!terms) {
    return (
      <p className="text-[12px] leading-relaxed text-ink-soft">
        <b className="font-semibold text-magenta">h = {h}</b>：模式数据库按子问题分组查表计算，属于整体组合估值，不支持单个棋子独立分解。
      </p>
    );
  }
  const parts = Array.from(board, (tile, cell) => ({ tile, value: terms[cell] }))
    .filter((part) => part.tile !== 0 && part.value > 0)
    .sort((a, b) => a.tile - b.tile);
  const manhattan = record.heuristic === "MANHATTAN";
  return (
    <div className="flex flex-col gap-2">
      <span className="text-[11px] text-ink-soft">
        {manhattan ? "h 为各棋子到达目标网格的曼哈顿距离总和，角标为单棋子距离" : "h 为未在目标位置的错位棋子数，角标为 1 即代表未就位"}
      </span>
      <div className="type-data flex flex-wrap items-center gap-x-1 gap-y-1 text-[12px] text-ink">
        {parts.length === 0 ? (
          <span>所有棋子已就位，h = 0</span>
        ) : (
          <>
            {parts.map((part, index) => (
              <span key={part.tile} className="flex items-center gap-1">
                {index > 0 && <span className="text-ink-soft">+</span>}
                <span className="rounded bg-muted px-1 py-px">
                  {part.tile}
                  {manhattan && <sub className="ml-px text-magenta">{part.value}</sub>}
                </span>
              </span>
            ))}
            <span className="ml-1 font-semibold text-magenta">= {h}</span>
          </>
        )}
      </div>
    </div>
  );
}
