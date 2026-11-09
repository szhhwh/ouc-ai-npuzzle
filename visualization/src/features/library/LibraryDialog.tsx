import NumberFlow from "@number-flow/react";
import type { OnChangeFn, RowSelectionState } from "@tanstack/react-table";
import { FileUp, LibraryBig, LoaderCircle, Trash2, X } from "lucide-react";
import { AnimatePresence, motion } from "motion/react";
import { useCallback, useState } from "react";
import { useImportPicker } from "@/app/import-context";
import { setLibraryOpen, useUi } from "@/app/ui-store";
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import { Button } from "@/components/ui/button";
import { Dialog, DialogClose, DialogContent, DialogDescription, DialogTitle } from "@/components/ui/dialog";
import { TooltipProvider } from "@/components/ui/tooltip";
import { removeRecords, useLibrary } from "@/data/library";
import type { RecordMeta } from "@/data/types";
import { formatBytes } from "@/lib/labels";
import { RENAME_ATTRIBUTE } from "./NameCell";
import { RecordTable } from "./RecordTable";

/** Returns the same object when every selected id still exists, so the caller can skip the update. */
function pruneSelection(selection: RowSelectionState, metas: RecordMeta[]) {
  const ids = Object.keys(selection);
  if (ids.length === 0) return selection;
  const existing = new Set(metas.map((meta) => meta.id));
  const kept = ids.filter((id) => existing.has(id));
  return kept.length === ids.length ? selection : Object.fromEntries(kept.map((id) => [id, true as const]));
}

export function LibraryDialog() {
  const open = useUi((state) => state.libraryOpen);

  return (
    <Dialog open={open} onOpenChange={setLibraryOpen}>
      <DialogContent
        showCloseButton={false}
        onEscapeKeyDown={(event) => {
          // Escape inside a name field reverts the edit; it must not close the library.
          if (event.target instanceof Element && event.target.closest(`[${RENAME_ATTRIBUTE}]`)) event.preventDefault();
        }}
        className="flex max-h-[85dvh] w-[calc(100vw-2rem)] max-w-[1180px] flex-col gap-0 overflow-hidden border-rule bg-surface p-0 shadow-[0_40px_80px_-32px_rgb(19_42_59/0.45)] sm:max-w-[1180px]"
      >
        <TooltipProvider delayDuration={300}>
          <LibraryPanel />
        </TooltipProvider>
      </DialogContent>
    </Dialog>
  );
}

