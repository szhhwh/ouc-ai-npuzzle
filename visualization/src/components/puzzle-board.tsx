import { motion, useReducedMotion } from "motion/react";
import { cn } from "@/lib/utils";

const GAP = 7; // tile gap, percent of a tile's width

interface PuzzleBoardProps {
  board: ArrayLike<number>;
  size: number;
  /** Tile that made the latest move; marked with a brass edge. */
  movedTile?: number | null;
  /** Per-cell heuristic contribution, shown as chart-magenta soundings on each tile. */
  terms?: readonly number[] | null;
  /** Seconds for tile slides; 0 snaps. */
  duration?: number;
  className?: string;
  label?: string;
}

/** A physical tray of ivory tiles. Tiles are keyed by value so every move slides rather than redraws. */
export function PuzzleBoard({ board, size, movedTile, terms, duration = 0.26, className, label }: PuzzleBoardProps) {
  const reduced = useReducedMotion();
  const cells = Array.from(board);
  const tile = `calc(100% / (${size} + ${((size - 1) * GAP) / 100}))`;
  return (
    <div
      role="img"
      aria-label={label ?? `棋盘 ${cells.map((value) => value || "空").join(" ")}`}
      className={cn("relative aspect-square rounded-[6%] bg-tray shadow-[inset_0_2px_10px_rgb(0_0_0/0.35),0_18px_40px_-24px_rgb(19_42_59/0.6)]", className)}
      style={{ containerType: "inline-size" }}
    >
      {/* Inset instead of padding: padding percentages follow the parent's width, not the board's. */}
      <div className="absolute inset-[4%]">
        {cells.map((value, cell) => {
          if (!value) return null;
          const term = terms?.[cell];
          return (
            <motion.div
              key={value}
              className={cn(
                "absolute top-0 left-0 grid place-items-center rounded-[14%] bg-tile text-ink type-display font-bold",
                "shadow-[inset_0_-0.09em_0_var(--tile-edge),inset_0_0.05em_0_rgb(255_255_255/0.8),0_0.06em_0.12em_rgb(0_0_0/0.35)]",
                value === movedTile && "bg-brass-wash text-ink ring-[0.12em] ring-brass ring-inset",
              )}
              style={{ width: tile, height: tile, fontSize: `calc(100cqi / ${size} * 0.4)` }}
              initial={false}
              animate={{ x: `${(cell % size) * (100 + GAP)}%`, y: `${Math.floor(cell / size) * (100 + GAP)}%` }}
              transition={reduced || duration === 0 ? { duration: 0 } : { duration, ease: [0.22, 0.9, 0.24, 1] }}
            >
              {value}
              {term != null && (
                <span
                  className={cn(
                    "type-data absolute top-[8%] right-[9%] font-semibold leading-none",
                    term ? "text-magenta" : "text-ink-soft/45",
                  )}
                  style={{ fontSize: `calc(100cqi / ${size} * 0.15)` }}
                >
                  {term}
                </span>
              )}
            </motion.div>
          );
        })}
      </div>
    </div>
  );
}

/** Static miniature for lists and tables. */
export function BoardThumb({ board, size, className }: { board: ArrayLike<number>; size: number; className?: string }) {
  const cells = Array.from(board);
  return (
    <div
      role="img"
      aria-label={`初始棋盘 ${cells.map((value) => value || "空").join(" ")}`}
      className={cn("relative aspect-square shrink-0 rounded-[12%] bg-tray", className)}
      style={{ containerType: "inline-size" }}
    >
      <div
        className="absolute inset-[8%] grid gap-[6%]"
        style={{ gridTemplateColumns: `repeat(${size}, minmax(0, 1fr))`, gridTemplateRows: `repeat(${size}, minmax(0, 1fr))` }}
      >
        {cells.map((value, cell) => (
          <span
            key={cell}
            className={cn("type-data grid place-items-center rounded-[18%] leading-none font-semibold", value ? "bg-tile text-ink" : "")}
            style={{ fontSize: `calc(100cqi / ${size} * 0.42)` }}
          >
            {value || ""}
          </span>
        ))}
      </div>
    </div>
  );
}
