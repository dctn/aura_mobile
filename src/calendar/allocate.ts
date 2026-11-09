// Task auto-allocation (O6) + Your sharpest hours (N13): places the day's undated to-do tasks into the free working
// hours. A faithful in-browser port of the backend, so the demo behaves as the real planner will:
//   services/planner/src/service/allocation/{place,inputs,allocate}.ts and the cost model in
//   services/capacity/src/service/engine/{demand,work,constants}.ts (commit 9b8b8bd / feature-logic-mvp).
//
// Urgent first, then the earliest due, then priority, then the hardest; 15-minute steps; 10 minutes left free after
// each block; hard work wants the good hours of the day (the person's sharpest hours when known, else the morning);
// every placement and refusal carries its reason. The capacity score sets how much the day can hold, the same score
// that shades the zones (./dayZones.ts), so the two features agree: hard tasks land in the morning heavy-work zone.
//
// Times are minutes after midnight in the demo person's zone (as the calendar draws them), not ms instants.

import { clockToMinutes, dayZones, type WorkSchedule } from './dayZones';

// -- constants (planner/src/service/evidence.ts) ------------------------------------------------------------------

export const SLOT_STEP_MIN = 15;
export const BLOCK_BUFFER_MIN = 10;
/** Below this the day's useful capacity is spent. */
export const RESERVE_FLOOR = 15;
/** A split piece is never shorter than this, so splitting makes work sessions, not slivers (the final piece may be
 *  the shorter remainder). */
export const MIN_SPLIT_PART = 30;
/** 0 = urgent ... 4 = none, from the task's priority. */
const LIGHT_DEMAND = 34;
const DEEP_DEMAND = 67;
export const DEFAULT_WORKDAY = { start: 9 * 60, end: 18 * 60 } as const;
export const DEFAULT_TASK_MIN = 30;

// -- recovery between heavy work (N13 "no burnout"): a short reset after a demanding task. Like a coach, the planner
// spaces hard work out and keeps the rest free — it does NOT schedule a task over a recovery period. The break is the
// person's chance to recover; when they do, their capacity score rises (by about RECOVERY_RESTORE), and because the
// plan reads the current score every time, the next run then fits a little more in. The activities echo the Recovery
// page's short presets; the planner rotates through them.
export const RECOVERY_RESTORE = 6;
export const RECOVERY_BREAKS: readonly { readonly title: string; readonly minutes: number }[] = [
  { title: 'Box breathing', minutes: 5 },
  { title: 'Stand and stretch', minutes: 10 },
  { title: 'Short walk', minutes: 10 },
  { title: 'Water + reset', minutes: 5 },
];

// -- the work cost (capacity/src/service/engine/{work,constants,demand}.ts) ---------------------------------------
// 6 h (360 min) of the hardest work (demand 100) costs the whole cap (CAPS.work = 4.5 points); cost per minute grows
// with demand squared (Blain 2016). Tasks cost well under a point, so capacity rarely refuses a normal to-do list;
// the day is shaped by free time, due dates, order and fit — exactly as the backend behaves.

const CAP_WORK = 4.5;
const FULL_DAY_MIN = 360;
const DEMAND_EXP = 2;
const WORK_POINTS_PER_MIN = CAP_WORK / FULL_DAY_MIN;
const clamp = (n: number, lo: number, hi: number) => Math.max(lo, Math.min(hi, n));
const round1 = (n: number) => Math.round(n * 10) / 10;

/** Points per minute of work at this demand (0-100). */
export const workRate = (demand: number) => WORK_POINTS_PER_MIN * (clamp(demand, 0, 100) / 100) ** DEMAND_EXP;
/** What `minutes` of work at `demand` would cost in one stretch, within the day's cap. */
export const stretchCost = (demand: number, minutes: number) => Math.min(CAP_WORK, workRate(demand) * Math.max(0, minutes));

