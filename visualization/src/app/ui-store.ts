import { create } from "zustand";

export type View = "path" | "search";

interface UiState {
  view: View;
  libraryOpen: boolean;
  /** Search view fills the screen without app chrome (browser fullscreen when available). */
  immersive: boolean;
}

export const useUi = create<UiState>(() => ({ view: "path", libraryOpen: false, immersive: false }));

type Pausable = () => void;
const pausers = new Set<Pausable>();

/** Views register their pause action so the shell can stop playback on view switches, dialogs and hidden tabs. */
export function registerPauser(pause: Pausable) {
  pausers.add(pause);
  return () => void pausers.delete(pause);
}

export function pauseAll() {
  for (const pause of pausers) pause();
}

export function setView(view: View) {
  if (useUi.getState().view === view) return;
  pauseAll();
  useUi.setState({ view });
}

export function setLibraryOpen(libraryOpen: boolean) {
  if (libraryOpen) pauseAll();
  useUi.setState({ libraryOpen });
}

/** Immersive mode hides app chrome and asks the browser for fullscreen; chrome-less mode still works if fullscreen is refused. */
export async function setImmersive(immersive: boolean) {
  if (useUi.getState().immersive === immersive) return;
  useUi.setState({ immersive });
  try {
    if (immersive && !document.fullscreenElement) await document.documentElement.requestFullscreen?.();
    if (!immersive && document.fullscreenElement) await document.exitFullscreen();
  } catch {
    // Fullscreen can be refused (iframes, permissions); immersive layout still applies.
  }
}
