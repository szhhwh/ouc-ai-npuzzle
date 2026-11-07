import { easeBackOut, easeCubicInOut, easeCubicOut } from "d3-ease";
import { interpolateZoom, type ZoomView } from "d3-interpolate";
import { select, type Selection } from "d3-selection";
import { zoom as d3zoom, zoomIdentity, type ZoomBehavior, type ZoomTransform } from "d3-zoom";
import { FIRST_PRUNED, STATUSES, type CompactTrace } from "@/data/types";
import { STATUS_TAGS } from "@/lib/labels";
import { layoutTree, ROW, SLOT, type TreeLayout } from "@/replay/layout";
import { isOpen, type Scene } from "@/replay/model";

const CARD_W = 100;
const CARD_H = 128;
const HALF_W = CARD_W / 2;
const HALF_H = CARD_H / 2;
const BOARD = 80;

export interface SceneOptions {
  showPruned: boolean;
  inspected: number | null;
  follow: boolean;
  /** Length of this change's node animations in ms; 0 snaps nodes into place. */
  animMs: number;
  /** Length of the follow-camera move in ms. */
  cameraMs: number;
}

export interface Insets {
  left: number;
  top: number;
  right: number;
  bottom: number;
}

interface Palette {
  paper: string;
  surface: string;
  ink: string;
  inkSoft: string;
  rule: string;
  sea: string;
  seaWash: string;
  magenta: string;
  brass: string;
  brassWash: string;
  signal: string;
  tray: string;
  tile: string;
}

function readPalette(): Palette {
  const style = getComputedStyle(document.documentElement);
  const value = (name: string) => style.getPropertyValue(name).trim();
  return {
    paper: value("--paper"),
    surface: value("--surface"),
    ink: value("--ink"),
    inkSoft: value("--ink-soft"),
    rule: value("--rule"),
    sea: value("--sea"),
    seaWash: value("--sea-wash"),
    magenta: value("--magenta"),
    brass: value("--brass"),
    brassWash: value("--brass-wash"),
    signal: value("--signal"),
    tray: value("--tray"),
    tile: value("--tile"),
  };
}

const MONO = '"Martian Mono Variable", ui-monospace, monospace';
const SANS = '"Archivo Variable", "PingFang SC", "Noto Sans CJK SC", sans-serif';
const clamp01 = (value: number) => (value < 0 ? 0 : value > 1 ? 1 : value);

/**
 * Imperative canvas renderer for the search tree. React owns the scene; this class owns pixels,
 * the camera (d3-zoom) and every in-between frame: layout tweens, successors fanning out of their
 * parent, prune marks, the pick ripple and the solution trace.
 */
export class TreeRenderer {
  private readonly ctx: CanvasRenderingContext2D;
  private readonly selection: Selection<HTMLCanvasElement, unknown, null, undefined>;
  private readonly zoom: ZoomBehavior<HTMLCanvasElement, unknown>;
  private readonly palette = readPalette();
  private transform: ZoomTransform = zoomIdentity;
  private width = 0;
  private height = 0;
  private dpr = 1;
  private insets: Insets = { left: 0, top: 0, right: 0, bottom: 0 };

  private scene: Scene | null = null;
  private options: SceneOptions = { showPruned: false, inspected: null, follow: true, animMs: 0, cameraMs: 0 };
  private layout: TreeLayout | null = null;
  private layoutKey = "";
  private fromX = new Float32Array(0);
  private dispX = new Float32Array(0);
  private dispY = new Float32Array(0);
  private tweenStart = 0;
  private tweenMs = 0;
  /** Per node: when it joined the layout (fans out of its parent) and when its content was generated. */
  private readonly enterAt: Float64Array;
  private readonly revealAt: Float64Array;
  private animMs = 0;
  private exiting: Array<{ id: number; x: number; y: number; start: number }> = [];
  private ripple = { id: -1, start: 0 };
  private solutionStart = 0;
  private readonly solutionMask: Uint8Array;
  private hovered = -1;
  /** Scale the follow camera returns to; only the user's own zooming changes it. */
  private followK = 1;
  private detachMinimap: (() => void) | null = null;

  private flight: { interpolate: (t: number) => ZoomView; start: number; duration: number } | null = null;
  /** Point the camera glides toward during fast playback. */
  private chase: { x: number; y: number } | null = null;
  private lastTick = 0;
  private frame = 0;
  private minimap: { canvas: HTMLCanvasElement; ctx: CanvasRenderingContext2D; image: HTMLCanvasElement; dirty: boolean; lastBuild: number } | null = null;
  private destroyed = false;
  private readonly cleanups: Array<() => void> = [];

