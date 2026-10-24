// Search → Sessions' arithmetic (demo ui/pages/sessions.py, runview.session_status), kept pure for tests.

import { localParts, weekStart, addDays, clock, daysBetween, weekday } from '../lib/agenda';
import type { Json } from '../lib/types';

const DAY_NAMES = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];

/** The disc on a session row, by session kind. */
export const KIND_TILES: Record<string, [string, string]> = {
  focus: ['timer', 'blue'],
  walk: ['walk', 'fitness'],
  meeting: ['people', 'teal'],
  recovery: ['recover', 'recovery'],
};

/** "30 min": the planned length, else the time between start and end; empty when neither is known. */
export function lengthText(s: Json): string {
  const planned = s.plannedMinutes;
  if (typeof planned === 'number' && planned > 0) return `${Math.round(planned)} min`;
  const a = new Date(String(s.startedAt ?? '')).getTime();
  const b = new Date(String(s.endedAt ?? '')).getTime();
  if (Number.isNaN(a) || Number.isNaN(b)) return '';
  return `${Math.max(1, Math.round((b - a) / 60_000))} min`;
}

/** Minutes a session took: what was timed, else its length. */
export function minutesOf(s: Json): number {
  if (typeof s.actualMinutes === 'number') return Math.max(0, s.actualMinutes);
  const text = lengthText(s);
  return text ? Number.parseInt(text, 10) : 0;
}

/** "Today 10:00", "Yesterday 10:00" or "Mon 10:00". */
export function whenText(value: unknown, zone: string, today: string): string {
  const at = localParts(value, zone);
  if (!at) return String(value ?? '').slice(0, 16).replace('T', ' ');
  const gap = daysBetween(at.day, today);
  const name = gap === 0 ? 'Today' : gap === 1 ? 'Yesterday' : DAY_NAMES[weekday(at.day)];
  return `${name} ${clock(at.minute)}`;
}

/** The sessions that started this week (Monday on), newest first. */
export function thisWeek(sessions: Json[], zone: string, today: string): Json[] {
  const from = weekStart(today);
  const to = addDays(from, 6);
  return sessions
    .filter((s) => {
      const day = localParts(s.startedAt, zone)?.day;
      return day !== undefined && day >= from && day <= to;
    })
    .sort((a, b) => new Date(String(b.startedAt)).getTime() - new Date(String(a.startedAt)).getTime());
}

export const isStopped = (s: Json) => (s.status ?? s.outcome ?? s.state) === 'stopped';

export interface Totals {
  count: number;
  completed: number;
  minutes: number;
}

export function totals(sessions: Json[]): Totals {
  return {
    count: sessions.length,
    completed: sessions.filter((s) => !isStopped(s)).length,
    minutes: sessions.reduce((n, s) => n + minutesOf(s), 0),
  };
}

/** "12 min left", "Running · 4 min", "Paused" or "Starting…" for a session the server says is open. */
export function sessionStatus(session: Json, nowMs: number): string {
  const state = String(session.state ?? '');
  if (state === 'paused') return 'Paused';
  if (state === 'starting') return 'Starting…';
  const started = new Date(String(session.startedAt ?? '')).getTime();
  if (Number.isNaN(started)) return 'Running';
  const ran = Math.max(0, Math.floor((nowMs - started) / 60_000));
  const planned = session.plannedMinutes;
  if (typeof planned === 'number' && planned > 0) {
    const left = Math.floor(planned) - ran;
    return left > 0 ? `${left} min left` : 'Time is up';
  }
  return `Running · ${ran} min`;
}

/** The server's running session, when it is still open. */
export function openServerSession(active: Json | null): Json | null {
  return active && !['ended', 'completed', 'stopped'].includes(String(active.state ?? '')) ? active : null;
}