/** Demand by work type (capacity WORK_TYPE_DEMAND), for a task that has no demand of its own. */
export const WORK_TYPE_DEMAND: Readonly<Record<string, number>> = {
  deepFocus: 82, detailHeavy: 75, ideas: 66, routine: 24, learning: 58,
};
const PRIORITY_RANK: Readonly<Record<string, number>> = { urgent: 0, high: 1, medium: 2, low: 3, none: 4 };

// -- types (planner place.ts) ------------------------------------------------------------------------------------

export interface AllocTask {
  readonly id: string;
  readonly title: string;
  readonly minutes: number;
  /** 0-100. */
  readonly demand: number;
  /** Capacity points it is expected to cost. */
  readonly cost: number;
  /** 0 = urgent ... 4 = none. */
  readonly priority: number;
  /** For the queue order: the due instant (ms), or null. */
  readonly dueMs: number | null;
  /** Must end by this minute of the day, when it is due on this day at a time; else null. */
  readonly dueCapMinute: number | null;
}

/** A busy stretch of the day, in minutes after midnight. */
export interface Busy {
  readonly start: number;
  readonly end: number;
}

export interface DayFrame {
  readonly dayStart: number;
  readonly dayEnd: number;
  /** No block starts before this (now, on today). */
  readonly earliest: number;
  /** The person's sharpest hours, when known (N13); null falls back to the morning. */
  readonly sharpest: Busy | null;
  /** Capacity points the plan may spend before it reaches the reserve floor. */
  readonly capacityRoom: number;
}

export interface Placed {
  readonly task: AllocTask;
  readonly start: number;
  readonly end: number;
  readonly reason: string;
  /** Set when a task was too big for one gap and split across the free windows: which piece this is. */
  readonly part?: { readonly index: number; readonly total: number };
}

/** A recovery break the planner slots in after hard work (drawn as a purple block). */
export interface Break {
  readonly start: number;
  readonly end: number;
  readonly title: string;
}

export type UnplacedReason = 'noFreeTime' | 'overCapacity' | 'tooLong';

export interface Refused {
  readonly taskId: string;
  readonly title: string;
  readonly reason: UnplacedReason;
  readonly detail: string;
}

export interface PlaceResult {
  readonly placed: Placed[];
  readonly unplaced: Refused[];
  /** Recovery breaks slotted in after heavy tasks, in time order. */
  readonly breaks: Break[];
  /** Capacity points those breaks gave back. */
  readonly restored: number;
}

export const isLight = (t: AllocTask) => t.demand < LIGHT_DEMAND;

/** Urgent first, then the earliest due, then priority, then the most demanding (planner place.ts queueOrder). */
export function queueOrder(a: AllocTask, b: AllocTask): number {
  const urgent = Number(a.priority !== 0) - Number(b.priority !== 0);
  if (urgent !== 0) return urgent;
  const due = (a.dueMs ?? Infinity) - (b.dueMs ?? Infinity);
  if (due !== 0 && !Number.isNaN(due)) return due;
  if (a.priority !== b.priority) return a.priority - b.priority;
  return b.demand - a.demand;
}

/** How good a minute is for hard work, -1 to 1: inside the sharpest hours, else the earlier in the working day. */
export function timeScore(start: number, frame: DayFrame): number {
  if (frame.sharpest) {
    if (start >= frame.sharpest.start && start < frame.sharpest.end) return 1;
    const distanceHours = Math.min(Math.abs(start - frame.sharpest.start), Math.abs(start - frame.sharpest.end)) / 60;
    return -Math.min(1, distanceHours / 3);
  }
  return 1 - (2 * (start - frame.dayStart)) / Math.max(1, frame.dayEnd - frame.dayStart);
}

/** Fit 0-100: hard work wants the good hours; light work leaves them free (50 + 30·d·t − 12·(1−d)·t). */
export function fit(task: AllocTask, start: number, frame: DayFrame): number {
  const d = task.demand / 100;
  const t = timeScore(start, frame);
  return 50 + 30 * d * t - 12 * (1 - d) * t;
}