  constructor(
    private readonly canvas: HTMLCanvasElement,
    private readonly trace: CompactTrace,
    private readonly callbacks: {
      onInspect: (id: number | null) => void;
      onUserCamera: () => void;
      onZoom?: (scale: number) => void;
    },
  ) {
    this.ctx = canvas.getContext("2d")!;
    this.enterAt = new Float64Array(trace.nodeCount);
    this.revealAt = new Float64Array(trace.nodeCount);
    this.solutionMask = new Uint8Array(trace.nodeCount);
    for (const id of trace.solution) this.solutionMask[id] = 1;

    this.selection = select(canvas);
    this.zoom = d3zoom<HTMLCanvasElement, unknown>()
      .scaleExtent([0.02, 2.5])
      .clickDistance(4)
      .on("start", (event) => {
        if (event.sourceEvent) {
          this.flight = null;
          this.chase = null;
          this.callbacks.onUserCamera();
          canvas.style.cursor = "grabbing";
        }
      })
      .on("zoom", (event) => {
        this.transform = event.transform;
        this.callbacks.onZoom?.(this.transform.k);
        if (this.minimap) this.drawMinimap();
        this.request();
      })
      .on("end", (event) => {
        if (event.sourceEvent) this.followK = this.transform.k;
        canvas.style.cursor = this.hovered >= 0 ? "pointer" : "grab";
      });
    this.selection.call(this.zoom).on("dblclick.zoom", null);

    const onClick = (event: MouseEvent) => {
      const id = this.hit(event.offsetX, event.offsetY);
      this.callbacks.onInspect(id >= 0 ? id : null);
    };
    let pointer: { x: number; y: number } | null = null;
    let pending = false;
    const onMove = (event: PointerEvent) => {
      pointer = { x: event.offsetX, y: event.offsetY };
      if (pending) return;
      pending = true;
      requestAnimationFrame(() => {
        pending = false;
        if (!pointer) return;
        const id = this.hit(pointer.x, pointer.y);
        if (id !== this.hovered) {
          this.hovered = id;
          canvas.style.cursor = id >= 0 ? "pointer" : "grab";
          this.request();
        }
      });
    };
    const onLeave = () => {
      pointer = null;
      if (this.hovered >= 0) {
        this.hovered = -1;
        this.request();
      }
    };
    canvas.addEventListener("click", onClick);
    canvas.addEventListener("pointermove", onMove);
    canvas.addEventListener("pointerleave", onLeave);
    canvas.style.cursor = "grab";
    this.cleanups.push(() => {
      canvas.removeEventListener("click", onClick);
      canvas.removeEventListener("pointermove", onMove);
      canvas.removeEventListener("pointerleave", onLeave);
      this.selection.on(".zoom", null);
    });

    const observer = new ResizeObserver(() => this.resize());
    observer.observe(canvas);
    this.cleanups.push(() => observer.disconnect());
    this.resize();
    void document.fonts?.ready.then(() => this.request());
  }

  destroy() {
    this.destroyed = true;
    cancelAnimationFrame(this.frame);
    for (const cleanup of this.cleanups) cleanup();
    this.detachMinimap?.();
  }

  setInsets(insets: Insets) {
    const changed =
      insets.left !== this.insets.left || insets.top !== this.insets.top || insets.right !== this.insets.right || insets.bottom !== this.insets.bottom;
    this.insets = insets;
    if (changed && this.scene && this.options.follow && !this.flight) this.followScene(280);
    this.request();
  }

  /** Applies a new replay position. Structure changes start a layout tween; follow mode steers the camera. */
  setScene(scene: Scene, options: SceneOptions) {
    const previous = this.scene;
    const now = performance.now();
    this.scene = scene;
    this.options = options;
    this.animMs = options.animMs;
    const animate = options.animMs > 0 && previous !== null;
    const key = `${scene.rangeStart}:${scene.layoutEnd}:${scene.childStart}:${options.showPruned}`;
    if (key !== this.layoutKey) {
      this.relayout(scene, options, animate ? now : 0);
      this.layoutKey = key;
    }

    // Content of newly generated nodes pops in; large jumps simply appear.
    if (previous && animate && scene.revealedEnd > previous.revealedEnd && scene.revealedEnd - previous.revealedEnd <= 8) {
      for (let id = Math.max(previous.revealedEnd, scene.rangeStart); id < scene.revealedEnd; id++) this.revealAt[id] = now;
    }
    if (animate && scene.phase === "pick" && previous?.shot !== scene.shot) this.ripple = { id: scene.current, start: now };
    if (scene.goal && !previous?.goal) this.solutionStart = animate ? now : 0;
    if (options.follow) this.followScene(previous ? options.cameraMs : 0);
    this.request();
  }

  setOptions(options: Partial<SceneOptions>) {
    if (!this.scene) return;
    this.setScene(this.scene, { ...this.options, animMs: 320, cameraMs: 320, ...options });
  }

  /** Fits the whole visible tree into the free area between panels. */
  fit(duration = 450) {
    const layout = this.layout;
    if (!layout || !layout.count) return;
    const area = this.area();
    const width = Math.max(1, layout.maxX - layout.minX + CARD_W + 80);
    const height = Math.max(1, layout.maxDepth * ROW + CARD_H + 80);
    const k = Math.min(1.2, area.width / width, area.height / height);
    this.zoom.scaleExtent([Math.min(0.02, k * 0.8), 2.5]);
    this.flyTo((layout.minX + layout.maxX) / 2, (layout.maxDepth * ROW) / 2, k, duration);
  }

  zoomBy(factor: number) {
    const area = this.area();
    const [min, max] = this.zoom.scaleExtent();
    const k = Math.max(min, Math.min(max, this.transform.k * factor));
    this.flyTo((area.cx - this.transform.x) / this.transform.k, (area.cy - this.transform.y) / this.transform.k, k, 220);
  }

  /** Centres a node at a comfortable reading scale. */
  focus(id: number, duration = 500) {
    const point = this.positionOf(id);
    if (!point) return;
    this.flyTo(point.x, point.y + ROW * 0.3, Math.max(this.transform.k, 0.85), duration);
  }

  /** Re-applies follow targeting immediately (used when follow is switched back on). */
  recenter(duration = 500) {
    this.followScene(duration, true);
  }

