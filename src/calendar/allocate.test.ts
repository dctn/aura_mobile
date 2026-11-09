// The day's undated tasks placed into the free working hours.
import { describe, expect, it } from 'vitest';
import {
  allocTaskOf, bestStart, BLOCK_BUFFER_MIN, fit, frameFor, isLight, lunchBusy, placeTasks, queueOrder,
  RECOVERY_BREAKS, RECOVERY_RESTORE, recoveryBusy, splitInPlace, stretchCost, timeScore, workRate,
  type AllocTask, type Busy, type DayFrame,
} from './allocate';
import type { WorkSchedule } from './dayZones';

const OFFICE: WorkSchedule = { workdayStart: '09:00', workdayEnd: '18:00', lunchStart: '13:00', lunchMinutes: 60, workDays: [1, 2, 3, 4, 5] };
// 540 = 09:00, 1080 = 18:00.
const FRAME: DayFrame = { dayStart: 540, dayEnd: 1080, earliest: 540, sharpest: null, capacityRoom: 85 };

const task = (id: string, over: Partial<AllocTask> = {}): AllocTask => ({
  id, title: id, minutes: 30, demand: 50, cost: 0.2, priority: 2, dueMs: null, dueCapMinute: null, ...over,
});

const order = (...tasks: AllocTask[]) => [...tasks].sort(queueOrder).map((t) => t.id);

describe('the queue order', () => {
  it('puts urgent (priority 0) first, whatever else', () => {
    expect(order(task('later', { priority: 2, dueMs: 100 }), task('urgent', { priority: 0, dueMs: 9999 }))).toEqual(['urgent', 'later']);
  });

  it('then takes the earliest due', () => {
    expect(order(task('late', { dueMs: 500 }), task('soon', { dueMs: 100 }))).toEqual(['soon', 'late']);
  });

  it('then the lower priority number', () => {
    expect(order(task('low', { priority: 3 }), task('high', { priority: 1 }))).toEqual(['high', 'low']);
  });

  it('then the higher demand', () => {
    expect(order(task('soft', { demand: 30 }), task('hard', { demand: 80 }))).toEqual(['hard', 'soft']);
  });
});

describe('the time score (no sharpest hours)', () => {
  it('runs 1 at the start, 0 at the midpoint, -1 at the end', () => {
    expect(timeScore(540, FRAME)).toBeCloseTo(1);
    expect(timeScore(810, FRAME)).toBeCloseTo(0);
    expect(timeScore(1080, FRAME)).toBeCloseTo(-1);
  });

  it('rates an earlier minute higher', () => {
    expect(timeScore(600, FRAME)).toBeGreaterThan(timeScore(900, FRAME));
  });
});

describe('the time score (with sharpest hours)', () => {
  const sharp: DayFrame = { ...FRAME, sharpest: { start: 600, end: 720 } };

  it('is 1 inside the sharpest window and negative outside', () => {
    expect(timeScore(660, sharp)).toBe(1);
    expect(timeScore(540, sharp)).toBeLessThan(0);
    expect(timeScore(540, sharp)).toBeCloseTo(-1 / 3);
  });

  it('clamps to -1 far from the window', () => {
    expect(timeScore(900, sharp)).toBe(-1);
  });
});

describe('the fit', () => {
  it('matches the formula 50 + 30·d·t − 12·(1−d)·t', () => {
    // demand 80 at the start: t = 1 -> 50 + 30*0.8 - 12*0.2 = 71.6
    expect(fit(task('x', { demand: 80 }), 540, FRAME)).toBeCloseTo(71.6);
  });

  it('scores a heavy task higher earlier in the day', () => {
    const heavy = task('heavy', { demand: 80 });
    expect(fit(heavy, 540, FRAME)).toBeGreaterThan(fit(heavy, 1080, FRAME));
  });

  it('keeps a light task near 50 all day', () => {
    const light = task('light', { demand: 20 });
    for (const start of [540, 810, 1080]) expect(Math.abs(fit(light, start, FRAME) - 50)).toBeLessThan(5);
  });
});

