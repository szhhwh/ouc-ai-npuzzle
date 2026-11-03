import { Crosshair, Map as MapIcon, Maximize, Minimize, PanelRightClose, PanelRightOpen, Scan, Scissors, ZoomIn, ZoomOut } from "lucide-react";
import { AnimatePresence, motion, useReducedMotion } from "motion/react";
import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState, useSyncExternalStore, type ReactNode } from "react";
import { useHotkeys } from "react-hotkeys-hook";
import { setImmersive, useUi } from "@/app/ui-store";
import { Button } from "@/components/ui/button";
import { Kbd } from "@/components/ui/kbd";
import { ScrollArea } from "@/components/ui/scroll-area";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import type { CompactTrace, LoadedRecord } from "@/data/types";
import { cn } from "@/lib/utils";
import { countersAt, frontierAt, iterationShots, roundShot, sceneAt } from "@/replay/model";
import { Dock } from "./Dock";
import { FrontierPanel } from "./FrontierPanel";
import { Inspector } from "./Inspector";
import { narrate } from "./narration";
import { PHASE_MS, searchActions, useSearchPlayback, type Motion, type Speed } from "./search-store";
import { TreeRenderer } from "./tree-renderer";

const WIDE = "(min-width: 1024px)";
const subscribeWide = (notify: () => void) => {
  const query = window.matchMedia(WIDE);
  query.addEventListener("change", notify);
  return () => query.removeEventListener("change", notify);
};

/** Below this many ms per successor shot, panels stop animating and refresh every PANEL_REFRESH_MS. */
const FAST_SHOT_MS = 120;
const PANEL_REFRESH_MS = 160;

/** Space must not toggle twice when a focused control already handles it natively. */
const ownsSpace = (event: KeyboardEvent) =>
  event.target instanceof Element &&
  event.target.closest('button, [role="button"], [role="slider"], [role="switch"], [role="combobox"], input, textarea, select, [contenteditable="true"]') !== null;

/** Advances the replay on animation frames, spending each shot's narration time divided by the speed. */
function usePlaybackLoop(trace: CompactTrace, playing: boolean) {
  useEffect(() => {
    if (!playing) return;
    const total = trace.shotCount;
    let frame = 0;
    let last = performance.now();
    let budget = 0;
    const loop = (now: number) => {
      const { shot, speed, playing: still } = useSearchPlayback.getState();
      if (!still) return;
      budget += Math.min(now - last, 250) * speed;
      last = now;
      let next = shot;
      while (next < total - 1) {
        const cost = PHASE_MS[sceneAt(trace, next).phase];
        if (budget < cost) break;
        budget -= cost;
        next++;
      }
      if (next !== shot) searchActions.advanceTo(next, total);
      if (next < total - 1) frame = requestAnimationFrame(loop);
    };
    frame = requestAnimationFrame(loop);
    return () => cancelAnimationFrame(frame);
  }, [trace, playing]);
}

function animationTiming(motionKind: Motion, phaseMs: number, speed: Speed, distance: number, reduced: boolean) {
  if (reduced) return { animMs: 0, cameraMs: 0 };
  if (motionKind === "step") return { animMs: 420, cameraMs: 520 };
  if (motionKind === "seek") return { animMs: distance <= 40 ? 300 : 0, cameraMs: 420 };
  const perShot = phaseMs / speed;
  const animMs = perShot >= 90 ? Math.min(450, perShot * 0.85) : 0;
  return { animMs, cameraMs: animMs ? Math.max(animMs, 320) : 180 };
}