  attachMinimap(canvas: HTMLCanvasElement | null) {
    this.detachMinimap?.();
    this.detachMinimap = null;
    this.minimap = null;
    if (!canvas) return;
    const ctx = canvas.getContext("2d")!;
    this.minimap = { canvas, ctx, image: document.createElement("canvas"), dirty: true, lastBuild: 0 };
    const navigate = (event: PointerEvent) => {
      const world = this.minimapToWorld(event.offsetX, event.offsetY);
      if (!world) return;
      this.flight = null;
      this.chase = null;
      this.callbacks.onUserCamera();
      const area = this.area();
      const k = this.transform.k;
      this.selection.call(this.zoom.transform, zoomIdentity.translate(area.cx - world.x * k, area.cy - world.y * k).scale(k));
    };
    const down = (event: PointerEvent) => {
      canvas.setPointerCapture(event.pointerId);
      navigate(event);
    };
    const move = (event: PointerEvent) => {
      if (canvas.hasPointerCapture(event.pointerId)) navigate(event);
    };
    canvas.addEventListener("pointerdown", down);
    canvas.addEventListener("pointermove", move);
    this.detachMinimap = () => {
      canvas.removeEventListener("pointerdown", down);
      canvas.removeEventListener("pointermove", move);
    };
    this.drawMinimap();
  }

  // ---------------------------------------------------------------- layout & camera

  private relayout(scene: Scene, options: SceneOptions, now: number) {
    const previous = this.layout;
    const next = layoutTree(this.trace, scene, options.showPruned);
    const size = next.end - next.start;
    const fromX = new Float32Array(size).fill(Number.NaN);
    if (previous) {
      // Start from whatever is on screen now so interrupted tweens continue smoothly.
      for (let i = 0; i < next.count; i++) {
        const id = next.members[i];
        if (id >= previous.start && id < previous.end && !Number.isNaN(previous.x[id - previous.start])) {
          fromX[id - next.start] = this.dispX[id - previous.start];
        }
      }
      if (now) {
        this.exiting = this.exiting.filter((item) => now - item.start < this.animMs);
        let gone = 0;
        for (let i = 0; i < previous.count && gone < 48; i++) {
          const id = previous.members[i];
          if (id < next.start || id >= next.end || Number.isNaN(next.x[id - next.start])) {
            this.exiting.push({ id, x: this.dispX[id - previous.start], y: this.dispY[id - previous.start], start: now });
            gone++;
          }
        }
      } else this.exiting = [];
    }
    for (let i = 0; i < next.count; i++) {
      const id = next.members[i];
      if (Number.isNaN(fromX[id - next.start])) this.enterAt[id] = now;
    }
    this.layout = next;
    this.fromX = fromX;
    this.dispX = new Float32Array(size);
    this.dispY = new Float32Array(size);
    this.tweenStart = now;
    this.tweenMs = now ? this.animMs : 0;
    if (this.minimap) this.minimap.dirty = true;
    this.updatePositions(now || performance.now());
  }

  /** Displayed positions at time `now`; parents are resolved before their children (reverse post-order). */
  private updatePositions(now: number) {
    const layout = this.layout;
    if (!layout) return false;
    const { start, members, count, x } = layout;
    const tween = this.tweenMs > 0 ? easeCubicInOut(clamp01((now - this.tweenStart) / this.tweenMs)) : 1;
    let moving = tween < 1;
    for (let i = count - 1; i >= 0; i--) {
      const id = members[i];
      const index = id - start;
      const targetX = x[index];
      const targetY = this.trace.g[id] * ROW;
      const from = this.fromX[index];
      const enter = this.enterAt[id];
      const parent = this.trace.parent[id];
      if (!Number.isNaN(from)) {
        this.dispX[index] = from + (targetX - from) * tween;
        this.dispY[index] = targetY;
      } else if (enter && parent >= start && this.animMs > 0) {
        const p = easeCubicOut(clamp01((now - enter) / this.animMs));
        if (p < 1) moving = true;
        const px = this.dispX[parent - start];
        const py = this.dispY[parent - start];
        this.dispX[index] = px + (targetX - px) * p;
        this.dispY[index] = py + (targetY - py) * p;
      } else {
        this.dispX[index] = targetX;
        this.dispY[index] = targetY;
      }
    }
    return moving;
  }

  private positionOf(id: number) {
    const layout = this.layout;
    if (!layout || id < layout.start || id >= layout.end) return null;
    const x = layout.x[id - layout.start];
    return Number.isNaN(x) ? null : { x, y: this.trace.g[id] * ROW };
  }

  private area() {
    const left = this.insets.left;
    const top = this.insets.top;
    const width = Math.max(120, this.width - this.insets.left - this.insets.right);
    const height = Math.max(120, this.height - this.insets.top - this.insets.bottom);
    return { left, top, width, height, cx: left + width / 2, cy: top + height / 2 };
  }

