import { describe, expect, it } from 'vitest';
import {
  alertWeeks,
  backfillMinutes,
  eventRow,
  hardTraining,
  hhmmMinus,
  lightTimes,
  moodLowThisWeek,
  nextWeekday,
  sessionRow,
  sleepMeaning,
  stressChangeText,
} from './actions';

describe('hhmmMinus', () => {
  it('takes minutes off a time of day', () => {
    expect(hhmmMinus('23:00', 30)).toBe('22:30');
    expect(hhmmMinus('07:15', 30)).toBe('06:45');
  });
  it('wraps past midnight', () => {
    expect(hhmmMinus('00:10', 30)).toBe('23:40');
  });
  it('counts anything unreadable as 23:00', () => {
    expect(hhmmMinus(undefined, 30)).toBe('22:30');
    expect(hhmmMinus('late', 30)).toBe('22:30');
  });
});

describe('nextWeekday', () => {
  it('is 1 to 7 days on', () => {
    expect(nextWeekday('2026-10-24', 5)).toBe('2026-10-31'); // a Saturday: the next one
    expect(nextWeekday('2026-10-22', 5)).toBe('2026-10-24'); // Thursday -> Saturday
    expect(nextWeekday('2026-10-25', 0)).toBe('2026-10-26'); // Sunday -> Monday
  });
});

describe('lightTimes', () => {
  it('plans light 30 min after waking and dim screens at 21:00 for three days', () => {
    const plan = lightTimes('2026-10-24', 6 * 60, '07:00');
    expect(plan).toEqual([
      { title: 'Outdoor light · 15 min', day: '2026-10-24', minute: 7 * 60 + 30, minutes: 15 },
      { title: 'Dim screens', day: '2026-10-24', minute: 21 * 60, minutes: 30 },
      { title: 'Outdoor light · 15 min', day: '2026-10-25', minute: 7 * 60 + 30, minutes: 15 },
      { title: 'Dim screens', day: '2026-10-25', minute: 21 * 60, minutes: 30 },
      { title: 'Outdoor light · 15 min', day: '2026-10-26', minute: 7 * 60 + 30, minutes: 15 },
      { title: 'Dim screens', day: '2026-10-26', minute: 21 * 60, minutes: 30 },
    ]);
  });
  it("skips today's morning once it has passed", () => {
    const plan = lightTimes('2026-10-24', 13 * 60, '07:00');
    expect(plan[0].title).toBe('Dim screens');
    expect(plan).toHaveLength(5);
  });
  it('keeps the morning between 07:30 and 09:30', () => {
    expect(lightTimes('2026-10-24', 0, '05:00')[0].minute).toBe(7 * 60 + 30);
    expect(lightTimes('2026-10-24', 0, '10:15')[0].minute).toBe(9 * 60 + 30);
    expect(lightTimes('2026-10-24', 0, 'x')[0].minute).toBe(7 * 60 + 30);
  });
});

describe('moodLowThisWeek', () => {
  const mood = (day: string, value: number) => ({ kind: 'mood', mood: value, takenAt: `${day}T09:00:00+05:30` });
  it('needs three moods in 7 days averaging 2 or less', () => {
    expect(moodLowThisWeek([mood('2026-10-18', 1), mood('2026-10-19', 2), mood('2026-10-24', 3)], '2026-10-24')).toBe(true);
    expect(moodLowThisWeek([mood('2026-10-18', 1), mood('2026-10-19', 2)], '2026-10-24')).toBe(false);
    expect(moodLowThisWeek([mood('2026-10-18', 3), mood('2026-10-19', 2), mood('2026-10-24', 3)], '2026-10-24')).toBe(false);
  });
  it('ignores older and other check-ins', () => {
    const rows = [mood('2026-10-10', 1), mood('2026-10-11', 1), mood('2026-10-24', 1), { kind: 'connection', connection: 1, takenAt: '2026-10-24T09:00:00Z' }];
    expect(moodLowThisWeek(rows, '2026-10-24')).toBe(false);
  });
});

