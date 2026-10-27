// What the visitor uses most, so Recovery can put it first ("Often used", the group order, the four modes shown).
// A use is a feature's verb or log, or a mode started: noted in LocalState.used, and also read off the rows that
// already exist (sessions, logs, check-ins, events a feature planned), so history counts from day one. Pure apart from
// noteUse/usedOf/scoresFor.

import type { Aura } from '../app/store';
import { FEATURES } from '../lib/catalog';
import { DEFAULT_MODES, MODE_LIST, findMode, type Mode, type Shelf } from '../lib/modes';
import type { Data, Json } from '../lib/types';
import { nowIn } from '../lib/when';

export type UseKey = `feature:${string}` | `mode:${string}`;

/** Uses older than this don't count. */
export const WINDOW_DAYS = 30;
/** A use loses half its weight every this many days. */
export const HALF_LIFE_DAYS = 7;
/** A noted use and a row of the same key this close are one use (noteUse and the row it wrote); so are the events
 *  one plan wrote. */
export const SAME_USE_MS = 90_000;
/** Of Often used's slots that history doesn't fill, the open alerts' features take at most this many; the default
 *  modes take the rest, so a fresh visitor sees both. */
export const ALERT_SLOTS = 2;

const DAY_MS = 86_400_000;
/** A little slack for the store's clock, which ticks every 30 s behind the moment a use is noted. */
const AHEAD_MS = 5 * 60_000;

/** The features Recovery lists: all but Account and Band (BS-01, BS-02), which stay on Home. */
export const RECOVERY_FEATURES: string[] = FEATURES.map((f) => f.id).filter((id) => id !== 'BS-01' && id !== 'BS-02');

const FEATURE_IDS = new Set(RECOVERY_FEATURES);
const MODE_IDS = new Set(MODE_LIST.map((m) => m.id));

/** True for a feature Recovery lists or a mode in MODE_LIST. */
export function validKey(key: string): key is UseKey {
  if (key.startsWith('feature:')) return FEATURE_IDS.has(key.slice(8));
  if (key.startsWith('mode:')) return MODE_IDS.has(key.slice(5));
  return false;
}

/** Note one use now (the demo's now, the same clock the rows are written on). */
export function noteUse(aura: Aura, key: UseKey): void {
  // optional call: the tests' small fake stores have no `used`
  aura.edit.used?.(key, nowIn(aura.zone, aura.today).iso);
}

/** The noted uses: key -> ISO times. */
export const usedOf = (aura: Aura): Record<string, string[]> => aura.local.used ?? {};

/** The demo's own now: the real time of day on the demo's today (the bundled sample is a fixed day), the clock that
 *  noteUse and the rows are stamped on. Scoring against the real date would age every new use by days. */
export const demoNow = (aura: Aura): Date => new Date(nowIn(aura.zone, aura.today, aura.now).iso);

export interface UseScore {
  score: number;
  /** The newest use (ISO). */
  last: string;
}

/** Log types and check-in kinds -> the feature they log for. */
const LOG_FEATURE: Record<string, string> = { water: 'NI-04', strength: 'PB-04' };
const CHECKIN_FEATURE: Record<string, string> = { mood: 'NI-02', connection: 'NI-03', insomnia: 'DX-03' };

const timeOf = (...values: unknown[]): number => {
  for (const v of values) {
    if (typeof v !== 'string' || !v) continue;
    const t = Date.parse(v);
    if (!Number.isNaN(t)) return t;
  }
  return Number.NaN;
};

/** What a session was a use of: the feature whose verb started it, else the mode it was started as (modeId), else the
 *  mode its title names, else (Focus and Meeting only) its Session-service mode. A Focus run keeps the visitor's own
 *  title, so only a Focus mode by that name is taken from it ("Yoga" written in the timer is not the Yoga mode). */
function sessionKey(s: Json): string | null {
  if (s.featureId) return `feature:${String(s.featureId)}`;
  const started = s.modeId ? findMode(String(s.modeId)) : undefined;
  if (started) return `mode:${started.id}`;
  const focus = s.kind === 'focus' || s.kind === 'meeting';
  const named = findMode(String(s.title ?? ''));
  if (named && (!focus || named.shelf === 'Focus')) return `mode:${named.id}`;
  const timer = focus ? findMode(String(s.mode ?? '')) : undefined;
  return timer ? `mode:${timer.id}` : null;
}

