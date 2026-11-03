import { create } from "zustand";
import { registerPauser } from "@/app/ui-store";
import type { Phase } from "@/replay/model";

export const SPEEDS = [0.5, 1, 2, 4, 16, 64, 256, 1024] as const;
export type Speed = (typeof SPEEDS)[number];

/** Narration time per shot at 1×. A round with three successors takes about 3.3 s. */
export const PHASE_MS: Record<Phase, number> = { ready: 1000, pick: 800, child: 600, merge: 700, goal: 1600 };

/** How the latest position change happened; the renderer animates steps and snaps on seeks. */
export type Motion = "play" | "step" | "seek";

interface SearchPlayback {
  recordId: string | null;
  shot: number;
  playing: boolean;
  speed: Speed;
  motion: Motion;
  showPruned: boolean;
  follow: boolean;
  inspected: number | null;
}

export const useSearchPlayback = create<SearchPlayback>(() => ({
  recordId: null,
  shot: 0,
  playing: false,
  speed: 1,
  motion: "seek",
  showPruned: false,
  follow: true,
  inspected: null,
}));

const set = useSearchPlayback.setState;

export const searchActions = {
  /** Progress belongs to a record: only a different record id resets it (renames keep it). */
  bind(recordId: string) {
    if (useSearchPlayback.getState().recordId === recordId) return;
    set({ recordId, shot: 0, playing: false, motion: "seek", inspected: null, follow: true });
  },
  pause() {
    if (useSearchPlayback.getState().playing) set({ playing: false });
  },
  toggle(total: number) {
    const { playing, shot } = useSearchPlayback.getState();
    if (playing) set({ playing: false });
    else set({ playing: true, shot: shot >= total - 1 ? 0 : shot, motion: shot >= total - 1 ? "seek" : "play", inspected: null, follow: true });
  },
  stepBy(delta: number, total: number) {
    const shot = Math.max(0, Math.min(total - 1, useSearchPlayback.getState().shot + delta));
    set({ shot, playing: false, motion: Math.abs(delta) === 1 ? "step" : "seek", inspected: null });
  },
  seek(shot: number, total: number, motion: Motion = "seek") {
    set({ shot: Math.max(0, Math.min(total - 1, shot)), playing: false, motion, inspected: null });
  },
  /** Playback tick; keeps playing state. */
  advanceTo(shot: number, total: number) {
    set({ shot: Math.min(total - 1, shot), motion: "play", inspected: null, ...(shot >= total - 1 ? { playing: false } : {}) });
  },
  setSpeed(speed: Speed) {
    set({ speed });
  },
  setShowPruned(showPruned: boolean) {
    set({ showPruned });
  },
  setFollow(follow: boolean) {
    set({ follow });
  },
  inspect(inspected: number | null) {
    set({ inspected, playing: false });
  },
};

registerPauser(searchActions.pause);