const overlaps = (start: number, end: number, busy: readonly Busy[]) => busy.some((b) => start < b.end && end > b.start);

function latestEnd(task: AllocTask, frame: DayFrame): number {
  if (task.dueCapMinute === null || task.dueCapMinute <= frame.earliest) return frame.dayEnd;
  return Math.min(frame.dayEnd, task.dueCapMinute);
}

/** The best free start for the task, or null. Ties keep the earliest. */
export function bestStart(task: AllocTask, busy: readonly Busy[], frame: DayFrame): number | null {
  const step = SLOT_STEP_MIN;
  const length = task.minutes;
  const latest = latestEnd(task, frame);
  let best: { start: number; fit: number } | null = null;
  for (let start = Math.ceil(frame.earliest / step) * step; start + length <= latest; start += step) {
    if (overlaps(start, start + length + BLOCK_BUFFER_MIN, busy)) continue;
    const score = fit(task, start, frame);
    if (!best || score > best.fit) best = { start, fit: score };
  }
  return best?.start ?? null;
}

const clock = (minute: number) => `${String(Math.floor(minute / 60) % 24).padStart(2, '0')}:${String(Math.round(minute) % 60).padStart(2, '0')}`;

function reasonFor(task: AllocTask, start: number, frame: DayFrame, left: number): string {
  const when = clock(start);
  const sharp = frame.sharpest !== null && timeScore(start, frame) === 1;
  if (task.demand >= DEEP_DEMAND && sharp) return `${when} is inside your sharpest hours: the right place for hard work.`;
  if (task.demand >= DEEP_DEMAND && frame.sharpest) return `${when} is the best slot left, outside your sharpest hours.`;
  if (task.demand >= DEEP_DEMAND) return `${when}: early in your working day. Hard work goes first.`;
  if (isLight(task)) return `Light enough for any hour: ${when} keeps the better hours for harder work.`;
  return `${when}. Costs about ${round1(task.cost)} points, leaving ${round1(left)} above the reserve.`;
}

/** Why a task cannot go in at all, before looking for time; null when it may. */
function refusal(task: AllocTask, state: { spent: number }, frame: DayFrame): Refused | null {
  if (task.minutes > frame.dayEnd - frame.earliest) {
    return { taskId: task.id, title: task.title, reason: 'tooLong', detail: `Longer than what is left of the working day (${task.minutes} min).` };
  }
  const room = isLight(task) ? frame.capacityRoom + RESERVE_FLOOR : frame.capacityRoom;
  if (state.spent + task.cost > room) {
    const leftOver = round1(frame.capacityRoom - state.spent);
    return { taskId: task.id, title: task.title, reason: 'overCapacity', detail: `Costs about ${round1(task.cost)} points with ${leftOver} left above the ${RESERVE_FLOOR}-point reserve.` };
  }
  return null;
}

/** The free stretches of [from, to] once the busy time is taken out, in time order (merged, clipped). */
function freeWindows(blocked: readonly Busy[], from: number, to: number): Busy[] {
  const segs = blocked
    .map((b) => ({ start: Math.max(b.start, from), end: Math.min(b.end, to) }))
    .filter((b) => b.end > b.start)
    .sort((a, b) => a.start - b.start);
  const gaps: Busy[] = [];
  let cur = from;
  for (const s of segs) {
    if (s.start > cur) gaps.push({ start: cur, end: s.start });
    cur = Math.max(cur, s.end);
  }
  if (cur < to) gaps.push({ start: cur, end: to });
  return gaps;
}

/** When a task is too big for any one free gap, break it into pieces that fill the free windows in order — each on a
 *  15-minute boundary and at least MIN_SPLIT_PART (the last piece may be the shorter remainder) — up to the task's
 *  deadline. The windows already exclude the recovery periods, so no piece lands on the rest. Returns the pieces and
 *  the minutes that still would not fit before the deadline (0 when the whole task is placed). */