describe('the best start', () => {
  it('is the earliest 15-min step for a heavy task with an empty day', () => {
    expect(bestStart(task('h', { demand: 80 }), [], FRAME)).toBe(540);
    expect(bestStart(task('h', { demand: 80 }), [], { ...FRAME, earliest: 545 })).toBe(555);
  });

  it('returns null when nothing fits the window', () => {
    expect(bestStart(task('big', { minutes: 600 }), [], FRAME)).toBeNull();
  });

  it('pushes the task past a busy block at the start', () => {
    const busy: Busy[] = [{ start: 540, end: 600 }];
    expect(bestStart(task('h', { demand: 80, minutes: 30 }), busy, FRAME)).toBe(600);
  });

  it('respects the 10-min buffer, skipping a gap too small for block + buffer', () => {
    // 60-min gap (540-600) can't hold a 55-min block plus its 10-min buffer, so it lands after the busy.
    const busy: Busy[] = [{ start: 600, end: 660 }];
    expect(bestStart(task('h', { demand: 80, minutes: 55 }), busy, FRAME)).toBe(660);
  });

  it('respects dueCapMinute as a latest end', () => {
    const busy: Busy[] = [{ start: 540, end: 690 }];
    // Without a cap the task fits right after the busy block (690 ends at 735)...
    expect(bestStart(task('h', { demand: 80, minutes: 45 }), busy, FRAME)).toBe(690);
    // ...but a 720 cap leaves no end before it, so nothing fits.
    expect(bestStart(task('h', { demand: 80, minutes: 45, dueCapMinute: 720 }), busy, FRAME)).toBeNull();
  });
});

describe('placing the tasks', () => {
  it('places in queue order, urgent first, with no overlap', () => {
    const { placed } = placeTasks([task('plain', { priority: 2 }), task('urgent', { priority: 0 })], [], FRAME);
    expect(placed.map((p) => p.task.id)).toEqual(['urgent', 'plain']);
    const [a, b] = placed;
    expect(a.start).toBe(540);
    expect(a.end).toBe(570);
    expect(b.start).toBe(585);
    // each placed block reserves its minutes plus the 10-min buffer before the next.
    expect(a.end + BLOCK_BUFFER_MIN).toBeLessThanOrEqual(b.start);
    expect(b.start).toBeGreaterThanOrEqual(a.end);
  });

  it('refuses a task longer than the working day with tooLong', () => {
    const { placed, unplaced } = placeTasks([task('marathon', { minutes: 600 })], [], FRAME);
    expect(placed).toHaveLength(0);
    expect(unplaced[0]).toMatchObject({ taskId: 'marathon', reason: 'tooLong' });
  });

  it('refuses a task with no free slot with noFreeTime', () => {
    const busy: Busy[] = [{ start: 540, end: 1080 }];
    const { placed, unplaced } = placeTasks([task('nowhere')], busy, FRAME);
    expect(placed).toHaveLength(0);
    expect(unplaced[0]).toMatchObject({ taskId: 'nowhere', reason: 'noFreeTime' });
  });
});

describe('recovery breaks between heavy work', () => {
  const heavy = (id: string, over: Partial<AllocTask> = {}) => task(id, { demand: 80, ...over });

  it('slots a short break in right after a heavy task', () => {
    const { placed, breaks } = placeTasks([heavy('deep')], [], FRAME);
    expect(placed).toHaveLength(1);
    expect(breaks).toHaveLength(1);
    const first = RECOVERY_BREAKS[0];
    // the break starts the moment the heavy block ends, and runs for its own length.
    expect(breaks[0]).toEqual({ start: placed[0].end, end: placed[0].end + first.minutes, title: first.title });
  });

  it('leaves a light task with no break', () => {
    const { breaks } = placeTasks([task('easy', { demand: 20 })], [], FRAME);
    expect(breaks).toHaveLength(0);
  });

  it('reserves the break so the next task never lands on it', () => {
    const { placed, breaks } = placeTasks([heavy('hard'), task('after', { demand: 50 })], [], FRAME);
    expect(placed.map((p) => p.task.id)).toEqual(['hard', 'after']);
    const [first, second] = placed;
    const brk = breaks[0];
    // the next block clears the break too, with the 10-min buffer after it.
    expect(second.start).toBeGreaterThanOrEqual(brk.end + BLOCK_BUFFER_MIN);
    expect(first.end).toBeLessThanOrEqual(brk.start);
  });

  it('rotates through the recovery activities for several heavy tasks', () => {
    const { breaks } = placeTasks([heavy('h1'), heavy('h2')], [], FRAME);
    expect(breaks).toHaveLength(2);
    const sorted = [...breaks].sort((a, b) => a.start - b.start);
    expect(sorted.map((b) => b.title)).toEqual([RECOVERY_BREAKS[0].title, RECOVERY_BREAKS[1].title]);
  });

  it('reports the capacity those breaks give back', () => {
    const { breaks, restored } = placeTasks([heavy('h1'), heavy('h2')], [], FRAME);
    expect(restored).toBe(breaks.length * RECOVERY_RESTORE);
  });
});