  private followScene(duration: number, force = false) {
    const scene = this.scene;
    const layout = this.layout;
    if (!scene || !layout) return;
    if (scene.goal) {
      // Frame the whole solution path.
      let minX = Infinity;
      let maxX = -Infinity;
      for (const id of this.trace.solution) {
        const point = this.positionOf(id);
        if (!point) continue;
        minX = Math.min(minX, point.x);
        maxX = Math.max(maxX, point.x);
      }
      if (!Number.isFinite(minX)) return;
      const area = this.area();
      const depth = this.trace.g[scene.current] * ROW;
      const k = Math.min(1, area.width / (maxX - minX + CARD_W + 120), area.height / (depth + CARD_H + 120));
      if (k < 0.08) {
        // The route spans more than a screen can show legibly: frame the goal and the last steps leading to it.
        const goal = this.positionOf(scene.current)!;
        this.flyTo(goal.x, goal.y - ROW * 1.5, Math.max(0.4, Math.min(0.7, area.height / (ROW * 5))), Math.max(duration, 600));
        return;
      }
      this.zoom.scaleExtent([Math.min(0.02, k * 0.8), 2.5]);
      this.flyTo((minX + maxX) / 2, depth / 2, k, Math.max(duration, 600));
      return;
    }
    const anchor = scene.phase === "ready" ? 0 : scene.current >= 0 ? scene.current : scene.focus;
    // Follow the next-pick highlight as soon as it appears, if it belongs to the visible iteration.
    const next = this.positionOf(scene.next);
    const point = next ?? this.positionOf(anchor) ?? this.positionOf(scene.rangeStart);
    if (!point) return;
    const targetY = point.y + (!next && scene.childEnd > scene.childStart && scene.phase !== "ready" ? ROW * 0.5 : 0);
    const area = this.area();
    const k = this.followK;
    const sx = point.x * k + this.transform.x;
    const sy = targetY * k + this.transform.y;
    // Dead zone: keep the camera still while the action stays comfortably inside the free area.
    const marginX = Math.min(area.width * 0.28, (SLOT * 2.5 + CARD_W) * k);
    const marginY = Math.min(area.height * 0.28, ROW * 1.2 * k);
    const inside =
      Math.abs(this.transform.k - k) < 0.01 &&
      sx > area.left + marginX &&
      sx < area.left + area.width - marginX &&
      sy > area.top + marginY &&
      sy < area.top + area.height - marginY;
    if (inside && !force) return;
    if (duration > 0 && duration < 250 && Math.abs(this.transform.k - k) < 0.01) {
      // Fast playback: glide toward a moving target instead of restarting a flight every frame.
      this.flight = null;
      this.chase = { x: point.x, y: targetY };
      this.request();
      return;
    }
    this.flyTo(point.x, targetY, k, duration);
  }

  private flyTo(x: number, y: number, k: number, duration: number) {
    this.chase = null;
    const area = this.area();
    const current: ZoomView = [(area.cx - this.transform.x) / this.transform.k, (area.cy - this.transform.y) / this.transform.k, area.width / this.transform.k];
    const target: ZoomView = [x, y, area.width / k];
    if (duration <= 0) {
      this.flight = null;
      this.applyView(target);
      return;
    }
    // Nearby targets pan straight; only far jumps zoom out and back in so the viewer keeps their bearings.
    const screens = (Math.hypot(x - current[0], y - current[1]) * Math.min(this.transform.k, k)) / Math.max(area.width, area.height);
    if (screens < 2.5) {
      this.flight = {
        interpolate: (t) => [current[0] + (x - current[0]) * t, current[1] + (y - current[1]) * t, current[2] * Math.pow(target[2] / current[2], t)],
        start: performance.now(),
        duration,
      };
    } else {
      const interpolate = interpolateZoom(current, target);
      this.flight = { interpolate, start: performance.now(), duration: Math.max(duration, Math.min(1400, interpolate.duration * 0.6)) };
    }
    this.request();
  }

  private applyView([x, y, w]: ZoomView) {
    const area = this.area();
    const k = area.width / w;
    this.selection.call(this.zoom.transform, zoomIdentity.translate(area.cx - x * k, area.cy - y * k).scale(k));
  }

  // ---------------------------------------------------------------- frame loop

  private request() {
    if (!this.frame && !this.destroyed) this.frame = requestAnimationFrame((time) => this.tick(time));
  }

  private tick(now: number) {
    this.frame = 0;
    const dt = this.lastTick ? Math.min(64, now - this.lastTick) : 16;
    this.lastTick = now;
    if (this.flight) {
      const t = clamp01((now - this.flight.start) / this.flight.duration);
      this.applyView(this.flight.interpolate(easeCubicInOut(t)));
      if (t >= 1) this.flight = null;
    } else if (this.chase) {
      const area = this.area();
      const k = this.transform.k;
      const cx = (area.cx - this.transform.x) / k;
      const cy = (area.cy - this.transform.y) / k;
      const alpha = 1 - Math.exp(-dt / 110);
      const nx = cx + (this.chase.x - cx) * alpha;
      const ny = cy + (this.chase.y - cy) * alpha;
      this.applyView([nx, ny, area.width / k]);
      if (Math.hypot(this.chase.x - nx, this.chase.y - ny) * k < 0.5) this.chase = null;
    }
    let animating = this.updatePositions(now);
    animating = this.draw(now) || animating;
    if (this.minimap?.dirty && now - this.minimap.lastBuild > 160) this.drawMinimap(true);
    if (animating || this.flight || this.chase || this.minimap?.dirty) this.request();
    else this.lastTick = 0;
  }

  private resize() {
    const rect = this.canvas.getBoundingClientRect();
    this.dpr = Math.min(window.devicePixelRatio || 1, 2);
    this.width = rect.width;
    this.height = rect.height;
    this.canvas.width = Math.round(rect.width * this.dpr);
    this.canvas.height = Math.round(rect.height * this.dpr);
    // A fixed extent spares d3-zoom a layout read on every programmatic camera move.
    this.zoom.extent([
      [0, 0],
      [rect.width, rect.height],
    ]);
    if (this.scene && this.options.follow) this.followScene(0);
    this.request();
  }

