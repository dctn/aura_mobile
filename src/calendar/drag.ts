// Drag and drop on the calendar: where a dragged block lands (15-minute steps), how long a stretched one gets, and
// the task or event changes a drop writes. The pointer handling is in useDrag.ts; this is the plain maths.

import { clock, horizonFor, type Item } from '../lib/agenda';
import type { Json } from '../lib/types';
import { HOUR, PAD } from './layout';
import { eventTimes, isLocked, taskFields } from './rows';
import { shortDay } from './zone';

/** A drop lands on a quarter hour. */
export const SNAP = 15;
/** A stretched block is never shorter than this. */
export const MIN_LENGTH = 15;
const DAY = 24 * 60;

const clamp = (n: number, lo: number, hi: number) => Math.max(lo, Math.min(hi, n));
export const snap = (minute: number) => Math.round(minute / SNAP) * SNAP;

/** The minute of day under `y` px on the day (not snapped). */
export const minuteAtY = (y: number) => ((y - PAD) * 60) / HOUR;

/** Where a moved block starts: the minute under the pointer less where it was grabbed, snapped, inside the day. */
export function dropStart(y: number, grab: number, minutes: number): number {
  return clamp(snap(minuteAtY(y) - grab), 0, Math.max(0, DAY - minutes));
}

/** A block stretched by its bottom edge to `y`: from its start to there, snapped, at least MIN_LENGTH. */
export function stretchTo(y: number, start: number): number {
  return clamp(snap(minuteAtY(y) - start), MIN_LENGTH, DAY - start);
}

/** Where a drag would put the item: a day, a start (null keeps a to-do without a time) and a length. */
export interface Target {
  day: string;
  start: number | null;
  minutes: number;
}

/** Done tasks stay where they were ticked, and a task placed by a calendar block keeps its time. */
export const canDrag = (item: Item) => !item.done && !(item.kind === 'task' && isLocked(item.raw));

/** Whether the drop changes nothing. */
export const same = (item: Item, t: Target) => item.day === t.day && item.start === t.start && (t.start === null || item.minutes === t.minutes);

/** What the drop writes: an event's start and end, or a task's day, time, length and list. Null when nothing moves. */
export function moveChanges(item: Item, t: Target, zone: string, today: string): Json | null {
  if (same(item, t)) return null;
  if (item.kind === 'event') return eventTimes(t.day, t.start ?? item.start ?? 9 * 60, t.minutes, zone);
  const changes: Json = { ...taskFields(t.day, t.start, zone), horizon: horizonFor(t.day, today) };
  if (t.start !== null) changes.estimateMinutes = t.minutes;
  return changes;
}

/** "Sun 25 Oct · 10:15–11:00", or "Sun 25 Oct" for a to-do without a time. */
export function targetText(t: Target): string {
  const day = shortDay(t.day);
  return t.start === null ? day : `${day} · ${clock(t.start)}–${clock(Math.min(t.start + t.minutes, DAY))}`;
}
