import { describe, expect, it } from 'vitest';
import * as m from '../lib/metrics';
import { FEATURES } from '../lib/catalog';
import {
  DEFAULT_PINS, MAX_PINS, age, alertAreas, checkinText, history, hours, inboxRows, insomniaMeaning, lastCheckin, logText,
  metricFields, missing, moodLowThisWeek, newestOf, pinsOf, searchHits, strengthWeek, switchAlert, togglePin, trend,
  trendWords, unread, valueText, waterGoal, waterOn, waterWeek,
} from './logic';
import { isoIn, offsetMinutes, offsetText, whenText } from './when';

describe('search', () => {
  it('reads values the way the rows show them', () => {
    expect(valueText(m.CARDS.sleep, 336)).toBe('5.6 h');
    expect(valueText(m.CARDS.heart, 62)).toBe('62 bpm');
    expect(valueText(m.CARDS.stress, 59)).toBe('59/100');
    expect(valueText(m.CARDS.stress, null)).toBe('');
  });

  it('shows everything with an empty box', () => {
    const hits = searchHits('  ', ['vo2max']);
    expect(hits.categories).toEqual([...m.CATEGORIES]);
    expect(hits.places).toEqual(['Logging', 'Modes', 'Sessions', 'Settings']);
    expect(hits.fields).toEqual([]);
  });

  it('matches extra words and any metric label', () => {
    expect(searchHits('hrv', []).categories).toEqual(['recovery']);
    expect(searchHits('WATER', []).places).toEqual(['Logging']);
    const hits = searchHits('vo2', ['vo2max', 'stepCount']);
    expect(hits.categories).toEqual([]);
    expect(hits.fields).toEqual(['vo2max']);
    // a category's own column is not listed twice
    expect(searchHits('sleep', ['totalSleepMin', 'sleepDebtMin14']).fields).toEqual(['sleepDebtMin14']);
    // the label counts, not only the column name
    expect(searchHits('time to fall', ['sleepLatencyMin']).fields).toEqual(['sleepLatencyMin']);
    expect(searchHits('zzz', ['vo2max'])).toEqual({ categories: [], places: [], fields: [] });
  });

  it('finds the newest value over all days and the numeric columns', () => {
    const days = [
      { date: '2026-09-24', metrics: { totalSleepMin: 225, risk: 'High' } },
      { date: '2026-09-29', metrics: { totalSleepMin: 465 } },
      { date: '2026-10-01', metrics: { stepCount: 10 } },
    ];
    expect(newestOf(days, 'totalSleepMin')).toEqual({ value: 465, day: '2026-09-29' });
    expect(newestOf(days, 'nothing')).toBeNull();
    const fields = metricFields(days);
    expect(fields).toContain('stepCount');
    expect(fields).toContain('vo2max');
    expect(fields).not.toContain('risk');
  });
});

describe('metric', () => {
  it('compares the last 7 days with the 7 before', () => {
    const values = [...Array(7).fill(100), ...Array(7).fill(90)];
    expect(trend(values)).toBeCloseTo(-10);
    expect(trendWords(-10)).toEqual({ direction: -1, title: 'Trending down', text: '-10%' });
    expect(trendWords(8.4)).toMatchObject({ direction: 1, title: 'Trending up', text: '+8%' });
    expect(trendWords(1.2).title).toBe('Steady');
  });

  it('needs both weeks and a non-zero week before', () => {
    expect(trend([...Array(7).fill(null), ...Array(7).fill(5)])).toBeNull();
    expect(trend([...Array(7).fill(0), ...Array(7).fill(5)])).toBeNull();
    expect(trend([1, 2])).toBeNull();
  });

  it('pins and unpins, up to six', () => {
    expect(pinsOf(null)).toEqual(DEFAULT_PINS);
    expect(togglePin(DEFAULT_PINS, 'vo2max', true).pins).toEqual([...DEFAULT_PINS, 'vo2max']);
    expect(togglePin(DEFAULT_PINS, 'recoveryScore', false).pins).not.toContain('recoveryScore');
    const six = ['a', 'b', 'c', 'd', 'e', 'f'];
    expect(six).toHaveLength(MAX_PINS);
    expect(togglePin(six, 'g', true)).toEqual({ pins: six, full: true });
    expect(togglePin(six, 'a', true)).toEqual({ pins: six, full: false });
  });
});

