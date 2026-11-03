import { ChevronDown, FileUp, LoaderCircle } from "lucide-react";
import { motion } from "motion/react";
import { BoardThumb } from "@/components/puzzle-board";
import { Button } from "@/components/ui/button";
import { Kbd } from "@/components/ui/kbd";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import { useLibrary } from "@/data/library";
import { HEURISTIC_NAMES } from "@/lib/labels";
import { cn } from "@/lib/utils";
import { useImportPicker } from "./import-context";
import { setLibraryOpen, setView, useUi, type View } from "./ui-store";

const VIEWS: Array<{ id: View; label: string; short: string; key: string }> = [
  { id: "path", label: "解路径回放", short: "解路径", key: "1" },
  { id: "search", label: "搜索过程", short: "搜索", key: "2" },
];

export function TopBar() {
  const view = useUi((state) => state.view);
  const current = useLibrary((state) => state.current);
  const count = useLibrary((state) => state.metas.length);
  const loading = useLibrary((state) => state.loadingId !== null);
  const pickFiles = useImportPicker();

  return (
    <motion.header
      className="relative z-30 flex h-14 shrink-0 items-center gap-3 border-b border-rule/80 bg-paper/85 px-3 backdrop-blur-md sm:gap-5 sm:px-5"
      initial={{ y: -56, opacity: 0 }}
      animate={{ y: 0, opacity: 1 }}
      exit={{ y: -56, opacity: 0 }}
      transition={{ duration: 0.22, ease: [0.22, 0.9, 0.24, 1] }}
    >
      <a href="./" className="flex shrink-0 items-center gap-2.5 rounded-md" aria-label="N-Puzzle 搜索过程可视化">
        <span aria-hidden className="grid size-7 grid-cols-2 gap-[3px] rounded-[7px] bg-tray p-[4px]">
          <span className="rounded-[2px] bg-tile" />
          <span className="rounded-[2px] bg-tile" />
          <span className="rounded-[2px] bg-brass" />
          <span />
        </span>
        <span className="type-display hidden text-[15px] font-bold tracking-wide text-ink sm:inline">
          N<span className="text-brass">·</span>PUZZLE
        </span>
      </a>

      <nav aria-label="视图" className={cn("flex rounded-full border border-rule bg-surface/80 p-0.5", !current && "pointer-events-none opacity-40")}>
        {VIEWS.map((item) => (
          <Tooltip key={item.id}>
            <TooltipTrigger asChild>
              <button
                type="button"
                aria-pressed={view === item.id}
                disabled={!current}
                onClick={() => setView(item.id)}
                className={cn(
                  "relative rounded-full px-3 py-1.5 text-[13px] font-medium transition-colors sm:px-4",
                  view === item.id ? "text-primary-foreground" : "text-ink-soft hover:text-ink",
                )}
              >
                {view === item.id && (
                  <motion.span
                    layoutId="view-pill"
                    className="absolute inset-0 rounded-full bg-ink"
                    transition={{ type: "spring", stiffness: 520, damping: 40 }}
                  />
                )}
                <span className="relative whitespace-nowrap">
                  <span className="sm:hidden">{item.short}</span>
                  <span className="hidden sm:inline">{item.label}</span>
                </span>
              </button>
            </TooltipTrigger>
            <TooltipContent>
              切换到{item.label} <Kbd>{item.key}</Kbd>
            </TooltipContent>
          </Tooltip>
        ))}
      </nav>

      <div className="ml-auto flex min-w-0 items-center gap-2">
        <button
          type="button"
          onClick={() => setLibraryOpen(true)}
          aria-haspopup="dialog"
          className="group flex min-w-0 items-center gap-2.5 rounded-xl border border-rule bg-surface px-2 py-1 text-left transition-[background-color,box-shadow] hover:bg-accent hover:shadow-sm"
        >
          {current ? (
            <BoardThumb board={current.states[0]} size={current.size} className="size-8" />
          ) : (
            <span aria-hidden className="grid size-8 place-items-center rounded-[10px] border border-dashed border-rule text-ink-soft">
              ＋
            </span>
          )}
          <span className="flex min-w-0 flex-col leading-tight">
            <span className="max-w-[9rem] truncate text-[13px] font-semibold text-ink sm:max-w-[14rem]">{current?.name ?? "问题记录"}</span>
            <span className="hidden max-w-[14rem] truncate text-[11px] text-ink-soft sm:block">
              {current ? `${current.algorithm} · ${HEURISTIC_NAMES[current.heuristic]} · ${current.states.length - 1} 步` : "导入与选择"}
            </span>
          </span>
          <span className="type-data flex items-center gap-1 rounded-md bg-paper px-1.5 py-0.5 text-[11px] text-ink-soft">
            {loading ? <LoaderCircle className="size-3 animate-spin" /> : count}
            <ChevronDown className="size-3 transition-transform group-hover:translate-y-px" />
          </span>
        </button>
        <Tooltip>
          <TooltipTrigger asChild>
            <Button variant="outline" size="sm" className="bg-surface" onClick={pickFiles}>
              <FileUp />
              <span className="hidden md:inline">导入</span>
            </Button>
          </TooltipTrigger>
          <TooltipContent>导入 Java 导出的 JSON，也可以直接拖到页面上</TooltipContent>
        </Tooltip>
      </div>
    </motion.header>
  );
}
