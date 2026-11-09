// Day zones: the shades behind the hours (light work, heavy work, short recovery, heavy recovery). The capacity score
// says how much heavy work the day holds; the person's work and lunch hours say where it goes. The score barely moves
// during a day, so it sets the amount once rather than the shade at each moment. Pure, so Planner can take it as is.
// Named dayZones because ./zone.ts means time zones.

export const ZONE_KINDS = ['lightWork', 'heavyWork', 'shortRecovery', 'heavyRecovery'] as const;
export type ZoneKind = (typeof ZONE_KINDS)[number];

export const ZONE_NAMES: Readonly<Record<ZoneKind, string>> = {
  lightWork: 'Light work',
  heavyWork: 'Heavy work',
  shortRecovery: 'Short recovery',
  heavyRecovery: 'Heavy recovery',
};

export const ZONE_MEANING: Readonly<Record<ZoneKind, string>> = {
  lightWork: 'Email, admin, routine',
  heavyWork: 'Your hardest task',
  shortRecovery: 'Step away for a moment',
  heavyRecovery: 'Lunch or a proper rest',
};

/** One stretch of the day, in minutes after midnight. */
export interface DayZone {
  kind: ZoneKind;
  start: number;
  end: number;
}

/** 'HH:MM' times. `workDays`: 1 = Monday ... 7 = Sunday. */
export interface WorkSchedule {
  workdayStart: string;
  workdayEnd: string;
  lunchStart: string;
  lunchMinutes: number;
  workDays: number[];
}

/**
 * The schedule the demo falls back to until the visitor sets their own work hours. It covers all seven days on purpose:
 * the demo should show the zones (and let Auto-allocate fill them) whatever day the snapshot lands on — a weekday or a
 * weekend — so both features are visible together. A visitor who sets their own hours (e.g. Mon–Fri) overrides this,
 * and then a day off shades nothing, as it should. The real app gets the schedule from the person's profile instead.
 */
export const DEMO_SCHEDULE: WorkSchedule = { workdayStart: '09:00', workdayEnd: '18:00', lunchStart: '13:00', lunchMinutes: 60, workDays: [1, 2, 3, 4, 5, 6, 7] };

export type ScoreLevel = 'peak' | 'focused' | 'steady' | 'shallow' | 'depleted';

// Design choices to tune in the preview, not research results. The levels and their cut-offs are the capacity score's.
export const LEVEL_FROM: readonly (readonly [number, ScoreLevel])[] = [[85, 'peak'], [70, 'focused'], [55, 'steady'], [40, 'shallow'], [0, 'depleted']];
export const HEAVY_MINUTES: Readonly<Record<ScoreLevel, number>> = { peak: 240, focused: 180, steady: 120, shallow: 0, depleted: 0 };
export const EXTRA_RECOVERY: Readonly<Record<ScoreLevel, { kind: ZoneKind; minutes: number } | null>> = {
  peak: null,
  focused: null,
  steady: null,
  shallow: { kind: 'shortRecovery', minutes: 15 },
  depleted: { kind: 'heavyRecovery', minutes: 30 },
};
export const MAX_HEAVY_BLOCK = 90;
export const SHORT_RECOVERY = 15;
/** Light work longer than this gets a short recovery in the middle. */
export const LONG_STRETCH = 180;
/** A heavy block is never shorter than this; less time than this stays light work. */
const MIN_HEAVY = 15;
const SNAP = 15;

export const levelOf = (score: number): ScoreLevel => LEVEL_FROM.find(([from]) => score >= from)?.[1] ?? 'depleted';

/** "13:30" as minutes after midnight, or null. */
export function clockToMinutes(clock: string): number | null {
  const m = /^(\d{1,2}):(\d{2})$/.exec(clock);
  if (!m) return null;
  const minutes = Number(m[1]) * 60 + Number(m[2]);
  return minutes <= 24 * 60 ? minutes : null;
}

/** The first thing wrong with a schedule, for the sheet to show, or null. */
export function scheduleProblem(s: WorkSchedule): string | null {
  const start = clockToMinutes(s.workdayStart);
  const end = clockToMinutes(s.workdayEnd);
  const lunch = clockToMinutes(s.lunchStart);
  if (start === null || end === null || lunch === null) return 'Choose every time.';
  if (end <= start) return 'Work must end after it starts.';
  if (s.lunchMinutes < 0) return 'Lunch can\'t have a negative length.';
  if (lunch < start || lunch + s.lunchMinutes > end) return 'Lunch must fit inside your work hours.';
  if (s.workDays.length === 0) return 'Choose at least one work day.';
  return null;
}

/** A saved value as a schedule, or null when it isn't a usable one. */
export function asSchedule(value: unknown): WorkSchedule | null {
  if (!value || typeof value !== 'object') return null;
  const s = value as WorkSchedule;
  if (typeof s.lunchMinutes !== 'number' || !Array.isArray(s.workDays)) return null;
  return scheduleProblem(s) === null ? s : null;
}

/** 1 = Monday ... 7 = Sunday, for 'YYYY-MM-DD'. */
export const weekdayOf = (day: string) => new Date(`${day}T00:00:00Z`).getUTCDay() || 7;

const snapped = (minutes: number) => Math.floor(minutes / SNAP) * SNAP;