describe('splitting a task too big for one gap', () => {
  const mid: Busy[] = [{ start: 720, end: 780 }]; // a meeting over the middle of the day

  it('splits a big task into pieces that fill the gaps around a busy block', () => {
    // 360 min is longer than either free window (540–720 and 780–1080), so it cannot go in one block.
    const { placed } = placeTasks([task('report', { minutes: 360 })], mid, FRAME);
    expect(placed).toHaveLength(2);
    expect(placed.map((p) => [p.start, p.end])).toEqual([[540, 720], [780, 960]]);
    expect(placed.map((p) => p.part)).toEqual([{ index: 1, total: 2 }, { index: 2, total: 2 }]);
    // the whole task is scheduled, and no piece runs over the busy block.
    expect(placed.reduce((s, p) => s + (p.end - p.start), 0)).toBe(360);
    expect(placed.every((p) => p.end <= 720 || p.start >= 780)).toBe(true);
  });

  it('skips a window too small to be a useful piece', () => {
    const busy: Busy[] = [{ start: 555, end: 705 }, { start: 855, end: 900 }]; // leaves a 15-min sliver up front
    const { placed } = placeTasks([task('big', { minutes: 300 })], busy, FRAME);
    expect(placed).toHaveLength(2);
    // the 540–555 sliver is skipped; the pieces fill the two usable windows.
    expect(placed.map((p) => [p.start, p.end])).toEqual([[705, 855], [900, 1050]]);
  });

  it('lets the final piece be the shorter remainder', () => {
    // three windows (540–720, 780–900, 960–1080), each too small for the whole 315-min task.
    const twoMeetings: Busy[] = [{ start: 720, end: 780 }, { start: 900, end: 960 }];
    const { placed } = placeTasks([task('tail', { minutes: 315 })], twoMeetings, FRAME);
    expect(placed.map((p) => [p.start, p.end])).toEqual([[540, 720], [780, 900], [960, 975]]);
    expect(placed[2].end - placed[2].start).toBe(15); // under MIN_SPLIT_PART, allowed as the remainder
  });

  it('keeps every split piece out of the recovery periods and before the day ends', () => {
    const rec = recoveryBusy(OFFICE, 100);
    const { placed } = placeTasks([task('long', { minutes: 300 })], rec, FRAME);
    expect(placed.length).toBeGreaterThan(1);
    for (const p of placed) {
      expect(rec.some((z) => p.start < z.end && p.end > z.start)).toBe(false);
      expect(p.end).toBeLessThanOrEqual(FRAME.dayEnd);
    }
  });

  it('flags a big task that cannot finish before its deadline, even split', () => {
    const { placed, unplaced } = placeTasks([task('due', { minutes: 300, dueCapMinute: 720 })], [], FRAME);
    expect(placed).toHaveLength(0);
    expect(unplaced[0]).toMatchObject({ taskId: 'due', reason: 'noFreeTime' });
    expect(unplaced[0].detail).toContain('180 of 300');
  });

  it('does not split a task that fits one gap', () => {
    const { placed } = placeTasks([task('solo', { minutes: 60 })], mid, FRAME);
    expect(placed).toHaveLength(1);
    expect(placed[0].part).toBeUndefined();
  });
});