  private hit(sx: number, sy: number) {
    const layout = this.layout;
    if (!layout) return -1;
    const k = this.transform.k;
    const wx = (sx - this.transform.x) / k;
    const wy = (sy - this.transform.y) / k;
    const padX = Math.max(HALF_W, 7 / k);
    const padY = Math.max(HALF_H, 7 / k);
    for (let i = 0; i < layout.count; i++) {
      const id = layout.members[i];
      if (id >= (this.scene?.revealedEnd ?? 0)) continue;
      const index = id - layout.start;
      if (Math.abs(this.dispX[index] - wx) <= padX && Math.abs(this.dispY[index] - wy) <= padY) return id;
    }
    return -1;
  }

  // ---------------------------------------------------------------- drawing

  /** Returns true while time-based effects are still running. */
  private draw(now: number) {
    const { ctx, palette, trace } = this;
    const scene = this.scene;
    const layout = this.layout;
    ctx.setTransform(this.dpr, 0, 0, this.dpr, 0, 0);
    ctx.clearRect(0, 0, this.width, this.height);
    if (!scene || !layout) return false;
    const { k, x: tx, y: ty } = this.transform;
    let animating = false;

    this.drawSoundings(layout);

    ctx.save();
    ctx.translate(tx, ty);
    ctx.scale(k, k);
    const left = -tx / k - CARD_W;
    const right = (this.width - tx) / k + CARD_W;
    const top = -ty / k - CARD_H;
    const bottom = (this.height - ty) / k + CARD_H;
    const lod = CARD_W * k >= 64 ? 2 : CARD_W * k >= 18 ? 1 : 0;
    const anim = Math.max(1, this.animMs);

    // Solution trace progress: the gold route draws itself from the root down.
    const solutionSteps = trace.solution.length - 1;
    let solutionReach = scene.goal ? solutionSteps : -1;
    if (scene.goal && this.solutionStart) {
      const p = clamp01((now - this.solutionStart) / Math.max(900, solutionSteps * 60));
      solutionReach = p * solutionSteps;
      if (p < 1) animating = true;
    }

    // Edges, batched by style.
    const plain = new Path2D();
    const faint = new Path2D();
    const ghost = new Path2D();
    const route = new Path2D();
    const growing: Array<{ id: number; p: number }> = [];
    for (let i = 0; i < layout.count; i++) {
      const id = layout.members[i];
      const parent = trace.parent[id];
      if (parent < layout.start) continue;
      const cx = this.dispX[id - layout.start];
      const cy = this.dispY[id - layout.start];
      const px = this.dispX[parent - layout.start];
      const py = this.dispY[parent - layout.start];
      if (Math.max(cx, px) < left || Math.min(cx, px) > right || Math.max(cy, py) < top || Math.min(cy, py) > bottom) continue;
      if (id >= scene.revealedEnd) {
        this.edge(ghost, px, py, cx, cy);
        continue;
      }
      const reveal = this.revealAt[id];
      const p = reveal && this.animMs > 0 ? (now - reveal) / anim : 1.5;
      if (p < 1.5) {
        growing.push({ id, p });
        animating = true;
        if (p < 1) continue;
      }
      const onRoute = scene.goal && this.solutionMask[id] && this.solutionMask[parent] && trace.g[id] <= solutionReach;
      this.edge(onRoute ? route : trace.status[id] >= FIRST_PRUNED ? faint : plain, px, py, cx, cy);
    }
    ctx.lineCap = "round";
    ctx.lineWidth = 1.4;
    ctx.strokeStyle = palette.rule;
    ctx.stroke(plain);
    ctx.setLineDash([5, 5]);
    ctx.globalAlpha = 0.8;
    ctx.stroke(faint);
    ctx.strokeStyle = palette.inkSoft;
    ctx.globalAlpha = 0.45;
    ctx.stroke(ghost);
    ctx.setLineDash([]);
    ctx.globalAlpha = 1;
    ctx.strokeStyle = palette.brass;
    ctx.lineWidth = 4;
    ctx.stroke(route);
    for (const { id, p } of growing) {
      const parent = trace.parent[id];
      const cx = this.dispX[id - layout.start];
      const cy = this.dispY[id - layout.start];
      const px = this.dispX[parent - layout.start];
      const py = this.dispY[parent - layout.start];
      ctx.strokeStyle = palette.sea;
      ctx.lineWidth = 2;
      ctx.globalAlpha = p <= 1 ? 1 : 1 - easeCubicInOut((p - 1) * 2);
      ctx.beginPath();
      ctx.moveTo(px, py + HALF_H);
      const e = easeCubicOut(clamp01(p));
      ctx.lineTo(px + (cx - px) * e, py + HALF_H + (cy - HALF_H - py - HALF_H) * e);
      ctx.stroke();
    }
    ctx.globalAlpha = 1;

    // Leaving nodes fade where they were.
    this.exiting = this.exiting.filter((item) => now - item.start < anim);
    for (const item of this.exiting) {
      const p = clamp01((now - item.start) / anim);
      ctx.globalAlpha = 1 - p;
      this.card(item.id, item.x, item.y + p * 18, lod, 1 - p * 0.15, "exit");
      animating = true;
    }
    ctx.globalAlpha = 1;

    // Nodes; emphasised ones are drawn last so they sit on top.
    const later: number[] = [];
    for (let i = 0; i < layout.count; i++) {
      const id = layout.members[i];
      const x = this.dispX[id - layout.start];
      const y = this.dispY[id - layout.start];
      if (x < left || x > right || y < top || y > bottom) continue;
      if (id === scene.current || id === scene.next || id === this.options.inspected || id === this.hovered || id === scene.focus) {
        later.push(id);
        continue;
      }
      animating = this.node(id, x, y, lod, now) || animating;
    }
    for (const id of later) {
      const x = this.dispX[id - layout.start];
      const y = this.dispY[id - layout.start];
      animating = this.node(id, x, y, Math.max(lod, 1), now) || animating;
    }

    // Pick ripple.
    if (this.ripple.id >= 0) {
      const p = clamp01((now - this.ripple.start) / 750);
      const point = this.positionOf(this.ripple.id);
      if (point && p < 1) {
        const index = this.ripple.id - layout.start;
        ctx.strokeStyle = palette.ink;
        ctx.globalAlpha = (1 - p) * 0.5;
        ctx.lineWidth = 2 / Math.max(k, 0.4);
        const grow = 6 + p * 26;
        ctx.beginPath();
        ctx.roundRect(this.dispX[index] - HALF_W - grow, this.dispY[index] - HALF_H - grow, CARD_W + grow * 2, CARD_H + grow * 2, 14 + grow);
        ctx.stroke();
        ctx.globalAlpha = 1;
        animating = true;
      }
    }
    ctx.restore();

    this.drawHoverTip(lod);
    return animating;
  }

