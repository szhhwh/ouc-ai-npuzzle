import NumberFlow from "@number-flow/react";
import { ChevronDown } from "lucide-react";
import { AnimatePresence, motion } from "motion/react";
import { memo } from "react";
import type { LoadedRecord } from "@/data/types";
import { cn } from "@/lib/utils";
import { nextBoundAt, type Frontier, type FrontierEntry, type Scene } from "@/replay/model";

interface FrontierPanelProps {
  record: LoadedRecord;
  scene: Scene;
  frontier: Frontier;
  inspected: number | null;
  onInspect: (id: number) => void;
  onJumpIteration: (iteration: number) => void;
  fast: boolean;
}

/** Best-first searches (A*, Dijkstra) show the priority queue; IDA* shows thresholds and iterations. */
export const FrontierPanel = memo(function FrontierPanel(props: FrontierPanelProps) {
  return props.record.trace.depthFirst ? <IterationPanel {...props} /> : <QueuePanel {...props} />;
});

function QueuePanel({ record, scene, frontier, inspected, onInspect, fast }: FrontierPanelProps) {
  const dijkstra = record.algorithm === "Dijkstra";
  const histogram = frontier.histogram.slice(0, 14);
  const maxCount = Math.max(1, ...histogram.map((bin) => bin.count));
  const headLabel = scene.phase === "merge" || scene.phase === "ready" ? "下一个取出" : "队首";
  const renderRow = (entry: FrontierEntry, rank: number) => (
    <button
      type="button"
      onClick={() => onInspect(entry.id)}
      className={cn(
        "grid w-full grid-cols-[minmax(0,1fr)_auto] items-center gap-x-3 gap-y-1 rounded-lg px-2.5 py-2.5 text-left transition-colors",
        rank === 0 ? "bg-sea-wash" : "hover:bg-muted",
        inspected === entry.id && "ring-2 ring-ink",
      )}
    >
      <span className="type-data text-[11px] text-ink-soft">#{entry.id}</span>
      <span className="type-data flex items-baseline gap-3 text-[12px]">
        <span className={cn(dijkstra ? "font-semibold text-ink" : "text-ink-soft")}><span className="mr-1 text-[10px]">g</span>{entry.g}</span>
        {!dijkstra && <span className="text-magenta"><span className="mr-1 text-[10px]">h</span>{entry.f - entry.g}</span>}
        {!dijkstra && <span className="font-semibold text-ink"><span className="mr-1 text-[10px]">f</span>{entry.f}</span>}
      </span>
      {rank === 0 && <span className="col-span-2 text-[10px] font-semibold text-sea">{headLabel}</span>}
    </button>
  );
  return (
    <section aria-label="候选队列" className="flex flex-col gap-3">
      <header className="flex items-baseline justify-between">
        <h3 className="text-[13px] font-semibold text-ink">候选队列</h3>
        <span className="type-data text-[12px] text-ink-soft">
          <NumberFlow value={frontier.size} animated={!fast} /> 个
        </span>
      </header>
      {frontier.ties > 1 && (
        <p className="-mt-1 text-[11px] leading-relaxed text-ink-soft">
          队首存在 {frontier.ties} 个代价值并列的候选节点。
        </p>
      )}
      <ol className="flex flex-col gap-1">
        {fast ? (
          frontier.top.map((entry, rank) => <li key={entry.id}>{renderRow(entry, rank)}</li>)
        ) : (
          <AnimatePresence initial={false} mode="popLayout">
            {frontier.top.map((entry, rank) => (
              <motion.li
                key={entry.id}
                layout
                initial={{ opacity: 0, x: 12 }}
                animate={{ opacity: 1, x: 0 }}
                exit={{ opacity: 0, x: -12, transition: { duration: 0.15 } }}
                transition={{ type: "spring", stiffness: 500, damping: 40 }}
              >
                {renderRow(entry, rank)}
              </motion.li>
            ))}
          </AnimatePresence>
        )}
      </ol>
      {frontier.size > frontier.top.length && (
        <span className="text-[11px] text-ink-soft">另有 {(frontier.size - frontier.top.length).toLocaleString("zh-CN")} 个候选</span>
      )}
      {histogram.length > 1 && (
        <details className="group border-t border-rule" open>
          <summary className="flex cursor-pointer list-none items-center justify-between gap-2 py-3 text-[11px] text-ink-soft [&::-webkit-details-marker]:hidden">
            {dijkstra ? "候选按 g 分布" : "候选按 f 分布"}
            <ChevronDown className="size-3.5 transition-transform group-open:rotate-180 motion-reduce:transition-none" />
          </summary>
          <div className="flex h-12 items-end gap-1" role="img" aria-label={histogram.map((bin) => `${dijkstra ? "g" : "f"} ${bin.value}：${bin.count} 个`).join("，")}>
            {histogram.map((bin, index) => (
              <div key={bin.value} className="flex min-w-0 flex-1 flex-col items-center gap-0.5">
                <motion.div
                  className={cn("w-full rounded-sm", index === 0 ? "bg-sea" : "bg-ink/25")}
                  initial={false}
                  animate={{ height: `${Math.max(2, (bin.count / maxCount) * 36)}px` }}
                  transition={{ duration: fast ? 0 : 0.25 }}
                />
                <span className="type-data text-[9px] text-ink-soft">{bin.value}</span>
              </div>
            ))}
          </div>
        </details>
      )}
    </section>
  );
}

