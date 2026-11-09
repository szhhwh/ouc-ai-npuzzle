import { useEffect, useRef } from "react";
import { motion, useReducedMotion } from "motion/react";
import { ScrollArea } from "@/components/ui/scroll-area";
import type { HeuristicQuality } from "@/data/types";
import { DIRECTION_ARROWS, DIRECTION_WORDS, formatEstimate } from "@/lib/labels";
import type { Move } from "@/lib/puzzle";
import { cn } from "@/lib/utils";

interface MoveListProps {
  /** moves[i] turns step i into step i + 1. */
  moves: readonly Move[];
  hs: readonly (number | null)[];
  qualities: readonly HeuristicQuality[];
  step: number;
  onSeek: (step: number) => void;
  className?: string;
}

/** One row per step of the solution; the active row follows playback. */
export function MoveList({ moves, hs, qualities, step, onSeek, className }: MoveListProps) {
  const rows = useRef<(HTMLButtonElement | null)[]>([]);
  const reduced = useReducedMotion();

  // Scroll only the list viewport (never the page) so the board stays in view on narrow screens.
  useEffect(() => {
    const row = rows.current[step];
    const viewport = row?.closest<HTMLElement>('[data-slot="scroll-area-viewport"]');
    if (!row || !viewport) return;
    const rowBox = row.getBoundingClientRect();
    const box = viewport.getBoundingClientRect();
    const offset = rowBox.top < box.top ? rowBox.top - box.top : rowBox.bottom > box.bottom ? rowBox.bottom - box.bottom : 0;
    if (offset) viewport.scrollTo({ top: viewport.scrollTop + offset, behavior: reduced ? "auto" : "smooth" });
  }, [step, reduced]);

  return (
    <ScrollArea className={cn("min-h-0", className)}>
      <ol className="flex flex-col gap-0.5 p-1.5" aria-label="解路径步骤">
        {hs.map((h, i) => {
          const move = i ? moves[i - 1] : null;
          const active = i === step;
          return (
            <li key={i}>
              <button
                ref={(node) => {
                  rows.current[i] = node;
                }}
                type="button"
                aria-current={active ? "step" : undefined}
                onClick={() => onSeek(i)}
                className={cn(
                  "relative grid w-full grid-cols-[2.25rem_minmax(0,1fr)_auto] items-center gap-2 rounded-md px-2 py-1.5 text-left transition-colors",
                  "hover:bg-paper-deep/70 focus-visible:outline-offset-0",
                  active ? "text-ink" : "text-ink-soft",
                )}
              >
                {active && (
                  <motion.span
                    layoutId="path-move-active"
                    className="absolute inset-0 rounded-md bg-brass-wash shadow-[inset_2px_0_0_var(--brass)]"
                    transition={{ type: "spring", stiffness: 520, damping: 42 }}
                  />
                )}
                <span className="type-data relative text-xs">{i}</span>
                <span className="relative flex items-center gap-1.5 text-sm">
                  {move ? (
                    <>
                      <span className="type-data inline-grid h-6 min-w-6 place-items-center rounded-[5px] bg-tile px-1 text-xs font-semibold text-ink shadow-[inset_0_-2px_0_var(--tile-edge)]">
                        {move.tile}
                      </span>
                      <span aria-hidden className="text-ink">
                        {DIRECTION_ARROWS[move.direction]}
                      </span>
                      <span>{DIRECTION_WORDS[move.direction]}</span>
                    </>
                  ) : (
                    <span className="font-medium text-ink">起点</span>
                  )}
                </span>
                <span className="type-data relative text-xs text-magenta">h {formatEstimate(h, qualities[i])}</span>
              </button>
            </li>
          );
        })}
      </ol>
    </ScrollArea>
  );
}