  private edge(path: Path2D, px: number, py: number, cx: number, cy: number) {
    const y0 = py + HALF_H;
    const y1 = cy - HALF_H;
    const mid = (y0 + y1) / 2;
    path.moveTo(px, y0);
    path.bezierCurveTo(px, mid, cx, mid, cx, y1);
  }

  /** Depth soundings: each tree row is a depth g, marked like contour bands on a chart. */
  private drawSoundings(layout: TreeLayout) {
    const { ctx, palette } = this;
    const { k, y: ty } = this.transform;
    const rowPx = ROW * k;
    const every = rowPx >= 26 ? 1 : Math.ceil(26 / rowPx);
    const first = Math.max(0, Math.floor((-ty / k) / ROW) - 1);
    const last = Math.min(layout.maxDepth + 1, Math.ceil((this.height - ty) / k / ROW) + 1);
    ctx.save();
    for (let depth = first; depth <= last; depth++) {
      const center = depth * ROW * k + ty;
      if (depth % 2 === 1) {
        ctx.fillStyle = palette.ink;
        ctx.globalAlpha = 0.028;
        ctx.fillRect(0, center - rowPx / 2, this.width, rowPx);
      }
      ctx.globalAlpha = 1;
      if (depth % every !== 0 || depth > layout.maxDepth) continue;
      const x = this.insets.left + 14;
      ctx.strokeStyle = palette.rule;
      ctx.setLineDash([2, 6]);
      ctx.beginPath();
      ctx.moveTo(x + 44, center);
      ctx.lineTo(this.width - this.insets.right, center);
      ctx.globalAlpha = 0.7;
      ctx.stroke();
      ctx.setLineDash([]);
      ctx.globalAlpha = 1;
      ctx.fillStyle = palette.inkSoft;
      ctx.font = `500 10px ${MONO}`;
      ctx.textBaseline = "middle";
      ctx.fillText(`g ${depth}`, x, center);
    }
    ctx.restore();
  }

  /** Draws one node; returns true while its own reveal animation runs. */
  private node(id: number, x: number, y: number, lod: number, now: number) {
    const scene = this.scene!;
    const ghost = id >= scene.revealedEnd;
    const reveal = this.revealAt[id];
    const p = ghost ? 0 : reveal ? clamp01((now - reveal) / Math.max(1, this.animMs)) : 1;
    const enter = this.enterAt[id];
    const entering = enter && this.animMs > 0 ? clamp01((now - enter) / this.animMs) : 1;
    if (ghost) {
      this.ghost(x, y, lod, entering);
      return entering < 1;
    }
    const scale = p < 1 ? 0.82 + 0.18 * easeBackOut(p) : 1;
    this.ctx.globalAlpha = p < 1 ? 0.25 + 0.75 * easeCubicOut(p) : 1;
    this.card(id, x, y, lod, scale, null, p);
    this.ctx.globalAlpha = 1;
    return p < 1 || entering < 1;
  }

  private ghost(x: number, y: number, lod: number, entering: number) {
    const { ctx, palette } = this;
    ctx.save();
    ctx.globalAlpha = 0.6 * entering;
    ctx.setLineDash(lod ? [6, 5] : []);
    ctx.strokeStyle = palette.inkSoft;
    ctx.lineWidth = 1.4;
    ctx.beginPath();
    ctx.roundRect(x - HALF_W, y - HALF_H, CARD_W, CARD_H, 12);
    ctx.stroke();
    ctx.restore();
  }

