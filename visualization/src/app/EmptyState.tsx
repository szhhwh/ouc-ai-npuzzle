import { Check, Copy, FileJson, LoaderCircle } from "lucide-react";
import { motion } from "motion/react";
import { useEffect, useMemo, useState } from "react";
import { PuzzleBoard } from "@/components/puzzle-board";
import { Button } from "@/components/ui/button";
import { useImportPicker } from "./import-context";

const EXPORT_COMMAND = "./scripts/export-visualization.sh resources/problems-stage1.txt results.json astar MANHATTAN";

/** Blank walk on a 3×3 board, played forward then back so the demo always returns to the goal. */
function useDemoBoards() {
  return useMemo(() => {
    const walk = [8, 5, 4, 7, 6, 3, 0, 1, 4, 5];
    const boards: number[][] = [[1, 2, 3, 4, 5, 6, 7, 8, 0]];
    for (let i = 1; i < walk.length; i++) {
      const board = [...boards[i - 1]];
      [board[walk[i - 1]], board[walk[i]]] = [board[walk[i]], 0];
      boards.push(board);
    }
    return [...boards, ...boards.slice(1, -1).reverse()];
  }, []);
}

export function EmptyState({ failure }: { failure: string | null }) {
  const pickFiles = useImportPicker();
  const boards = useDemoBoards();
  const [frame, setFrame] = useState(0);
  const [copied, setCopied] = useState(false);
  useEffect(() => {
    const timer = setInterval(() => setFrame((value) => (value + 1) % boards.length), 900);
    return () => clearInterval(timer);
  }, [boards.length]);

  const copy = async () => {
    await navigator.clipboard?.writeText(EXPORT_COMMAND);
    setCopied(true);
    setTimeout(() => setCopied(false), 1600);
  };

  return (
    <motion.section
      className="chart-grid flex h-full items-center justify-center overflow-y-auto px-5 py-10"
      initial={{ opacity: 0 }}
      animate={{ opacity: 1 }}
      exit={{ opacity: 0 }}
    >
      <div className="grid w-full max-w-5xl items-center gap-10 md:grid-cols-[minmax(0,5fr)_minmax(0,6fr)] md:gap-16">
        <motion.div
          className="mx-auto w-[min(72vw,340px)]"
          initial={{ opacity: 0, scale: 0.94, rotate: -2 }}
          animate={{ opacity: 1, scale: 1, rotate: -2 }}
          transition={{ duration: 0.5, ease: [0.22, 0.9, 0.24, 1] }}
        >
          <PuzzleBoard board={boards[frame]} size={3} duration={0.42} className="w-full" label="演示棋盘" />
        </motion.div>
        <motion.div
          className="flex flex-col gap-6"
          initial={{ opacity: 0, y: 12 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.4, delay: 0.1 }}
        >
          <div className="flex flex-col gap-3">
            <h1 className="type-display text-[clamp(28px,4vw,44px)] leading-[1.08] font-bold text-ink">导入算法搜索记录</h1>
            <p className="max-w-[46ch] text-[15px] leading-relaxed text-ink-soft">
              导入求解记录 JSON 文件，即可单步回放解题路径，或交互式探索搜索树的生成、展开与剪枝过程。
            </p>
          </div>
          {failure && <p className="rounded-lg border border-signal/40 bg-signal-wash px-3 py-2 text-[13px] text-signal">{failure}</p>}
          <div className="flex flex-wrap items-center gap-3">
            <Button size="lg" onClick={pickFiles} className="shadow-[0_10px_24px_-14px_rgb(19_42_59/0.8)]">
              <FileJson />
              选择 JSON 文件
            </Button>
            <span className="text-[13px] text-ink-soft">或直接把文件拖到页面任意位置</span>
          </div>
          <div className="flex flex-col gap-2">
            <span className="text-[12px] text-ink-soft">还没有记录文件？可在终端运行以下命令快速生成：</span>
            <div className="flex items-start gap-2 rounded-xl border border-rule bg-surface p-3">
              <code className="type-data min-w-0 flex-1 text-[11.5px] leading-relaxed break-all text-ink">{EXPORT_COMMAND}</code>
              <Button variant="ghost" size="icon-sm" onClick={copy} aria-label="复制命令">
                {copied ? <Check className="text-sea" /> : <Copy />}
              </Button>
            </div>
          </div>
        </motion.div>
      </div>
    </motion.section>
  );
}

export function LoadingState({ label }: { label: string }) {
  return (
    <motion.div
      className="chart-grid flex h-full flex-col items-center justify-center gap-3 text-ink-soft"
      role="status"
      initial={{ opacity: 0 }}
      animate={{ opacity: 1 }}
      exit={{ opacity: 0 }}
      transition={{ delay: 0.15 }}
    >
      <LoaderCircle className="size-6 animate-spin text-sea" />
      <span className="text-[13px]">{label}</span>
    </motion.div>
  );
}

export function DropOverlay() {
  return (
    <motion.div
      className="pointer-events-none fixed inset-0 z-[60] grid place-items-center bg-paper/70 p-6 backdrop-blur-sm"
      initial={{ opacity: 0 }}
      animate={{ opacity: 1 }}
      exit={{ opacity: 0 }}
      transition={{ duration: 0.15 }}
    >
      <motion.div
        className="flex h-full w-full flex-col items-center justify-center gap-3 rounded-3xl border-2 border-dashed border-sea bg-sea-wash/50"
        initial={{ scale: 0.97 }}
        animate={{ scale: 1 }}
      >
        <FileJson className="size-10 text-sea" />
        <span className="type-display text-2xl font-bold text-ink">松开以导入</span>
        <span className="text-[13px] text-ink-soft">支持导入 .json 文件，可一次拖入多个</span>
      </motion.div>
    </motion.div>
  );
}
