import { describe, expect, it } from 'vitest';
import type { Json } from '../lib/types';
import {
  DERIVED, STRENGTH_DAYS, backToBack, bmi, calorieGoal, eatingWindow, entries, entryAt, entryText, fastingMinutes, freeGaps, isFocus, longestStreak, mealTotals,
  mealsOn, meetingMinutes, meetingsOn, overworkStreak, strengthDays, todayCount, type Ctx,
} from './derive';
import { averageMs, digitRow } from './QuickCheck';
import { whenText } from '../search/when';

const zone = 'Asia/Kolkata';
const today = '2026-10-24'; // a Saturday

const log = (type: string, date: string, time: string, data: Json, id = `${type}-${date}-${time}`): Json => ({ id, type, date, takenAt: `${date}T${time}:00+05:30`, data });
const meal = (date: string, time: string, data: Json) => log('meal', date, time, data);
const event = (kind: string, start: string, end: string, title = kind): Json => ({ id: `${kind}-${start}`, kind, title, start, end });
const session = (mode: string, startedAt: string, actualMinutes: number, plannedMinutes: number | null = actualMinutes, kind = 'focus'): Json => ({ kind, mode, startedAt, actualMinutes, plannedMinutes });

const ctx = (part: Partial<Ctx>): Ctx => ({ logs: [], events: [], sessions: [], me: {}, today, zone, ...part });
const show = (id: string, c: Ctx) => DERIVED[id](c);

describe('nutrition', () => {
  const logs = [
    meal('2026-10-23', '20:30', { meal: 'dinner', kcal: 700, time: '20:30' }),
    meal(today, '08:05', { meal: 'breakfast', kcal: 400, protein: 20, carbs: 50, fat: 15, time: '08:00', photo: true }),
    meal(today, '13:10', { meal: 'lunch', kcal: 600, protein: 30, carbs: 70, fat: 20, time: '13:00' }),
    meal(today, '16:30', { meal: 'snack', kcal: 200, protein: 10, carbs: 20, fat: 5 }), // no time typed: when it was logged
    log('water', today, '09:00', { ml: 250 }),
  ];

  it('totals today by meal and macro', () => {
    const t = mealTotals(mealsOn(logs, today, zone));
    expect(t).toMatchObject({ kcal: 1200, protein: 60, carbs: 140, fat: 40, count: 3, photos: 1, first: 480, last: 990 });
    expect(t.byMeal).toEqual({ breakfast: 400, lunch: 600, snack: 200, dinner: 0 });
    expect(t.largest?.meal).toBe('lunch');
    expect(eatingWindow(t)).toBe(510);
    expect(eatingWindow(mealTotals(mealsOn(logs, '2026-10-23', zone)))).toBeNull(); // one meal has no window
  });

  it('measures the overnight fast from yesterday’s last meal', () => {
    expect(fastingMinutes(logs, today, zone)).toBe(24 * 60 - (20 * 60 + 30) + 8 * 60);
    expect(fastingMinutes(logs, '2026-10-23', zone)).toBeNull();
  });

  it('reads the calorie goal from the profile, else 2000', () => {
    expect(calorieGoal({})).toBe(2000);
    expect(calorieGoal({ healthGoals: { calorieGoal: 1500 } })).toBe(1500);
    expect(show('calorieGoal', ctx({ logs }))).toBe('60% of 2,000');
    expect(show('calorieGoal', ctx({ logs, me: { healthGoals: { calorieGoal: 1500 } } }))).toBe('80% of 1,500');
  });

  it('shows the rows', () => {
    const c = ctx({ logs });
    expect(show('kcalToday', c)).toBe('1,200 kcal');
    expect(show('kcalDinner', c)).toBe('None yet');
    expect(show('protein', c)).toBe('60 g');
    expect(show('mealTiming', c)).toBe('08:00 – 16:30');
    expect(show('eatingWindow', c)).toBe('8 h 30 m');
    expect(show('fasting', c)).toBe('11 h 30 m');
    expect(show('proteinPer100', c)).toBe('5 g');
    expect(show('mealPhotos', c)).toBe('1');
    expect(show('largestMeal', c)).toBe('Lunch · 600 kcal');
    expect(show('kcalToday', ctx({}))).toBe('None yet');
  });
});