describe('splitInPlace (a committed block around recovery)', () => {
  // A short recovery band from 630 to 645 (10:30–10:45), like the zones draw.
  const rec: Busy[] = [{ start: 630, end: 645 }];

  it('splits a block that crosses a recovery band, anchored at its own start', () => {
    // Deep work 10:00–12:00 (600–720) over the 10:30 break.
    const { pieces, remaining } = splitInPlace({ start: 600, minutes: 120 }, rec, 1080);
    expect(pieces.map((p) => [p.start, p.end])).toEqual([[600, 630], [645, 735]]);
    expect(remaining).toBe(0);
  });

  it('leaves a block that crosses nothing as a single whole piece from its start', () => {
    const { pieces, remaining } = splitInPlace({ start: 780, minutes: 60 }, rec, 1080);
    expect(pieces.map((p) => [p.start, p.end])).toEqual([[780, 840]]);
    expect(remaining).toBe(0);
  });

  it('flows the tail past later blocked time, not through it', () => {
    // A meeting at 11:00–11:30 (660–690) also stands in the way after the recovery.
    const blocked: Busy[] = [...rec, { start: 660, end: 690 }];
    const { pieces } = splitInPlace({ start: 600, minutes: 120 }, blocked, 1080);
    // 30 min before the break, the 15-min sliver between break and meeting, then the 75-min tail after the meeting.
    expect(pieces.map((p) => [p.start, p.end])).toEqual([[600, 630], [645, 660], [690, 765]]);
    for (const p of pieces) expect(blocked.some((b) => p.start < b.end && p.end > b.start)).toBe(false);
  });

  it('reports minutes that will not fit before the day ends', () => {
    const { pieces, remaining } = splitInPlace({ start: 1020, minutes: 120 }, [], 1080);
    expect(pieces).toEqual([{ start: 1020, end: 1080 }]);
    expect(remaining).toBe(60);
  });
});

describe('the work cost model', () => {
  it('costs the hardest work 4.5/360 points per minute', () => {
    expect(workRate(100)).toBeCloseTo(4.5 / 360);
    expect(workRate(100)).toBeCloseTo(0.0125);
  });

  it('is free at demand 0', () => {
    expect(workRate(0)).toBe(0);
  });

  it('scales with the square of demand', () => {
    expect(workRate(50)).toBeCloseTo(workRate(100) / 4);
  });

  it('caps a long stretch at the whole work cap', () => {
    expect(stretchCost(100, 360)).toBeCloseTo(4.5);
    expect(stretchCost(100, 1000)).toBeCloseTo(4.5);
  });
});

describe('isLight', () => {
  it('is true only below demand 34', () => {
    expect(isLight(task('a', { demand: 33 }))).toBe(true);
    expect(isLight(task('b', { demand: 34 }))).toBe(false);
  });
});

describe('allocTaskOf', () => {
  const ZONE = 'Asia/Kolkata';
  const DAY = '2026-11-08';

  it('reads a full demo task, capping a same-day timed due to its minute', () => {
    const out = allocTaskOf(
      { id: 't1', title: 'Write', estimateMinutes: 45, demand: 70, priority: 'high', costPoints: 1.2, dueAt: '2026-11-08T14:30', hasTime: true },
      DAY, ZONE,
    );
    expect(out).toMatchObject({ id: 't1', title: 'Write', minutes: 45, demand: 70, priority: 1, cost: 1.2, dueCapMinute: 870 });
    expect(out.dueMs).toBe(Date.parse('2026-11-08T14:30'));
  });

  it('defaults the minutes to 30 and falls back to the work-type demand', () => {
    const out = allocTaskOf({ id: 't2', workType: 'deepFocus' }, DAY, ZONE);
    expect(out.minutes).toBe(30);
    expect(out.demand).toBe(82);
    expect(out.priority).toBe(4);
    expect(out.cost).toBeCloseTo(stretchCost(82, 30));
    expect(out.dueCapMinute).toBeNull();
  });

  it('falls back to demand 50 with no demand or work type', () => {
    expect(allocTaskOf({ id: 't3' }, DAY, ZONE).demand).toBe(50);
  });

  it('leaves dueCapMinute null without a time or off the day', () => {
    expect(allocTaskOf({ id: 'a', dueAt: '2026-11-08T14:30', hasTime: false }, DAY, ZONE).dueCapMinute).toBeNull();
    expect(allocTaskOf({ id: 'b', dueAt: '2026-11-09T14:30', hasTime: true }, DAY, ZONE).dueCapMinute).toBeNull();
  });

  it('uses an explicit deadline (deadlineAt) for the cap and the queue order, over any scheduled dueAt', () => {
    // An untimed to-do carrying a hard finish-before: caps at that minute even though it has no dueAt.
    const hard = allocTaskOf({ id: 'd1', deadlineAt: '2026-11-08T16:00', deadlineHasTime: true }, DAY, ZONE);
    expect(hard.dueCapMinute).toBe(960);
    expect(hard.dueMs).toBe(Date.parse('2026-11-08T16:00'));
    // A day-only deadline orders the queue but sets no hard cap.
    const soft = allocTaskOf({ id: 'd2', deadlineAt: '2026-11-08T00:00', deadlineHasTime: false }, DAY, ZONE);
    expect(soft.dueCapMinute).toBeNull();
    expect(soft.dueMs).toBe(Date.parse('2026-11-08T00:00'));
    // The deadline wins over a scheduled dueAt (they are separate fields in the demo model).
    const both = allocTaskOf({ id: 'd3', dueAt: '2026-11-08T11:00', hasTime: true, deadlineAt: '2026-11-08T16:00', deadlineHasTime: true }, DAY, ZONE);
    expect(both.dueCapMinute).toBe(960);
  });
});

