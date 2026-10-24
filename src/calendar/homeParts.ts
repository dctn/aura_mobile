// Home's pure pieces, ported from ui/pages/home.py, ui/prefs.py, core/profile.py and ui/actions.py: pins, the
// burnout word, profile gaps, the band line and the movement ring.

import { KNOWN, newest, series, type MetricDay } from '../lib/metrics';
import { localParts } from '../lib/agenda';
import type { Json } from '../lib/types';
import { weekdayShort } from './zone';

/** DX-09: the WHO's 150 active minutes a week. */
export const WEEKLY_ACTIVE_TARGET = 150;
/** BS-02: the band line warns after this long without a sync. */
export const SYNC_WARN_AFTER_MS = 24 * 3_600_000;
/** Alert pills on Home; the rest wait in Recovery. */
export const SHOW_ALERTS = 3;
export const MAX_PINS = 6;
export const DEFAULT_PINS = ['totalSleepMin', 'recoveryScore', 'restingHrBpm', 'stressDurationMin'];
/** Minutes the band holds offline: 30 min to 12 h. */
export const BACKFILL_MIN = 30;
export const BACKFILL_MAX = 720;

/** A pinned metric's tile: [icon, accent]. */
export const PIN_LOOK: Record<string, [string, string]> = {
  totalSleepMin: ['sleep', 'sleep'],
  sleepDebtMin14: ['sleep', 'sleep'],
  sleepLatencyMin: ['sleep', 'sleep'],
  wakeRegularityMin: ['sunrise', 'sleep'],
  recoveryScore: ['recover', 'recovery'],
  restingHrBpm: ['heart', 'cardiac'],
  restingHrBaselineBpm: ['heart', 'cardiac'],
  hrvAboveBaseline: ['signal', 'cardiac'],
  skinTempDeviationC: ['sun', 'stress'],
  stressDurationMin: ['bolt', 'stress'],
  weeklyActiveMinutes: ['walk', 'fitness'],
  longestSittingBoutMin: ['timer', 'fitness'],
  steps: ['walk', 'fitness'],
  vo2max: ['flame', 'workout'],
  circadianAlignmentPct: ['sun', 'focus'],
  spo2DipEvents: ['drop', 'cardiac'],
  burnoutIndexScore: ['flame', 'workout'],
};

export const pinLook = (field: string): [string, string] => PIN_LOOK[field] ?? ['chart', 'blue'];

/** The visitor's pins, else the defaults. */
export const pinsOf = (pins: string[] | null) => (Array.isArray(pins) ? pins : DEFAULT_PINS).slice(0, MAX_PINS);

/** The fields a person could pin: the scalars, known ones first. */
export function numericFields(metrics: Json): string[] {
  const keys = Object.entries(metrics)
    .filter(([, v]) => v === null || typeof v === 'boolean' || (typeof v === 'number' && Number.isFinite(v)))
    .map(([k]) => k);
  const known = Object.keys(KNOWN).filter((k) => keys.includes(k));
  return [...known, ...keys.filter((k) => !(k in KNOWN)).sort()];
}

/** Tick or untick a pin, never past MAX_PINS. */
export function togglePin(picked: string[], field: string): string[] {
  if (picked.includes(field)) return picked.filter((f) => f !== field);
  return picked.length < MAX_PINS ? [...picked, field] : picked;
}

/** The risk word: the server's burnoutIndexRisk when it sends one, else the newest score bucketed. */
export function burnoutRisk(days: MetricDay[], latest: Json | null, today: string): string {
  const word = (latest?.metrics as Json | undefined)?.burnoutIndexRisk;
  if (typeof word === 'string' && word.trim()) {
    const w = word.trim();
    return w.charAt(0).toUpperCase() + w.slice(1).toLowerCase();
  }
  const score = newest(series(days, 'burnoutIndexScore', today));
  if (score === null) return 'No data yet';
  return score >= 70 ? 'High' : score >= 40 ? 'Moderate' : 'Low';
}

/** BS-01: field on /v1/me -> what Home calls it. */
const NEEDED: [string, string][] = [
  ['displayName', 'name'],
  ['birthDate', 'birth date'],
  ['sex', 'sex'],
  ['heightCm', 'height'],
  ['weightKg', 'weight'],
];

/** The profile fields still unset ("sex: notSet" counts as a choice). */
export function missing(me: Json | null | undefined): string[] {
  if (!me || typeof me !== 'object') return [];
  return NEEDED.filter(([field]) => me[field] === null || me[field] === undefined || me[field] === '').map(([, name]) => name);
}

/** When the band last synced: the visitor's Reconnect, else the newest computed day's computedAt. */
export function lastSync(localSync: string | null, days: Json[], latest: Json | null): string | null {
  if (localSync) return localSync;
  const stamps = [latest, ...days].map((d) => String(d?.computedAt ?? '')).filter((s) => s && !Number.isNaN(Date.parse(s)));
  return stamps.length ? stamps.reduce((a, b) => (Date.parse(b) > Date.parse(a) ? b : a)) : null;
}

/** "Synced 10:42" (or "Synced Fri 10:42"), or "Not synced since Fri" after a day (`stale`). */
export function syncText(last: string | null, now: Date, zone: string): { text: string; stale: boolean } {
  if (!last || Number.isNaN(Date.parse(last))) return { text: 'Not synced', stale: false };
  const at = localParts(last, zone);
  const here = localParts(now.toISOString(), zone);
  if (!at || !here) return { text: 'Not synced', stale: false };
  if (now.getTime() - Date.parse(last) > SYNC_WARN_AFTER_MS) return { text: `Not synced since ${weekdayShort(at.day)}`, stale: true };
  const hm = `${String(Math.floor(at.minute / 60)).padStart(2, '0')}:${String(at.minute % 60).padStart(2, '0')}`;
  return { text: at.day === here.day ? `Synced ${hm}` : `Synced ${weekdayShort(at.day)} ${hm}`, stale: false };
}

/** Minutes the band sends on Reconnect: the gap since the last sync, 30 min to 12 h. */
export function backfillMinutes(last: string | null, now: Date): number {
  if (!last || Number.isNaN(Date.parse(last))) return BACKFILL_MIN;
  const gap = Math.floor((now.getTime() - Date.parse(last)) / 60_000);
  return Math.max(BACKFILL_MIN, Math.min(BACKFILL_MAX, gap));
}

/** This week's active minutes from the newest day (0 when missing). */
export function weeklyMinutes(latest: Json | null): number {
  const v = (latest?.metrics as Json | undefined)?.weeklyActiveMinutes;
  return typeof v === 'number' && Number.isFinite(v) ? Math.trunc(v) : 0;
}