/** noted: noteUse's times; rows: sessions, logs, check-ins; planned: the createdAt of the events a feature planned. */
type Uses = { noted: number[]; rows: number[]; planned: number[] };

/** Every use on hand, key -> times (ms): the noted ones, and the ones the rows show. */
function usesFrom(data: Pick<Data, 'sessions' | 'logs' | 'checkins' | 'events'>, used: Record<string, string[]>): Map<string, Uses> {
  const out = new Map<string, Uses>();
  const add = (key: string, t: number, from: keyof Uses) => {
    if (Number.isNaN(t) || !validKey(key)) return;
    const uses = out.get(key) ?? { noted: [], rows: [], planned: [] };
    uses[from].push(t);
    out.set(key, uses);
  };
  for (const [key, times] of Object.entries(used)) for (const at of times) add(key, timeOf(at), 'noted');
  for (const s of data.sessions ?? []) {
    const key = sessionKey(s);
    if (key) add(key, timeOf(s.startedAt, s.endedAt, s.createdAt), 'rows');
  }
  const row = (r: Json, feature: string | undefined) => feature && add(`feature:${feature}`, timeOf(r.takenAt, r.createdAt, r.date), 'rows');
  for (const l of data.logs ?? []) row(l, LOG_FEATURE[String(l.type ?? 'water')]);
  for (const c of data.checkins ?? []) row(c, CHECKIN_FEATURE[String(c.kind ?? '')]);
  // only when it was planned: an event's start is when it happens, and a plan ahead would count again on the day
  for (const e of data.events ?? []) if (e.featureId) add(`feature:${String(e.featureId)}`, timeOf(e.createdAt), 'planned');
  return out;
}

/** One time per plan: events stamped within 90 s of a plan's first event belong to that plan. */
function plans(planned: number[]): number[] {
  const out: number[] = [];
  for (const t of [...planned].sort((x, y) => x - y)) if (!out.length || t - out[out.length - 1] > SAME_USE_MS) out.push(t);
  return out;
}

/** The uses counted: every noted one, every row that isn't the row a noted use wrote (a noted use within 90 s takes
 *  one row with it), and every plan with no other use of the feature beside it. Several taps in a row are several
 *  uses. */
function distinct({ noted, rows, planned }: Uses): number[] {
  const free = [...noted].sort((x, y) => x - y);
  const taken = new Set<number>();
  const own = rows.filter((t) => {
    const i = free.findIndex((n, j) => !taken.has(j) && Math.abs(n - t) <= SAME_USE_MS);
    if (i < 0) return true;
    taken.add(i);
    return false;
  });
  const near = (t: number, times: number[]) => times.some((x) => Math.abs(x - t) <= SAME_USE_MS);
  // a plan noted by its verb, or made with a check-in (NI-03's meal), is that use already
  const alone = plans(planned).filter((t) => !near(t, noted) && !near(t, rows));
  return [...free, ...own, ...alone];
}

/** Each used key's score: uses in the last 30 days, each weighted 0.5^(age in days / 7), with a noted use and the row
 *  it wrote (within 90 s) counted once. Keys with no use in the window are left out. */
export function useScores(data: Pick<Data, 'sessions' | 'logs' | 'checkins' | 'events'>, used: Record<string, string[]>, now: Date): Map<UseKey, UseScore> {
  const nowMs = now.getTime();
  const scores = new Map<UseKey, UseScore>();
  for (const [key, uses] of usesFrom(data, used)) {
    const times = distinct(uses).filter((t) => t <= nowMs + AHEAD_MS && nowMs - t <= WINDOW_DAYS * DAY_MS);
    if (!times.length) continue;
    const score = times.reduce((sum, t) => sum + 0.5 ** (Math.max(0, nowMs - t) / DAY_MS / HALF_LIFE_DAYS), 0);
    scores.set(key as UseKey, { score, last: new Date(Math.max(...times)).toISOString() });
  }
  return scores;
}

/** The visitor's scores on the demo's own clock (what Recovery and the Modes page read). */
export function scoresFor(aura: Aura): Map<UseKey, UseScore> {
  return useScores(aura.data, usedOf(aura), demoNow(aura));
}