  private card(id: number, x: number, y: number, lod: number, scale: number, mode: "exit" | null, revealP = 1) {
    const { ctx, palette, trace } = this;
    const scene = this.scene!;
    const pruned = trace.status[id] >= FIRST_PRUNED;
    const current = id === scene.current && !scene.goal;
    const route = scene.goal && this.solutionMask[id] === 1;
    const open = !pruned && isOpen(trace, scene, id);
    const inspected = id === this.options.inspected;
    const next = id === scene.next;
    const focus = id === scene.focus && scene.phase === "child";
    const markP = clamp01((revealP - 0.45) / 0.55);

    let fill = palette.surface;
    let stroke = palette.rule;
    let line = 1.2;
    let dash: number[] = [];
    let tray = palette.tray;
    let tile = palette.tile;
    let text = palette.ink;
    if (pruned && markP > 0) {
      fill = "#eef1f0";
      stroke = palette.signal;
      dash = [5, 4];
      tray = "#8a99a2";
      tile = "#e9eceb";
      text = palette.inkSoft;
    }
    if (open) {
      stroke = palette.sea;
      line = 2;
    }
    if (route) {
      fill = palette.brassWash;
      stroke = palette.brass;
      line = 2.6;
    }
    if (current) {
      fill = palette.ink;
      stroke = palette.ink;
      line = 2;
      text = "#f4f6f5";
    }

    if (lod === 0) {
      const size = Math.max(CARD_W, 6 / this.transform.k);
      ctx.fillStyle = current ? palette.ink : route ? palette.brass : pruned ? "#b9c4c8" : open ? palette.sea : palette.inkSoft;
      ctx.fillRect(x - size / 2, y - size / 2, size, size * 1.2);
      return;
    }

    ctx.save();
    ctx.translate(x, y);
    if (scale !== 1) ctx.scale(scale, scale);
    if (pruned && markP > 0 && mode !== "exit") ctx.globalAlpha *= 1 - 0.25 * markP;

    // Card body.
    ctx.beginPath();
    ctx.roundRect(-HALF_W, -HALF_H, CARD_W, CARD_H, 12);
    ctx.fillStyle = fill;
    ctx.shadowColor = current ? "rgba(19,42,59,0.35)" : "rgba(19,42,59,0.12)";
    ctx.shadowBlur = current ? 18 : 6;
    ctx.shadowOffsetY = current ? 6 : 2;
    ctx.fill();
    ctx.shadowColor = "transparent";
    ctx.setLineDash(dash);
    ctx.lineWidth = line;
    ctx.strokeStyle = stroke;
    ctx.stroke();
    ctx.setLineDash([]);

    // Mini board.
    const n = trace.size;
    const bx = -BOARD / 2;
    const by = -HALF_H + 20;
    ctx.fillStyle = tray;
    ctx.beginPath();
    ctx.roundRect(bx, by, BOARD, BOARD, 6);
    ctx.fill();
    if (lod === 2 || CARD_W * this.transform.k >= 24) {
      const gap = 3;
      const cell = (BOARD - gap * (n + 1)) / n;
      const offset = id * trace.cells;
      ctx.fillStyle = tile;
      ctx.beginPath();
      for (let c = 0; c < trace.cells; c++) {
        if (!trace.boards[offset + c]) continue;
        ctx.roundRect(bx + gap + (c % n) * (cell + gap), by + gap + Math.floor(c / n) * (cell + gap), cell, cell, 3);
      }
      ctx.fill();
      if (lod === 2) {
        ctx.fillStyle = pruned && markP > 0 ? palette.inkSoft : palette.ink;
        ctx.font = `700 ${n === 3 ? 13 : 10}px ${SANS}`;
        ctx.textAlign = "center";
        ctx.textBaseline = "middle";
        for (let c = 0; c < trace.cells; c++) {
          const value = trace.boards[offset + c];
          if (!value) continue;
          ctx.fillText(String(value), bx + gap + (c % n) * (cell + gap) + cell / 2, by + gap + Math.floor(c / n) * (cell + gap) + cell / 2 + 0.5);
        }
      }
    }

    if (lod === 2) {
      ctx.textBaseline = "alphabetic";
      // Header: id and status.
      ctx.textAlign = "left";
      ctx.font = `500 9px ${MONO}`;
      ctx.fillStyle = current ? "rgba(244,246,245,0.75)" : palette.inkSoft;
      ctx.fillText(`#${id}`, -HALF_W + 8, -HALF_H + 14);
      const tag = pruned ? STATUS_TAGS[STATUSES[trace.status[id]]] : current ? "扩展中" : open ? "候选" : route ? "解路径" : "";
      if (tag && (!pruned || markP > 0)) {
        ctx.textAlign = "right";
        ctx.font = `600 9px ${SANS}`;
        ctx.globalAlpha *= pruned ? markP : 1;
        ctx.fillStyle = pruned ? palette.signal : current ? "#f4f6f5" : open ? palette.sea : palette.brass;
        ctx.fillText(tag, HALF_W - 8, -HALF_H + 14);
        ctx.globalAlpha /= pruned ? Math.max(markP, 0.001) : 1;
      }
      // Display g, h and the estimate f = g + h; Dijkstra orders by g alone.
      const g = trace.g[id];
      const h = trace.h[id];
      const fy = HALF_H - 9;
      ctx.textAlign = "left";
      ctx.font = `500 10px ${MONO}`;
      ctx.fillStyle = current ? "#f4f6f5" : palette.ink;
      const gText = String(g);
      ctx.fillText(gText, -HALF_W + 8, fy);
      let cursor = -HALF_W + 8 + ctx.measureText(gText).width;
      ctx.fillStyle = current ? "rgba(244,246,245,0.6)" : palette.inkSoft;
      ctx.fillText("+", cursor + 1, fy);
      cursor += ctx.measureText("+").width + 2;
      ctx.fillStyle = current ? "#f3b6d6" : palette.magenta;
      ctx.fillText(String(h), cursor, fy);
      ctx.textAlign = "right";
      ctx.font = `700 11px ${MONO}`;
      ctx.fillStyle = text;
      ctx.fillText(`f ${g + h}`, HALF_W - 8, fy);
    }

    // Rings and labels for emphasised nodes.
    if (inspected || focus || id === this.hovered) {
      ctx.beginPath();
      ctx.roundRect(-HALF_W - 5, -HALF_H - 5, CARD_W + 10, CARD_H + 10, 16);
      ctx.lineWidth = inspected ? 2.5 : 1.5;
      ctx.strokeStyle = inspected ? palette.ink : focus ? palette.sea : palette.inkSoft;
      ctx.globalAlpha *= inspected ? 1 : 0.6;
      ctx.stroke();
    }
    ctx.restore();
    if (next && mode === null) this.pill(x, y - HALF_H - 14, "下一个", palette.sea);
    if (current && lod > 0) this.pill(x, y - HALF_H - 14, scene.phase === "pick" ? "取出" : "扩展", palette.ink);
  }