describe('meetings', () => {
  // IST is UTC+5:30: 04:00Z is 09:30 there
  const events = [
    event('meeting', '2026-10-24T04:00:00Z', '2026-10-24T04:30:00Z'), // 09:30-10:00
    event('meeting', '2026-10-24T04:30:00Z', '2026-10-24T05:00:00Z'), // 10:00-10:30, back-to-back
    event('meeting', '2026-10-24T08:30:00Z', '2026-10-24T09:30:00Z'), // 14:00-15:00
    event('focus', '2026-10-24T05:30:00Z', '2026-10-24T06:30:00Z'), // not a meeting
    event('meeting', '2026-10-25T04:00:00Z', '2026-10-25T05:00:00Z'), // tomorrow (Sunday)
  ];
  const sessions = [session('timer', '2026-10-24T11:00:00+05:30', 25), session('flowtime', '2026-10-24T15:00:00+05:30', 50, null)];

  it('finds the day’s meetings and its free stretches', () => {
    const items = meetingsOn(events, today, zone);
    expect(items.map((i) => i.start)).toEqual([570, 600, 840]);
    expect(meetingMinutes(items)).toBe(120);
    expect(backToBack(items)).toBe(1);
    expect(freeGaps(items)).toEqual([30, 210, 180]);
  });

  it('shows the rows', () => {
    const c = ctx({ events, sessions });
    expect(show('meetingsToday', c)).toBe('3');
    expect(show('meetingTime', c)).toBe('2 h');
    expect(show('backToBack', c)).toBe('1');
    expect(show('freeBlocks', c)).toBe('2');
    expect(show('fragmentation', c)).toBe('1 short gap');
    expect(show('makerScore', c)).toBe('72/100'); // 390 of 540 minutes in 2-hour stretches
    expect(show('meetingLoad', c)).toBe('22% of 9–6');
    expect(show('heaviestDay', c)).toBe('Sat · 2 h');
    expect(show('meetingFocusRatio', c)).toBe('1.6 : 1'); // 120 meeting minutes to 75 focus
    expect(show('meetingFocusRatio', ctx({ events }))).toBe('No focus yet');
    expect(show('freeBlocks', ctx({}))).toBe('1'); // an empty day is one long block
  });
});

