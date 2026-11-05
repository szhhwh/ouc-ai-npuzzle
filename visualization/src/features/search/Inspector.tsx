import NumberFlow from "@number-flow/react";
import { LocateFixed, X } from "lucide-react";
import { AnimatePresence, motion } from "motion/react";
import { memo } from "react";
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

const STATE_SENTENCES: Record<Exclude<NodeState, "pruned">, (depthFirst: boolean) => string> = {
  pending: () => "还没有生成。",
  current: (depthFirst) => (depthFirst ? "正在访问，依次生成它的后继。" : "刚从候选队列取出，依次生成它的后继。"),
  expanded: () => "已经扩展过，后继都已生成。",
  open: (depthFirst) => (depthFirst ? "保留在搜索栈中，等待深度优先访问。" : "保留在候选队列中，等待取出。"),
  superseded: (depthFirst) => (depthFirst ? "属于之前的迭代，本次迭代已从起点重新开始。" : "已被代价更低的同一状态替换。"),
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
            <Button variant="ghost" size="icon-sm" onClick={() => onInspect(null)} aria-label="回到当前步骤" title="回到当前步骤（Esc）">
              <X />
            </Button>
          )}
        </div>
      </header>

      <div className="grid grid-cols-[minmax(0,148px)_minmax(0,1fr)] items-center gap-4">
        <PuzzleBoard board={board} size={trace.size} movedTile={move?.tile} terms={terms} duration={fast ? 0 : 0.3} className="w-full" />
        <div className="flex min-w-0 flex-col gap-2">
          <Term label="已走步数" symbol="g" value={g} tone="text-ink" animated={!fast} />
          <Term label={record.heuristic === "ZERO" ? "零估计" : "剩余估计"} symbol="h" value={h} tone="text-magenta" animated={!fast} delta={parent >= 0 ? dh : null} />
          <div className="flex items-baseline justify-between gap-2 rounded-lg bg-ink px-2.5 py-1.5 text-primary-foreground">
            <span className="text-[11px] opacity-75">{record.algorithm === "Dijkstra" ? "估值 f = g + h" : "权值 f = g + h"}</span>
            <span className="type-data text-xl font-semibold">
              <NumberFlow value={f} animated={!fast} />
            </span>
          </div>
          {record.algorithm === "Dijkstra" && <p className="text-[11px] leading-relaxed text-ink-soft">按 g 排序；h 和 f 仅供查看估计。</p>}
        </div>
      </div>

      {scene.bound >= 0 && (
        <BoundMeter f={f} bound={scene.bound} over={overBound} />
      )}

      <HeuristicBreakdown record={record} board={board} terms={terms} h={h} />

      <div className="flex flex-col gap-1.5 text-[13px] leading-relaxed">
        {move ? (
          <p className="text-ink">
            由{" "}
            <button type="button" className="type-data font-semibold text-sea underline-offset-2 hover:underline" onClick={() => onInspect(parent)}>
              #{parent}
            </button>{" "}
            把 <b className="font-semibold">{move.tile}</b> 号块{DIRECTION_WORDS[move.direction]}：g +1，h {dh > 0 ? `+${dh}` : dh === 0 ? "不变" : `−${-dh}`}，f{" "}
            {dh + 1 === 0 ? "不变" : dh + 1 > 0 ? `+${dh + 1}` : `−${-(dh + 1)}`}。
          </p>
        ) : (
          <p className="text-ink">起点：g = 0，f 就是 h。</p>
        )}
        <p className={cn(state === "pruned" ? "text-signal" : "text-ink-soft")}>
          {isGoal
            ? "这就是目标状态：取出它时搜索结束，沿父节点回溯得到解路径。"
            : state === "pruned"
              ? STATUS_REASONS[status]
              : status === "replaced" && state === "open"
                ? "以更低代价替换了候选中的同一状态，等待取出。"
                : STATE_SENTENCES[state](trace.depthFirst)}
          {reference >= 0 && (
            <>
              {" "}同一状态：
              <button type="button" className="type-data font-semibold text-sea underline-offset-2 hover:underline" onClick={() => onInspect(reference)}>
                #{reference}
              </button>
            </>
          )}
          {replacedBy >= 0 && replacedBy < scene.revealedEnd && (
            <>
              {" "}后来被
              <button type="button" className="type-data mx-0.5 font-semibold text-sea underline-offset-2 hover:underline" onClick={() => onInspect(replacedBy)}>
                #{replacedBy}
              </button>
              以更低代价替换。
            </>
          )}
        </p>
      </div>

      <div className="flex flex-col gap-1.5">
        <span className="text-[11px] font-medium text-ink-soft">从起点走到这里（{g} 步）</span>
        <div className="flex flex-wrap gap-1">
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
      </div>
    </section>
  );
});

