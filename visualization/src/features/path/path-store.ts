import { create } from "zustand";
import { registerPauser } from "@/app/ui-store";

export const SPEEDS = [0.5, 1, 2, 4] as const;
export type Speed = (typeof SPEEDS)[number];

/** Milliseconds per step at 1×. */
const BASE_INTERVAL = 500;
export const stepInterval = (speed: Speed) => BASE_INTERVAL / speed;

interface PathPlayback {
  recordId: string | null;
  step: number;
  playing: boolean;
  speed: Speed;
  /** Resets progress only when a different record is shown; renames keep the id. */
  bindRecord: (id: string) => void;
  pause: () => void;
  /** `total` is the number of moves, i.e. the last step index. */
  toggle: (total: number) => void;
  /** Timer tick: advance one step and stop on the last one. */
  advance: (total: number) => void;
  stepBy: (delta: number, total: number) => void;
  seek: (step: number, total: number) => void;
  setSpeed: (speed: Speed) => void;
}

const clamp = (step: number, total: number) => Math.min(Math.max(Math.round(step), 0), Math.max(total, 0));

export const usePathPlayback = create<PathPlayback>((set, get) => ({
  recordId: null,
  step: 0,
  playing: false,
  speed: 1,
  bindRecord: (id) => {
    if (get().recordId !== id) set({ recordId: id, step: 0, playing: false });
  },
  pause: () => {
    if (get().playing) set({ playing: false });
  },
  toggle: (total) => {
    const { playing, step } = get();
    if (playing) set({ playing: false });
    else if (total > 0) set({ playing: true, step: step >= total ? 0 : step });
  },
  advance: (total) => {
    const next = clamp(get().step + 1, total);
    set({ step: next, playing: next < total });
  },
  stepBy: (delta, total) => set({ playing: false, step: clamp(get().step + delta, total) }),
  seek: (step, total) => set({ playing: false, step: clamp(step, total) }),
  setSpeed: (speed) => set({ speed }),
}));

registerPauser(() => usePathPlayback.getState().pause());
