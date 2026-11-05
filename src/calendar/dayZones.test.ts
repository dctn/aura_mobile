// The day's shades from a score and the person's hours.
import { describe, expect, it } from 'vitest';
import { asSchedule, dayZones, hoursRange, hoursText, levelOf, scheduleProblem, weekdayOf, zoneDay, zoneReason, type DayZone, type WorkSchedule } from './dayZones';

const OFFICE: WorkSchedule = { workdayStart: '09:00', workdayEnd: '18:00', lunchStart: '13:00', lunchMinutes: 60, workDays: [1, 2, 3, 4, 5] };
/** A Thursday and a Saturday. */
const THURSDAY = '2026-11-05';
const SATURDAY = '2026-11-07';

const clock = (m: number) => `${String(Math.floor(m / 60)).padStart(2, '0')}:${String(m % 60).padStart(2, '0')}`;
const table = (zones: DayZone[]) => zones.map((z) => `${clock(z.start)}-${clock(z.end)} ${z.kind}`);
const minutesOf = (zones: DayZone[], kind: DayZone['kind']) => zones.filter((z) => z.kind === kind).reduce((n, z) => n + z.end - z.start, 0);

describe('the amount', () => {
  it('sets the level from the score', () => {
    expect([100, 85, 84, 70, 69, 55, 54, 40, 39, 0].map(levelOf)).toEqual([
      'peak', 'peak', 'focused', 'focused', 'steady', 'steady', 'shallow', 'shallow', 'depleted', 'depleted',
    ]);
  });

  it.each([[90, 240], [75, 180], [62, 120], [45, 0], [35, 0]])('score %i gives %i minutes of heavy work', (score, heavy) => {
    expect(minutesOf(dayZones(OFFICE, score), 'heavyWork')).toBe(heavy);
  });
});

describe('the day', () => {
  it('matches the worked example for score 62', () => {
    expect(table(dayZones(OFFICE, 62))).toEqual([
      '09:00-10:30 heavyWork',
      '10:30-10:45 shortRecovery',
      '10:45-11:15 heavyWork',
      '11:15-13:00 lightWork',
      '13:00-14:00 heavyRecovery',
      '14:00-15:45 lightWork',
      '15:45-16:00 shortRecovery',
      '16:00-18:00 lightWork',
    ]);
  });

  it('carries heavy work past lunch when the morning is full', () => {
    expect(table(dayZones(OFFICE, 90)).slice(0, 7)).toEqual([
      '09:00-10:30 heavyWork',
      '10:30-10:45 shortRecovery',
      '10:45-12:15 heavyWork',
      '12:15-12:30 shortRecovery',
      '12:30-13:00 heavyWork',
      '13:00-14:00 heavyRecovery',
      '14:00-14:30 heavyWork',
    ]);
  });

  it('keeps a shallow day light, with recovery in both halves', () => {
    const zones = dayZones(OFFICE, 45);
    expect(minutesOf(zones, 'heavyWork')).toBe(0);
    expect(zones.filter((z) => z.kind === 'shortRecovery').map((z) => clock(z.start))).toEqual(['10:45', '15:45']);
  });

  it('gives a depleted day a heavy recovery in the afternoon as well as lunch', () => {
    const zones = dayZones(OFFICE, 35);
    expect(minutesOf(zones, 'heavyWork')).toBe(0);
    expect(zones.filter((z) => z.kind === 'heavyRecovery').map((z) => `${clock(z.start)}-${clock(z.end)}`)).toEqual(['13:00-14:00', '15:45-16:15']);
  });

  it.each([62, 90, 45, 35])('leaves no gap or overlap across the working day at score %i', (score) => {
    const zones = dayZones(OFFICE, score);
    expect(zones[0].start).toBe(9 * 60);
    expect(zones.at(-1)?.end).toBe(18 * 60);
    zones.slice(1).forEach((z, i) => expect(z.start).toBe(zones[i].end));
  });

  it('never leaves light work over three hours', () => {
    const long = { ...OFFICE, workdayStart: '06:00', workdayEnd: '22:00' };
    for (const z of dayZones(long, 45)) if (z.kind === 'lightWork') expect(z.end - z.start).toBeLessThanOrEqual(180);
  });

  it('fits a short day, placing only the heavy work there is room for', () => {
    const short = { ...OFFICE, workdayStart: '12:00', workdayEnd: '15:00', lunchStart: '13:00', lunchMinutes: 30 };
    const zones = dayZones(short, 90);
    expect(zones[0].start).toBe(12 * 60);
    expect(zones.at(-1)?.end).toBe(15 * 60);
    // 60 min before lunch and 90 after: a 240-minute day's heavy work doesn't fit in 150 minutes of work time
    expect(minutesOf(zones, 'heavyWork')).toBe(150);
  });

  it('handles lunch at the very start or end of the day', () => {
    expect(dayZones({ ...OFFICE, lunchStart: '09:00' }, 62)[0]).toEqual({ kind: 'heavyRecovery', start: 9 * 60, end: 10 * 60 });
    expect(dayZones({ ...OFFICE, lunchStart: '17:00' }, 62).at(-1)).toEqual({ kind: 'heavyRecovery', start: 17 * 60, end: 18 * 60 });
  });
});

