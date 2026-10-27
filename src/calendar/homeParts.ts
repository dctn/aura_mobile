// Home's pure pieces, ported from ui/pages/home.py, ui/prefs.py, core/profile.py and ui/actions.py: today's to-dos,
// pins, the burnout word, profile gaps, the band line and the movement ring.

import { KNOWN, newest, series, type MetricDay } from '../lib/metrics';
import { daysBetween, eventItem, localParts, type Item } from '../lib/agenda';
import { alertWeeks } from '../lib/actions';
import { featureFor } from '../lib/catalog';
import { MODE_LIST, findMode, type Mode } from '../lib/modes';
import type { Json } from '../lib/types';
import { waterGoal, waterOn } from '../search/logic';
import { openAlerts } from '../screens/shared';
import { STRENGTH_DAYS, strengthDays as strengthDaysIn } from '../logbook/derive';
import { weekdayShort } from './zone';

/** DX-09: the WHO's 150 active minutes a week. */
export const WEEKLY_ACTIVE_TARGET = 150;
/** BS-02: the band line warns after this long without a sync. */
export const SYNC_WARN_AFTER_MS = 24 * 3_600_000;
/** Pills under Today on Home; the rest wait behind "See all". */
export const SHOW_TODAY = 6;
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

// -- today's to-dos -----------------------------------------------------------------------------------------------

/** PB-04: the WHO's muscle-strengthening on 2 or more days a week (kept with the logbook's count, which reads it too). */
export { STRENGTH_DAYS };
/** DX-03: the sleep check is offered after this many weeks of the alert, at most once a fortnight. */
export const SLEEP_CHECK_WEEKS = 3;
export const SLEEP_CHECK_DAYS = 14;
/** How the events a recovery action plans begin (rows from before featureId was written have only the title). */
export const RECOVERY_TITLES = ['Outdoor light', 'Dim screens', 'Walk ·', 'Recovery day', 'Strength ·', 'Brisk session', 'Meal with', 'Wind-down', 'Nap'];
/** An event's Start that is the wind-down (with its list), not a timer. */
export const WIND_DOWN = 'wind-down';
/** Title start -> the mode its Start runs (lib/modes.ts id), for the titles the recovery actions write
 *  ("Walk · 30 min"). The rest are only shown. */
const EVENT_RUNS: [string, string][] = [
  ['Nap', 'nap-10'],
  ['Walk ·', 'urban-walk'],
  ['Outdoor light', 'morning-light'],
  ['Recovery day', 'recovery-break'],
  ['Brisk session', 'zone2'],
  ['Wind-down', WIND_DOWN],
];

export type TodayItem =
  | { kind: 'alert'; key: string; alert: Json }
  | { kind: 'checkin'; key: string; check: 'mood' | 'connection' | 'sleep' }
  | { kind: 'water'; key: string; ml: number; goal: number }
  /** `days`: days of the last 7 with a strength log (Recovery's minutes) or a lift entry (the logbook's). */
  | { kind: 'strength'; key: string; days: number }
  /** `end` in minutes after midnight (past 1440 when it runs over); `run` is a mode id, WIND_DOWN or null. */
  | { kind: 'event'; key: string; item: Item; end: number; run: string | null };

export type TodayData = { alerts: Json[]; alertOff: string[]; checkins: Json[]; logs: Json[]; events: Json[]; me: Json };

/** A check-in's or log's day: its date, else the day of its takenAt. */
const dayOf = (row: Json, zone: string) => String(row.date ?? '').slice(0, 10) || localParts(row.takenAt, zone)?.day || '';

/** The alert's action.ref, else its feature's. */
const refOf = (alert: Json) => String((alert.action as Json | undefined)?.ref ?? '') || featureFor(alert)?.action || '';

/** Minutes after midnight in `zone` right now (the demo's today may be the sample's fixed day; the time is real). */
export const minuteNow = (now: Date, zone: string) => localParts(now.toISOString(), zone)?.minute ?? now.getUTCHours() * 60 + now.getUTCMinutes();

/** Whether a recovery action planned this event. */
export function isRecoveryEvent(event: Json): boolean {
  const title = String(event.title ?? '');
  return Boolean(event.featureId) || RECOVERY_TITLES.some((t) => title.startsWith(t));
}

/** The mode an event title names: its part before " · " as a mode's name or old title (not its id: an event called
 *  "Strength" is not the "strength" mode's timer). */
