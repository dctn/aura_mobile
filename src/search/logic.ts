// The pure parts of Search, Metric, Logging, Settings and Inbox (ported from ui/pages/search.py, metric.py,
// logbook.py, profile.py and core/profile.py), kept apart from React so vitest can check them.

import type { Feature } from '../lib/catalog';
import * as m from '../lib/metrics';
import { addDays, daysBetween } from '../lib/agenda';
import type { Json } from '../lib/types';

// -- Search ---------------------------------------------------------------------------------------------------------

/** The second list: name, icon, accent, extra words the field matches. */
export const PLACES = [
  { name: 'Logging', icon: 'pencil', accent: 'fitness', words: 'water hydration mood connection strength sleep check log' },
  { name: 'Modes', icon: 'bolt', accent: 'workout', words: 'timer flowtime walk meeting focus start' },
  { name: 'Sessions', icon: 'clock', accent: 'sleep', words: 'history running stress change' },
  { name: 'Settings', icon: 'settings', accent: 'label3', words: 'profile goals alerts appearance dark theme inbox reset' },
] as const;

export type PlaceName = (typeof PLACES)[number]['name'];

/** Extra words per category, so "hrv" or "steps" still finds a row. */
export const WORDS: Record<string, string> = {
  sleep: 'night bed rest',
  heart: 'hr bpm resting pulse',
  stress: 'calm tension',
  recovery: 'readiness hrv',
  movement: 'active steps walk exercise',
  breathing: 'breath respiration',
  burnout: 'tired fatigue',
  body: 'temperature skin temp',
};

/** "7.5 h", "62 bpm", "41/100"; empty without a value. */
export function valueText(card: m.Card, raw: number | null): string {
  if (raw === null) return '';
  const shown = m.cardShow(card, raw);
  return card.unit.startsWith('/') ? `${shown}${card.unit}` : `${shown} ${card.unit}`.trim();
}

/** The newest value of a column over every day on hand (up to 60), with its day. */
export function newestOf(days: m.MetricDay[], field: string): { value: number; day: string } | null {
  let best: { value: number; day: string } | null = null;
  for (const d of days) {
    const v = (d.metrics as Record<string, unknown> | undefined)?.[field];
    const day = String(d.date ?? '').slice(0, 10);
    if (typeof v === 'number' && Number.isFinite(v) && (!best || day > best.day)) best = { value: v, day };
  }
  return best;
}

/** Numeric metric columns seen in the days, plus the known ones. */
export function metricFields(days: m.MetricDay[]): string[] {
  const out = new Set(Object.keys(m.KNOWN));
  for (const d of days) {
    for (const [k, v] of Object.entries((d.metrics as Record<string, unknown> | undefined) ?? {})) {
      if (typeof v === 'number') out.add(k);
    }
  }
  return [...out];
}

export interface SearchHits {
  categories: string[];
  places: PlaceName[];
  /** Other metric columns whose label (or name) matches; only while typing. */
  fields: string[];
}

export const FIELD_HITS = 12;

/** What the search box leaves on: categories and places by word, then any other metric column by label. */
export function searchHits(query: string, fields: string[]): SearchHits {
  const needle = query.trim().toLowerCase();
  const categories = m.CATEGORIES.filter((key) => {
    const card = m.CARDS[key];
    return !needle || `${card.label} ${WORDS[key] ?? ''} health`.toLowerCase().includes(needle);
  });
  const places = PLACES.filter((p) => !needle || `${p.name} ${p.words}`.toLowerCase().includes(needle)).map((p) => p.name);
  if (!needle) return { categories: [...categories], places, fields: [] };
  const all = new Set<string>(m.CATEGORIES.map((key) => m.CARDS[key].field));
  const hits = fields
    .filter((f) => !all.has(f) && (m.labelFor(f).toLowerCase().includes(needle) || f.toLowerCase().includes(needle)))
    .sort((a, b) => m.labelFor(a).localeCompare(m.labelFor(b)))
    .slice(0, FIELD_HITS);
  return { categories: [...categories], places, fields: hits };
}

