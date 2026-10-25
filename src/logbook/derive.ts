// The logbook's computed values (kinds.ts `derive`), pure so vitest can check them. They read the visitor's logs, the
// calendar's meeting events, the phone's timer / flowtime sessions and the profile. Definitions are kept simple and
// written next to each function; "today" and every day are counted in the demo person's zone.

import { addDays, clock, daysBetween, eventItem, localParts, weekOf, type Item } from '../lib/agenda';
import type { Json } from '../lib/types';
import { BY_KIND, MEALS, kindsIn, type Meal } from './kinds';

export interface Ctx {
  logs: Json[];
  events: Json[];
  sessions: Json[];
  me: Json;
  today: string;
  zone: string;
}

const num = (v: unknown) => (typeof v === 'number' && Number.isFinite(v) ? v : Number(v) || 0);
const dataOf = (x: Json) => (x.data && typeof x.data === 'object' ? (x.data as Json) : {});
/** A log's day: its date, else the day of its takenAt in `zone` (a UTC slice would move late-evening entries east of
 *  UTC, and early-morning ones west of it, to the wrong day). */
const dayOf = (x: Json, zone: string) => String(x.date ?? '').slice(0, 10) || localParts(x.takenAt, zone)?.day || '';
/** `day` is one of the `n` days ending `today`. */
const within = (day: string, today: string, n: number) => {
  const gap = daysBetween(day, today);
  return gap >= 0 && gap < n;
};

export const logsOf = (logs: Json[], type: string) => logs.filter((x) => String(x.type) === type);

// -- words ----------------------------------------------------------------------------------------------------------

/** 45 -> "45 min", 90 -> "1 h 30 m", 120 -> "2 h". */
export function duration(minutes: number): string {
  const m = Math.round(minutes);
  if (m < 60) return `${m} min`;
  const h = Math.floor(m / 60);
  return m % 60 ? `${h} h ${m % 60} m` : `${h} h`;
}

const n0 = (v: number) => Math.round(v).toLocaleString('en-US');
const n1 = (v: number) => (Math.round(v * 10) / 10).toLocaleString('en-US');
const plural = (n: number, one: string, many = `${one}s`) => `${n} ${n === 1 ? one : many}`;
const SHORT_DAYS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
const MEAL_NAMES: Record<Meal, string> = { breakfast: 'Breakfast', lunch: 'Lunch', snack: 'Snack', dinner: 'Dinner' };

// -- nutrition ------------------------------------------------------------------------------------------------------

export const CALORIE_GOAL = 2000;

export interface MealRow {
  meal: Meal;
  kcal: number;
  protein: number;
  carbs: number;
  fat: number;
  /** Minutes after midnight: the time typed on the entry, else when it was logged. */
  minute: number | null;
  photo: boolean;
}

/** The meals logged for `day`, earliest first. */
export function mealsOn(logs: Json[], day: string, zone: string): MealRow[] {
  return logsOf(logs, 'meal')
    .filter((x) => dayOf(x, zone) === day)
    .map((x) => {
      const d = dataOf(x);
      const time = /^(\d{1,2}):(\d{2})$/.exec(String(d.time ?? ''));
      const minute = time ? Number(time[1]) * 60 + Number(time[2]) : localParts(x.takenAt, zone)?.minute ?? null;
      const meal = (MEALS as readonly string[]).includes(String(d.meal)) ? (d.meal as Meal) : 'snack';
      return { meal, kcal: num(d.kcal), protein: num(d.protein), carbs: num(d.carbs), fat: num(d.fat), minute, photo: d.photo === true };
    })
    .sort((a, b) => (a.minute ?? 0) - (b.minute ?? 0));
}

export interface MealTotals {
  kcal: number;
  protein: number;
  carbs: number;
  fat: number;
  byMeal: Record<Meal, number>;
  count: number;
  photos: number;
  /** Earliest and latest meal time (minutes), null without a meal. */
  first: number | null;
  last: number | null;
  largest: MealRow | null;
}

