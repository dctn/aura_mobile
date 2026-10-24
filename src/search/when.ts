// Times in the demo person's zone (aura.zone): the ISO strings new rows carry, and how a moment reads on a row.

import { daysBetween, localParts } from '../lib/agenda';

interface Clock {
  day: string;
  hh: string;
  mm: string;
  ss: string;
}

function clockIn(zone: string, at: Date): Clock {
  try {
    const f = new Intl.DateTimeFormat('en-CA', {
      timeZone: zone, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23',
    });
    const p = Object.fromEntries(f.formatToParts(at).map((x) => [x.type, x.value]));
    return { day: `${p.year}-${p.month}-${p.day}`, hh: p.hour, mm: p.minute, ss: p.second };
  } catch {
    const iso = at.toISOString();
    return { day: iso.slice(0, 10), hh: iso.slice(11, 13), mm: iso.slice(14, 16), ss: iso.slice(17, 19) };
  }
}

/** The zone's offset from UTC at `at`, in minutes (Asia/Kolkata: 330). */
export function offsetMinutes(zone: string, at: Date): number {
  const c = clockIn(zone, at);
  const [y, m, d] = c.day.split('-').map(Number);
  const local = Date.UTC(y, m - 1, d, Number(c.hh), Number(c.mm), Number(c.ss));
  return Math.round((local - Math.floor(at.getTime() / 1000) * 1000) / 60_000);
}

/** "+05:30", "-04:00", "+00:00". */
export function offsetText(minutes: number): string {
  const sign = minutes < 0 ? '-' : '+';
  const a = Math.abs(minutes);
  return `${sign}${String(Math.floor(a / 60)).padStart(2, '0')}:${String(a % 60).padStart(2, '0')}`;
}

/** `at` as an ISO string in `zone` with its offset ("2026-10-24T13:29:09+05:30"). With `day`, that day at the same
 *  clock time (the bundled sample's "today" is a fixed day, so new rows land on it). */
export function isoIn(zone: string, at: Date, day?: string): string {
  const c = clockIn(zone, at);
  return `${day ?? c.day}T${c.hh}:${c.mm}:${c.ss}${offsetText(offsetMinutes(zone, at))}`;
}

const WEEKDAYS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

/** "1 Oct" for a 'YYYY-MM-DD' day. */
export function shortDay(day: string): string {
  const [, m, d] = day.split('-').map(Number);
  return `${d} ${MONTHS[m - 1] ?? ''}`.trim();
}

/** A moment as "Today 10:00", "Yesterday 10:00", "Mon 10:00" (this week) or "18 Oct 10:00", in `zone`. */
export function whenText(value: unknown, today: string, zone: string): string {
  const at = localParts(value, zone);
  if (!at) return '';
  const clock = `${String(Math.floor(at.minute / 60)).padStart(2, '0')}:${String(at.minute % 60).padStart(2, '0')}`;
  const gap = daysBetween(at.day, today);
  let name: string;
  if (gap === 0) name = 'Today';
  else if (gap === 1) name = 'Yesterday';
  else if (gap > 1 && gap < 7) name = WEEKDAYS[(new Date(`${at.day}T00:00:00Z`).getUTCDay() + 6) % 7];
  else name = shortDay(at.day);
  return `${name} ${clock}`;
}