export function SearchView({ record }: { record: LoadedRecord }) {
  const { trace } = record;
  const total = trace.shotCount;
  const reduced = useReducedMotion() ?? false;
  const wide = useSyncExternalStore(subscribeWide, () => window.matchMedia(WIDE).matches);
  const immersive = useUi((state) => state.immersive);
  const hotkeysOn = useUi((state) => state.view === "search" && !state.libraryOpen);

  useLayoutEffect(() => searchActions.bind(record.id), [record.id]);
  const playback = useSearchPlayback();
  const bound = playback.recordId === record.id;
  const shot = bound ? Math.min(playback.shot, total - 1) : 0;
  const playing = bound && playback.playing;
  const { speed, showPruned, follow } = playback;
  const inspected = bound ? playback.inspected : null;
  usePlaybackLoop(trace, playing);
  useEffect(() => () => searchActions.pause(), []);
  useEffect(() => () => void setImmersive(false), []);

  const scene = useMemo(() => sceneAt(trace, shot), [trace, shot]);
  const fast = playing && PHASE_MS.child / speed < FAST_SHOT_MS;
  // Panels are read, not watched: during fast playback they refresh a few times per second while the tree runs every frame.
  const panelShot = useRef({ shot, at: 0 });
  const now = performance.now();
  if (!fast || now - panelShot.current.at > PANEL_REFRESH_MS) panelShot.current = { shot, at: now };
  const panelScene = useMemo(() => sceneAt(trace, panelShot.current.shot), [trace, panelShot.current.shot]);
  const frontier = useMemo(() => frontierAt(trace, panelScene, 6), [trace, panelScene]);
  const counters = countersAt(trace, scene);
  const narration = useMemo(() => narrate(record, panelScene, frontier), [record, panelScene, frontier]);
  const markers = useMemo(() => (trace.depthFirst ? iterationShots(trace).slice(1) : []), [trace]);

  const [panelOpen, setPanelOpen] = useState(wide);
  const [minimapOpen, setMinimapOpen] = useState(true);
  useEffect(() => setPanelOpen(wide), [wide]);

  // ---------------------------------------------------------------- renderer
  const rootRef = useRef<HTMLDivElement>(null);
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const minimapRef = useRef<HTMLCanvasElement>(null);
  const narrationRef = useRef<HTMLDivElement>(null);
  const panelRef = useRef<HTMLElement>(null);
  const dockRef = useRef<HTMLDivElement>(null);
  const rendererRef = useRef<TreeRenderer | null>(null);
  const shownShot = useRef(-1);

  useLayoutEffect(() => {
    const renderer = new TreeRenderer(canvasRef.current!, trace, {
      onInspect: (id) => searchActions.inspect(id),
      onUserCamera: () => searchActions.setFollow(false),
    });
    rendererRef.current = renderer;
    shownShot.current = -1;
    return () => {
      renderer.destroy();
      rendererRef.current = null;
    };
  }, [trace]);

  useLayoutEffect(() => {
    rendererRef.current?.attachMinimap(minimapOpen && wide ? minimapRef.current : null);
  }, [minimapOpen, wide, trace]);

  useLayoutEffect(() => {
    const renderer = rendererRef.current;
    if (!renderer) return;
    const distance = shownShot.current < 0 ? Infinity : Math.abs(shot - shownShot.current);
    const timing = shownShot.current < 0 ? { animMs: 0, cameraMs: 0 } : animationTiming(playback.motion, PHASE_MS[scene.phase], speed, distance, reduced);
    renderer.setScene(scene, { showPruned, inspected, follow, ...timing });
    shownShot.current = shot;
    // motion and speed only shape how a scene change animates; they never trigger one.
  }, [scene, showPruned, inspected, follow, trace]);

  // Free area for the camera: everything not covered by floating panels.
  useLayoutEffect(() => {
    const root = rootRef.current!;
    const update = () => {
      const box = root.getBoundingClientRect();
      const narrationBox = narrationRef.current?.getBoundingClientRect();
      const panelBox = panelRef.current?.getBoundingClientRect();
      const dockBox = dockRef.current?.getBoundingClientRect();
      rendererRef.current?.setInsets({
        left: 0,
        top: narrationBox ? narrationBox.bottom - box.top + 8 : 0,
        right: wide && panelOpen && panelBox ? box.right - panelBox.left + 8 : 0,
        bottom: dockBox ? box.bottom - dockBox.top + 8 : 0,
      });
    };
    const observer = new ResizeObserver(update);
    for (const element of [root, narrationRef.current, panelRef.current, dockRef.current]) if (element) observer.observe(element);
    update();
    return () => observer.disconnect();
  }, [wide, panelOpen, immersive, trace]);

  // ---------------------------------------------------------------- actions
  const toggle = useCallback(() => searchActions.toggle(total), [total]);
  const step = useCallback((delta: number) => searchActions.stepBy(delta, total), [total]);
  const round = useCallback(
    (delta: number) => {
      const current = sceneAt(trace, useSearchPlayback.getState().shot);
      if (delta > 0) searchActions.seek(current.shot === 0 ? 1 : roundShot(trace, current.round + 1), total, "step");
      else searchActions.seek(current.phase === "pick" || current.phase === "ready" ? (current.round === 0 ? 0 : roundShot(trace, current.round - 1)) : roundShot(trace, current.round), total, "step");
    },
    [trace, total],
  );
  const seek = useCallback((target: number) => searchActions.seek(target, total), [total]);
  const fit = () => {
    searchActions.setFollow(false);
    rendererRef.current?.fit();
  };
  const toggleFollow = () => {
    const next = !useSearchPlayback.getState().follow;
    searchActions.setFollow(next);
    if (next) rendererRef.current?.recenter();
  };
  const locate = useCallback((id: number) => {
    searchActions.setFollow(false);
    rendererRef.current?.focus(id);
  }, []);
  const jumpIteration = useCallback(
    (iteration: number) => searchActions.seek(trace.shotStart[trace.iterationFirstFrame[iteration]], total, "step"),
    [trace, total],
  );

  const keys = { enabled: hotkeysOn, preventDefault: true };
  useHotkeys("space", toggle, { ...keys, ignoreEventWhen: ownsSpace }, [toggle]);
  useHotkeys("left", () => step(-1), keys, [step]);
  useHotkeys("right", () => step(1), keys, [step]);
  useHotkeys("shift+left", () => round(-1), keys, [round]);
  useHotkeys("shift+right", () => round(1), keys, [round]);
  useHotkeys("home", () => seek(0), keys, [seek]);
  useHotkeys("end", () => seek(total - 1), keys, [seek, total]);
  useHotkeys("f", () => void setImmersive(!useUi.getState().immersive), keys);
  useHotkeys("p", () => searchActions.setShowPruned(!useSearchPlayback.getState().showPruned), keys);
  useHotkeys("c", toggleFollow, keys);
  useHotkeys("0", fit, keys);
  useHotkeys("escape", () => searchActions.inspect(null), { enabled: hotkeysOn && inspected !== null });

  const narrationKey = fast ? "fast" : panelScene.shot;

  return (
    <div ref={rootRef} className="relative h-full min-h-0 overflow-hidden bg-paper">
      <canvas ref={canvasRef} className="absolute inset-0 size-full touch-none select-none" aria-label="搜索树，可拖动和缩放；点击节点查看计算" />

      {/* Narration */}
      <div
        ref={narrationRef}
        className={cn(
          "glass pointer-events-auto absolute top-3 left-3 flex max-w-[calc(100%-1.5rem)] flex-col gap-1.5 rounded-2xl border border-rule/80 px-4 py-3 shadow-[0_18px_40px_-30px_rgb(19_42_59/0.7)] sm:top-4 sm:left-4",
          wide && panelOpen ? "lg:max-w-[min(620px,calc(100%-400px))]" : "lg:max-w-[620px]",
          immersive && "lg:max-w-[min(720px,calc(100%-400px))]",
        )}
        aria-live={playing ? "off" : "polite"}
      >
        <div className="flex items-center gap-2">
          <span className={cn("rounded-full px-2 py-0.5 text-[11px] font-semibold", panelScene.goal ? "bg-brass text-primary-foreground" : "bg-ink text-primary-foreground")}>
            {narration.tag}
          </span>
          <span className="truncate text-[11px] text-ink-soft">
            {record.name} · {record.algorithm}
          </span>
        </div>
        <AnimatePresence mode="wait" initial={false}>
          <motion.div
            key={narrationKey}
            initial={{ opacity: 0, y: 6 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0, y: -4, transition: { duration: 0.08 } }}
            transition={{ duration: 0.2, ease: [0.22, 0.9, 0.24, 1] }}
            className="flex flex-col gap-1"
          >
            <h2 className={cn("font-semibold text-ink", immersive ? "text-[20px]" : "text-[16px] sm:text-[17px]")}>{narration.title}</h2>
            {narration.body && <p className={cn("leading-relaxed text-ink-soft", immersive ? "text-[14px]" : "text-[13px]")}>{narration.body}</p>}
          </motion.div>
        </AnimatePresence>
        <Legend />
      </div>

      {/* Tree tools */}
      <div
        className={cn(
          "glass absolute top-3 right-3 flex flex-col gap-0.5 rounded-xl border border-rule/80 p-1 shadow-sm sm:top-4 sm:right-4",
          wide && panelOpen && "lg:right-[calc(360px+2rem)]",
          !wide && "top-auto bottom-[calc(var(--dock-h,128px)+1.5rem)]",
        )}
        role="toolbar"
        aria-label="搜索树工具"
      >
        <Tool label="放大" onClick={() => rendererRef.current?.zoomBy(1.4)}>
          <ZoomIn />
        </Tool>
        <Tool label="缩小" onClick={() => rendererRef.current?.zoomBy(1 / 1.4)}>
          <ZoomOut />
        </Tool>
        <Tool label="显示整棵树" keys="0" onClick={fit}>
          <Scan />
        </Tool>
        <Tool label={follow ? "镜头跟随当前节点（已开启）" : "镜头跟随当前节点"} keys="C" pressed={follow} onClick={toggleFollow}>
          <Crosshair />
        </Tool>
        <Tool label={showPruned ? "隐藏已剪枝节点" : "显示已剪枝节点"} keys="P" pressed={showPruned} onClick={() => searchActions.setShowPruned(!showPruned)}>
          <Scissors />
        </Tool>
        <Tool label={minimapOpen ? "隐藏缩略图" : "显示缩略图"} pressed={minimapOpen} onClick={() => setMinimapOpen((open) => !open)}>
          <MapIcon />
        </Tool>
        <Tool label={panelOpen ? "收起计算面板" : "展开计算面板"} pressed={panelOpen} onClick={() => setPanelOpen((open) => !open)}>
          {panelOpen ? <PanelRightClose /> : <PanelRightOpen />}
        </Tool>
        <div className="mx-1 my-0.5 h-px bg-rule" />
        <Tool label={immersive ? "退出全屏" : "全屏查看搜索过程"} keys={immersive ? "Esc" : "F"} onClick={() => void setImmersive(!immersive)}>
          {immersive ? <Minimize /> : <Maximize />}
        </Tool>
      </div>

      {/* Calculation panel */}
      <AnimatePresence initial={false}>
        {panelOpen && (
          <motion.aside
            ref={panelRef}
            key="panel"
            aria-label="计算面板"
            className={cn(
              "glass absolute z-10 flex flex-col overflow-hidden rounded-2xl border border-rule/80 shadow-[0_18px_40px_-28px_rgb(19_42_59/0.7)]",
              wide ? "top-4 right-4 w-[360px]" : "inset-x-3 top-3 max-h-[60%]",
            )}
            style={wide ? { bottom: "calc(var(--dock-h, 128px) + 1.75rem)" } : undefined}
            initial={wide ? { opacity: 0, x: 40 } : { opacity: 0, y: -20 }}
            animate={{ opacity: 1, x: 0, y: 0 }}
            exit={wide ? { opacity: 0, x: 40 } : { opacity: 0, y: -20 }}
            transition={{ duration: 0.24, ease: [0.22, 0.9, 0.24, 1] }}
          >
            <ScrollArea className="min-h-0 flex-1">
              <div className="flex flex-col gap-6 p-4">
                <Inspector record={record} scene={panelScene} inspected={inspected} onInspect={searchActions.inspect} onLocate={locate} fast={fast} />
                <div className="h-px bg-rule" />
                <FrontierPanel
                  record={record}
                  scene={panelScene}
                  frontier={frontier}
                  inspected={inspected}
                  onInspect={searchActions.inspect}
                  onJumpIteration={jumpIteration}
                  fast={fast}
                />
              </div>
            </ScrollArea>
          </motion.aside>
        )}
      </AnimatePresence>

      {/* Minimap */}
      <AnimatePresence initial={false}>
        {minimapOpen && wide && (
          <motion.div
            key="minimap"
            className="glass absolute left-4 overflow-hidden rounded-xl border border-rule/80 shadow-sm"
            style={{ bottom: "calc(var(--dock-h, 128px) + 1.75rem)" }}
            initial={{ opacity: 0, scale: 0.92 }}
            animate={{ opacity: 1, scale: 1 }}
            exit={{ opacity: 0, scale: 0.92 }}
            transition={{ duration: 0.18 }}
          >
            <canvas ref={minimapRef} className="block h-[120px] w-[200px] cursor-crosshair" aria-label="缩略图：点击或拖动以移动视野" />
          </motion.div>
        )}
      </AnimatePresence>

      <DockHeight target={dockRef} root={rootRef} />
      <Dock
        ref={dockRef}
        record={record}
        scene={scene}
        counters={counters}
        playing={playing}
        speed={speed}
        fast={fast}
        markers={markers}
        onToggle={toggle}
        onStep={step}
        onRound={round}
        onRestart={() => seek(0)}
        onSeek={seek}
        onSpeed={searchActions.setSpeed}
        className={cn("absolute bottom-3 left-3 right-3 sm:bottom-4 sm:left-4 sm:right-4", wide && "lg:left-1/2 lg:right-auto lg:w-[min(860px,calc(100%-2rem))] lg:-translate-x-1/2")}
      />
    </div>
  );
}