// -- Metric ---------------------------------------------------------------------------------------------------------

export const RANGES = [
  { value: '14', label: '2 Weeks', days: 14 },
  { value: '30', label: '1 Month', days: 30 },
  { value: '60', label: '2 Months', days: 60 },
] as const;

/** A change smaller than this reads as "Steady". */
export const STEADY_PCT = 2;

/** The last 7 days' average against the 7 before, in percent; null without both weeks. */
export function trend(values: (number | null)[]): number | null {
  const now = m.average(values.slice(-7));
  const before = m.average(values.slice(-14, -7));
  if (now === null || before === null || before === 0) return null;
  return ((now - before) / Math.abs(before)) * 100;
}

export function trendWords(pct: number): { direction: -1 | 0 | 1; title: string; text: string } {
  const direction = Math.abs(pct) < STEADY_PCT ? 0 : pct > 0 ? 1 : -1;
  const title = direction === 0 ? 'Steady' : direction > 0 ? 'Trending up' : 'Trending down';
  const r = Math.round(pct);
  return { direction, title, text: `${r > 0 ? '+' : r < 0 ? '-' : '+'}${Math.abs(r)}%` };
}

export const DEFAULT_PINS = ['totalSleepMin', 'recoveryScore', 'restingHrBpm', 'stressDurationMin'];
export const MAX_PINS = 6;

export const pinsOf = (local: string[] | null) => local ?? DEFAULT_PINS;

/** The pins after switching `field` on or off; `full` when it would be a seventh. */
export function togglePin(pins: string[], field: string, on: boolean): { pins: string[]; full: boolean } {
  const rest = pins.filter((f) => f !== field);
  if (!on) return { pins: rest, full: false };
  if (pins.includes(field)) return { pins, full: false };
  if (rest.length >= MAX_PINS) return { pins, full: true };
  return { pins: [...rest, field], full: false };
}

// -- Logging --------------------------------------------------------------------------------------------------------

export const WATER_GLASS_ML = 250;
export const WATER_GOAL_ML = 2000;

const num = (v: unknown) => (typeof v === 'number' && Number.isFinite(v) ? v : Number(v) || 0);
const dataOf = (x: Json) => (x.data && typeof x.data === 'object' ? (x.data as Json) : {});
const isWater = (x: Json) => String(x.type ?? 'water') === 'water';

/** Today's water in ml. */
export function waterOn(logs: Json[], day: string): number {
  return logs.filter((x) => isWater(x) && String(x.date ?? '').slice(0, 10) === day).reduce((n, x) => n + num(dataOf(x).ml), 0);
}

/** The last 7 days' water, oldest first, ending `today`. */
export function waterWeek(logs: Json[], today: string): number[] {
  return Array.from({ length: 7 }, (_, i) => waterOn(logs, addDays(today, i - 6)));
}

/** Strength minutes over the last 7 days. */
export function strengthWeek(logs: Json[], today: string): number {
  return logs
    .filter((x) => String(x.type) === 'strength')
    .filter((x) => {
      const gap = daysBetween(String(x.date ?? '').slice(0, 10), today);
      return gap >= 0 && gap < 7;
    })
    .reduce((n, x) => n + num(dataOf(x).minutes), 0);
}

export function waterGoal(me: Json): number {
  const goals = (me.healthGoals as Json | undefined) ?? {};
  return num(goals.waterGoalMl) || WATER_GOAL_ML;
}

export const FACE_WORDS: Record<'mood' | 'connection', string[]> = {
  mood: ['Very low', 'Low', 'Okay', 'Good', 'Great'],
  connection: ['Alone', 'A little', 'Some', 'Good', 'Very connected'],
};

