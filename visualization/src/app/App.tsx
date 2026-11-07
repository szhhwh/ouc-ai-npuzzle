import { AnimatePresence, MotionConfig, motion } from "motion/react";
import { useEffect } from "react";
import { useDropzone } from "react-dropzone";
import { useHotkeys } from "react-hotkeys-hook";
import { toast } from "sonner";
import { Toaster } from "@/components/ui/sonner";
import { TooltipProvider } from "@/components/ui/tooltip";
import { importFiles, useLibrary } from "@/data/library";
import { LibraryDialog } from "@/features/library/LibraryDialog";
import { PathView } from "@/features/path/PathView";
import { SearchView } from "@/features/search/SearchView";
import { DropOverlay, EmptyState, LoadingState } from "./EmptyState";
import { ImportPickerContext } from "./import-context";
import { TopBar } from "./TopBar";
import { pauseAll, setImmersive, setView, useUi } from "./ui-store";

export function App() {
  return (
    <MotionConfig reducedMotion="user">
      <TooltipProvider delayDuration={350}>
        <Shell />
        <Toaster position="top-center" richColors={false} closeButton />
      </TooltipProvider>
    </MotionConfig>
  );
}

function Shell() {
  const phase = useLibrary((state) => state.phase);
  const failure = useLibrary((state) => state.failure);
  const current = useLibrary((state) => state.current);
  const loadingId = useLibrary((state) => state.loadingId);
  const loadingName = useLibrary((state) => state.metas.find((meta) => meta.id === state.loadingId)?.name);
  const view = useUi((state) => state.view);
  const immersive = useUi((state) => state.immersive);
  const libraryOpen = useUi((state) => state.libraryOpen);

  const { getRootProps, getInputProps, open, isDragActive } = useDropzone({
    accept: { "application/json": [".json"] },
    multiple: true,
    noClick: true,
    noKeyboard: true,
    onDrop: (accepted, rejected) => {
      if (rejected.length) toast.error(`仅支持导入 JSON 格式文件，已跳过 ${rejected.length} 个不支持的文件。`);
      if (accepted.length) importFiles(accepted).catch(() => {});
    },
  });

  useEffect(() => {
    const onVisibility = () => document.hidden && pauseAll();
    const onFullscreen = () => !document.fullscreenElement && useUi.getState().immersive && useUi.setState({ immersive: false });
    document.addEventListener("visibilitychange", onVisibility);
    document.addEventListener("fullscreenchange", onFullscreen);
    return () => {
      document.removeEventListener("visibilitychange", onVisibility);
      document.removeEventListener("fullscreenchange", onFullscreen);
    };
  }, []);

  const shortcuts = !libraryOpen && current !== null;
  useHotkeys("1", () => setView("path"), { enabled: shortcuts && !immersive });
  useHotkeys("2", () => setView("search"), { enabled: shortcuts && !immersive });
  useHotkeys("escape", () => void setImmersive(false), { enabled: immersive && !libraryOpen });

  let body;
  if (!current) {
    body =
      phase === "starting" || loadingId ? (
        <LoadingState key="loading" label={loadingName ? `正在加载“${loadingName}”…` : "正在加载历史记录…"} />
      ) : (
        <EmptyState key="empty" failure={failure} />
      );
  } else {
    body = (
      <motion.div
        key={view}
        className="h-full min-h-0"
        initial={{ opacity: 0, y: 10 }}
        animate={{ opacity: 1, y: 0 }}
        exit={{ opacity: 0, y: -6 }}
        transition={{ duration: 0.2, ease: [0.22, 0.9, 0.24, 1] }}
      >
        {view === "path" ? <PathView record={current} /> : <SearchView record={current} />}
      </motion.div>
    );
  }

  return (
    <ImportPickerContext.Provider value={open}>
      <div {...getRootProps({ className: "relative flex h-dvh flex-col overflow-hidden outline-none" })}>
        <input {...getInputProps()} />
        <AnimatePresence initial={false}>{!immersive && <TopBar key="top" />}</AnimatePresence>
        <AnimatePresence>
          {current && loadingId && (
            <motion.div
              key="record-loading"
              className="pointer-events-none absolute inset-x-0 top-14 z-40 h-0.5 overflow-hidden bg-sea-wash"
              initial={{ opacity: 0 }}
              animate={{ opacity: 1 }}
              exit={{ opacity: 0 }}
              role="status"
              aria-label={`正在加载“${loadingName ?? ""}”`}
            >
              <motion.div
                className="h-full w-1/3 bg-sea"
                animate={{ x: ["-100%", "300%"] }}
                transition={{ duration: 1.1, repeat: Infinity, ease: "easeInOut" }}
              />
            </motion.div>
          )}
        </AnimatePresence>
        <main className="relative min-h-0 flex-1">
          <AnimatePresence mode="wait" initial={false}>
            {body}
          </AnimatePresence>
        </main>
        <AnimatePresence>{isDragActive && <DropOverlay />}</AnimatePresence>
        <LibraryDialog />
      </div>
    </ImportPickerContext.Provider>
  );
}