function splitPlace(task: AllocTask, blocked: readonly Busy[], frame: DayFrame): { pieces: Busy[]; remaining: number } {
  const latest = latestEnd(task, frame);
  const pieces: Busy[] = [];
  let remaining = task.minutes;
  for (const w of freeWindows(blocked, Math.ceil(frame.earliest / SLOT_STEP_MIN) * SLOT_STEP_MIN, latest)) {
    if (remaining <= 0) break;
    const start = Math.ceil(w.start / SLOT_STEP_MIN) * SLOT_STEP_MIN;
    const room = Math.floor((w.end - start) / SLOT_STEP_MIN) * SLOT_STEP_MIN; // usable minutes, on the grid
    if (room < Math.min(MIN_SPLIT_PART, remaining)) continue; // too small to be a useful piece
    const take = Math.min(room, remaining);
    pieces.push({ start, end: start + take });
    remaining -= take;
  }
  return { pieces, remaining };
}

/** Split a committed block (a focus / deep-work event, or a timed task that already has a start) in place around the
 *  recovery periods it crosses: keep its minutes, but lay them into the free windows from its own start — skipping the
 *  recovery (and any other blocked time) — so the rest stays free. Unlike splitPlace this is anchored at the block's
 *  start: the work is split around the recovery, not moved to the earliest gap. Returns the pieces in time order and
 *  the minutes that still would not fit before dayEnd. One piece back means it never really crossed a recovery period,
 *  so the caller leaves the block whole. */
export function splitInPlace(block: { readonly start: number; readonly minutes: number }, blocked: readonly Busy[], dayEnd: number): { pieces: Busy[]; remaining: number } {
  const from = Math.floor(block.start / SLOT_STEP_MIN) * SLOT_STEP_MIN;
  const pieces: Busy[] = [];
  let remaining = block.minutes;
  for (const w of freeWindows(blocked, from, dayEnd)) {
    if (remaining <= 0) break;
    const start = Math.ceil(w.start / SLOT_STEP_MIN) * SLOT_STEP_MIN;
    const room = Math.floor((w.end - start) / SLOT_STEP_MIN) * SLOT_STEP_MIN; // usable minutes, on the grid
    if (room <= 0) continue;
    const take = Math.min(room, remaining);
    pieces.push({ start, end: start + take });
    remaining -= take;
  }
  return { pieces, remaining };
}

/** Slot a recovery break right after a heavy task's block (when the day still has room), recording it in `breaks`, and
 *  return the minute the next block must stay clear of — the break (if any) plus the 10-min buffer. Light work gets no
 *  break, just the buffer. The caller reserves [start, returned] in `blocked`. */
function reserveBreak(task: AllocTask, end: number, breaks: Break[], frame: DayFrame): number {
  if (task.demand < DEEP_DEMAND) return end + BLOCK_BUFFER_MIN;
  const spec = RECOVERY_BREAKS[breaks.length % RECOVERY_BREAKS.length];
  if (end + spec.minutes > frame.dayEnd) return end + BLOCK_BUFFER_MIN;
  breaks.push({ start: end, end: end + spec.minutes, title: spec.title });
  return end + spec.minutes + BLOCK_BUFFER_MIN;
}

function splitReason(task: AllocTask, index: number, total: number): string {
  const tail = task.dueCapMinute !== null ? 'and still finish before it is due' : 'around your recovery time';
  return `Part ${index} of ${total}: too big for one gap, so it is split to fit ${tail}.`;
}

/** Places the tasks in queue order around the busy time; `placed` keeps that order. A short recovery break follows
 *  each heavy task when the day has room: the break is reserved so no task ever lands on it. The break does not give
 *  capacity back here — doing it raises the capacity score, which the next plan reads (N13); `restored` is how much
 *  the person stands to gain by taking all of them. A task too big for any single gap is split into pieces that fill
 *  the free windows before its deadline, so it still completes without a piece landing on the recovery time. */