function LibraryPanel() {
  const metas = useLibrary((state) => state.metas);
  const busy = useLibrary((state) => state.busy);
  const phase = useLibrary((state) => state.phase);
  const failure = useLibrary((state) => state.failure);
  const pickFiles = useImportPicker();

  const [selection, setSelection] = useState<RowSelectionState>({});
  const pruned = pruneSelection(selection, metas);
  if (pruned !== selection) setSelection(pruned);
  const selectedIds = Object.keys(pruned);

  const [confirm, setConfirm] = useState<{ ids: string[]; open: boolean }>({ ids: [], open: false });
  const requestDelete = useCallback((ids: string[]) => setConfirm({ ids, open: true }), []);
  const onSelectionChange = useCallback<OnChangeFn<RowSelectionState>>(
    (updater) => setSelection((old) => (typeof updater === "function" ? updater(old) : updater)),
    [],
  );

  const confirmDelete = () => {
    const ids = confirm.ids;
    removeRecords(ids)
      .then(() =>
        setSelection((old) => Object.fromEntries(Object.keys(old).filter((id) => !ids.includes(id)).map((id) => [id, true as const]))),
      )
      .catch(() => {});
  };

  const totalBytes = metas.reduce((sum, meta) => sum + meta.compressedBytes, 0);
  const empty = metas.length === 0;

  return (
    <>
      <header className="relative shrink-0 border-b border-rule px-6 pt-6 pb-5 pr-16">
        <DialogTitle className="flex items-baseline gap-2.5 text-2xl font-semibold text-ink">
          <span className="type-display">搜索记录</span>
          <span className="type-data text-base font-normal text-ink-soft">
            <NumberFlow value={metas.length} locales="zh-CN" />
            <span className="ml-1 font-sans text-sm">条</span>
          </span>
        </DialogTitle>
        <DialogDescription className="mt-1.5 max-w-prose text-sm text-ink-soft">
          所有记录均保存在本地浏览器，支持随时切换、重命名与回放。
        </DialogDescription>
        <DialogClose asChild>
          <Button variant="ghost" size="icon-sm" aria-label="关闭" className="absolute top-4 right-4 text-ink-soft">
            <X />
          </Button>
        </DialogClose>
      </header>

      {empty ? (
        <EmptyState starting={phase === "starting"} failure={phase === "failed" ? failure : null} busy={busy} onImport={pickFiles} />
      ) : (
        <>
          <div className="flex shrink-0 flex-wrap items-center gap-x-4 gap-y-2 border-b border-rule bg-paper/60 px-6 py-3">
            <Button onClick={pickFiles} disabled={busy}>
              <FileUp />
              导入记录
            </Button>
            <div className="ml-auto flex items-center gap-3">
              <p aria-live="polite" className="text-xs text-ink-soft">
                <AnimatePresence initial={false}>
                  {selectedIds.length > 0 && (
                    <motion.span
                      key="selection"
                      initial={{ opacity: 0, x: 6 }}
                      animate={{ opacity: 1, x: 0 }}
                      exit={{ opacity: 0, x: 6 }}
                      transition={{ duration: 0.15 }}
                      className="inline-block"
                    >
                      已选择 <span className="type-data text-ink">{selectedIds.length}</span> 条
                    </motion.span>
                  )}
                </AnimatePresence>
              </p>
              <Button
                variant="outline"
                disabled={busy || selectedIds.length === 0}
                onClick={() => requestDelete(selectedIds)}
                className="border-signal/40 bg-surface text-signal hover:bg-signal-wash hover:text-signal"
              >
                <Trash2 />
                删除所选
              </Button>
            </div>
          </div>
          <RecordTable metas={metas} selection={pruned} onSelectionChange={onSelectionChange} onRequestDelete={requestDelete} />
        </>
      )}

      <footer className="shrink-0 border-t border-rule bg-paper/60 px-6 py-3 text-xs text-ink-soft">
        {empty ? (
          "暂无已保存的记录"
        ) : (
          <>
            共 {metas.length} 条记录，占用本地空间 <span className="type-data text-ink">{formatBytes(totalBytes)}</span>
          </>
        )}
      </footer>

      <AlertDialog open={confirm.open} onOpenChange={(open) => setConfirm((previous) => ({ ...previous, open }))}>
        <AlertDialogContent className="border-rule bg-surface">
          <AlertDialogHeader>
            <AlertDialogTitle className="text-ink">删除所选的 {confirm.ids.length} 条记录？</AlertDialogTitle>
            <AlertDialogDescription>此操作无法撤销，确认从本地移除这些记录？</AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>取消</AlertDialogCancel>
            <AlertDialogAction variant="destructive" onClick={confirmDelete}>
              删除
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </>
  );
}

function EmptyState({
  starting,
  failure,
  busy,
  onImport,
}: {
  starting: boolean;
  failure: string | null;
  busy: boolean;
  onImport: () => void;
}) {
  return (
    <div className="min-h-0 flex-1 overflow-auto p-6">
      <div className="chart-grid flex min-h-72 flex-col items-center justify-center gap-4 rounded-lg border border-dashed border-rule px-6 py-12 text-center">
        {starting ? (
          <p className="flex items-center gap-2 text-sm text-ink-soft">
            <LoaderCircle aria-hidden className="size-4 animate-spin text-sea" />
            正在加载记录列表…
          </p>
        ) : (
          <>
            <div className="grid size-14 place-items-center rounded-xl bg-tray text-tile shadow-[inset_0_2px_8px_rgb(0_0_0/0.35)]">
              <LibraryBig aria-hidden className="size-6" />
            </div>
            <div className="space-y-1.5">
              <h3 className="type-display text-lg font-semibold text-ink">暂无已保存的记录</h3>
              <p className="max-w-sm text-sm text-ink-soft">导入 JSON 记录文件，解路径与完整搜索过程将自动保存到本地。</p>
              {failure && <p className="max-w-sm text-sm text-signal">{failure}</p>}
            </div>
            <Button onClick={onImport} disabled={busy}>
              <FileUp />
              导入记录
            </Button>
          </>
        )}
      </div>
    </div>
  );
}