describe('focus and work-life', () => {
  const sessions = [
    session('timer', '2026-10-24T11:00:00+05:30', 25),
    session('flowtime', '2026-10-24T15:00:00+05:30', 50, null),
    session('timer', '2026-10-23T10:00:00+05:30', 45),
    session('timer', '2026-10-21T10:00:00+05:30', 10, 25), // stopped early: not deep work
    session('timer', '2026-10-20T20:00:00+05:30', 30), // after 19:00
    session('walk', '2026-10-24T08:00:00+05:30', 20, 20, 'walk'), // not focus
    session('timer', '2026-10-24T09:00:00+05:30', 10, 10, 'recovery'), // a Recovery break runs as a timer: not focus
    session('timer', '2026-09-01T10:00:00+05:30', 30), // outside the 30 days
  ];
  const c = ctx({ sessions });

  it('counts focus time from the timer and flowtime sessions', () => {
    expect(show('focusToday', c)).toBe('1 h 15 m');
    expect(show('focus30', c)).toBe('2 h 40 m');
    expect(show('deepWorkHours', c)).toBe('1.3 h');
    expect(show('sessions7', c)).toBe('5');
    expect(show('focusStreak', c)).toBe('2 days');
    expect(show('bestDay', c)).toBe('1 h 15 m');
    expect(show('worstDay', c)).toBe('10 min');
    expect(show('deepWorkWeek', c)).toBe('2 h 30 m');
    expect(show('timedLength', c)).toBe('31 min');
    expect(show('flowtimeUsed', c)).toBe('1 time in 7 days');
    expect(show('focusStreak', ctx({}))).toBe('0 days');
  });

  it('counts only kind focus, not every timer', () => {
    expect(isFocus({ kind: 'focus', mode: 'timer' })).toBe(true);
    expect(isFocus({ kind: 'focus', mode: 'flowtime' })).toBe(true);
    expect(isFocus({ kind: 'recovery', mode: 'timer' })).toBe(false);
    expect(isFocus({ kind: 'walk', mode: 'timer' })).toBe(false);
    const breaks = ctx({ sessions: [session('timer', '2026-10-24T10:00:00+05:30', 10, 10, 'recovery')] });
    expect(show('focusToday', breaks)).toBe('0 min');
    expect(show('sessions7', breaks)).toBe('0');
  });

  it('works out the week’s balance', () => {
    expect(show('deepWorkAvg', c)).toBe('21 min a day');
    expect(show('boundaryScore', c)).toBe('86/100'); // 6 of 7 days kept 07:00-19:00
    expect(show('boundaryScore', ctx({}))).toBe('None yet'); // no focus or meeting: nothing to score
    expect(show('longestFocusStreak', c)).toBe('2 days');
    expect(longestStreak(new Set(['2026-10-01', '2026-10-02', '2026-10-03', '2026-10-10']))).toBe(3);
    const logs = [log('shutdown', today, '18:00', { value: true }), log('shutdown', '2026-10-22', '18:00', { value: true }), log('shutdown', '2026-10-01', '18:00', { value: true })];
    expect(show('shutdownDiscipline', ctx({ logs }))).toBe('2 of 7 days');
  });

  it('counts days in a row of 9 hours or more', () => {
    const long = [session('timer', '2026-10-24T08:00:00+05:30', 480), session('timer', '2026-10-23T08:00:00+05:30', 540), session('timer', '2026-10-22T08:00:00+05:30', 600)];
    const meetingToday = [event('meeting', '2026-10-24T11:00:00Z', '2026-10-24T12:00:00Z')];
    expect(overworkStreak(ctx({ sessions: long }))).toBe(2); // today's 8 hours don't count; yesterday and before do
    expect(overworkStreak(ctx({ sessions: long, events: meetingToday }))).toBe(3);
    expect(show('overworkStreak', ctx({}))).toBe('0 days');
  });
});

describe('strength and body', () => {
  const logs = [
    log('lift', today, '18:00', { exercise: 'Squat', group: 'Legs', sets: 3, reps: 8, kg: 60 }),
    log('lift', '2026-10-22', '18:00', { exercise: 'Bench press', group: 'Chest', sets: 3, reps: 10, kg: 40 }),
    log('lift', '2026-10-10', '18:00', { exercise: 'Deadlift', group: 'Back', sets: 1, reps: 5, kg: 100 }),
    log('strength', '2026-10-23', '18:00', { minutes: 30 }), // Recovery's strength minutes
  ];

  it('totals the week of lifts', () => {
    const c = ctx({ logs });
    expect(show('volume', c)).toBe('2,640 kg');
    expect(show('heaviest', c)).toBe('60 kg · Squat');
    expect(show('groupCount', c)).toBe('2');
    expect(show('groupList', c)).toBe('Legs, Chest');
    expect(show('liftEntries', c)).toBe('2');
    // against the WHO's 2 days a week, the target Home's Strength pill uses
    expect(STRENGTH_DAYS).toBe(2);
    expect(show('strengthDays', c)).toBe('3 of 2 days');
    expect(show('strengthDays', ctx({ logs: [] }))).toBe('0 of 2 days');
  });

  it('works out BMI from the newest weight, else the profile', () => {
    expect(bmi(61, 165)).toBe(22.4);
    expect(bmi(0, 165)).toBeNull();
    const me = { weightKg: 61, heightCm: 165 };
    expect(show('bmi', ctx({ me }))).toBe('22.4 · healthy');
    expect(show('bmi', ctx({ me, logs: [log('weight', today, '07:00', { value: 70 })] }))).toBe('25.7 · overweight');
    expect(show('bmi', ctx({}))).toBe('Needs height and weight');
    expect(show('bodyRecord', ctx({ logs: [log('weight', '2026-10-22', '07:00', { value: 70 }), log('bodyFat', '2026-10-22', '07:01', { value: 24 })] }))).toBe('2 days ago · 2 readings');
  });
});