/** The newest check-in of a kind that has a value. */
export function lastCheckin(checkins: Json[], kind: string): Json | null {
  const rows = checkins.filter((c) => c.kind === kind && (kind === 'insomnia' || (c[kind] !== null && c[kind] !== undefined)));
  rows.sort((a, b) => String(a.takenAt ?? '').localeCompare(String(b.takenAt ?? '')));
  return rows.at(-1) ?? null;
}

export function insomniaScore(c: Json): number | null {
  if (typeof c.insomniaScore === 'number') return c.insomniaScore;
  if (Array.isArray(c.insomnia)) return (c.insomnia as unknown[]).reduce<number>((n, v) => n + num(v), 0);
  return null;
}

export function checkinText(c: Json): string {
  const kind = String(c.kind);
  if (kind === 'insomnia') {
    const score = insomniaScore(c);
    return score !== null ? `Sleep check ${score}/28` : 'Sleep check';
  }
  const name = kind.charAt(0).toUpperCase() + kind.slice(1);
  const value = c[kind];
  return value !== null && value !== undefined ? `${name} ${String(value)}/5` : name;
}

export function logText(x: Json): string {
  const kind = String(x.type ?? 'water');
  if (kind === 'water') return `Water ${num(dataOf(x).ml)} ml`;
  if (kind === 'strength') return `Strength ${num(dataOf(x).minutes)} min`;
  return kind.charAt(0).toUpperCase() + kind.slice(1);
}

/** The tile on a history row, by the start of its text. */
export function historyTile(text: string): { icon: string; accent: string } {
  const tiles: [string, string, string][] = [
    ['Water', 'drop', 'blue'],
    ['Strength', 'dumbbell', 'fitness'],
    ['Mood', 'bubble', 'sleep'],
    ['Connection', 'people', 'teal'],
    ['Sleep check', 'sleep', 'sleep'],
  ];
  const hit = tiles.find(([start]) => text.startsWith(start));
  return hit ? { icon: hit[1], accent: hit[2] } : { icon: 'note', accent: 'label3' };
}

/** Check-ins and logs, newest first: [takenAt, text]. */
export function history(checkins: Json[], logs: Json[], count = 6): { at: string; text: string; id: string }[] {
  return [
    ...checkins.map((c) => ({ at: String(c.takenAt ?? ''), text: checkinText(c), id: String(c.id) })),
    ...logs.map((x) => ({ at: String(x.takenAt ?? ''), text: logText(x), id: String(x.id) })),
  ]
    .sort((a, b) => new Date(b.at).getTime() - new Date(a.at).getTime() || b.at.localeCompare(a.at))
    .slice(0, count);
}

/** NI-02: three or more mood check-ins in the last 7 days, averaging 2 or less. */
export function moodLowThisWeek(checkins: Json[], today: string): boolean {
  const since = addDays(today, -7);
  const moods = checkins
    .filter((c) => c.kind === 'mood' && typeof c.mood === 'number' && String(c.date ?? c.takenAt ?? '').slice(0, 10) >= since)
    .map((c) => c.mood as number);
  return moods.length >= 3 && moods.reduce((a, b) => a + b, 0) / moods.length <= 2;
}

export const SEVERITY = ['None', 'Mild', 'Moderate', 'Severe', 'Very severe'];
const AMOUNT = ['Not at all', 'A little', 'Somewhat', 'Much', 'Very much'];

/** The Insomnia Severity Index (Bastien 2001): 7 questions, 0-4 each, about the last two weeks. */
export const QUESTIONS: [string, string[]][] = [
  ['Trouble falling asleep', SEVERITY],
  ['Trouble staying asleep', SEVERITY],
  ['Waking up too early', SEVERITY],
  ['How satisfied are you with your sleep now?', ['Very satisfied', 'Satisfied', 'Neutral', 'Dissatisfied', 'Very dissatisfied']],
  ['How much do others notice your sleep problem?', AMOUNT],
  ['How worried are you about your sleep?', AMOUNT],
  ['How much does it get in the way of your day?', AMOUNT],
];