describe('alertWeeks', () => {
  it('counts the ISO weeks a feature came up in', () => {
    const alerts = [
      { featureId: 'DX-03', date: '2026-10-05' },
      { featureId: 'DX-03', date: '2026-10-07' },
      { featureId: 'DX-03', date: '2026-10-13' },
      { featureId: 'DX-03', date: '2026-10-19' },
      { featureId: 'DX-04', date: '2026-10-26' },
      { featureId: 'DX-03', date: 'never' },
    ];
    expect(alertWeeks(alerts, 'DX-03')).toBe(3);
    expect(alertWeeks(alerts, 'DX-04')).toBe(1);
  });
});

describe('backfillMinutes', () => {
  const now = new Date('2026-10-24T12:00:00Z').getTime();
  it('is the gap since the last sync, 30 min to 12 h', () => {
    expect(backfillMinutes(null, now)).toBe(30);
    expect(backfillMinutes('2026-10-24T11:50:00Z', now)).toBe(30);
    expect(backfillMinutes('2026-10-24T10:00:00Z', now)).toBe(120);
    expect(backfillMinutes('2026-10-20T10:00:00Z', now)).toBe(720);
  });
});

describe('hardTraining', () => {
  it("finds today's and tomorrow's training, earliest first", () => {
    const events = [
      { id: 'b', title: 'Strength session', start: '2026-10-25T18:00:00+05:30' },
      { id: 'a', title: 'Gym', start: '2026-10-24T07:00:00+05:30' },
      { id: 'c', title: 'Team lunch', start: '2026-10-24T13:00:00+05:30' },
      { id: 'd', title: 'Long run', start: '2026-10-26T07:00:00+05:30' },
      { id: 'e', title: 'Workout', start: '2026-10-23T07:00:00+05:30' },
    ];
    expect(hardTraining(events, '2026-10-24', 'Asia/Kolkata').map((e) => e.id)).toEqual(['a', 'b']);
  });
});

describe('copy', () => {
  it('reads an insomnia score', () => {
    expect(sleepMeaning(5)).toBe('No sign of insomnia.');
    expect(sleepMeaning(14)).toMatch(/^Some trouble/);
    expect(sleepMeaning(21)).toMatch(/^Moderate/);
    expect(sleepMeaning(22)).toMatch(/^Severe/);
  });
  it('tells the stress change', () => {
    expect(stressChangeText({ stressDrop: 7.4 })).toBe('Stress fell 7 points: the 15 min before against the last 10 min of the session.');
    expect(stressChangeText({ stressDrop: 0 })).toMatch(/didn't drop/);
    expect(stressChangeText({ status: 'noData' })).toMatch(/No band data/);
    expect(stressChangeText(null)).toMatch(/overnight/);
  });
});

describe('rows', () => {
  it('writes an event in the zone', () => {
    expect(eventRow('Asia/Kolkata', { title: 'Walk · 30 min', day: '2026-10-31', minute: 9 * 60, minutes: 30 })).toEqual({
      title: 'Walk · 30 min',
      start: '2026-10-31T09:00:00+05:30',
      end: '2026-10-31T09:30:00+05:30',
    });
  });
  it('writes a session', () => {
    const start = new Date('2026-10-24T07:00:00Z').getTime();
    const row = sessionRow('Asia/Kolkata', '2026-10-24', { title: 'Walk', minutes: 20, kind: 'walk', mode: 'walk' }, false, 310, start, start + 310_000);
    expect(row).toEqual({
      kind: 'walk',
      mode: 'walk',
      plannedMinutes: 20,
      title: 'Walk',
      device: 'phone',
      status: 'stopped',
      startedAt: '2026-10-24T12:30:00+05:30',
      endedAt: '2026-10-24T12:35:10+05:30',
      actualMinutes: 5,
    });
    expect(sessionRow('Asia/Kolkata', '2026-10-24', { title: 'Flowtime', minutes: 0 }, true, 60, start, start).plannedMinutes).toBe(null);
  });
});