/** Highest score first; a tie goes to the newer use. */
function ranked(scores: Map<UseKey, UseScore>, keep: (key: UseKey) => boolean): UseKey[] {
  return [...scores.entries()]
    .filter(([key, s]) => s.score > 0 && keep(key))
    .sort(([, a], [, b]) => b.score - a.score || Date.parse(b.last) - Date.parse(a.last))
    .map(([key]) => key);
}

/** Recovery's "Often used": the top `n` features and modes, filled (while history is short) with a mix: the features
 *  of the open alerts (`openFeatureIds`, newest first) in at most ALERT_SLOTS of the slots left, the default modes in
 *  the rest (more features when the modes run out). */
export function oftenUsed(scores: Map<UseKey, UseScore>, openFeatureIds: string[], n = 4): UseKey[] {
  const out: UseKey[] = [];
  const fresh = (key: UseKey) => validKey(key) && !out.includes(key);
  for (const key of ranked(scores, validKey)) if (out.length < n && fresh(key)) out.push(key);
  const features = [...new Set(openFeatureIds.map((id) => `feature:${id}` as UseKey))].filter(fresh);
  const modes = DEFAULT_MODES.map((id) => `mode:${id}` as UseKey).filter(fresh);
  const left = n - out.length;
  const take = Math.min(features.length, Math.max(ALERT_SLOTS, left - modes.length));
  for (const key of [...features.slice(0, take), ...modes, ...features.slice(take)]) if (out.length < n) out.push(key);
  return out;
}

/** The Recovery group a shelf's modes count towards (Focus is work, not recovery: none). Keyed by the group titles
 *  Recovery's featureGroups() uses. */
export const SHELF_GROUP: Partial<Record<Shelf, string>> = {
  Sleep: 'Sleep',
  Rest: 'Recovery & stress',
  Breathe: 'Recovery & stress',
  Meditate: 'Recovery & stress',
  Mind: 'Recovery & stress',
  Movement: 'Movement',
  Outside: 'Movement',
  People: 'Mind & people',
  Fuel: 'Hydration',
};

const featureScore = (scores: Map<UseKey, UseScore>, id: string) => scores.get(`feature:${id}`)?.score ?? 0;

/** Group title -> the summed scores of the modes on its shelves. */
function modeScores(scores: Map<UseKey, UseScore>): Map<string, number> {
  const out = new Map<string, number>();
  for (const [key, s] of scores) {
    const shelf = key.startsWith('mode:') ? findMode(key.slice(5))?.shelf : undefined;
    const group = shelf && SHELF_GROUP[shelf];
    if (group) out.set(group, (out.get(group) ?? 0) + s.score);
  }
  return out;
}

/** Stable sort by score, busiest first; equal scores keep their order. */
function byScore<T>(items: T[], score: (item: T) => number): T[] {
  return items
    .map((item, i) => ({ item, i, s: score(item) }))
    .sort((a, b) => b.s - a.s || a.i - b.i)
    .map((x) => x.item);
}

/** The groups busiest first (the sum of their features' scores and of the modes on their shelves: a nap moves
 *  Sleep up) and the features in each by their own scores; ties keep the given order. New arrays and group objects;
 *  nothing passed in is changed. */
export function orderGroups<G extends { title: string; features: { id: string }[] }>(groups: G[], scores: Map<UseKey, UseScore>): G[] {
  const modes = modeScores(scores);
  const sorted = groups.map((g) => ({ ...g, features: byScore(g.features, (f) => featureScore(scores, f.id)) }));
  return byScore(sorted, (g) => g.features.reduce((sum, f) => sum + featureScore(scores, f.id), modes.get(g.title) ?? 0));
}

/** The `n` modes used most, leaving out `exclude` (ids already shown, e.g. in Often used), filled with the default
 *  modes and then the list's own order (not the plan modes, which are calendar blocks). */
export function topModes(scores: Map<UseKey, UseScore>, n = 4, exclude: string[] = []): Mode[] {
  const used = ranked(scores, (key) => key.startsWith('mode:') && validKey(key)).map((key) => key.slice(5));
  const fill = [...DEFAULT_MODES, ...MODE_LIST.filter((m) => m.run !== 'plan').map((m) => m.id)];
  const skip = new Set(exclude);
  const out: Mode[] = [];
  for (const id of [...used, ...fill]) {
    const mode = findMode(id);
    if (out.length >= n) break;
    if (mode && !skip.has(mode.id) && !out.includes(mode)) out.push(mode);
  }
  return out;
}
