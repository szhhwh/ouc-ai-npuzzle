import {
  createColumnHelper,
  createSortedRowModel,
  rowSelectionFeature,
  rowSortingFeature,
  tableFeatures,
  useTable,
  type OnChangeFn,
  type RowSelectionState,
  type SortFn,
  type SortingState,
} from "@tanstack/react-table";
import { ArrowDown, ArrowUp, ArrowUpDown, LoaderCircle, Trash2 } from "lucide-react";
import { AnimatePresence, motion } from "motion/react";
import { useEffect, useMemo, useState } from "react";
import { setLibraryOpen } from "@/app/ui-store";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import { selectRecord, useLibrary } from "@/data/library";
import type { RecordMeta } from "@/data/types";
import { HEURISTIC_NAMES, formatBytes, formatInt, formatMs } from "@/lib/labels";
import { cn } from "@/lib/utils";
import { NameCell } from "./NameCell";

const features = tableFeatures({
  rowSortingFeature,
  rowSelectionFeature,
  sortedRowModel: createSortedRowModel(),
});

type Features = typeof features;

/** Ascending comparator over a record field; the table reverses it for descending order. */
const by =
  (pick: (meta: RecordMeta) => number | string): SortFn<Features, RecordMeta> =>
  (a, b) => {
    const left = pick(a.original);
    const right = pick(b.original);
    return typeof left === "string" ? left.localeCompare(String(right), "zh-CN") : left - Number(right);
  };

const DEFAULT_SORTING: SortingState = [{ id: "importedAt", desc: false }];
const NUMERIC: Record<string, true> = { moves: true, expanded: true, timeMs: true, compressedBytes: true };
const getRowId = (meta: RecordMeta) => meta.id;

interface SortableColumn {
  getIsSorted: () => false | "asc" | "desc";
  getToggleSortingHandler: () => undefined | ((event: unknown) => void);
}

function SortHeader({ column, label, end }: { column: SortableColumn; label: string; end?: boolean }) {
  const sorted = column.getIsSorted();
  const Icon = sorted === "asc" ? ArrowUp : sorted === "desc" ? ArrowDown : ArrowUpDown;
  return (
    <button
      type="button"
      onClick={column.getToggleSortingHandler()}
      className={cn(
        "-mx-2 inline-flex h-8 items-center gap-1.5 rounded-md px-2 transition-colors hover:bg-accent hover:text-ink",
        sorted && "text-ink",
        end && "flex-row-reverse",
      )}
    >
      {label}
      <Icon aria-hidden className={cn("size-3.5", sorted ? "text-sea" : "text-ink-soft/50")} />
    </button>
  );
}

function RowActions({ meta, onRequestDelete }: { meta: RecordMeta; onRequestDelete: (ids: string[]) => void }) {
  const current = useLibrary((state) => state.current?.id === meta.id);
  const loading = useLibrary((state) => state.loadingId === meta.id);
  const busy = useLibrary((state) => state.busy);
  const load = () => {
    selectRecord(meta.id)
      .then(() => setLibraryOpen(false))
      .catch(() => {});
  };
  return (
    <div className="flex items-center justify-end gap-1">
      {current ? (
        <Button size="sm" variant="ghost" disabled className="w-[4.75rem] text-sea">
          已载入
        </Button>
      ) : (
        <Button
          size="sm"
          variant="outline"
          disabled={busy}
          onClick={load}
          className={cn("w-[4.75rem] bg-surface", loading && "disabled:opacity-100")}
        >
          {loading && <LoaderCircle aria-hidden className="animate-spin text-sea" />}
          载入
        </Button>
      )}
      <Tooltip>
        <TooltipTrigger asChild>
          <Button
            size="icon-sm"
            variant="ghost"
            disabled={busy}
            onClick={() => onRequestDelete([meta.id])}
            aria-label={`删除“${meta.name}”`}
            className="text-ink-soft hover:bg-signal-wash hover:text-signal"
          >
            <Trash2 />
          </Button>
        </TooltipTrigger>
        <TooltipContent>删除</TooltipContent>
      </Tooltip>
    </div>
  );
}