  private pill(x: number, y: number, label: string, color: string) {
    const { ctx } = this;
    const k = this.transform.k;
    ctx.save();
    ctx.translate(x, y);
    const scale = 1 / Math.max(k, 0.55);
    ctx.scale(scale, scale);
    ctx.font = `600 11px ${SANS}`;
    const width = ctx.measureText(label).width + 16;
    ctx.fillStyle = color;
    ctx.beginPath();
    ctx.roundRect(-width / 2, -10, width, 20, 10);
    ctx.fill();
    ctx.fillStyle = "#f4f6f5";
    ctx.textAlign = "center";
    ctx.textBaseline = "middle";
    ctx.fillText(label, 0, 0.5);
    ctx.restore();
  }

  private drawHoverTip(lod: number) {
    if (this.hovered < 0 || lod === 2 || !this.layout) return;
    const { ctx, palette, trace } = this;
    const index = this.hovered - this.layout.start;
    const sx = this.dispX[index] * this.transform.k + this.transform.x;
    const sy = (this.dispY[index] - HALF_H) * this.transform.k + this.transform.y - 10;
    const label = `#${this.hovered}  g ${trace.g[this.hovered]} · h ${trace.h[this.hovered]} · f ${trace.g[this.hovered] + trace.h[this.hovered]}${trace.pathCostOnly ? " · 按 g 排序" : ""}`;
    ctx.save();
    ctx.font = `500 11px ${MONO}`;
    const width = ctx.measureText(label).width + 18;
    ctx.fillStyle = palette.ink;
    ctx.beginPath();
    ctx.roundRect(sx - width / 2, sy - 24, width, 24, 8);
    ctx.fill();
    ctx.fillStyle = "#f4f6f5";
    ctx.textAlign = "center";
    ctx.textBaseline = "middle";
    ctx.fillText(label, sx, sy - 11.5);
    ctx.restore();
  }

  // ---------------------------------------------------------------- minimap

  private minimapFrame() {
    const layout = this.layout!;
    const map = this.minimap!;
    const width = map.canvas.clientWidth;
    const height = map.canvas.clientHeight;
    const worldW = Math.max(SLOT, layout.maxX - layout.minX + CARD_W);
    const worldH = Math.max(ROW, layout.maxDepth * ROW + CARD_H);
    const scale = Math.min((width - 12) / worldW, (height - 12) / worldH);
    const ox = (width - worldW * scale) / 2 - (layout.minX - HALF_W) * scale;
    const oy = (height - worldH * scale) / 2 + HALF_H * scale;
    return { width, height, scale, ox, oy };
  }

  private minimapToWorld(mx: number, my: number) {
    if (!this.layout || !this.minimap) return null;
    const { scale, ox, oy } = this.minimapFrame();
    return { x: (mx - ox) / scale, y: (my - oy) / scale };
  }

  private drawMinimap(rebuild = false) {
    const map = this.minimap;
    const layout = this.layout;
    if (!map || !layout || !this.scene) return;
    const dpr = this.dpr;
    const { width, height, scale, ox, oy } = this.minimapFrame();
    if (map.canvas.width !== Math.round(width * dpr)) {
      map.canvas.width = Math.round(width * dpr);
      map.canvas.height = Math.round(height * dpr);
      map.dirty = true;
    }
    if (map.dirty && (rebuild || !map.lastBuild)) {
      map.image.width = map.canvas.width;
      map.image.height = map.canvas.height;
      const ictx = map.image.getContext("2d")!;
      ictx.setTransform(dpr, 0, 0, dpr, 0, 0);
      ictx.clearRect(0, 0, width, height);
      const dot = Math.max(1.2, Math.min(4, CARD_W * scale));
      const scene = this.scene;
      for (let i = 0; i < layout.count; i++) {
        const id = layout.members[i];
        if (id >= scene.revealedEnd) continue;
        const pruned = this.trace.status[id] >= FIRST_PRUNED;
        ictx.fillStyle = scene.goal && this.solutionMask[id] ? this.palette.brass : pruned ? "#aab6bb" : isOpen(this.trace, scene, id) ? this.palette.sea : this.palette.ink;
        ictx.fillRect(layout.x[id - layout.start] * scale + ox - dot / 2, this.trace.g[id] * ROW * scale + oy - dot / 2, dot, dot);
      }
      map.dirty = false;
      map.lastBuild = performance.now();
    }
    const ctx = map.ctx;
    ctx.setTransform(1, 0, 0, 1, 0, 0);
    ctx.clearRect(0, 0, map.canvas.width, map.canvas.height);
    ctx.drawImage(map.image, 0, 0);
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    const { k, x, y } = this.transform;
    const vx = (-x / k) * scale + ox;
    const vy = (-y / k) * scale + oy;
    const vw = (this.width / k) * scale;
    const vh = (this.height / k) * scale;
    ctx.strokeStyle = this.palette.ink;
    ctx.lineWidth = 1.5;
    ctx.fillStyle = "rgba(19,42,59,0.06)";
    ctx.beginPath();
    ctx.rect(Math.max(-2, vx), Math.max(-2, vy), Math.min(width + 4, vw), Math.min(height + 4, vh));
    ctx.fill();
    ctx.stroke();
  }
}
