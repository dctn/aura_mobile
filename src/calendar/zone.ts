// Moments in the demo person's zone (aura.zone), not the browser's. A day is 'YYYY-MM-DD' and a time is minutes after
// midnight there; agenda.localParts reads the ISO strings made here back into the same day and minute.

const pad = (n: number, w = 2) => String(Math.abs(n)).padStart(w, '0');

/** How many minutes `zone` is ahead of UTC at the moment `ms`. */
export function zoneOffset(ms: number, zone: string): number {
  const f = new Intl.DateTimeFormat('en-US', {
    timeZone: zone, hourCycle: 'h23', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit',
  });
  const p = Object.fromEntries(f.formatToParts(new Date(ms)).map((x) => [x.type, x.value]));
  const wall = Date.UTC(Number(p.year), Number(p.month) - 1, Number(p.day), Number(p.hour), Number(p.minute), Number(p.second));
  const whole = ms - (((ms % 1000) + 1000) % 1000);
  return Math.round((wall - whole) / 60_000);
}

/** The moment (ms) of `minute` after midnight on `day` in `zone`. Minutes past 24 h roll into the next days. */
export function zonedMs(day: string, minute: number, zone: string): number {
  const [y, m, d] = day.split('-').map(Number);
  const wall = Date.UTC(y, m - 1, d) + minute * 60_000;
  // first guess with the offset at the wall time, then again at the guess (right across a DST change)
  const guess = wall - zoneOffset(wall, zone) * 60_000;
  return wall - zoneOffset(guess, zone) * 60_000;
}

/** `ms` as an ISO string with the zone's offset: "2026-10-24T15:00:00+05:30". */
export function isoInZone(ms: number, zone: string): string {
  const off = zoneOffset(ms, zone);
  const d = new Date(ms + off * 60_000);
  const date = `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())}`;
  const time = `${pad(d.getUTCHours())}:${pad(d.getUTCMinutes())}:${pad(d.getUTCSeconds())}`;
  return `${date}T${time}${off < 0 ? '-' : '+'}${pad(Math.floor(Math.abs(off) / 60))}:${pad(Math.abs(off) % 60)}`;
}

/** `minute` after midnight on `day` in `zone`, as an ISO string with that zone's offset. */
export const zonedIso = (day: string, minute: number, zone: string) => isoInZone(zonedMs(day, minute, zone), zone);

/** "Sat 24 Oct" for a plain day. */
export const shortDay = (day: string) =>
  new Date(`${day}T00:00:00Z`).toLocaleDateString('en-GB', { weekday: 'short', day: 'numeric', month: 'short', timeZone: 'UTC' });

/** "Saturday 24 October" for a plain day (Home's subtitle). */
export const longDay = (day: string) =>
  new Date(`${day}T00:00:00Z`).toLocaleDateString('en-GB', { weekday: 'long', day: 'numeric', month: 'long', timeZone: 'UTC' });

/** "Oct 2026". */
export const monthTitle = (day: string) =>
  new Date(`${day}T00:00:00Z`).toLocaleDateString('en-GB', { month: 'short', year: 'numeric', timeZone: 'UTC' });

/** "Sat" for a plain day. */
export const weekdayShort = (day: string) =>
  new Date(`${day}T00:00:00Z`).toLocaleDateString('en-GB', { weekday: 'short', timeZone: 'UTC' });