const helper = createColumnHelper<Features, RecordMeta>();

function buildColumns(onRequestDelete: (ids: string[]) => void) {
  return helper.columns([
    helper.display({
      id: "select",
      header: ({ table }) => (
        <Checkbox
          checked={table.getIsAllRowsSelected() ? true : table.getIsSomeRowsSelected() ? "indeterminate" : false}
          onCheckedChange={(checked) => table.toggleAllRowsSelected(checked === true)}
          aria-label="选择全部记录"
        />
      ),
      cell: ({ row }) => (
        <Checkbox
          checked={row.getIsSelected()}
          onCheckedChange={(checked) => row.toggleSelected(checked === true)}
          aria-label={`选择“${row.original.name}”`}
        />
      ),
    }),
    helper.accessor("name", {
      header: ({ column }) => <SortHeader column={column} label="起点 / 名称" />,
      sortFn: by((meta) => meta.name),
      cell: ({ row }) => <NameCell meta={row.original} />,
    }),
    helper.accessor("size", {
      header: ({ column }) => <SortHeader column={column} label="棋盘" />,
      sortFn: by((meta) => meta.size),
      cell: ({ row }) => <span className="type-data text-xs">{`${row.original.size}×${row.original.size}`}</span>,
    }),
    helper.accessor("algorithm", {
      header: ({ column }) => <SortHeader column={column} label="算法 / 启发函数" />,
      sortFn: by((meta) => meta.algorithm),
      cell: ({ row }) => (
        <div className="leading-tight">
          <div className="font-medium text-ink">{row.original.algorithm}</div>
          <div className="mt-0.5 text-xs text-ink-soft">{HEURISTIC_NAMES[row.original.heuristic]}</div>
        </div>
      ),
    }),
    helper.accessor("moves", {
      header: ({ column }) => <SortHeader column={column} label="路径" end />,
      sortFn: by((meta) => meta.moves),
      cell: ({ row }) => (
        <span>
          <span className="type-data text-xs text-ink">{formatInt(row.original.moves)}</span>
          <span className="ml-1 text-xs text-ink-soft">步</span>
        </span>
      ),
    }),
    helper.accessor("expanded", {
      header: ({ column }) => <SortHeader column={column} label="扩展 / 生成" end />,
      sortFn: by((meta) => meta.expanded),
      cell: ({ row }) => (
        <div className="type-data text-xs leading-tight">
          <div className="text-ink">{formatInt(row.original.expanded)}</div>
          <div className="mt-1 text-ink-soft">{formatInt(row.original.generated)}</div>
        </div>
      ),
    }),
    helper.accessor("timeMs", {
      header: ({ column }) => <SortHeader column={column} label="耗时" end />,
      sortFn: by((meta) => meta.timeMs),
      cell: ({ row }) => (
        <span>
          <span className="type-data text-xs text-ink">{formatMs(row.original.timeMs)}</span>
          <span className="ml-1 text-xs text-ink-soft">ms</span>
        </span>
      ),
    }),
    helper.accessor("compressedBytes", {
      header: "大小",
      enableSorting: false,
      cell: ({ row }) => <span className="type-data text-xs text-ink-soft">{formatBytes(row.original.compressedBytes)}</span>,
    }),
    helper.accessor("importedAt", {
      header: ({ column }) => <SortHeader column={column} label="导入时间" />,
      sortFn: by((meta) => meta.importedAt),
      cell: ({ row }) => (
        <span className="type-data text-xs text-ink-soft">{new Date(row.original.importedAt).toLocaleString("zh-CN")}</span>
      ),
    }),
    helper.display({
      id: "actions",
      header: () => <span className="sr-only">操作</span>,
      cell: ({ row }) => <RowActions meta={row.original} onRequestDelete={onRequestDelete} />,
    }),
  ]);
}

const MotionRow = motion.create(TableRow);