/** Publishes the dock height as a CSS variable so floating panels stop above it. */
function DockHeight({ target, root }: { target: React.RefObject<HTMLDivElement | null>; root: React.RefObject<HTMLDivElement | null> }) {
  useLayoutEffect(() => {
    const dock = target.current;
    const container = root.current;
    if (!dock || !container) return;
    const observer = new ResizeObserver(() => container.style.setProperty("--dock-h", `${dock.offsetHeight}px`));
    observer.observe(dock);
    return () => observer.disconnect();
  }, [target, root]);
  return null;
}

function Tool({ label, keys, pressed, onClick, children }: { label: string; keys?: string; pressed?: boolean; onClick: () => void; children: ReactNode }) {
  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <Button
          variant="ghost"
          size="icon-sm"
          aria-label={label}
          aria-pressed={pressed}
          onClick={onClick}
          className={cn(pressed && "bg-ink text-primary-foreground hover:bg-ink/90 hover:text-primary-foreground")}
        >
          {children}
        </Button>
      </TooltipTrigger>
      <TooltipContent side="left" className="flex items-center gap-2">
        {label}
        {keys && <Kbd>{keys}</Kbd>}
      </TooltipContent>
    </Tooltip>
  );
}

function Legend() {
  const items = [
    { label: "扩展中", swatch: "bg-ink" },
    { label: "候选", swatch: "border-2 border-sea bg-surface" },
    { label: "已剪枝", swatch: "border border-dashed border-signal bg-[#eef1f0]" },
    { label: "待生成", swatch: "border border-dashed border-ink-soft" },
    { label: "解路径", swatch: "bg-brass" },
  ];
  return (
    <ul className="mt-1 hidden flex-wrap items-center gap-x-3 gap-y-1 border-t border-rule/70 pt-2 text-[11px] text-ink-soft sm:flex" aria-label="图例">
      {items.map((item) => (
        <li key={item.label} className="flex items-center gap-1.5">
          <span className={cn("inline-block h-3 w-2.5 rounded-[3px]", item.swatch)} aria-hidden />
          {item.label}
        </li>
      ))}
      <li className="flex items-center gap-1.5">
        <span className="type-data text-ink">g</span>+<span className="type-data text-magenta">h</span>=<span className="type-data font-semibold text-ink">f</span>
      </li>
    </ul>
  );
}