function IterationPanel({ record, scene, frontier, onInspect, onJumpIteration, fast }: FrontierPanelProps) {
  const { trace } = record;
  const next = nextBoundAt(trace, scene);
  const iterations = Array.from(trace.iterationFirstFrame, (frame, iteration) => ({
    iteration,
    bound: trace.frameBound[frame],
    rounds: (iteration + 1 < trace.iterationFirstFrame.length ? trace.iterationFirstFrame[iteration + 1] : trace.frameCount) - frame,
  }));
  return (
    <section aria-label="迭代与界限" className="flex flex-col gap-3">
      <header className="flex items-baseline justify-between">
        <h3 className="text-[13px] font-semibold text-ink">迭代与界限</h3>
        <span className="text-[12px] text-ink-soft">
          待访问 <span className="type-data"><NumberFlow value={frontier.size} animated={!fast} /></span> 个
        </span>
      </header>
      <div className="grid grid-cols-2 gap-2">
        <div className="rounded-lg border border-rule px-3 py-2 text-ink">
          <span className="block text-[11px] text-ink-soft">本次界限</span>
          <span className="type-data text-xl font-semibold">
            <NumberFlow value={scene.bound} animated={!fast} />
          </span>
        </div>
        <button
          type="button"
          disabled={!next}
          onClick={() => next && onInspect(next.id)}
          className="rounded-lg border border-dashed border-signal/50 px-3 py-2 text-left transition-colors enabled:hover:bg-signal-wash"
        >
          <span className="block text-[11px] text-ink-soft">超界的最小 f</span>
          <span className="type-data text-xl font-semibold text-signal">{next ? next.f : "—"}</span>
        </button>
      </div>
      <ol className="flex flex-col gap-1">
        {iterations.map((item) => {
          const state = item.iteration < scene.iteration ? "done" : item.iteration === scene.iteration ? "active" : "todo";
          return (
            <li key={item.iteration}>
              <button
                type="button"
                onClick={() => onJumpIteration(item.iteration)}
                className={cn(
                  "grid w-full grid-cols-[auto_minmax(0,1fr)_auto] items-center gap-2 rounded-lg px-2 py-1.5 text-left text-[12px] transition-colors",
                  state === "active" ? "bg-sea-wash" : "hover:bg-muted",
                )}
              >
                <span
                  className={cn(
                    "grid size-5 place-items-center rounded-full text-[10px] font-semibold",
                    state === "done" ? "bg-ink text-primary-foreground" : state === "active" ? "bg-sea text-primary-foreground" : "border border-rule text-ink-soft",
                  )}
                >
                  {item.iteration + 1}
                </span>
                <span className={cn(state === "todo" ? "text-ink-soft" : "text-ink")}>
                  {state === "todo" ? "尚未开始" : <>界限 <b className="type-data font-semibold">{item.bound}</b></>}
                </span>
                <span className="type-data text-[11px] text-ink-soft">{item.rounds.toLocaleString("zh-CN")} 轮</span>
              </button>
            </li>
          );
        })}
      </ol>
    </section>
  );
}
