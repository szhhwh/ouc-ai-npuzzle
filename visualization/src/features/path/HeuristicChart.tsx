import type { KeyboardEvent, PointerEvent } from "react";
import { motion } from "motion/react";
import type { HeuristicQuality } from "@/data/types";
import { formatEstimate } from "@/lib/labels";

const W = 320;
const H = 132;
const PAD = { left: 26, right: 10, top: 10, bottom: 20 };
const PLOT_W = W - PAD.left - PAD.right;
const PLOT_H = H - PAD.top - PAD.bottom;
const MARKER_SPRING = { type: "spring", stiffness: 420, damping: 40 } as const;

interface HeuristicChartProps {
  /** h of the solution node at each step. */
  hs: readonly (number | null)[];
  qualities: readonly HeuristicQuality[];
  step: number;
  onSeek: (step: number) => void;
}

/** h along the solution against the true remaining distance; an admissible h stays on or below the dashed line. */
export function HeuristicChart({ hs, qualities, step, onSeek }: HeuristicChartProps) {
  const total = hs.length - 1;
  const yMax = Math.max(total, ...hs.filter((h): h is number => h !== null), 1);
  const x = (i: number) => PAD.left + (total ? (i / total) * PLOT_W : PLOT_W / 2);
  const y = (v: number) => PAD.top + (1 - v / yMax) * PLOT_H;
  let connected = false;
  const hLine = hs.map((h, i) => {
    if (h === null) { connected = false; return ""; }
    const command = connected ? "L" : "M";
    connected = true;
    return `${command}${x(i).toFixed(2)},${y(h).toFixed(2)}`;
  }).join("");
  const currentH = hs[step];

  const seekAt = (event: PointerEvent<SVGSVGElement>) => {
    const rect = event.currentTarget.getBoundingClientRect();
    const px = ((event.clientX - rect.left) / rect.width) * W;
    onSeek(total ? Math.round(((px - PAD.left) / PLOT_W) * total) : 0);
  };

  const onKeyDown = (event: KeyboardEvent<SVGSVGElement>) => {
    const target = { ArrowLeft: step - 1, ArrowDown: step - 1, ArrowRight: step + 1, ArrowUp: step + 1, Home: 0, End: total }[event.key];
    if (target === undefined) return;
    event.preventDefault();
    onSeek(target);
  };

  return (
    <svg
      viewBox={`0 0 ${W} ${H}`}
      className="block h-auto w-full cursor-crosshair touch-none rounded-md select-none"
      role="slider"
      tabIndex={0}
      aria-label="按启发值曲线定位步数"
      aria-valuemin={0}
      aria-valuemax={total}
      aria-valuenow={step}
      aria-valuetext={`第 ${step} 步，h ${formatEstimate(currentH, qualities[step])}，剩余 ${total - step} 步`}
      onPointerDown={(event) => {
        event.currentTarget.setPointerCapture(event.pointerId);
        seekAt(event);
      }}
      onPointerMove={(event) => {
        if (event.currentTarget.hasPointerCapture(event.pointerId)) seekAt(event);
      }}
      onKeyDown={onKeyDown}
    >
      {[0, yMax].map((v) => (
        <g key={v}>
          <line x1={PAD.left} x2={W - PAD.right} y1={y(v)} y2={y(v)} className="stroke-rule" strokeWidth={1} />
          <text x={PAD.left - 6} y={y(v)} dy="0.35em" textAnchor="end" className="type-data fill-ink-soft text-[9px]">
            {v}
          </text>
        </g>
      ))}
      {[0, total].map((i) => (
        <text key={`x${i}`} x={x(i)} y={H - 5} textAnchor="middle" className="type-data fill-ink-soft text-[9px]">
          {i}
        </text>
      ))}
      <line
        x1={x(0)}
        y1={y(total)}
        x2={x(total)}
        y2={y(0)}
        className="stroke-ink"
        strokeWidth={1.25}
        strokeDasharray="4 3"
        strokeOpacity={0.7}
      />
      <path d={hLine} fill="none" className="stroke-magenta" strokeWidth={2} strokeLinejoin="round" strokeLinecap="round" />
      {qualities[step] !== "exact" && <text x={W - PAD.right} y={PAD.top + 10} textAnchor="end" className="type-data fill-magenta text-[10px]">h {formatEstimate(currentH, qualities[step])}</text>}
      <motion.g initial={false} animate={{ x: x(step) }} transition={MARKER_SPRING}>
        <line x1={0} x2={0} y1={PAD.top} y2={H - PAD.bottom} className="stroke-brass" strokeWidth={1.5} />
        <motion.circle cx={0} r={3} initial={false} animate={{ cy: y(total - step) }} transition={MARKER_SPRING} className="fill-surface stroke-ink" strokeWidth={1.25} />
        {currentH !== null && <motion.circle cx={0} r={3.5} initial={false} animate={{ cy: y(currentH) }} transition={MARKER_SPRING} className={qualities[step] === "lower-bound" ? "fill-surface stroke-magenta" : "fill-magenta stroke-surface"} strokeWidth={1.25} />}
      </motion.g>
    </svg>
  );
}
