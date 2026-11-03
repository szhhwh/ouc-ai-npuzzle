import type { ReactNode } from "react";
import { AnimatePresence, motion } from "motion/react";
import { ChevronLeft, ChevronRight, Pause, Play, SkipBack } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Kbd } from "@/components/ui/kbd";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Slider } from "@/components/ui/slider";
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from "@/components/ui/tooltip";
import { cn } from "@/lib/utils";
import { SPEEDS, usePathPlayback, type Speed } from "./path-store";

/** Fine tick marks under the step slider stay legible up to this many moves. */
const MAX_TICKS = 80;

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

/** Playback buttons, speed and the step scrubber for a solution of `total` moves. */
export function Transport({ total, className }: { total: number; className?: string }) {
  const { step, playing, speed, toggle, stepBy, seek, setSpeed } = usePathPlayback();
  const ticks = total > 0 && total <= MAX_TICKS;

  return (
    <TooltipProvider delayDuration={300}>
      <div className={cn("flex w-full flex-col gap-4", className)}>
        <div className="relative pb-2">
          <Slider
            value={[step]}
            min={0}
            max={Math.max(total, 1)}
            step={1}
            disabled={total === 0}
            onValueChange={([value]) => seek(value, total)}
            aria-label="当前步数"
            className="py-2"
          />
          {ticks && (
            <div aria-hidden className="pointer-events-none absolute inset-x-2 bottom-0 h-1.5">
              {Array.from({ length: total + 1 }, (_, i) => (
                <span
                  key={i}
                  className={cn(
                    "absolute top-0 w-px -translate-x-1/2",
                    i <= step ? "bg-ink/55" : "bg-rule",
                    i % 5 === 0 || i === total ? "h-1.5" : "h-1",
                  )}
                  style={{ left: `${(i / total) * 100}%` }}
                />
              ))}
            </div>
          )}
        </div>

        <div className="flex flex-wrap items-center justify-center gap-x-3 gap-y-2">
          <div className="flex items-center gap-1.5">
            <Hint label="回到起点" keys="Home">
              <Button variant="ghost" size="icon" aria-label="回到起点" disabled={step === 0} onClick={() => seek(0, total)}>
                <SkipBack />
              </Button>
            </Hint>
            <Hint label="上一步" keys="←">
              <Button variant="ghost" size="icon" aria-label="上一步" disabled={step === 0} onClick={() => stepBy(-1, total)}>
                <ChevronLeft className="size-5" />
              </Button>
            </Hint>
            <Hint label={playing ? "暂停" : step >= total ? "从头播放" : "播放"} keys="空格">
              <Button
                size="icon"
                aria-label={playing ? "暂停" : "播放"}
                disabled={total === 0}
                onClick={() => toggle(total)}
                className="size-12 rounded-full shadow-[0_8px_20px_-10px_var(--ink)] [&_svg:not([class*='size-'])]:size-5"
              >
                <AnimatePresence mode="popLayout" initial={false}>
                  <motion.span
                    key={playing ? "pause" : "play"}
                    className="grid place-items-center"
                    initial={{ scale: 0.5, rotate: -60, opacity: 0 }}
                    animate={{ scale: 1, rotate: 0, opacity: 1 }}
                    exit={{ scale: 0.5, rotate: 60, opacity: 0 }}
                    transition={{ duration: 0.16, ease: "easeOut" }}
                  >
                    {playing ? <Pause fill="currentColor" /> : <Play fill="currentColor" className="translate-x-px" />}
                  </motion.span>
                </AnimatePresence>
              </Button>
            </Hint>
            <Hint label="下一步" keys="→">
              <Button variant="ghost" size="icon" aria-label="下一步" disabled={step >= total} onClick={() => stepBy(1, total)}>
                <ChevronRight className="size-5" />
              </Button>
            </Hint>
          </div>

          <Select value={String(speed)} onValueChange={(value) => setSpeed(Number(value) as Speed)}>
            <SelectTrigger size="sm" aria-label="播放速度" className="type-data w-[5.5rem] bg-surface">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {SPEEDS.map((value) => (
                <SelectItem key={value} value={String(value)} className="type-data">
                  {value}×
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
      </div>
    </TooltipProvider>
  );
}