describe('logging', () => {
  const logs = [
    { id: '1', type: 'water', date: '2026-10-24', takenAt: '2026-10-24T09:00:00+05:30', data: { ml: 250 } },
    { id: '2', type: 'water', date: '2026-10-24', takenAt: '2026-10-24T11:00:00+05:30', data: { ml: 500 } },
    { id: '3', type: 'water', date: '2026-10-23', takenAt: '2026-10-23T11:00:00+05:30', data: { ml: 250 } },
    { id: '4', type: 'strength', date: '2026-10-22', takenAt: '2026-10-22T18:00:00+05:30', data: { minutes: 30 } },
    { id: '5', type: 'strength', date: '2026-10-10', takenAt: '2026-10-10T18:00:00+05:30', data: { minutes: 45 } },
  ];

  it("totals today's water and the week", () => {
    expect(waterOn(logs, '2026-10-24')).toBe(750);
    expect(waterOn([], '2026-10-24')).toBe(0);
    expect(waterWeek(logs, '2026-10-24')).toEqual([0, 0, 0, 0, 0, 250, 750]);
    expect(strengthWeek(logs, '2026-10-24')).toBe(30);
  });

  it('reads the water goal with a default', () => {
    expect(waterGoal({ healthGoals: { waterGoalMl: 2500 } })).toBe(2500);
    expect(waterGoal({})).toBe(2000);
  });

  it('names check-ins and logs', () => {
    expect(checkinText({ kind: 'mood', mood: 4 })).toBe('Mood 4/5');
    expect(checkinText({ kind: 'insomnia', insomnia: [1, 2, 3, 0, 0, 0, 1] })).toBe('Sleep check 7/28');
    expect(checkinText({ kind: 'insomnia', insomniaScore: 12 })).toBe('Sleep check 12/28');
    expect(logText(logs[1])).toBe('Water 500 ml');
    expect(logText(logs[3])).toBe('Strength 30 min');
  });

  it('keeps the newest check-in of a kind, and history newest first', () => {
    const checkins = [
      { id: 'a', kind: 'mood', mood: 1, takenAt: '2026-10-18T04:30:00.000Z' },
      { id: 'b', kind: 'mood', mood: 4, takenAt: '2026-10-19T03:30:00.000Z' },
      { id: 'c', kind: 'connection', connection: null, takenAt: '2026-10-20T03:30:00.000Z' },
    ];
    expect(lastCheckin(checkins, 'mood')?.id).toBe('b');
    expect(lastCheckin(checkins, 'connection')).toBeNull();
    expect(history(checkins, logs, 3).map((h) => h.id)).toEqual(['2', '1', '3']);
  });

  it('spots a low week of moods', () => {
    const low = [1, 2, 2].map((mood, i) => ({ kind: 'mood', mood, date: `2026-10-2${i}` }));
    expect(moodLowThisWeek(low, '2026-10-24')).toBe(true);
    expect(moodLowThisWeek(low.slice(0, 2), '2026-10-24')).toBe(false);
    expect(moodLowThisWeek(low, '2026-11-24')).toBe(false);
  });

  it('reads the insomnia score', () => {
    expect(insomniaMeaning(7)).toMatch(/No sign/);
    expect(insomniaMeaning(15)).toMatch(/Moderate/);
    expect(insomniaMeaning(28)).toMatch(/Severe/);
  });
});

describe('settings', () => {
  it('lists the missing profile fields in order', () => {
    expect(missing({ displayName: 'Asha Rao', birthDate: '1994-06-12', sex: null, heightCm: 165, weightKg: 61 })).toEqual(['sex']);
    expect(missing({ sex: 'notSet', displayName: '' })).toEqual(['name', 'birth date', 'height', 'weight']);
  });

  it('formats hours and age', () => {
    expect(hours(480)).toBe('8 h');
    expect(hours(465)).toBe('7 h 45 m');
    expect(age('1994-06-12', '2026-10-24')).toBe(32);
    expect(age('1994-11-12', '2026-10-24')).toBe(31);
    expect(age('', '2026-10-24')).toBeNull();
  });

  it('groups the alert switches and flips one', () => {
    const areas = alertAreas(FEATURES);
    expect(areas.map((a) => a.area)).toEqual(['Sleep', 'Recovery & stress', 'Movement', 'Mood & habits', 'Band']);
    expect(areas.flatMap((a) => a.features).length).toBe(FEATURES.filter((f) => f.hasAlert).length);
    expect(switchAlert(['DX-01'], 'DX-01', true)).toEqual([]);
    expect(switchAlert([], 'NI-04', false)).toEqual(['NI-04']);
  });
});

describe('inbox', () => {
  it('drops dismissed ones and puts the newest first', () => {
    const rows = inboxRows([
      { id: 'a', createdAt: '2026-10-24T07:58:08Z' },
      { id: 'b', createdAt: '2026-10-24T07:58:57Z', readAt: 'x' },
      { id: 'c', createdAt: '2026-10-24T08:00:00Z', status: 'dismissed' },
    ]);
    expect(rows.map((n) => n.id)).toEqual(['b', 'a']);
    expect(unread(rows)).toBe(1);
  });
});

describe('when', () => {
  const at = new Date('2026-10-24T07:59:09.316Z');

  it('builds ISO strings with the zone offset', () => {
    expect(offsetMinutes('Asia/Kolkata', at)).toBe(330);
    expect(offsetText(330)).toBe('+05:30');
    expect(offsetText(-240)).toBe('-04:00');
    expect(isoIn('Asia/Kolkata', at)).toBe('2026-10-24T13:29:09+05:30');
    expect(isoIn('UTC', at)).toBe('2026-10-24T07:59:09+00:00');
    expect(isoIn('Asia/Kolkata', at, '2026-10-20')).toBe('2026-10-20T13:29:09+05:30');
    expect(new Date(isoIn('America/New_York', at)).getTime()).toBe(Math.floor(at.getTime() / 1000) * 1000);
  });

  it('reads a moment against today', () => {
    expect(whenText('2026-10-24T04:30:00.000Z', '2026-10-24', 'Asia/Kolkata')).toBe('Today 10:00');
    expect(whenText('2026-10-23T10:00:00+05:30', '2026-10-24', 'Asia/Kolkata')).toBe('Yesterday 10:00');
    expect(whenText('2026-10-19T03:30:00.000Z', '2026-10-24', 'Asia/Kolkata')).toBe('Mon 09:00');
    expect(whenText('2026-10-01T03:30:00.000Z', '2026-10-24', 'Asia/Kolkata')).toBe('1 Oct 09:00');
    expect(whenText(null, '2026-10-24', 'Asia/Kolkata')).toBe('');
  });
});
