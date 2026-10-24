// What the run view on screen is doing, for the pieces outside it (Search → Sessions' running card, the actions'
// "one run at a time" check). RunView publishes; others read with useLiveRun() and call the controls it registered.

import { useSyncExternalStore } from 'react';

export interface LiveRun {
  title: string;
  accent: string;
  breathing: boolean;
  night: boolean;
  openEnded: boolean;
  paused: boolean;
  collapsed: boolean;
  /** "03:12 left", "Paused · 03:12", "Running · 04:00". */
  status: string;
}

export interface RunControls {
  expand(): void;
  togglePause(): void;
  /** The "End now?" choice (mark as done / end session). */
  askEnd(): void;
  /** Count it as done now. */
  complete(): void;
}

let current: LiveRun | null = null;
let controls: RunControls | null = null;
const listeners = new Set<() => void>();

export function publish(next: LiveRun | null, ctl?: RunControls | null): void {
  current = next;
  if (ctl !== undefined) controls = ctl;
  if (next === null) controls = null;
  listeners.forEach((l) => l());
}

export const liveRun = () => current;
export const runControls = () => controls;

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export function useLiveRun(): LiveRun | null {
  return useSyncExternalStore(subscribe, liveRun, liveRun);
}