export function mealTotals(meals: MealRow[]): MealTotals {
  const byMeal = { breakfast: 0, lunch: 0, snack: 0, dinner: 0 } as Record<Meal, number>;
  for (const m of meals) byMeal[m.meal] += m.kcal;
  const times = meals.map((m) => m.minute).filter((m): m is number => m !== null);
  return {
    kcal: meals.reduce((n, m) => n + m.kcal, 0),
    protein: meals.reduce((n, m) => n + m.protein, 0),
    carbs: meals.reduce((n, m) => n + m.carbs, 0),
    fat: meals.reduce((n, m) => n + m.fat, 0),
    byMeal,
    count: meals.length,
    photos: meals.filter((m) => m.photo).length,
    first: times.length ? Math.min(...times) : null,
    last: times.length ? Math.max(...times) : null,
    largest: meals.reduce<MealRow | null>((best, m) => (!best || m.kcal > best.kcal ? m : best), null),
  };
}

/** The eating window: first meal to last meal today, in minutes; null with fewer than two meals. */
export function eatingWindow(t: MealTotals): number | null {
  return t.count >= 2 && t.first !== null && t.last !== null ? t.last - t.first : null;
}

/** The overnight fast: yesterday's last meal to today's first, in minutes; null without both. */
export function fastingMinutes(logs: Json[], today: string, zone: string): number | null {
  const before = mealTotals(mealsOn(logs, addDays(today, -1), zone)).last;
  const first = mealTotals(mealsOn(logs, today, zone)).first;
  return before === null || first === null ? null : 24 * 60 - before + first;
}

/** The daily calorie goal: the profile's healthGoals.calorieGoal, else 2000. */
export function calorieGoal(me: Json): number {
  const goals = (me.healthGoals as Json | undefined) ?? {};
  return num(goals.calorieGoal) || CALORIE_GOAL;
}

/** Grams of protein per 100 kcal eaten (protein density); null before any calories. */
export const proteinPer100 = (t: MealTotals) => (t.kcal > 0 ? (t.protein / t.kcal) * 100 : null);

// -- meetings -------------------------------------------------------------------------------------------------------

/** The working day the meeting maths looks at: 09:00-18:00. */
export const WORK_FROM = 9 * 60;
export const WORK_TO = 18 * 60;
/** A free stretch this long is a meeting-free block (enough for deep work). */
export const FREE_BLOCK = 90;
/** A free stretch this long counts towards the maker-schedule score (Paul Graham's "maker's schedule"). */
export const MAKER_BLOCK = 120;
/** A meeting starting within this many minutes of the last one's end is back-to-back. */
export const BACK_TO_BACK = 5;

/** The calendar's meetings on `day`: events whose kind is "meeting" (what the calendar colours as a meeting). */
export function meetingsOn(events: Json[], day: string, zone: string): Item[] {
  return events
    .filter((e) => String(e.kind ?? '') === 'meeting' && !e.allDay)
    .map((e) => eventItem(e, zone))
    .filter((i): i is Item => i !== null && i.day === day && i.start !== null)
    .sort((a, b) => a.start! - b.start!);
}

/** The meetings as [start, end] minutes with overlaps merged, clipped to [from, to]. */
function busy(items: Item[], from = 0, to = 24 * 60): [number, number][] {
  const out: [number, number][] = [];
  for (const i of items) {
    const s = Math.max(from, i.start!);
    const e = Math.min(to, i.start! + i.minutes);
    if (e <= s) continue;
    const last = out.at(-1);
    if (last && s <= last[1]) last[1] = Math.max(last[1], e);
    else out.push([s, e]);
  }
  return out;
}

/** Minutes in meetings (overlaps counted once). */
export const meetingMinutes = (items: Item[], from?: number, to?: number) => busy(items, from, to).reduce((n, [s, e]) => n + e - s, 0);

/** Meetings that start within 5 minutes of the end of an earlier one (or overlap it). */
export function backToBack(items: Item[]): number {
  let count = 0;
  let end: number | null = null;
  for (const i of items) {
    if (end !== null && i.start! - end <= BACK_TO_BACK) count += 1;
    end = Math.max(end ?? 0, i.start! + i.minutes);
  }
  return count;
}

