// The calendar's data, ported from demo/src/aura_mobile/core/agenda.py: which day each event and task falls on, and
// what a day shows. A task's day is the first of: its calendar block (scheduledAt), its due date (dueAt; hasTime
// says whether the time counts), its planned day (plannedFor). An open task with none of them is "Anytime" and shows
// on today. A done task shows on the day it was done.
//
// Days are 'YYYY-MM-DD' strings in the demo person's zone; times are minutes after midnight in that zone.

export type Json = Record<string, unknown>;

/** A timed task with no estimate takes this long on the timeline. */
export const TASK_MINUTES = 30;
/** An empty day's hours open here. */
export const DAY_FROM = 8;

export interface Item {
  kind: 'event' | 'task';
  id: string;
  title: string;
  /** The day it shows on. */
  day: string | null;
  /** Minutes after midnight, when it has a time. */
  start: number | null;
  minutes: number;
  done: boolean;
  /** The event's kind (meeting / recovery / focus); "task" for tasks. */
  look: string;
  raw: Json;
}

export const keyOf = (i: Item) => `${i.kind}:${i.id}`;

/** A moment's day and minute of day in `zone`. */
export function localParts(value: unknown, zone: string): { day: string; minute: number } | null {
  if (!value) return null;
  const text = String(value);
  const plain = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2})?$/.test(text); // no offset: already local
  if (plain) return { day: text.slice(0, 10), minute: Number(text.slice(11, 13)) * 60 + Number(text.slice(14, 16)) };
  const moment = new Date(text);
  if (Number.isNaN(moment.getTime())) return null;
  const f = new Intl.DateTimeFormat('en-CA', {
    timeZone: zone, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23',
  });
  const p = Object.fromEntries(f.formatToParts(moment).map((x) => [x.type, x.value]));
  return { day: `${p.year}-${p.month}-${p.day}`, minute: Number(p.hour) * 60 + Number(p.minute) };
}

export const isDone = (task: Json) => String(task.status ?? '') === 'completed';

/** The task's day and, when it has one, its time. */
export function taskWhen(task: Json, zone: string): { day: string | null; minute: number | null } {
  if (isDone(task)) {
    const doneAt = localParts(task.completedAt, zone);
    if (doneAt) return { day: doneAt.day, minute: null };
  }
  const block = localParts(task.scheduledAt, zone);
  if (block) return { day: block.day, minute: block.minute };
  const due = localParts(task.dueAt, zone);
  if (due) return { day: due.day, minute: task.hasTime ? due.minute : null };
  const planned = String(task.plannedFor ?? '').slice(0, 10);
  if (/^\d{4}-\d{2}-\d{2}$/.test(planned)) return { day: planned, minute: null };
  return { day: null, minute: null };
}

/** The day the calendar shows the task on: its own day, or today for an open task with none. */
export function taskDay(task: Json, zone: string, today: string): string | null {
  const { day } = taskWhen(task, zone);
  return day === null && !isDone(task) ? today : day;
}

export function taskItem(task: Json, zone: string, today: string): Item {
  const { minute } = taskWhen(task, zone);
  return {
    kind: 'task', id: String(task.id ?? ''), title: String(task.title || 'Task'), day: taskDay(task, zone, today),
    start: minute, minutes: Number(task.estimateMinutes) || TASK_MINUTES, done: isDone(task), look: 'task', raw: task,
  };
}

export function eventItem(event: Json, zone: string): Item | null {
  const start = localParts(event.start, zone);
  if (!start) return null;
  const s = new Date(String(event.start)).getTime();
  const e = new Date(String(event.end)).getTime();
  const minutes = Number.isFinite(s) && Number.isFinite(e) && e > s ? Math.floor((e - s) / 60_000) : TASK_MINUTES;
  return {
    kind: 'event', id: String(event.id ?? ''), title: String(event.title || 'Event'), day: start.day, start: start.minute,
    minutes, done: false, look: String(event.kind || 'focus'), raw: event,
  };
}

export interface Day {
  /** Events and timed tasks, by start. */
  timed: Item[];
  /** Tasks without a time: open first, then by title. */
  todo: Item[];
}

export function dayItems(day: string, events: Json[], tasks: Json[], zone: string, today: string): Day {
  const timed: Item[] = [];
  const todo: Item[] = [];
  for (const e of events) {
    const item = eventItem(e, zone);
    if (item && item.day === day) timed.push(item);
  }
  for (const t of tasks) {
    const item = taskItem(t, zone, today);
    if (item.day !== day) continue;
    (item.start !== null ? timed : todo).push(item);
  }
  timed.sort((a, b) => (a.start ?? 0) - (b.start ?? 0) || a.title.localeCompare(b.title));
  todo.sort((a, b) => Number(a.done) - Number(b.done) || a.title.toLowerCase().localeCompare(b.title.toLowerCase()));
  return { timed, todo };
}