describe('entries', () => {
  const logs = [
    log('energy', today, '09:00', { value: 4 }),
    log('meal', today, '13:00', { meal: 'lunch', kcal: 600 }),
    log('lift', today, '18:00', { exercise: 'Squat', sets: 3, reps: 8, kg: 60 }),
    log('dietPattern', today, '10:00', { value: 'Vegan' }),
    log('shutdown', today, '18:30', { value: true }),
    log('tvTime', '2026-10-23', '22:00', { value: 90 }),
    log('weight', '2026-10-23', '07:00', { value: 61.5 }),
    log('quickCheck', '2026-10-23', '11:00', { reactionMs: 312, span: 6 }),
    log('parkIt', '2026-10-01', '11:00', { text: 'Call the bank' }),
    log('water', today, '08:00', { ml: 250 }),
  ];

  it('reads each kind in plain words', () => {
    expect(logs.slice(0, 8).map(entryText)).toEqual([
      'Energy 4/5', 'Lunch 600 kcal', 'Squat 3×8 at 60 kg', 'Diet pattern: Vegan', 'Shutdown checklist: done today',
      'TV time 1 h 30 m', 'Body weight 61.5 kg', 'Quick check 312 ms · span 6',
    ]);
  });

  it("lists the week's logbook entries newest first, without Recovery's logs", () => {
    const week = entries(logs, today, zone);
    expect(week.map((x) => x.type)).toEqual(['shutdown', 'lift', 'meal', 'dietPattern', 'energy', 'tvTime', 'quickCheck', 'weight']);
    expect(entries(logs, today, zone, 7, 'nutrition').map((x) => x.type)).toEqual(['meal', 'dietPattern']);
    expect(todayCount(logs, today, zone, 'nutrition')).toBe(2);
    expect(todayCount(logs, today, zone, 'body')).toBe(0);
  });

  it("shows a meal at the time typed on it, on its own day; anything else at when it was logged", () => {
    const typed = log('meal', today, '13:40', { meal: 'breakfast', kcal: 300, time: '8:15' });
    expect(entryAt(typed, zone)).toBe('2026-10-24T08:15');
    expect(whenText(entryAt(typed, zone), today, zone)).toBe('Today 08:15');
    const plain = log('energy', '2026-10-23', '09:00', { value: 4 });
    expect(entryAt(plain, zone)).toBe(plain.takenAt);
    expect(whenText(entryAt(plain, zone), today, zone)).toBe('Yesterday 09:00');
  });

  it("puts a row without a date on its takenAt's day in the person's zone", () => {
    // 19:30 UTC on the 23rd is 01:00 on the 24th in Kolkata; a UTC slice would call it yesterday
    const late = [{ id: 'e', type: 'energy', takenAt: '2026-10-23T19:30:00Z', data: { value: 3 } }];
    expect(entries(late, today, zone, 1)).toHaveLength(1);
    expect(entries(late, today, 'UTC', 1)).toHaveLength(0);
    const lifts = [{ id: 'l', type: 'lift', takenAt: '2026-10-17T19:00:00Z', data: { sets: 1, reps: 1, kg: 1 } }]; // 00:30 on the 18th
    expect(strengthDays(lifts, today, zone)).toBe(1);
    expect(strengthDays(lifts, today, 'UTC')).toBe(0);
  });
});

describe('quick check', () => {
  it('makes digit rows and averages the taps', () => {
    const row = digitRow(6);
    expect(row).toMatch(/^\d{6}$/);
    expect(/(\d)\1/.test(row)).toBe(false);
    expect(averageMs([300, 320, 341])).toBe(320);
    expect(averageMs([])).toBe(0);
  });
});