/** The free stretches between meetings in the working day, in minutes. */
export function freeGaps(items: Item[]): number[] {
  const gaps: number[] = [];
  let at = WORK_FROM;
  for (const [s, e] of busy(items, WORK_FROM, WORK_TO)) {
    if (s > at) gaps.push(s - at);
    at = e;
  }
  if (WORK_TO > at) gaps.push(WORK_TO - at);
  return gaps;
}

/** Meeting minutes on each day of this week (Monday first). */
export function weekMeetings(events: Json[], today: string, zone: string): { day: string; minutes: number }[] {
  return weekOf(today).map((day) => ({ day, minutes: meetingMinutes(meetingsOn(events, day, zone)) }));
}

// -- focus sessions -------------------------------------------------------------------------------------------------

/** A focus session counts as deep work when it ran at least this long. */
export const DEEP_MIN = 25;

/** Only the kind says focus: Modes' Timer and Flowtime write kind "focus", while Recovery's breaks, naps and walks
 *  also run with mode "timer" (sessionRow's default) and are not work. */
export const isFocus = (s: Json) => String(s.kind ?? '') === 'focus';

/** Minutes a session took: what was timed, else start to end, else what was planned. */
export function sessionMinutes(s: Json): number {
  if (typeof s.actualMinutes === 'number') return Math.max(0, s.actualMinutes);
  const a = new Date(String(s.startedAt ?? '')).getTime();
  const b = new Date(String(s.endedAt ?? '')).getTime();
  if (!Number.isNaN(a) && !Number.isNaN(b) && b > a) return Math.round((b - a) / 60_000);
  return num(s.plannedMinutes);
}

interface Run {
  day: string;
  start: number;
  minutes: number;
  mode: string;
  planned: number;
}

/** The focus sessions with their day and start in `zone`. */
export function focusRuns(sessions: Json[], zone: string): Run[] {
  return sessions.filter(isFocus).flatMap((s) => {
    const at = localParts(s.startedAt, zone);
    return at ? [{ day: at.day, start: at.minute, minutes: sessionMinutes(s), mode: String(s.mode ?? ''), planned: num(s.plannedMinutes) }] : [];
  });
}

/** Focus minutes per day. */
export function focusByDay(runs: Run[], deepOnly = false): Map<string, number> {
  const out = new Map<string, number>();
  for (const r of runs) if (!deepOnly || r.minutes >= DEEP_MIN) out.set(r.day, (out.get(r.day) ?? 0) + r.minutes);
  return out;
}

/** Days in a row with focus, ending today; a day without focus yet doesn't break it until it is over. */
export function currentStreak(days: Set<string>, today: string): number {
  let day = days.has(today) ? today : addDays(today, -1);
  let n = 0;
  while (days.has(day)) {
    n += 1;
    day = addDays(day, -1);
  }
  return n;
}

/** The longest run of days in a row in the set. */
export function longestStreak(days: Set<string>): number {
  let best = 0;
  for (const d of days) {
    if (days.has(addDays(d, -1))) continue; // only count from the start of a run
    let n = 0;
    while (days.has(addDays(d, n))) n += 1;
    best = Math.max(best, n);
  }
  return best;
}

// -- work-life ------------------------------------------------------------------------------------------------------

/** A day with at least this much focus and meeting time counts towards the overwork streak. */
export const OVERWORK_MIN = 9 * 60;
/** Work outside these hours crosses the work-life boundary. */
export const BOUNDARY_FROM = 7 * 60;
export const BOUNDARY_TO = 19 * 60;

/** Focus plus meeting minutes on `day`. */
export function workMinutes(c: Ctx, day: string, runs = focusRuns(c.sessions, c.zone)): number {
  return (focusByDay(runs).get(day) ?? 0) + meetingMinutes(meetingsOn(c.events, day, c.zone));
}