export function placeTasks(tasks: readonly AllocTask[], busy: readonly Busy[], frame: DayFrame): PlaceResult {
  const blocked = [...busy];
  const state = { spent: 0 };
  const placed: Placed[] = [];
  const unplaced: Refused[] = [];
  const breaks: Break[] = [];
  for (const task of [...tasks].sort(queueOrder)) {
    const refused = refusal(task, state, frame);
    if (refused) {
      unplaced.push(refused);
      continue;
    }
    const start = bestStart(task, blocked, frame);
    if (start !== null) {
      const end = start + task.minutes;
      state.spent += task.cost;
      placed.push({ task, start, end, reason: reasonFor(task, start, frame, frame.capacityRoom - state.spent) });
      // A recovery break after hard work: slot a short reset in right after it, and keep the next block clear of the
      // break as well as the task (plus the buffer), so no task is ever placed over the rest.
      blocked.push({ start, end: reserveBreak(task, end, breaks, frame) });
      continue;
    }
    // Too big for one gap: split it across the free windows (which already exclude the recovery periods) up to its
    // deadline. If it still cannot all fit, nothing is placed — the day can't hold the whole task in time.
    const split = splitPlace(task, blocked, frame);
    if (split.remaining > 0 || split.pieces.length === 0) {
      const fitMin = task.minutes - split.remaining;
      unplaced.push({
        taskId: task.id, title: task.title, reason: 'noFreeTime',
        detail: task.dueCapMinute !== null
          ? `Even split up, only ${fitMin} of ${task.minutes} min fit before it is due.`
          : `No free ${task.minutes}-min slot left, and not enough gaps to split it into.`,
      });
      continue;
    }
    state.spent += task.cost;
    const total = split.pieces.length;
    split.pieces.forEach((pc, i) => {
      placed.push({
        task, start: pc.start, end: pc.end,
        reason: total > 1 ? splitReason(task, i + 1, total) : reasonFor(task, pc.start, frame, frame.capacityRoom - state.spent),
        ...(total > 1 ? { part: { index: i + 1, total } } : {}),
      });
      // reserve each piece plus its buffer; the final piece also carries the heavy-work recovery break.
      const guardEnd = i === total - 1 ? reserveBreak(task, pc.end, breaks, frame) : pc.end + BLOCK_BUFFER_MIN;
      blocked.push({ start: pc.start, end: guardEnd });
    });
  }
  return { placed, unplaced, breaks, restored: breaks.length * RECOVERY_RESTORE };
}

// -- building the inputs from the demo's data --------------------------------------------------------------------

type Json = Record<string, unknown>;
const num = (v: unknown): number | null => (typeof v === 'number' && Number.isFinite(v) ? v : null);

/** A task's demand: its own, else its work type's prior, else ordinary focused work (50). */
function demandOf(task: Json): number {
  const own = num(task.demand);
  if (own !== null) return clamp(own, 0, 100);
  const type = typeof task.workType === 'string' ? task.workType : null;
  return type && type in WORK_TYPE_DEMAND ? WORK_TYPE_DEMAND[type] : 50;
}

/** A task's deadline — the latest it must be done. An explicit `deadlineAt` (set from the Add-task form) wins; a task
 *  without one falls back to its scheduled `dueAt`, so older rows still order and cap as before. `hasTime` says whether
 *  the chosen moment carries a time (a hard finish-before) or is only a day. */
function deadlineOf(task: Json): { at: string; hasTime: boolean } | null {
  if (task.deadlineAt) return { at: String(task.deadlineAt), hasTime: task.deadlineHasTime === true };
  if (task.dueAt) return { at: String(task.dueAt), hasTime: task.hasTime === true };
  return null;
}

/** The target day's minute of a deadline, when it falls on that day at a time (a hard "finish before"); else null. */
function dueCapOf(task: Json, day: string, zone: string): number | null {
  const dl = deadlineOf(task);
  if (!dl || !dl.hasTime) return null;
  const parts = localDayMinute(dl.at, zone);
  return parts && parts.day === day ? parts.minute : null;
}

