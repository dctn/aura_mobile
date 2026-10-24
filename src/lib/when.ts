// Moments in the demo person's zone (aura.zone), written the way the PyQt app writes them: an ISO wall time with that
// zone's offset ("2026-10-24T18:00:00+05:30"). The offset comes from Intl, so summer time is right too.

import { addDays, localParts } from './agenda';

const pad = (n: number) => String(n).padStart(2, '0');

/** Minutes the zone is ahead of UTC at `at` (Asia/Kolkata: 330). An unknown zone counts as UTC. */
export function offsetMinutes(zone: string, at: Date): number {
  try {
    const f = new Intl.DateTimeFormat('en-US', {
      timeZone: zone, hourCycle: 'h23', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit',
    });
    const p = Object.fromEntries(f.formatToParts(at).map((x) => [x.type, x.value]));
    const wall = Date.UTC(Number(p.year), Number(p.month) - 1, Number(p.day), Number(p.hour) % 24, Number(p.minute), Number(p.second));
    const whole = Math.floor(at.getTime() / 1000) * 1000;
    return Math.round((wall - whole) / 60_000);
  } catch {
    return 0;
  }
}

/** 330 -> "+05:30", -240 -> "-04:00". */
export function offsetText(minutes: number): string {
  const sign = minutes < 0 ? '-' : '+';
  const m = Math.abs(minutes);
  return `${sign}${pad(Math.floor(m / 60))}:${pad(m % 60)}`;
}

/** The wall time `minute` (after midnight; past 1440 rolls into the next days) on `day` in `zone`, as ISO with offset. */
export function wallIso(zone: string, day: string, minute: number, second = 0): string {
  const extra = Math.floor(minute / 1440);
  const d = extra ? addDays(day, extra) : day;
  const m = minute - extra * 1440;
  const [y, mo, dd] = d.split('-').map(Number);
  const guess = Date.UTC(y, mo - 1, dd, Math.floor(m / 60), m % 60, second);
  let off = offsetMinutes(zone, new Date(guess));
  const again = offsetMinutes(zone, new Date(guess - off * 60_000));
  if (again !== off) off = again;
  return `${d}T${pad(Math.floor(m / 60))}:${pad(m % 60)}:${pad(second)}${offsetText(off)}`;
}

export interface Now {
  /** The demo's today ('YYYY-MM-DD'). */
  day: string;
  /** Minutes after midnight in the zone. */
  minute: number;
  second: number;
  /** That moment as ISO with the zone's offset. */
  iso: string;
}

/** Now in `zone`: the real time of day there, on `today` when given (the bundled sample is a fixed day). */
export function nowIn(zone: string, today?: string, at: Date = new Date()): Now {
  const parts = localParts(at.toISOString(), zone);
  const day = today ?? parts?.day ?? at.toISOString().slice(0, 10);
  const minute = parts?.minute ?? at.getUTCHours() * 60 + at.getUTCMinutes();
  const second = at.getUTCSeconds();
  return { day, minute, second, iso: wallIso(zone, day, minute, second) };
}

/** The same wall time `days` later in `zone` (an event moved to tomorrow); anything unreadable comes back as it was. */
export function shiftIso(value: unknown, days: number, zone: string): unknown {
  const at = localParts(value, zone);
  if (!at) return value;
  const second = Number(String(value).slice(17, 19)) || 0;
  return wallIso(zone, addDays(at.day, days), at.minute, second);
}

/** Whole minutes from one ISO moment to another (0 when either is unreadable). */
export function minutesBetween(from: unknown, to: unknown): number {
  const a = new Date(String(from)).getTime();
  const b = new Date(String(to)).getTime();
  return Number.isNaN(a) || Number.isNaN(b) ? 0 : Math.round((b - a) / 60_000);
}