/** Days in a row, ending today (or yesterday), with 9 hours or more of focus and meetings. */
export function overworkStreak(c: Ctx): number {
  const runs = focusRuns(c.sessions, c.zone);
  const long = new Set(Array.from({ length: 60 }, (_, i) => addDays(c.today, -i)).filter((d) => workMinutes(c, d, runs) >= OVERWORK_MIN));
  return currentStreak(long, c.today);
}

/** The share of the last 7 days with no focus session or meeting before 07:00 or after 19:00, in percent; null when
 *  the 7 days hold no focus or meeting at all (an empty week would otherwise score a perfect 100). */
export function boundaryScore(c: Ctx): number | null {
  const runs = focusRuns(c.sessions, c.zone);
  let kept = 0;
  let worked = false;
  for (let i = 0; i < 7; i++) {
    const day = addDays(c.today, -i);
    const spans = [
      ...runs.filter((r) => r.day === day).map((r) => [r.start, r.start + r.minutes]),
      ...meetingsOn(c.events, day, c.zone).map((m) => [m.start!, m.start! + m.minutes]),
    ];
    if (spans.length) worked = true;
    if (spans.every(([s, e]) => s >= BOUNDARY_FROM && e <= BOUNDARY_TO)) kept += 1;
  }
  return worked ? Math.round((kept / 7) * 100) : null;
}

/** Days of the last 7 with the shutdown checklist logged. */
export const shutdownDays = (c: Ctx) => new Set(logsOf(c.logs, 'shutdown').map((x) => dayOf(x, c.zone)).filter((d) => within(d, c.today, 7))).size;

// -- strength and body ----------------------------------------------------------------------------------------------

export interface Lift {
  exercise: string;
  group: string;
  sets: number;
  reps: number;
  kg: number;
  day: string;
}

/** The lift entries of the last 7 days. */
export function liftsWeek(logs: Json[], today: string, zone: string): Lift[] {
  return logsOf(logs, 'lift')
    .filter((x) => within(dayOf(x, zone), today, 7))
    .map((x) => {
      const d = dataOf(x);
      return { exercise: String(d.exercise ?? 'Exercise'), group: String(d.group ?? ''), sets: num(d.sets), reps: num(d.reps), kg: num(d.kg), day: dayOf(x, zone) };
    });
}

/** Volume lifted: sets × reps × kg, summed. */
export const volume = (lifts: Lift[]) => lifts.reduce((n, l) => n + l.sets * l.reps * l.kg, 0);

export const muscleGroups = (lifts: Lift[]) => [...new Set(lifts.map((l) => l.group).filter(Boolean))];

/** PB-04: the WHO's muscle-strengthening on 2 or more days a week (Home's Strength pill and the logbook's row). */
export const STRENGTH_DAYS = 2;

/** Days of the last 7 with a lift entry or one of Recovery's strength logs (Home's Strength pill counts the same). */
export const strengthDays = (logs: Json[], today: string, zone: string) =>
  new Set(logs.filter((x) => x.type === 'lift' || x.type === 'strength').map((x) => dayOf(x, zone)).filter((d) => within(d, today, 7))).size;

/** The newest log of a type (by when it was taken). */
export function latest(logs: Json[], type: string): Json | null {
  const rows = logsOf(logs, type).sort((a, b) => String(a.takenAt ?? a.date ?? '').localeCompare(String(b.takenAt ?? b.date ?? '')));
  return rows.at(-1) ?? null;
}

/** BMI: kg / m², one decimal; null without both. */
export function bmi(kg: number, cm: number): number | null {
  if (!(kg > 0) || !(cm > 0)) return null;
  return Math.round((kg / (cm / 100) ** 2) * 10) / 10;
}

/** The weight BMI uses: the newest weight entry, else the profile's. */
export const weightNow = (c: Ctx) => {
  const row = latest(c.logs, 'weight');
  return row ? num(dataOf(row).value) : num(c.me.weightKg);
};

export function bmiWord(value: number): string {
  if (value < 18.5) return 'underweight';
  if (value < 25) return 'healthy';
  if (value < 30) return 'overweight';
  return 'obese';
}

// -- every derived kind as the row shows it -------------------------------------------------------------------------

const NONE = 'None yet';

