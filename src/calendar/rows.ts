// The task and event rows the calendar writes, as ui/actions.py builds them (task_fields, add_task, edit_task,
// set_task_done, add_events, edit_event). They go to aura.edit only, so they stay in this visitor's browser.
//
// task:  {id, title, horizon, dueAt?, hasTime?, estimateMinutes?, notes?, status: 'open'|'completed', completedAt?,
//         scheduledAt?, plannedFor?}
// event: {id, title, start, end, kind?}

import { horizonFor } from '../lib/agenda';
import type { Json } from '../lib/types';
import { isoInZone, zonedIso } from './zone';

/** A task's day and time as the PC's editor writes them: dueAt plus hasTime (false: only the day counts). No day
 *  clears both, and plannedFor, so the task is "Anytime" again. */
export function taskFields(day: string | null, at: number | null, zone: string): Json {
  if (day === null) return { dueAt: null, hasTime: false, plannedFor: null };
  return { dueAt: zonedIso(day, at ?? 0, zone), hasTime: at !== null, plannedFor: null };
}

/** A new open task on `day` (at `at`, for `minutes`); no day is "Anytime" on today's list. */
export function newTask(title: string, day: string | null, at: number | null, minutes: number | null, zone: string, today: string): Json {
  const fields = Object.fromEntries(Object.entries(taskFields(day, at, zone)).filter(([, v]) => v !== null));
  const row: Json = { title, status: 'open', horizon: horizonFor(day, today), ...fields };
  if (at !== null && minutes) row.estimateMinutes = minutes;
  return row;
}

/** An event's start and end: `minutes` long from `at` on `day`. */
export function eventTimes(day: string, at: number, minutes: number, zone: string): { start: string; end: string } {
  const start = zonedIso(day, at, zone);
  return { start, end: isoInZone(Date.parse(start) + minutes * 60_000, zone) };
}

export const newEvent = (title: string, day: string, at: number, minutes: number, zone: string): Json => ({ title, ...eventTimes(day, at, minutes, zone) });

/** What an edit changes on a task; null when nothing does. A task placed by a calendar block (`locked`) keeps its
 *  time: only its title changes. */
export function taskChanges(
  task: Json, title: string, day: string | null, at: number | null, minutes: number, locked: boolean, zone: string, today: string,
): Json | null {
  const changes: Json = { title };
  if (!locked) {
    Object.assign(changes, taskFields(day, at, zone));
    if (at !== null) changes.estimateMinutes = minutes;
    changes.horizon = horizonFor(day, today);
  }
  const differs = Object.entries(changes).some(([k, v]) => (task[k] ?? null) !== v);
  return differs ? changes : null;
}

/** Ticking a task: done stamps completedAt now (in the zone); reopening clears it. */
export function doneChanges(done: boolean, now: Date, zone: string): Json {
  return { status: done ? 'completed' : 'open', completedAt: done ? isoInZone(now.getTime(), zone) : null };
}

/** A task is locked to its calendar block. */
export const isLocked = (task: Json) => Boolean(task.blockId || task.scheduledAt);