/** Heavy blocks from the start of the free time, with a short recovery between them. What's left stays free. */
function placeHeavy(free: readonly (readonly [number, number])[], amount: number): { zones: DayZone[]; left: [number, number][] } {
  const zones: DayZone[] = [];
  const left: [number, number][] = [];
  let heavy = amount;
  for (const [from, to] of free) {
    let at = from;
    while (heavy >= MIN_HEAVY && to - at >= MIN_HEAVY) {
      const block = Math.min(MAX_HEAVY_BLOCK, heavy, to - at);
      zones.push({ kind: 'heavyWork', start: at, end: at + block });
      at += block;
      heavy -= block;
      if (heavy < MIN_HEAVY || to - at < SHORT_RECOVERY + MIN_HEAVY) break;
      zones.push({ kind: 'shortRecovery', start: at, end: at + SHORT_RECOVERY });
      at += SHORT_RECOVERY;
    }
    if (to > at) left.push([at, to]);
  }
  return { zones, left };
}

/** A recovery in the middle of a stretch, snapped to the quarter hour, and the two stretches either side of it. */
function splitAtMiddle([from, to]: readonly [number, number], extra: { kind: ZoneKind; minutes: number }) {
  const start = from + snapped((to - from - extra.minutes) / 2);
  const zone: DayZone = { kind: extra.kind, start, end: start + extra.minutes };
  return { zone, before: [from, start] as [number, number], after: [zone.end, to] as [number, number] };
}

/** The day's extra recovery goes in the middle of the longest stretch (the later one when two are equal). */
function placeExtra(stretches: readonly [number, number][], extra: { kind: ZoneKind; minutes: number } | null) {
  const longest = stretches.reduce<[number, number] | null>((best, s) => (!best || s[1] - s[0] >= best[1] - best[0] ? s : best), null);
  if (!extra || !longest || longest[1] - longest[0] < extra.minutes + 2 * MIN_HEAVY) return { zones: [] as DayZone[], left: [...stretches] };
  const { zone, before, after } = splitAtMiddle(longest, extra);
  return { zones: [zone], left: [...stretches.filter((s) => s !== longest), before, after] };
}

/** Light work, split by a short recovery until no stretch is over LONG_STRETCH. */
function lightWork(stretch: readonly [number, number]): DayZone[] {
  const [from, to] = stretch;
  if (to <= from) return [];
  if (to - from <= LONG_STRETCH) return [{ kind: 'lightWork', start: from, end: to }];
  const { zone, before, after } = splitAtMiddle(stretch, { kind: 'shortRecovery', minutes: SHORT_RECOVERY });
  return [...lightWork(before), zone, ...lightWork(after)];
}

/** The zones of a work day: lunch, then heavy work from the morning, then any extra recovery, then light work. The
 *  zones are the day's whole rhythm as a backdrop; committed blocks sit on top of them, so the rhythm is laid across
 *  the full work hours and is not cut around what is booked. */
export function dayZones(schedule: WorkSchedule, score: number): DayZone[] {
  const start = clockToMinutes(schedule.workdayStart) ?? 0;
  const end = clockToMinutes(schedule.workdayEnd) ?? 0;
  const lunch = clockToMinutes(schedule.lunchStart) ?? start;
  const lunchEnd = lunch + schedule.lunchMinutes;
  const level = levelOf(score);
  const heavy = placeHeavy([[start, lunch], [lunchEnd, end]], HEAVY_MINUTES[level]);
  const extra = placeExtra(heavy.left, EXTRA_RECOVERY[level]);
  const zones: DayZone[] = [...heavy.zones, ...extra.zones, ...extra.left.flatMap(lightWork)];
  if (schedule.lunchMinutes > 0) zones.push({ kind: 'heavyRecovery', start: lunch, end: lunchEnd });
  return zones.sort((a, b) => a.start - b.start);
}

export type DayStatus = 'needsSchedule' | 'noScore' | 'dayOff' | 'ready';

export interface ZoneDay {
  status: DayStatus;
  zones: DayZone[];
  heavyMinutes: number;
}

/** A day as the calendar shows it. `score` is null when the day has none, and then nothing is shaded. */
export function zoneDay(schedule: WorkSchedule | null, score: number | null, day: string): ZoneDay {
  const blank = (status: DayStatus): ZoneDay => ({ status, zones: [], heavyMinutes: 0 });
  if (!schedule) return blank('needsSchedule');
  if (score === null) return blank('noScore');
  if (!schedule.workDays.includes(weekdayOf(day))) return blank('dayOff');
  const zones = dayZones(schedule, score);
  const heavyMinutes = zones.filter((z) => z.kind === 'heavyWork').reduce((sum, z) => sum + z.end - z.start, 0);
  return { status: 'ready', zones, heavyMinutes };
}

/** The hours a page shows for these zones: an hour either side of them, on the hour. Nothing to show: 08:00-18:00. */
export function hoursRange(zones: readonly DayZone[]): [number, number] {
  if (zones.length === 0) return [8, 18];
  const first = Math.floor(zones[0].start / 60);
  const last = Math.ceil((zones.at(-1)?.end ?? 0) / 60);
  return [Math.max(0, first - 1), Math.min(24, last + 1)];
}

/** "2 h", "1 h 30 min". */
export function hoursText(minutes: number): string {
  const h = Math.floor(minutes / 60);
  const m = minutes % 60;
  if (!h) return `${m} min`;
  return m ? `${h} h ${m} min` : `${h} h`;
}

/** Why the day looks as it does. */
export function zoneReason(score: number, heavyMinutes: number): string {
  const base = `Your capacity score is ${score}`;
  if (heavyMinutes > 0) return `${base}, so about ${hoursText(heavyMinutes)} of heavy work today, first thing.`;
  return levelOf(score) === 'depleted' ? `${base}, so today is for recovery and light work only.` : `${base}, so keep to lighter work today.`;
}