function nutrition(c: Ctx) {
  return mealTotals(mealsOn(c.logs, c.today, c.zone));
}

function focusStats(c: Ctx) {
  const runs = focusRuns(c.sessions, c.zone);
  const recent = runs.filter((r) => within(r.day, c.today, 31)); // 30 days + today
  const byDay = focusByDay(recent);
  const values = [...byDay.values()];
  const deep = focusByDay(runs, true);
  const deepWeek = [...deep.entries()].filter(([d]) => within(d, c.today, 7)).reduce((n, [, m]) => n + m, 0);
  return { runs, recent, byDay, values, deep, deepWeek };
}

const mealKcal = (meal: Meal) => (c: Ctx) => {
  const kcal = nutrition(c).byMeal[meal];
  return kcal ? `${n0(kcal)} kcal` : NONE;
};
const grams = (key: 'protein' | 'carbs' | 'fat') => (c: Ctx) => {
  const t = nutrition(c);
  return t.count ? `${n0(t[key])} g` : NONE;
};

/** kind id -> its value as text. The keys match the `derive` kinds in kinds.ts (checked in kinds.test.ts). */
export const DERIVED: Record<string, (c: Ctx) => string> = {
  // Nutrition (today)
  kcalToday: (c) => {
    const t = nutrition(c);
    return t.count ? `${n0(t.kcal)} kcal` : NONE;
  },
  kcalBreakfast: mealKcal('breakfast'),
  kcalLunch: mealKcal('lunch'),
  kcalSnack: mealKcal('snack'),
  kcalDinner: mealKcal('dinner'),
  protein: grams('protein'),
  carbs: grams('carbs'),
  fat: grams('fat'),
  calorieGoal: (c) => {
    const goal = calorieGoal(c.me);
    return `${Math.round((nutrition(c).kcal / goal) * 100)}% of ${n0(goal)}`;
  },
  mealTiming: (c) => {
    const t = nutrition(c);
    if (t.first === null || t.last === null) return NONE;
    return t.count > 1 ? `${clock(t.first)} – ${clock(t.last)}` : clock(t.first);
  },
  eatingWindow: (c) => {
    const w = eatingWindow(nutrition(c));
    return w === null ? 'Needs two meals' : duration(w);
  },
  fasting: (c) => {
    const f = fastingMinutes(c.logs, c.today, c.zone);
    return f === null ? 'Needs two days of meals' : duration(f);
  },
  proteinPer100: (c) => {
    const p = proteinPer100(nutrition(c));
    return p === null ? NONE : `${n1(p)} g`;
  },
  mealPhotos: (c) => String(nutrition(c).photos),
  largestMeal: (c) => {
    const big = nutrition(c).largest;
    return big ? `${MEAL_NAMES[big.meal]} · ${n0(big.kcal)} kcal` : NONE;
  },

  // Meetings (today, 09:00-18:00 for the shape of the day)
  meetingsToday: (c) => String(meetingsOn(c.events, c.today, c.zone).length),
  meetingTime: (c) => duration(meetingMinutes(meetingsOn(c.events, c.today, c.zone))),
  meetingFocusRatio: (c) => {
    const meet = meetingMinutes(meetingsOn(c.events, c.today, c.zone));
    const focus = focusByDay(focusRuns(c.sessions, c.zone)).get(c.today) ?? 0;
    if (!meet && !focus) return NONE;
    if (!focus) return 'No focus yet';
    return `${n1(meet / focus)} : 1`;
  },
  meetingLoad: (c) => `${Math.round((meetingMinutes(meetingsOn(c.events, c.today, c.zone), WORK_FROM, WORK_TO) / (WORK_TO - WORK_FROM)) * 100)}% of 9–6`,
  backToBack: (c) => String(backToBack(meetingsOn(c.events, c.today, c.zone))),
  freeBlocks: (c) => String(freeGaps(meetingsOn(c.events, c.today, c.zone)).filter((g) => g >= FREE_BLOCK).length),
  fragmentation: (c) => plural(freeGaps(meetingsOn(c.events, c.today, c.zone)).filter((g) => g < FREE_BLOCK).length, 'short gap'),
  makerScore: (c) => {
    const long = freeGaps(meetingsOn(c.events, c.today, c.zone)).filter((g) => g >= MAKER_BLOCK).reduce((n, g) => n + g, 0);
    return `${Math.round((long / (WORK_TO - WORK_FROM)) * 100)}/100`;
  },
  heaviestDay: (c) => {
    const week = weekMeetings(c.events, c.today, c.zone);
    const top = week.reduce((a, b) => (b.minutes > a.minutes ? b : a));
    return top.minutes ? `${SHORT_DAYS[week.indexOf(top)]} · ${duration(top.minutes)}` : NONE;
  },

  // Focus (the phone's timer and flowtime sessions)
  focusToday: (c) => duration(focusStats(c).byDay.get(c.today) ?? 0),
  focus30: (c) => duration(focusStats(c).values.reduce((a, b) => a + b, 0)),
  deepWorkHours: (c) => `${n1((focusStats(c).deep.get(c.today) ?? 0) / 60)} h`,
  sessions7: (c) => String(focusStats(c).runs.filter((r) => within(r.day, c.today, 7)).length),
  // read from the 31 days focusStats keeps, so it tops out at 31; a longer run still shows in longestFocusStreak
  focusStreak: (c) => plural(currentStreak(new Set(focusStats(c).byDay.keys()), c.today), 'day'),
  bestDay: (c) => {
    const v = focusStats(c).values;
    return v.length ? duration(Math.max(...v)) : NONE;
  },
  worstDay: (c) => {
    const v = focusStats(c).values;
    return v.length ? duration(Math.min(...v)) : NONE;
  },
  deepWorkWeek: (c) => duration(focusStats(c).deepWeek),
  timedLength: (c) => {
    const timed = focusStats(c).recent.filter((r) => r.mode === 'timer' && r.planned > 0);
    return timed.length ? duration(timed.reduce((n, r) => n + r.planned, 0) / timed.length) : NONE;
  },
  flowtimeUsed: (c) => plural(focusStats(c).runs.filter((r) => r.mode === 'flowtime' && within(r.day, c.today, 7)).length, 'time') + ' in 7 days',

  // Work-life balance (the last 7 days)
  deepWorkAvg: (c) => `${duration(focusStats(c).deepWeek / 7)} a day`,
  shutdownDiscipline: (c) => `${shutdownDays(c)} of 7 days`,
  boundaryScore: (c) => {
    const score = boundaryScore(c);
    return score === null ? NONE : `${score}/100`;
  },
  overworkStreak: (c) => plural(overworkStreak(c), 'day'),
  longestFocusStreak: (c) => plural(longestStreak(new Set(focusByDay(focusRuns(c.sessions, c.zone)).keys())), 'day'),

  // Body
  bodyRecord: (c) => {
    const types = kindsIn('body').filter((k) => k.input).map((k) => k.id);
    const rows = c.logs.filter((x) => types.includes(String(x.type)));
    const newest = rows.map((x) => dayOf(x, c.zone)).sort().at(-1);
    if (!newest) return NONE;
    const gap = daysBetween(newest, c.today);
    const when = gap === 0 ? 'Today' : gap === 1 ? 'Yesterday' : `${gap} days ago`;
    return `${when} · ${plural(rows.filter((x) => dayOf(x, c.zone) === newest).length, 'reading')}`;
  },
  bmi: (c) => {
    const value = bmi(weightNow(c), num(c.me.heightCm));
    return value === null ? 'Needs height and weight' : `${n1(value)} · ${bmiWord(value)}`;
  },

  // Strength training (the last 7 days)
  volume: (c) => `${n0(volume(liftsWeek(c.logs, c.today, c.zone)))} kg`,
  heaviest: (c) => {
    const top = liftsWeek(c.logs, c.today, c.zone).reduce<Lift | null>((a, b) => (!a || b.kg > a.kg ? b : a), null);
    return top ? `${n1(top.kg)} kg · ${top.exercise}` : NONE;
  },
  groupCount: (c) => String(muscleGroups(liftsWeek(c.logs, c.today, c.zone)).length),
  groupList: (c) => muscleGroups(liftsWeek(c.logs, c.today, c.zone)).join(', ') || NONE,
  liftEntries: (c) => String(liftsWeek(c.logs, c.today, c.zone).length),
  strengthDays: (c) => `${strengthDays(c.logs, c.today, c.zone)} of ${STRENGTH_DAYS} days`,
};