export function insomniaMeaning(score: number): string {
  if (score <= 7) return 'No sign of insomnia.';
  if (score <= 14) return 'Some trouble sleeping. Keep the wind-down going and check again in two weeks.';
  if (score <= 21) return 'Moderate insomnia. Talk to a doctor; CBT for insomnia (CBT-I) works better than sleeping pills over time.';
  return 'Severe insomnia. Please see a doctor soon; CBT for insomnia (CBT-I) helps most people.';
}

// -- Settings -------------------------------------------------------------------------------------------------------

/** field on the profile -> what the "missing" line calls it, in order (core/profile.py NEEDED). */
export const NEEDED: [string, string][] = [
  ['displayName', 'name'],
  ['birthDate', 'birth date'],
  ['sex', 'sex'],
  ['heightCm', 'height'],
  ['weightKg', 'weight'],
];

/** The profile fields still unset. `sex: notSet` counts as set (a choice). */
export function missing(me: Json): string[] {
  return NEEDED.filter(([field]) => me[field] === null || me[field] === undefined || me[field] === '').map(([, name]) => name);
}

export const SEX = ['notSet', 'female', 'male', 'other'] as const;
export const sexText = (v: unknown) => (!v || v === 'notSet' ? 'Not set' : String(v).charAt(0).toUpperCase() + String(v).slice(1));

/** 480 -> "8 h", 465 -> "7 h 45 m". */
export function hours(minutes: number): string {
  const h = Math.floor(minutes / 60);
  const r = Math.round(minutes % 60);
  return r ? `${h} h ${r} m` : `${h} h`;
}

/** Whole years from a 'YYYY-MM-DD' birth date to `today`. */
export function age(birth: string, today: string): number | null {
  if (!/^\d{4}-\d{2}-\d{2}/.test(birth)) return null;
  const [by, bm, bd] = birth.slice(0, 10).split('-').map(Number);
  const [ty, tm, td] = today.split('-').map(Number);
  return ty - by - (tm < bm || (tm === bm && td < bd) ? 1 : 0);
}

/** The feature's group -> the Alerts list it sits in (one-feature groups join a bigger one). */
export const ALERT_AREA: Record<string, string> = {
  Sleep: 'Sleep',
  'Recovery & stress': 'Recovery & stress',
  Burnout: 'Recovery & stress',
  'Health check': 'Recovery & stress',
  Movement: 'Movement',
  Mood: 'Mood & habits',
  Connection: 'Mood & habits',
  Hydration: 'Mood & habits',
  Band: 'Band',
};

/** The features with an alert, grouped by area in first-seen order. */
export function alertAreas(features: Feature[]): { area: string; features: Feature[] }[] {
  const out: { area: string; features: Feature[] }[] = [];
  for (const f of features.filter((x) => x.hasAlert)) {
    const area = ALERT_AREA[f.group] ?? f.group;
    let g = out.find((x) => x.area === area);
    if (!g) out.push((g = { area, features: [] }));
    g.features.push(f);
  }
  return out;
}

/** The alert-off list after switching one feature. */
export function switchAlert(off: string[], id: string, on: boolean): string[] {
  const rest = off.filter((x) => x !== id);
  return on ? rest : [...rest, id];
}

// -- Inbox ----------------------------------------------------------------------------------------------------------

const stamp = (n: Json) => String(n.arrivedAt ?? n.createdAt ?? n.sendAt ?? '');

/** Notifications still in the inbox, newest first. */
export function inboxRows(inbox: Json[]): Json[] {
  return inbox.filter((n) => n.status !== 'dismissed').sort((a, b) => new Date(stamp(b)).getTime() - new Date(stamp(a)).getTime() || stamp(b).localeCompare(stamp(a)));
}

export const inboxTime = stamp;
export const unread = (inbox: Json[]) => inboxRows(inbox).filter((n) => !n.readAt).length;