interface RecordTableProps {
  metas: RecordMeta[];
  selection: RowSelectionState;
  onSelectionChange: OnChangeFn<RowSelectionState>;
  onRequestDelete: (ids: string[]) => void;
}

export function RecordTable({ metas, selection, onSelectionChange, onRequestDelete }: RecordTableProps) {
  const currentId = useLibrary((state) => state.current?.id);
  const [sorting, setSorting] = useState<SortingState>(DEFAULT_SORTING);
  const columns = useMemo(() => buildColumns(onRequestDelete), [onRequestDelete]);
  // Rows stagger in only while the dialog is opening; later imports appear at once.
  const [opening, setOpening] = useState(true);
  useEffect(() => {
    const timer = window.setTimeout(() => setOpening(false), 400);
    return () => window.clearTimeout(timer);
  }, []);

  const table = useTable({
    features,
    columns,
    data: metas,
    getRowId,
    state: { sorting, rowSelection: selection },
    onSortingChange: setSorting,
    onRowSelectionChange: onSelectionChange,
    enableSortingRemoval: false,
    enableMultiSort: false,
    sortDescFirst: false,
    enableSubRowSelection: false,
  });

  return (
    <motion.div layoutScroll className="min-h-0 flex-1 overflow-auto overscroll-contain">
      <table data-slot="table" className="w-full min-w-[1040px] caption-bottom text-sm">
        <TableHeader className="[&_tr]:border-b-0">
          {table.getHeaderGroups().map((group) => (
            <TableRow key={group.id} className="hover:bg-transparent">
              {group.headers.map((header) => {
                const sorted = header.column.getCanSort() ? header.column.getIsSorted() : null;
                return (
                  <TableHead
                    key={header.id}
                    aria-sort={sorted === null ? undefined : sorted === "asc" ? "ascending" : sorted === "desc" ? "descending" : "none"}
                    className={cn(
                      "sticky top-0 z-10 h-11 bg-surface text-xs font-medium text-ink-soft shadow-[inset_0_-1px_0_var(--rule)]",
                      header.id === "select" && "w-10 pl-6",
                      NUMERIC[header.id] && "text-right",
                      header.id === "actions" && "pr-6",
                    )}
                  >
                    {header.isPlaceholder ? null : <table.FlexRender header={header} />}
                  </TableHead>
                );
              })}
            </TableRow>
          ))}
        </TableHeader>
        <TableBody>
          <AnimatePresence initial>
            {table.getRowModel().rows.map((row, index) => {
              const current = row.id === currentId;
              return (
                <MotionRow
                  key={row.id}
                  layout="position"
                  data-state={row.getIsSelected() ? "selected" : undefined}
                  initial={{ opacity: 0, y: 6 }}
                  animate={{ opacity: 1, y: 0 }}
                  exit={{ opacity: 0, x: -12, transition: { duration: 0.14, ease: "easeIn" } }}
                  transition={{
                    duration: 0.18,
                    ease: [0.22, 0.9, 0.24, 1],
                    delay: opening ? Math.min(index, 8) * 0.02 : 0,
                  }}
                  className={cn(
                    "duration-150 data-[state=selected]:bg-accent",
                    current && "bg-sea-wash/55 hover:bg-sea-wash/80 data-[state=selected]:bg-sea-wash",
                  )}
                >
                  {row.getAllCells().map((cell) => (
                    <TableCell
                      key={cell.id}
                      className={cn(
                        "py-2.5",
                        cell.column.id === "select" && "w-10 pl-6",
                        cell.column.id === "select" && current && "shadow-[inset_3px_0_0_var(--sea)]",
                        NUMERIC[cell.column.id] && "text-right",
                        cell.column.id === "actions" && "pr-6",
                      )}
                    >
                      <table.FlexRender cell={cell} />
                    </TableCell>
                  ))}
                </MotionRow>
              );
            })}
          </AnimatePresence>
        </TableBody>
      </table>
    </motion.div>
  );
}
