// How a computed day's metric reads, ported from demo/src/aura_mobile/core/metrics.py. Absent is null and reads "—";
// a measured zero stays a zero.

import { addDays } from './agenda';

export const NO_VALUE = '—';

type Kind = 'duration' | 'minutes' | 'bpm' | 'bool' | 'celsius' | 'number' | 'percent' | 'score' | 'raw';

/** field -> [label, unit kind] */
export const KNOWN: Record<string, [string, Kind]> = {
  totalSleepMin: ['Sleep', 'duration'],
  sleepDebtMin14: ['Sleep debt (14 d)', 'duration'],
  sleepLatencyMin: ['Time to fall asleep', 'minutes'],
  recoveryScore: ['Recovery', 'score'],
  restingHrBpm: ['Resting HR', 'bpm'],
  restingHrBaselineBpm: ['Resting HR baseline', 'bpm'],
  hrvAboveBaseline: ['HRV above baseline', 'bool'],
  skinTempDeviationC: ['Skin temp vs normal', 'celsius'],
  stressDurationMin: ['High stress', 'duration'],
  weeklyActiveMinutes: ['Active this week', 'minutes'],
  longestSittingBoutMin: ['Longest sitting', 'minutes'],
  vo2max: ['VO2max', 'number'],
  wakeRegularityMin: ['Wake-time swing', 'minutes'],
  circadianAlignmentPct: ['Body-clock alignment', 'percent'],
  spo2DipEvents: ['SpO2 dips', 'number'],
  steps: ['Steps', 'number'],
  burnoutIndexScore: ['Burnout index', 'score'],
};

export function labelFor(field: string): string {
  const known = KNOWN[field];
  if (known) return known[0];
  const words = field.replace(/[A-Z]/g, (c) => ` ${c.toLowerCase()}`).trim();
  return words.charAt(0).toUpperCase() + words.slice(1);
}

const isNumber = (v: unknown): v is number => typeof v === 'number' && Number.isFinite(v);

export function formatValue(field: string, value: unknown): string {
  if (value === null || value === undefined) return NO_VALUE;
  const kind = KNOWN[field]?.[1] ?? 'raw';
  if (typeof value === 'boolean' || kind === 'bool') return value ? 'Yes' : 'No';
  if (!isNumber(value)) return String(value).slice(0, 18);
  const r = Math.round(value);
  switch (kind) {
    case 'duration':
      return r >= 60 ? `${Math.floor(r / 60)} h ${String(r % 60).padStart(2, '0')} m` : `${r} min`;
    case 'minutes':
      return `${r} min`;
    case 'bpm':
      return `${r} bpm`;
    case 'celsius':
      return `${value >= 0 ? '+' : ''}${value.toFixed(1)} °C`;
    case 'percent':
      return `${r} %`;
    case 'score':
      return String(r);
    default:
      return String(Number.isInteger(value) ? value : Number(value.toPrecision(6)));
  }
}

/** How one metric reads on a card. `accent` names a colour in tokens.css; `icon` a glyph in kit/icons. */
export interface Card {
  key: string;
  label: string;
  field: string;
  unit: string;
  accent: string;
  icon: string;
  div: number;
  digits: number;
}

const card = (key: string, label: string, field: string, unit: string, accent: string, icon: string, div = 1, digits = 0): Card => ({
  key, label, field, unit, accent, icon, div, digits,
});

export const cardNumber = (c: Card, raw: unknown): number | null => (typeof raw === 'boolean' || !isNumber(raw) ? null : raw / c.div);

export function cardShow(c: Card, raw: unknown): string {
  const n = cardNumber(c, raw);
  if (n === null) return NO_VALUE;
  return c.digits ? n.toFixed(c.digits) : String(Math.round(n));
}

/** Search's categories, in order, then the extra cards Home shows under Burnout. */
export const CARDS: Record<string, Card> = Object.fromEntries(
  [
    card('sleep', 'Sleep', 'totalSleepMin', 'h', 'cyan', 'sleep', 60, 1),
    card('heart', 'Heart', 'restingHrBpm', 'bpm', 'cardiac', 'heart'),
    card('stress', 'Stress', 'stressScore', '/100', 'stress', 'bolt'),
    card('recovery', 'Recovery', 'recoveryScore', '/100', 'recovery', 'recover'),
    card('movement', 'Movement', 'activeMinutes', 'min', 'fitness', 'walk'),
    card('breathing', 'Breathing', 'breathsPerMin', '/min', 'mint', 'wind', 1, 1),
    card('burnout', 'Burnout', 'burnoutIndexScore', '/100', 'workout', 'flame'),
    card('body', 'Body', 'skinTempDeviationC', '°C', 'purple', 'body', 1, 1),
    card('avg_sleep', 'Avg sleep', 'avgSleepBurnoutHrs', 'h', 'cyan', 'sleep', 1, 1),
    card('late_nights', 'Late nights', 'lateNightsBurnout', 'nights', 'purple', 'evening'),
    card('sleep_score', 'Sleep score', 'sleepScore', '/100', 'sleep', 'sleep'),
  ].map((c) => [c.key, c]),
);

export const CATEGORIES = ['sleep', 'heart', 'stress', 'recovery', 'movement', 'breathing', 'burnout', 'body'] as const;

const UNITS: Partial<Record<Kind, string>> = { duration: 'min', minutes: 'min', bpm: 'bpm', celsius: '°C', percent: '%', score: '/100' };

/** The card for a metric column: a known one, else one built from the column's label. */
export function cardFor(field: string): Card {
  const found = Object.values(CARDS).find((c) => c.field === field);
  if (found) return found;
  return card(field, labelFor(field), field, UNITS[KNOWN[field]?.[1] ?? 'raw'] ?? '', 'blue', 'chart');
}

/** One day from /v1/metrics/daily (loose: it arrives as JSON). */
export interface MetricDay {
  date?: unknown;
  status?: unknown;
  metrics?: unknown;
}

/** `count` values of one column, oldest first, ending today; null where a day has no value. */
export function series(days: MetricDay[], field: string, today: string, count = 14): (number | null)[] {
  const byDate = new Map(days.map((d) => [String(d.date).slice(0, 10), (d.metrics as Record<string, unknown> | undefined)?.[field]]));
  return Array.from({ length: count }, (_, i) => {
    const v = byDate.get(addDays(today, i - count + 1));
    return isNumber(v) ? v : null;
  });
}

export const newest = (values: (number | null)[]) => [...values].reverse().find((v) => v !== null) ?? null;

export function average(values: (number | null)[]): number | null {
  const got = values.filter((v): v is number => v !== null);
  return got.length ? got.reduce((a, b) => a + b, 0) / got.length : null;
}
