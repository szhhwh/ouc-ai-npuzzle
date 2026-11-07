import NumberFlow from "@number-flow/react";
import { ChevronLeft, ChevronRight, ChevronsLeft, ChevronsRight, Pause, Play, SkipBack } from "lucide-react";
import { AnimatePresence, motion } from "motion/react";
import { forwardRef, type ReactNode } from "react";
import { Button } from "@/components/ui/button";
import { Kbd } from "@/components/ui/kbd";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Slider } from "@/components/ui/slider";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import type { LoadedRecord } from "@/data/types";
import { cn } from "@/lib/utils";
import type { Counters, Scene } from "@/replay/model";
import { SPEEDS, type Speed } from "./search-store";

interface DockProps {
  record: LoadedRecord;
  scene: Scene;
  counters: Counters;
  playing: boolean;
  speed: Speed;
  fast: boolean;
  markers: number[];
  onToggle: () => void;
  onStep: (delta: number) => void;
  onRound: (delta: number) => void;
  onRestart: () => void;
  onSeek: (shot: number) => void;
  onSpeed: (speed: Speed) => void;
  className?: string;
}

function Hint({ label, keys, children }: { label: string; keys: string; children: ReactNode }) {
  return (
    <Tooltip>
      <TooltipTrigger asChild>{children}</TooltipTrigger>
      <TooltipContent className="flex items-center gap-2">
        {label}
        <Kbd>{keys}</Kbd>
      </TooltipContent>
    </Tooltip>
  );
}

/** Playback controls; the timeline runs over every shot of the replay, with iteration starts marked. */
export const Dock = forwardRef<HTMLDivElement, DockProps>(function Dock(
  { record, scene, counters, playing, speed, fast, markers, onToggle, onStep, onRound, onRestart, onSeek, onSpeed, className },
  ref,
) {
  const total = record.trace.shotCount;
  const last = total - 1;
  return (
    <div ref={ref} className={cn("glass flex flex-col gap-2 rounded-2xl border border-rule/80 px-4 pt-3 pb-2.5 shadow-[0_18px_40px_-28px_rgb(19_42_59/0.7)]", className)}>
      <div className="relative">
        <Slider
          value={[scene.shot]}
          min={0}
          max={last}
          step={1}
          onValueChange={([value]) => onSeek(value)}
          aria-label="搜索过程进度"
          className="[&_[data-slot=slider-range]]:bg-sea [&_[data-slot=slider-thumb]]:border-sea"
        />
        <div aria-hidden className="pointer-events-none absolute inset-x-2 -bottom-1.5 h-1">
          {markers.map((shot, index) => (
            <span key={index} className="absolute top-0 h-1 w-px bg-ink/50" style={{ left: `${(shot / Math.max(1, last)) * 100}%` }} />
          ))}
          <span className="absolute -top-0.5 size-1.5 -translate-x-1/2 rounded-full bg-brass" style={{ left: "100%" }} />
        </div>
      </div>
      <div className="flex flex-wrap items-center gap-x-4 gap-y-2">
        <dl className="order-2 flex items-center gap-4 text-[11px] sm:order-1">
          <Counter label="扩展" value={counters.expanded} total={record.expanded} fast={fast} />
          <Counter label="生成" value={counters.generated} total={record.generated} fast={fast} />
          <Counter label="剪枝" value={counters.pruned} fast={fast} tone="text-signal" />
        </dl>
        <div className="order-1 mx-auto flex items-center gap-1 sm:order-2">
          <Hint label="回到起点" keys="Home">
            <Button variant="ghost" size="icon-sm" onClick={onRestart} disabled={scene.shot === 0} aria-label="回到起点">
              <SkipBack />
            </Button>
          </Hint>
          <Hint label="上一轮" keys="Shift ←">
            <Button variant="ghost" size="icon-sm" onClick={() => onRound(-1)} disabled={scene.shot === 0} aria-label="上一轮">
              <ChevronsLeft />
            </Button>
          </Hint>
          <Hint label="上一步" keys="←">
            <Button variant="ghost" size="icon-sm" onClick={() => onStep(-1)} disabled={scene.shot === 0} aria-label="上一步">
              <ChevronLeft />
            </Button>
          </Hint>
          <Hint label={playing ? "暂停" : scene.shot === last ? "从头播放" : "播放"} keys="Space">
            <Button size="icon-lg" onClick={onToggle} aria-label={playing ? "暂停" : "播放"} className="mx-1 rounded-full shadow-[0_8px_18px_-10px_rgb(19_42_59/0.9)]">
              <AnimatePresence mode="popLayout" initial={false}>
                <motion.span
                  key={playing ? "pause" : "play"}
                  initial={{ scale: 0.5, opacity: 0, rotate: -30 }}
                  animate={{ scale: 1, opacity: 1, rotate: 0 }}
                  exit={{ scale: 0.5, opacity: 0, rotate: 30 }}
                  transition={{ duration: 0.16 }}
                  className="grid place-items-center"
                >
                  {playing ? <Pause className="size-5" /> : <Play className="size-5 translate-x-px" />}
                </motion.span>
              </AnimatePresence>
            </Button>
          </Hint>
          <Hint label="下一步" keys="→">
            <Button variant="ghost" size="icon-sm" onClick={() => onStep(1)} disabled={scene.shot === last} aria-label="下一步">
              <ChevronRight />
            </Button>
          </Hint>
          <Hint label="下一轮" keys="Shift →">
            <Button variant="ghost" size="icon-sm" onClick={() => onRound(1)} disabled={scene.shot === last} aria-label="下一轮">
              <ChevronsRight />
            </Button>
          </Hint>
        </div>
        <div className="order-3 flex items-center gap-3">
          <span className="type-data hidden text-[11px] text-ink-soft md:inline">
            {(scene.shot + 1).toLocaleString("zh-CN")} / {total.toLocaleString("zh-CN")}
          </span>
          <Select value={String(speed)} onValueChange={(value) => onSpeed(Number(value) as Speed)}>
            <SelectTrigger size="sm" className="type-data w-[5.5rem] bg-surface" aria-label="播放速度">
              <SelectValue />
            </SelectTrigger>
            <SelectContent position="popper" side="top" align="end">
              {SPEEDS.map((option) => (
                <SelectItem key={option} value={String(option)} className="type-data">
                  {option}×
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
      </div>
    </div>
  );
});

function Counter({ label, value, total, fast, tone = "text-ink" }: { label: string; value: number; total?: number; fast: boolean; tone?: string }) {
  return (
    <div className="flex items-baseline gap-1.5">
      <dt className="text-ink-soft">{label}</dt>
      <dd className={cn("type-data font-semibold", tone)}>
        <NumberFlow value={value} animated={!fast} locales="zh-CN" />
        {total !== undefined && <span className="hidden font-normal text-ink-soft lg:inline"> / {total.toLocaleString("zh-CN")}</span>}
      </dd>
    </div>
  );
}