// -- entries --------------------------------------------------------------------------------------------------------

const clip = (text: string, n = 40) => (text.length > n ? `${text.slice(0, n - 1).trimEnd()}…` : text);

/** An entry's value as its form shows it ("4/5", "61.5 kg", "1 h 30 m", "Lyrics", "Lunch 520 kcal"). */
export function entryValue(row: Json): string {
  const kind = BY_KIND[String(row.type)];
  const d = dataOf(row);
  if (!kind?.input) return '';
  const v = d.value;
  switch (kind.input.type) {
    case 'scale5':
      return `${num(v)}/5`;
    case 'scale10':
      return `${num(v)}/10`;
    case 'number':
      return `${n1(num(v))}${kind.input.unit ? ` ${kind.input.unit}` : ''}`;
    case 'minutes':
      return duration(num(v));
    case 'choice':
      return String(v ?? '');
    case 'text':
      return `${typeof v === 'number' ? `${v}/5 · ` : ''}${clip(String(d.text ?? ''))}`;
    case 'yes':
      return kind.input.verb.toLowerCase();
    case 'meal': {
      const meal = (MEALS as readonly string[]).includes(String(d.meal)) ? MEAL_NAMES[d.meal as Meal] : 'Meal';
      return `${meal} ${n0(num(d.kcal))} kcal`;
    }
    case 'lift':
      return `${String(d.exercise ?? 'Exercise')} ${num(d.sets)}×${num(d.reps)} at ${n1(num(d.kg))} kg`;
    case 'quick':
      return `${n0(num(d.reactionMs))} ms · span ${num(d.span)}`;
  }
}

