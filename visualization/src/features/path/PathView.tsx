import { useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import { motion } from "motion/react";
import NumberFlow from "@number-flow/react";
import { useHotkeys } from "react-hotkeys-hook";
import { PuzzleBoard } from "@/components/puzzle-board";
import { Badge } from "@/components/ui/badge";
import { ScrollArea } from "@/components/ui/scroll-area";
import { Switch } from "@/components/ui/switch";
import { useUi } from "@/app/ui-store";
import { hOf, qualityOf } from "@/data/trace-values";
import type { LoadedRecord } from "@/data/types";
import { DIRECTION_ARROWS, DIRECTION_WORDS, HEURISTIC_NAMES } from "@/lib/labels";
import { heuristicTerms, moveBetween } from "@/lib/puzzle";
import { HeuristicChart } from "./HeuristicChart";
import { MoveList } from "./MoveList";
import { stepInterval, usePathPlayback } from "./path-store";
import { Transport } from "./Transport";

/** Space must not toggle twice when a focused control already handles it natively. */
const ownsSpace = (event: KeyboardEvent) =>
  event.target instanceof Element &&
  event.target.closest('button, [role="button"], [role="slider"], [role="switch"], [role="combobox"], input, textarea, select, [contenteditable="true"]') !== null;

const enter = (order: number) => ({
  initial: { opacity: 0, y: 10 },
  animate: { opacity: 1, y: 0 },
  transition: { duration: 0.32, delay: 0.05 + order * 0.06, ease: [0.22, 0.9, 0.24, 1] as const },
});

function Metric({ label, value, unit, decimals = 0 }: { label: string; value: number; unit?: string; decimals?: number }) {
  return (
    <div className="flex flex-col gap-1 rounded-lg border border-rule bg-surface px-3 py-2.5">
      <span className="text-xs text-ink-soft">{label}</span>
      <span className="flex items-baseline gap-1 text-ink">
        <NumberFlow
          value={value}
          locales="zh-CN"
          format={{ maximumFractionDigits: decimals }}
          className="type-data text-lg leading-none font-medium"
        />
        {unit && <span className="text-xs text-ink-soft">{unit}</span>}
      </span>
    </div>
  );
}

/** Solution replay: the board walks `record.states` while the details column tracks h against the remaining moves. */
export function PathView({ record }: { record: LoadedRecord }) {
  const { states, size, trace } = record;
  const total = states.length - 1;

  const playback = usePathPlayback();
  const bound = playback.recordId === record.id;
  const step = bound ? Math.min(playback.step, total) : 0;
  const playing = bound && playback.playing;
  const interval = stepInterval(playback.speed);
  const { advance, toggle, stepBy, seek } = playback;

  useLayoutEffect(() => usePathPlayback.getState().bindRecord(record.id), [record.id]);
  useEffect(() => () => usePathPlayback.getState().pause(), []);

  useEffect(() => {
    if (!playing) return;
    const timer = window.setTimeout(() => advance(total), interval);
    return () => window.clearTimeout(timer);
  }, [playing, step, interval, total, advance]);

  const hotkeysOn = useUi((ui) => ui.view === "path" && !ui.libraryOpen);
  useHotkeys("space", () => toggle(total), { enabled: hotkeysOn, preventDefault: true, ignoreEventWhen: ownsSpace }, [total, toggle]);
  useHotkeys("left", () => stepBy(-1, total), { enabled: hotkeysOn, preventDefault: true }, [total, stepBy]);
  useHotkeys("right", () => stepBy(1, total), { enabled: hotkeysOn, preventDefault: true }, [total, stepBy]);
  useHotkeys("home", () => seek(0, total), { enabled: hotkeysOn, preventDefault: true }, [total, seek]);
  useHotkeys("end", () => seek(total, total), { enabled: hotkeysOn, preventDefault: true }, [total, seek]);

  const moves = useMemo(() => states.slice(1).map((board, i) => moveBetween(states[i], board, size)), [states, size]);
  const hs = useMemo(() => Array.from(trace.solution, (id) => hOf(trace, id)), [trace]);
  const qualities = useMemo(() => Array.from(trace.solution, (id) => qualityOf(trace, id)), [trace]);
  const canShowTerms = qualities[step] === "exact" && record.heuristic !== "DISJOINT_PATTERN";
  const [showTerms, setShowTerms] = useState(false);
  const terms = canShowTerms && showTerms ? heuristicTerms(record.heuristic, states[step], record.goal, size) : null;

  // Slides follow the playback cadence; multi-step seeks get a slower glide so the jump stays readable.
  const shownStep = useRef(step);
  const jumped = Math.abs(step - shownStep.current) > 1;
  useEffect(() => {
    shownStep.current = step;
  }, [step]);
  const duration = jumped ? 0.36 : playing ? Math.min(0.26, (0.7 * interval) / 1000) : 0.26;

  const move = step ? moves[step - 1] : null;
  const seekTo = (target: number) => seek(target, total);

  return (
    <div className="path-layout">
      <motion.section
        {...enter(0)}
        aria-label="解路径回放"
        className="path-replay"
      >
        <div className="path-replay-content">
          <div className="path-step flex items-end justify-between gap-4">
            <p className="flex items-baseline gap-2 text-ink">
              <NumberFlow value={step} className="path-step-number type-display leading-none font-bold" />
              <span className="type-data text-sm text-ink-soft">/ {total} 步</span>
            </p>
            <p className="flex min-h-7 items-center gap-1.5 pb-1 text-sm text-ink">
              {move ? (
                <>
                  <span className="type-data inline-grid h-7 min-w-7 place-items-center rounded-md bg-brass-wash px-1.5 font-semibold ring-1 ring-brass ring-inset">
                    {move.tile}
                  </span>
                  <span>号块</span>
                  <span aria-hidden className="text-base">
                    {DIRECTION_ARROWS[move.direction]}
                  </span>
                  <span>{DIRECTION_WORDS[move.direction]}</span>
                </>
              ) : (
                <span className="font-medium">起点</span>
              )}
            </p>
          </div>

          <div className="path-board-space">
            <PuzzleBoard
              board={states[step]}
              size={size}
              movedTile={move?.tile ?? null}
              terms={terms}
              duration={duration}
              className="path-board"
              label={`第 ${step} 步棋盘 ${states[step].map((value) => value || "空").join(" ")}`}
            />
          </div>

          <div className="path-controls">
            <Transport total={total} />

            {canShowTerms && (
              <label className="flex items-center justify-center gap-2.5 text-sm text-ink-soft">
                <Switch checked={showTerms} onCheckedChange={setShowTerms} />
                在棋子上标注启发值
              </label>
            )}
          </div>
        </div>
      </motion.section>

      <aside
        aria-label="记录详情"
        className="path-details min-w-0 border-t border-rule bg-paper-deep/40 lg:border-t-0 lg:border-l"
      >
        <ScrollArea className="path-details-scroll">
        <div className="flex flex-col gap-5 px-4 py-5 sm:px-6">
        <motion.header {...enter(1)} className="flex flex-col gap-2">
          <h2 className="type-display text-xl leading-tight font-semibold break-words text-ink">{record.name}</h2>
          <div className="flex flex-wrap gap-1.5">
            <Badge variant="outline" className="bg-surface">{record.algorithm}</Badge>
            <Badge variant="outline" className="bg-surface">{HEURISTIC_NAMES[record.heuristic]}</Badge>
            <Badge variant="outline" className="type-data bg-surface">
              {size}×{size}
            </Badge>
          </div>
        </motion.header>

        <motion.section {...enter(2)} aria-label="求解数据" className="flex flex-col gap-2">
          <div className="grid grid-cols-2 gap-2">
            <Metric label="解路径长度" value={total} unit="步" />
            <Metric label="求解耗时" value={record.timeMs} unit="ms" decimals={2} />
            <Metric label="生成节点" value={record.generated} />
            <Metric label="扩展节点" value={record.expanded} />
          </div>
        </motion.section>

        <motion.section {...enter(3)} aria-labelledby="path-chart-title" className="flex flex-col gap-2">
          <div className="flex items-center justify-between gap-3">
            <h3 id="path-chart-title" className="text-sm font-medium text-ink">
              启发值与实际剩余步数
            </h3>
            <div aria-hidden className="flex items-center gap-3 text-xs text-ink-soft">
              <span className="flex items-center gap-1">
                <span className="h-0.5 w-3.5 rounded-full bg-magenta" />h
              </span>
              <span className="flex items-center gap-1">
                <span className="w-3.5 border-t-[1.5px] border-dashed border-ink/70" />
                剩余
              </span>
            </div>
          </div>
          <div className="rounded-lg border border-rule bg-surface p-2">
            <HeuristicChart hs={hs} qualities={qualities} step={step} onSeek={seekTo} />
          </div>
          <p className="text-xs text-ink-soft">启发函数满足可采纳性时，h 曲线不高于实际剩余步数。点击图表可跳转至对应步。</p>
        </motion.section>

        <motion.section
          {...enter(4)}
          aria-labelledby="path-moves-title"
          className="flex flex-col gap-2"
        >
          <h3 id="path-moves-title" className="text-sm font-medium text-ink">
            移动步骤
          </h3>
          <MoveList
            moves={moves}
            hs={hs}
            qualities={qualities}
            step={step}
            onSeek={seekTo}
            className="h-72 rounded-lg border border-rule bg-surface"
          />
        </motion.section>
        </div>
        </ScrollArea>
      </aside>
    </div>
  );
}