function Term({ label, symbol, value, tone, animated, delta = null }: { label: string; symbol: string; value: number; tone: string; animated: boolean; delta?: number | null }) {
  return (
    <div className="flex items-baseline justify-between gap-2 rounded-lg border border-rule bg-surface px-2.5 py-1.5">
      <span className="text-[11px] text-ink-soft">
        <span className={cn("type-data mr-1 font-semibold", tone)}>{symbol}</span>
        {label}
      </span>
      <span className={cn("type-data flex items-center gap-2 text-lg font-semibold", tone)}>
        {delta !== null && delta !== 0 && (
          <span className="rounded bg-muted px-1 py-px text-[10px] font-medium text-ink-soft" title="相对父节点的变化">
            {delta > 0 ? `+${delta}` : `−${-delta}`}
          </span>
        )}
        <NumberFlow value={value} animated={animated} />
      </span>
    </div>
  );
}

/** IDA*: where this node's f sits against the iteration's threshold. */
function BoundMeter({ f, bound, over }: { f: number; bound: number; over: boolean }) {
  const scale = Math.max(bound + 4, f + 1);
  return (
    <div className="flex flex-col gap-1.5">
      <div className="flex items-center justify-between text-[11px]">
        <span className="text-ink-soft">本次迭代界限 {bound}</span>
        <span className={cn("font-semibold", over ? "text-signal" : "text-sea")}>{over ? `f = ${f} > ${bound}，超界` : `f = ${f} ≤ ${bound}，可以继续`}</span>
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
    return <p className="rounded-lg bg-muted px-3 py-2 text-[12px] text-ink-soft">零启发函数对所有棋盘都返回 0，因此 h 恒为 0。</p>;
  }
  if (!terms) {
    return (
      <p className="rounded-lg bg-magenta-wash px-3 py-2 text-[12px] text-ink">
        <b className="font-semibold text-magenta">h = {h}</b>：模式数据库把棋子分组，各组到目标的最少步数查表后相加，不能拆成单个棋子的贡献。
      </p>
    );
  }
  const parts = Array.from(board, (tile, cell) => ({ tile, value: terms[cell] }))
    .filter((part) => part.tile !== 0 && part.value > 0)
    .sort((a, b) => a.tile - b.tile);
  const manhattan = record.heuristic === "MANHATTAN";
  return (
    <div className="flex flex-col gap-1.5 rounded-lg bg-magenta-wash/70 px-3 py-2">
      <span className="text-[11px] text-ink-soft">
        {manhattan ? "h = 每个棋子到目标位置的横纵距离之和（棋子角标）" : "h = 不在目标位置的棋子数（角标为 1 的棋子）"}
      </span>
      <div className="type-data flex flex-wrap items-center gap-x-1 gap-y-1 text-[12px] text-ink">
        {parts.length === 0 ? (
          <span>全部就位，h = 0</span>
        ) : (
          <>
            {parts.map((part, index) => (
              <span key={part.tile} className="flex items-center gap-1">
                {index > 0 && <span className="text-ink-soft">+</span>}
                <span className="rounded bg-surface px-1 py-px">
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