/** One logbook entry as a history row reads it ("Energy 4/5", "Lunch 520 kcal", "Squat 3×8 at 60 kg"). */
export function entryText(row: Json): string {
  const kind = BY_KIND[String(row.type)];
  if (!kind?.input) return String(row.type ?? 'Entry');
  const value = entryValue(row);
  switch (kind.input.type) {
    case 'meal':
    case 'lift':
      return value;
    case 'choice':
    case 'text':
    case 'yes':
      return `${kind.label}: ${value}`;
    case 'quick':
      return `Quick check ${value}`;
    default:
      return `${kind.label} ${value}`;
  }
}

/** When an entry happened, for its row's time: the time typed on it (a meal's "08:30") on its own day, as a local
 *  "YYYY-MM-DDTHH:MM", else when it was logged (takenAt). */
export function entryAt(row: Json, zone: string): unknown {
  const time = /^(\d{1,2}):(\d{2})$/.exec(String(dataOf(row).time ?? ''));
  const day = dayOf(row, zone);
  if (!time || !day) return row.takenAt;
  return `${day}T${time[1].padStart(2, '0')}:${time[2]}`;
}

/** The logbook's own entries (not Recovery's water / strength), in `category` when given, from the last `days`
 *  days, newest first. */
export function entries(logs: Json[], today: string, zone: string, days = 7, category?: string): Json[] {
  return logs
    .filter((x) => {
      const kind = BY_KIND[String(x.type)];
      return kind?.input && (!category || kind.category === category) && within(dayOf(x, zone), today, days);
    })
    .sort((a, b) => new Date(String(b.takenAt ?? '')).getTime() - new Date(String(a.takenAt ?? '')).getTime() || String(b.takenAt ?? '').localeCompare(String(a.takenAt ?? '')));
}

/** How many entries a category has today (the Logging list's value). */
export const todayCount = (logs: Json[], today: string, zone: string, category: string) => entries(logs, today, zone, 1, category).length;
