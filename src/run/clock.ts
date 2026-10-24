// The run view's arithmetic (ui/runview.py), kept pure so it can be tested: a wall-clock start, pauses added up, the
// clock text, and the breathing pacer's size.

/** The word in the kicker, by session kind ("MOVE · 20 MIN"). */
export const KIND_WORDS: Record<string, string> = { recovery: 'Recovery', walk: 'Move', nap: 'Nap', focus: 'Focus', meeting: 'Meeting' };

export interface Clock {
  /** Seconds the run lasts; 0 is open-ended (the clock counts up). */
  total: number;
  /** Date.now() when it started. */
  startedAt: number;
  /** Date.now() when it was paused, or null while it runs. */
  pausedAt: number | null;
  /** Milliseconds spent paused before the current pause. */
  pausedMs: number;
}

export const startClock = (total: number, now: number): Clock => ({ total: Math.max(0, total), startedAt: now, pausedAt: null, pausedMs: 0 });

export const openEnded = (c: Clock) => c.total <= 0;

/** Seconds run so far, not counting pauses. */
export function elapsed(c: Clock, now: number): number {
  const until = c.pausedAt ?? now;
  const s = Math.max(0, (until - c.startedAt - c.pausedMs) / 1000);
  return openEnded(c) ? s : Math.min(c.total, s);
}

/** Seconds left (open-ended: always the whole of nothing, 0). */
export const left = (c: Clock, now: number) => (openEnded(c) ? 0 : Math.max(0, c.total - elapsed(c, now)));

/** What the big clock shows: the time left, or the time so far when open-ended. */
export const clockSeconds = (c: Clock, now: number) => (openEnded(c) ? elapsed(c, now) : left(c, now));

/** 0..1 along the progress line. */
export const progress = (c: Clock, now: number) => (openEnded(c) ? 0 : Math.min(1, elapsed(c, now) / Math.max(1, c.total)));

export const isUp = (c: Clock, now: number) => !openEnded(c) && left(c, now) <= 0;

export const pause = (c: Clock, now: number): Clock => (c.pausedAt === null ? { ...c, pausedAt: now } : c);

export const resume = (c: Clock, now: number): Clock => (c.pausedAt === null ? c : { ...c, pausedAt: null, pausedMs: c.pausedMs + (now - c.pausedAt) });

/** "05:00": minutes and seconds, rounded up (a fresh 20-minute run shows 20:00, not 19:59). */
export function clockText(seconds: number): string {
  const s = Math.max(0, Math.ceil(seconds - 1e-9));
  return `${String(Math.floor(s / 60)).padStart(2, '0')}:${String(s % 60).padStart(2, '0')}`;
}

/** "3 minutes left", "1 minute left", "less than a minute left". */
export function minutesLeftText(seconds: number): string {
  if (seconds < 60) return 'less than a minute left';
  const m = Math.ceil(Math.max(0, seconds) / 60);
  return `${m} minute${m !== 1 ? 's' : ''} left`;
}

/** The small caps line over the title: "MOVE · 20 MIN", "BREATHE · 5 MIN", "FOCUS · OPEN". */
export function kicker(kind: string, minutes: number, breathing = false): string {
  const word = breathing ? 'Breathe' : KIND_WORDS[kind] ?? (kind ? kind[0].toUpperCase() + kind.slice(1) : 'Session');
  return `${word} · ${minutes > 0 ? `${minutes} min` : 'open'}`.toUpperCase();
}

/** The pacer: in for 5 s while the circle grows from 0.62 to full, out for 5 s while it shrinks (eased). */
export const BREATH_IN = 5;
export const BREATH_OUT = 5;
export const BREATH_SMALL = 0.62;

export function breath(seconds: number): { inhaling: boolean; scale: number; text: string } {
  const phase = ((seconds % (BREATH_IN + BREATH_OUT)) + BREATH_IN + BREATH_OUT) % (BREATH_IN + BREATH_OUT);
  const inhaling = phase < BREATH_IN;
  const x = inhaling ? phase / BREATH_IN : 1 - (phase - BREATH_IN) / BREATH_OUT;
  const eased = 0.5 - 0.5 * Math.cos(Math.PI * x);
  return { inhaling, scale: BREATH_SMALL + (1 - BREATH_SMALL) * eased, text: inhaling ? 'Breathe in' : 'Breathe out' };
}

/** The mini bar's second line: "Paused · 03:12", "Running · 04:00" (open-ended) or "03:12 left". */
export function statusText(c: Clock, now: number): string {
  const clock = clockText(clockSeconds(c, now));
  if (c.pausedAt !== null) return `Paused · ${clock}`;
  return openEnded(c) ? `Running · ${clock}` : `${clock} left`;
}