describe('a day on the calendar', () => {
  it('stays blank without hours, without a score, and on a day off', () => {
    expect(zoneDay(null, 62, THURSDAY)).toEqual({ status: 'needsSchedule', zones: [], heavyMinutes: 0 });
    expect(zoneDay(OFFICE, null, THURSDAY)).toEqual({ status: 'noScore', zones: [], heavyMinutes: 0 });
    expect(zoneDay(OFFICE, 62, SATURDAY)).toEqual({ status: 'dayOff', zones: [], heavyMinutes: 0 });
  });

  it('is ready with hours and a score on a work day, and reports the heavy work placed', () => {
    const day = zoneDay(OFFICE, 62, THURSDAY);
    expect(day.status).toBe('ready');
    expect(day.heavyMinutes).toBe(120);
  });

  it('counts weekdays from Monday', () => {
    expect(weekdayOf('2026-11-02')).toBe(1);
    expect(weekdayOf('2026-11-08')).toBe(7);
  });
});

describe('the hours a page shows', () => {
  it('is an hour either side of the day, kept inside the day', () => {
    expect(hoursRange(dayZones(OFFICE, 62))).toEqual([8, 19]);
    expect(hoursRange([{ kind: 'lightWork', start: 30, end: 23 * 60 + 30 }])).toEqual([0, 24]);
    expect(hoursRange([])).toEqual([8, 18]);
  });
});

describe('the schedule', () => {
  it('finds what is wrong with it', () => {
    expect(scheduleProblem(OFFICE)).toBeNull();
    expect(scheduleProblem({ ...OFFICE, workdayEnd: '08:00' })).toMatch(/end after/);
    expect(scheduleProblem({ ...OFFICE, lunchStart: '17:30' })).toMatch(/Lunch/);
    expect(scheduleProblem({ ...OFFICE, workDays: [] })).toMatch(/work day/);
    expect(scheduleProblem({ ...OFFICE, lunchMinutes: -30 })).toMatch(/negative/);
  });

  it('reads only a usable saved value', () => {
    expect(asSchedule(OFFICE)).toEqual(OFFICE);
    expect(asSchedule(undefined)).toBeNull();
    expect(asSchedule({ workdayStart: '09:00' })).toBeNull();
    expect(asSchedule({ ...OFFICE, workdayEnd: '08:00' })).toBeNull();
  });
});

describe('the words', () => {
  it('writes the hours and the reason', () => {
    expect(hoursText(120)).toBe('2 h');
    expect(hoursText(90)).toBe('1 h 30 min');
    expect(hoursText(45)).toBe('45 min');
    expect(zoneReason(62, 120)).toBe('Your capacity score is 62, so about 2 h of heavy work today, first thing.');
    expect(zoneReason(45, 0)).toMatch(/lighter work/);
    expect(zoneReason(35, 0)).toMatch(/recovery and light work/);
  });
});
