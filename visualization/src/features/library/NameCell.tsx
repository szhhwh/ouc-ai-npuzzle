import { useState, type KeyboardEvent } from "react";
import { BoardThumb } from "@/components/puzzle-board";
import { Badge } from "@/components/ui/badge";
import { Input } from "@/components/ui/input";
import { renameRecord, useLibrary } from "@/data/library";
import type { RecordMeta } from "@/data/types";

/** Marks inputs whose Escape key reverts the edit instead of closing the dialog. */
export const RENAME_ATTRIBUTE = "data-rename";

export function NameCell({ meta }: { meta: RecordMeta }) {
  const current = useLibrary((state) => state.current?.id === meta.id);
  // null while the field shows the stored name.
  const [draft, setDraft] = useState<string | null>(null);

  const commit = () => {
    if (draft === null) return;
    if (draft === meta.name) {
      setDraft(null);
      return;
    }
    const committed = draft;
    // Keep the typed text until the store answers so the old name never flashes back.
    renameRecord(meta.id, committed)
      .catch(() => {})
      .finally(() => setDraft((latest) => (latest === committed ? null : latest)));
  };

  const onKeyDown = (event: KeyboardEvent<HTMLInputElement>) => {
    if (event.nativeEvent.isComposing) return;
    if (event.key === "Enter") {
      event.preventDefault();
      commit();
    } else if (event.key === "Escape") {
      event.preventDefault();
      event.stopPropagation();
      setDraft(null);
    }
  };

  return (
    <div className="flex min-w-[15rem] items-center gap-3">
      {meta.initial ? (
        <BoardThumb board={meta.initial} size={meta.size} className="size-12" />
      ) : (
        <div className="size-12 shrink-0 animate-pulse rounded-[12%] bg-tray/60" role="img" aria-label="正在读取起点棋盘" />
      )}
      <div className="flex min-w-0 flex-1 items-center gap-2">
        <Input
          {...{ [RENAME_ATTRIBUTE]: "" }}
          value={draft ?? meta.name}
          onChange={(event) => setDraft(event.target.value)}
          onKeyDown={onKeyDown}
          onBlur={commit}
          aria-label={`重命名“${meta.name}”`}
          spellCheck={false}
          autoComplete="off"
          className="-ml-2 h-8 min-w-0 flex-1 border-transparent bg-transparent px-2 font-medium text-ink shadow-none hover:border-rule focus-visible:border-ring focus-visible:bg-surface"
        />
        {current && (
          <Badge className="shrink-0 border-sea/30 bg-sea-wash px-1.5 text-[11px] text-sea">当前</Badge>
        )}
      </div>
    </div>
  );
}