describe('frameFor', () => {
  it('uses the default 09:00-18:00 workday without a schedule', () => {
    const frame = frameFor({ schedule: null, score: 70, day: '2026-11-08', today: '2026-11-07', nowMinute: null });
    expect(frame.dayStart).toBe(540);
    expect(frame.dayEnd).toBe(1080);
    expect(frame.sharpest).toBeNull();
  });

  it('sets the capacity room to score - 15, defaulting the score to 100', () => {
    expect(frameFor({ schedule: null, score: 70, day: 'd', today: 'd', nowMinute: null }).capacityRoom).toBe(55);
    expect(frameFor({ schedule: null, score: null, day: 'd', today: 'd', nowMinute: null }).capacityRoom).toBe(85);
  });

  it('starts no earlier than now on today, but at the day start on another day', () => {
    expect(frameFor({ schedule: null, score: 100, day: '2026-11-08', today: '2026-11-08', nowMinute: 600 }).earliest).toBe(600);
    expect(frameFor({ schedule: null, score: 100, day: '2026-11-08', today: '2026-11-07', nowMinute: 600 }).earliest).toBe(540);
  });

  it('reads the hours from the schedule when given', () => {
    const frame = frameFor({ schedule: { ...OFFICE, workdayStart: '08:00', workdayEnd: '16:00' }, score: 100, day: 'd', today: 'd', nowMinute: null });
    expect(frame.dayStart).toBe(480);
    expect(frame.dayEnd).toBe(960);
  });
});

describe('lunchBusy', () => {
  it('returns the lunch window as a busy stretch', () => {
    expect(lunchBusy(OFFICE)).toEqual({ start: 780, end: 840 });
  });

  it('is null without a schedule or with no lunch', () => {
    expect(lunchBusy(null)).toBeNull();
    expect(lunchBusy({ ...OFFICE, lunchMinutes: 0 })).toBeNull();
  });
});

describe('recoveryBusy', () => {
  it('reserves the lunch (heavy recovery) and any short-recovery zones as real stretches', () => {
    const busy = recoveryBusy(OFFICE, 90); // peak day: heavy blocks with short recoveries between them
    expect(busy).toContainEqual({ start: 780, end: 840 }); // lunch 13:00-14:00 is always reserved
    expect(busy.length).toBeGreaterThan(1); // plus the short recoveries between heavy work
    expect(busy.every((b) => b.end > b.start)).toBe(true);
  });

  it('falls back to just the lunch when there is no usable schedule', () => {
    expect(recoveryBusy(null, 90)).toEqual([]);
  });

  it('keeps every placed task out of the recovery periods', () => {
    const busy = recoveryBusy(OFFICE, 90);
    const frame = frameFor({ schedule: OFFICE, score: 90, day: 'd', today: 'x', nowMinute: null });
    const { placed } = placeTasks(
      [task('hard', { demand: 80, minutes: 60 }), task('easy', { demand: 20, minutes: 45 })], busy, frame,
    );
    expect(placed.length).toBeGreaterThan(0);
    const overlapsRecovery = placed.some((p) => busy.some((r) => p.start < r.end && p.end > r.start));
    expect(overlapsRecovery).toBe(false);
  });
});