/** A demo task as an AllocTask (planner estimateTasks). */
export function allocTaskOf(task: Json, day: string, zone: string): AllocTask {
  const minutes = num(task.estimateMinutes) ?? DEFAULT_TASK_MIN;
  const demand = demandOf(task);
  const priority = typeof task.priority === 'string' ? PRIORITY_RANK[task.priority] ?? 4 : 4;
  const cost = num(task.costPoints) ?? stretchCost(demand, minutes);
  const dl = deadlineOf(task);
  const dueMs = dl ? Date.parse(dl.at) : NaN;
  return {
    id: String(task.id ?? ''),
    title: String(task.title || 'Task'),
    minutes,
    demand,
    cost,
    priority,
    dueMs: Number.isNaN(dueMs) ? null : dueMs,
    dueCapMinute: dueCapOf(task, day, zone),
  };
}

/** A moment's day and minute of day in `zone` (the agenda uses the same rule). */
function localDayMinute(value: string, zone: string): { day: string; minute: number } | null {
  const plain = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}/.test(value) && !/[Z+]/.test(value.slice(16));
  if (plain) return { day: value.slice(0, 10), minute: Number(value.slice(11, 13)) * 60 + Number(value.slice(14, 16)) };
  const moment = new Date(value);
  if (Number.isNaN(moment.getTime())) return null;
  const f = new Intl.DateTimeFormat('en-CA', { timeZone: zone, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' });
  const p = Object.fromEntries(f.formatToParts(moment).map((x) => [x.type, x.value]));
  return { day: `${p.year}-${p.month}-${p.day}`, minute: Number(p.hour) * 60 + Number(p.minute) };
}

/**
 * The working-day frame. The hours come from the person's work schedule (the same one the zones use), else 09:00-18:00.
 * The capacity score sets how much the day can hold (room = score − reserve); sharpest hours aren't known in the demo,
 * so hard work falls back to the morning — which lines up with the morning heavy-work zone.
 */
export function frameFor(input: { schedule: WorkSchedule | null; score: number | null; day: string; today: string; nowMinute: number | null }): DayFrame {
  const { schedule, score, day, today, nowMinute } = input;
  const dayStart = (schedule && clockToMinutes(schedule.workdayStart)) ?? DEFAULT_WORKDAY.start;
  const dayEnd = (schedule && clockToMinutes(schedule.workdayEnd)) ?? DEFAULT_WORKDAY.end;
  const earliest = day === today && nowMinute !== null ? Math.max(dayStart, nowMinute) : dayStart;
  return { dayStart, dayEnd, earliest, sharpest: null, capacityRoom: (score ?? 100) - RESERVE_FLOOR };
}

/** The lunch break as a busy stretch, so work is never placed over the heavy-recovery (lunch) zone. */
export function lunchBusy(schedule: WorkSchedule | null): Busy | null {
  if (!schedule || schedule.lunchMinutes <= 0) return null;
  const start = clockToMinutes(schedule.lunchStart);
  if (start === null) return null;
  return { start, end: start + schedule.lunchMinutes };
}

/** Every recovery stretch the zones draw for this day — the short recoveries between heavy work and the heavy
 *  recovery (lunch or a proper rest) — as busy time. Auto-allocate takes these as busy so it never schedules a task
 *  over a recovery period: the rest stays free for the person to actually recover, which is what lifts the capacity
 *  score. Supersedes lunchBusy (the lunch is a heavyRecovery zone and is included). */
export function recoveryBusy(schedule: WorkSchedule | null, score: number | null): Busy[] {
  if (!schedule) {
    const lunch = lunchBusy(schedule);
    return lunch ? [lunch] : [];
  }
  return dayZones(schedule, score ?? 100)
    .filter((z) => z.kind === 'shortRecovery' || z.kind === 'heavyRecovery')
    .map((z) => ({ start: z.start, end: z.end }));
}