export function modeNamed(title: string): Mode | undefined {
  const t = title.split(' · ')[0].trim().toLowerCase();
  if (!t) return undefined;
  return MODE_LIST.find((m) => m.name.toLowerCase() === t || (m.aliases ?? []).some((a) => a.toLowerCase() === t));
}

/** What an event's Start runs: a mode id (the title naming a mode, else the title's start; WIND_DOWN for the
 *  wind-down), or null when it has no run: no mode in the list, or a plan mode (a calendar block itself). Home starts
 *  the mode through startMode, for the event's own length. */
export function eventRun(title: string): string | null {
  const id = modeNamed(title)?.id ?? EVENT_RUNS.find(([start]) => title.startsWith(start))?.[1];
  const mode = id ? findMode(id) : undefined;
  if (!mode || mode.run === 'plan') return null;
  return mode.run === 'winddown' ? WIND_DOWN : mode.id;
}

/** Where Today's one "See all" (to Recovery) goes, if anywhere: the header while open alerts from other days wait in
 *  Recovery, else the bottom when the six-pill cap hides some of today's. Never both: they lead to the same place. */
export function seeAllAt(openAlerts: number, todayAlerts: number, items: number): 'top' | 'bottom' | null {
  if (openAlerts > todayAlerts) return 'top';
  return items > SHOW_TODAY ? 'bottom' : null;
}

/** What is due today, in order: today's alerts (unless `hideAlerts`, Home's "Not now"), the recovery events still
 *  ahead today (nearest first), the mood and connection check-ins not yet given today, the sleep check when it is
 *  due (not while DX-03 is switched off), water under the goal, then strength on under 2 of the last 7 days. Each
 *  drops off once done, since it is read from the data. */
export function todayItems(data: TodayData, today: string, zone: string, now: Date, hideAlerts = false): TodayItem[] {
  const out: TodayItem[] = [];

  // today's alerts; an alert already asking for a check-in or water stands in for that pill
  const alerts = hideAlerts ? [] : openAlerts(data.alerts, data.alertOff).filter((a) => String(a.date ?? '').slice(0, 10) === today);
  for (const a of alerts) out.push({ kind: 'alert', key: `alert:${String(a.id ?? a.featureId ?? '')}`, alert: a });
  const asked = new Set(alerts.map(refOf));

  // the recovery events still ahead (or under way) today: they have a time, so they come before what can wait
  const at = minuteNow(now, zone);
  const events = data.events
    .filter(isRecoveryEvent)
    .map((e) => eventItem(e, zone))
    .filter((i): i is Item => i !== null && i.day === today && i.start !== null && i.start + i.minutes > at)
    .sort((a, b) => (a.start ?? 0) - (b.start ?? 0));
  for (const i of events) {
    out.push({ kind: 'event', key: `event:${i.id || i.start}`, item: i, end: (i.start ?? 0) + i.minutes, run: eventRun(i.title) });
  }

  // check-ins
  for (const check of ['mood', 'connection'] as const) {
    const given = data.checkins.some((c) => c.kind === check && dayOf(c, zone) === today);
    if (!given && !asked.has(`checkin/${check}`)) out.push({ kind: 'checkin', key: `checkin:${check}`, check });
  }
  const recentCheck = data.checkins.some((c) => {
    if (c.kind !== 'insomnia') return false;
    const gap = daysBetween(dayOf(c, zone), today);
    return gap >= 0 && gap < SLEEP_CHECK_DAYS;
  });
  // the sleep check is DX-03's: with that feature switched off in Settings it stays away too
  const sleepOff = data.alertOff.includes('DX-03');
  if (!sleepOff && alertWeeks(data.alerts, 'DX-03') >= SLEEP_CHECK_WEEKS && !recentCheck) out.push({ kind: 'checkin', key: 'checkin:sleep', check: 'sleep' });

  // water and strength
  const ml = waterOn(data.logs, today);
  const goal = waterGoal(data.me);
  if (ml < goal && !asked.has('water')) out.push({ kind: 'water', key: 'water', ml, goal });
  // the logbook's lift entries are strength training too; its "Strength days" row counts the same way
  const strengthDays = strengthDaysIn(data.logs, today, zone);
  if (strengthDays < STRENGTH_DAYS) out.push({ kind: 'strength', key: 'strength', days: strengthDays });
  return out;
}