/** Each day's dots: "event" and/or "task" (open tasks only). */
export function marks(events: Json[], tasks: Json[], zone: string, today: string): Map<string, Set<'event' | 'task'>> {
  const out = new Map<string, Set<'event' | 'task'>>();
  const add = (day: string, what: 'event' | 'task') => out.set(day, (out.get(day) ?? new Set()).add(what));
  for (const e of events) {
    const start = localParts(e.start, zone);
    if (start) add(start.day, 'event');
  }
  for (const t of tasks) {
    if (isDone(t)) continue;
    const day = taskDay(t, zone, today);
    if (day) add(day, 'task');
  }
  return out;
}

// -- plain days -----------------------------------------------------------------------------------------------

const toDate = (day: string) => new Date(`${day}T00:00:00Z`);
const fromDate = (d: Date) => d.toISOString().slice(0, 10);

export function addDays(day: string, n: number): string {
  const d = toDate(day);
  d.setUTCDate(d.getUTCDate() + n);
  return fromDate(d);
}

export const daysBetween = (a: string, b: string) => Math.round((toDate(b).getTime() - toDate(a).getTime()) / 86_400_000);

/** Monday = 0. */
export const weekday = (day: string) => (toDate(day).getUTCDay() + 6) % 7;

export const weekStart = (day: string) => addDays(day, -weekday(day));

export const weekOf = (day: string) => Array.from({ length: 7 }, (_, i) => addDays(weekStart(day), i));

/** The month of `day` as whole weeks, Monday first (5 or 6 rows). */
export function monthGrid(day: string): string[] {
  const first = `${day.slice(0, 7)}-01`;
  const next = addMonths(first, 1);
  const start = weekStart(first);
  const weeks = Math.floor((daysBetween(start, next) + 6) / 7);
  return Array.from({ length: weeks * 7 }, (_, i) => addDays(start, i));
}

export function addMonths(day: string, n: number): string {
  const [y, m, d] = day.split('-').map(Number);
  const month = m - 1 + n;
  const year = y + Math.floor(month / 12);
  const mm = ((month % 12) + 12) % 12;
  const last = new Date(Date.UTC(year, mm + 1, 0)).getUTCDate();
  return fromDate(new Date(Date.UTC(year, mm, Math.min(d, last))));
}

/** Side-by-side columns for overlapping items: key -> [lane, lanes in its group]. */
export function lanes(items: Item[]): Map<string, [number, number]> {
  const out = new Map<string, [number, number]>();
  let group: [Item, number][] = [];
  let ends: number[] = [];
  let groupEnd: number | null = null;
  const close = () => group.forEach(([it, lane]) => out.set(keyOf(it), [lane, ends.length]));
  const timed = items.filter((i) => i.start !== null).sort((a, b) => a.start! - b.start! || b.minutes - a.minutes);
  for (const it of timed) {
    const start = it.start!;
    const end = start + it.minutes;
    if (groupEnd !== null && start >= groupEnd) {
      close();
      group = [];
      ends = [];
      groupEnd = null;
    }
    let lane = ends.findIndex((e) => e <= start);
    if (lane < 0) {
      lane = ends.length;
      ends.push(end);
    } else ends[lane] = end;
    group.push([it, lane]);
    groupEnd = groupEnd === null ? end : Math.max(groupEnd, end);
  }
  close();
  return out;
}

/** The Task list a dated task sits on, as the PC's Today / Week / Month lists read it. */
export function horizonFor(day: string | null, today: string): 'today' | 'week' | 'month' {
  if (day === null || day <= today) return 'today';
  return daysBetween(today, day) < 7 ? 'week' : 'month';
}

/** "Today", "Tomorrow", or "Fri 24 Oct". */
export function dayLabel(day: string, today: string): string {
  const diff = daysBetween(today, day);
  if (diff === 0) return 'Today';
  if (diff === 1) return 'Tomorrow';
  if (diff === -1) return 'Yesterday';
  return toDate(day).toLocaleDateString('en-GB', { weekday: 'short', day: 'numeric', month: 'short', timeZone: 'UTC' });
}

export const clock = (minute: number) => `${String(Math.floor(minute / 60) % 24).padStart(2, '0')}:${String(minute % 60).padStart(2, '0')}`;
