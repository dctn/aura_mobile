import { describe, expect, it } from 'vitest';
import {
  BACKFILL_MAX, BACKFILL_MIN, DEFAULT_PINS, MAX_PINS, WIND_DOWN, backfillMinutes, burnoutRisk, eventRun, isRecoveryEvent, lastSync,
  missing, numericFields, pinsOf, seeAllAt, syncText, todayItems, togglePin, weeklyMinutes, type TodayData, type TodayItem,
} from './homeParts';
import { findMode } from '../lib/modes';

const Z = 'Asia/Kolkata';

describe('pins', () => {
  it('defaults when the visitor has not chosen', () => {
    expect(pinsOf(null)).toEqual(DEFAULT_PINS);
    expect(pinsOf([])).toEqual([]);
    expect(pinsOf(['a', 'b', 'c', 'd', 'e', 'f', 'g'])).toHaveLength(MAX_PINS);
  });

  it('lists the scalar fields, known ones first', () => {
    expect(numericFields({ zeta: 1, recoveryScore: 80, totalSleepMin: 400, series: '[1,2]', flag: true, gone: null })).toEqual([
      'totalSleepMin', 'recoveryScore', 'flag', 'gone', 'zeta',
    ]);
  });

  it('ticks up to six', () => {
    expect(togglePin(['a'], 'a')).toEqual([]);
    expect(togglePin(['a'], 'b')).toEqual(['a', 'b']);
    const full = ['a', 'b', 'c', 'd', 'e', 'f'];
    expect(togglePin(full, 'g')).toBe(full);
  });
});

describe('burnout', () => {
  const day = (date: string, score: number) => ({ date, metrics: { burnoutIndexScore: score } });
  it("uses the server's word, else buckets the newest score", () => {
    expect(burnoutRisk([], { metrics: { burnoutIndexRisk: 'moderate' } }, '2026-10-24')).toBe('Moderate');
    expect(burnoutRisk([day('2026-10-23', 75)], null, '2026-10-24')).toBe('High');
    expect(burnoutRisk([day('2026-10-20', 20), day('2026-10-23', 45)], null, '2026-10-24')).toBe('Moderate');
    expect(burnoutRisk([], null, '2026-10-24')).toBe('No data yet');
  });
});

describe('profile gaps', () => {
  it('lists unset body details', () => {
    expect(missing({ displayName: 'Asha', birthDate: '1994-06-12', sex: null, heightCm: 165, weightKg: '' })).toEqual(['sex', 'weight']);
    expect(missing({ displayName: 'A', birthDate: 'x', sex: 'notSet', heightCm: 1, weightKg: 1 })).toEqual([]);
    expect(missing(null)).toEqual([]);
  });
});

describe('the band line', () => {
  const now = new Date('2026-10-24T08:00:00Z'); // 13:30 in Kolkata

  it('prefers the visitor Reconnect, else the newest computedAt', () => {
    expect(lastSync('2026-10-24T07:00:00Z', [], null)).toBe('2026-10-24T07:00:00Z');
    expect(lastSync(null, [{ computedAt: '2026-10-22T01:00:00Z' }, { computedAt: '2026-10-23T01:00:00Z' }], null)).toBe('2026-10-23T01:00:00Z');
    expect(lastSync(null, [], null)).toBeNull();
  });

  it('says when, in the zone', () => {
    expect(syncText(null, now, Z)).toEqual({ text: 'Not synced', stale: false });
    expect(syncText('2026-10-24T05:12:00Z', now, Z)).toEqual({ text: 'Synced 10:42', stale: false });
    expect(syncText('2026-10-23T17:00:00Z', now, Z)).toEqual({ text: 'Synced Fri 22:30', stale: false });
    expect(syncText('2026-10-22T05:00:00Z', now, Z)).toEqual({ text: 'Not synced since Thu', stale: true });
  });

  it('sends 30 min to 12 h on Reconnect', () => {
    expect(backfillMinutes(null, now)).toBe(BACKFILL_MIN);
    expect(backfillMinutes('2026-10-24T07:00:00Z', now)).toBe(60);
    expect(backfillMinutes('2026-10-20T07:00:00Z', now)).toBe(BACKFILL_MAX);
  });
});

describe('movement', () => {
  it('reads weeklyActiveMinutes', () => {
    expect(weeklyMinutes({ metrics: { weeklyActiveMinutes: 76.6 } })).toBe(76);
    expect(weeklyMinutes(null)).toBe(0);
  });
});

describe('seeAllAt', () => {
  it('puts one See all at the top for alerts waiting in Recovery, else at the bottom when the cap hides pills', () => {
    expect(seeAllAt(3, 1, 4)).toBe('top');
    expect(seeAllAt(3, 1, 9)).toBe('top'); // never both
    expect(seeAllAt(2, 2, 9)).toBe('bottom');
    expect(seeAllAt(2, 2, 6)).toBeNull();
    expect(seeAllAt(0, 0, 0)).toBeNull();
  });
});

describe("today's to-dos", () => {
  const today = '2026-10-24';
  const now = new Date('2026-10-24T08:00:00Z'); // 13:30 in Kolkata
  // a day with both check-ins, the water and this week's strength done, so each test adds only what it is about
  const base = (): TodayData => ({
    alerts: [],
    alertOff: [],
    checkins: [
      { kind: 'mood', mood: 4, date: today, takenAt: `${today}T08:00:00+05:30` },
      { kind: 'connection', connection: 3, takenAt: `${today}T09:00:00+05:30` },
    ],
    logs: [
      { type: 'water', date: today, data: { ml: 2000 } },
      { type: 'strength', date: '2026-10-20', data: { minutes: 30 } },
      { type: 'strength', date: '2026-10-22', data: { minutes: 30 } },
    ],
    events: [],
    me: {},
  });
  const keys = (items: TodayItem[]) => items.map((i) => i.key);
  const at = (hhmm: string) => `${today}T${hhmm}:00+05:30`;

  it('is empty when everything is done', () => {
    expect(todayItems(base(), today, Z, now)).toEqual([]);
  });

  it("shows today's open alerts only, unless hidden", () => {
    const data = {
      ...base(),
      alerts: [
        { id: 'a1', featureId: 'DX-04', date: today },
        { id: 'a2', featureId: 'DX-07', date: '2026-10-23' },
        { id: 'a3', featureId: 'PB-03', date: today, done: true },
      ],
    };
    expect(keys(todayItems(data, today, Z, now))).toEqual(['alert:a1']);
    expect(todayItems(data, today, Z, now, true)).toEqual([]);
    expect(todayItems({ ...data, alertOff: ['DX-04'] }, today, Z, now)).toEqual([]);
  });

  it('asks for mood and connection until given today', () => {
    const data = { ...base(), checkins: [{ kind: 'mood', mood: 3, date: '2026-10-23' }] as TodayData['checkins'] };
    expect(keys(todayItems(data, today, Z, now))).toEqual(['checkin:mood', 'checkin:connection']);
    data.checkins.push({ kind: 'mood', mood: 4, takenAt: at('07:00') });
    expect(keys(todayItems(data, today, Z, now))).toEqual(['checkin:connection']);
  });

  it("lets today's check-in or water alert stand in for its pill", () => {
    const data = {
      ...base(),
      checkins: [],
      logs: [],
      alerts: [{ id: 'm', featureId: 'NI-02', date: today }, { id: 'w', featureId: 'NI-04', date: today }],
    };
    expect(keys(todayItems(data, today, Z, now))).toEqual(['alert:m', 'alert:w', 'checkin:connection', 'strength']);
  });

  it('offers the sleep check after 3 weeks of trouble sleeping, then not for 14 days', () => {
    const alerts = ['2026-10-05', '2026-10-12', '2026-10-19'].map((date, i) => ({ id: `s${i}`, featureId: 'DX-03', date, done: true }));
    const data = { ...base(), alerts };
    expect(keys(todayItems(data, today, Z, now))).toEqual(['checkin:sleep']);
    expect(todayItems({ ...data, alerts: alerts.slice(1) }, today, Z, now)).toEqual([]);
    const within = { ...data, checkins: [...data.checkins, { kind: 'insomnia', date: '2026-10-11' }] };
    expect(todayItems(within, today, Z, now)).toEqual([]);
    const before = { ...data, checkins: [...data.checkins, { kind: 'insomnia', date: '2026-10-10' }] };
    expect(keys(todayItems(before, today, Z, now))).toEqual(['checkin:sleep']);
    // DX-03 switched off in Settings: no sleep check either
    expect(todayItems({ ...data, alertOff: ['DX-03'] }, today, Z, now)).toEqual([]);
  });

  it('counts water against the goal', () => {
    const data = { ...base(), logs: [...base().logs.slice(1), { type: 'water', date: today, data: { ml: 750 } }] };
    expect(todayItems(data, today, Z, now)).toEqual([{ kind: 'water', key: 'water', ml: 750, goal: 2000 }]);
    expect(todayItems({ ...data, me: { healthGoals: { waterGoalMl: 500 } } }, today, Z, now)).toEqual([]);
  });

  it('wants strength on 2 days of the last 7', () => {
    const water = base().logs[0];
    const strength = (date: string) => ({ type: 'strength', date, data: { minutes: 20 } });
    const items = (...dates: string[]) => todayItems({ ...base(), logs: [water, ...dates.map(strength)] }, today, Z, now);
    expect(items('2026-10-24', '2026-10-24')).toEqual([{ kind: 'strength', key: 'strength', days: 1 }]);
    expect(items('2026-10-17', '2026-10-23')).toEqual([{ kind: 'strength', key: 'strength', days: 1 }]);
    expect(items('2026-10-18', '2026-10-23')).toEqual([]);
    // the logbook's lift entries count too, on the day of their takenAt in the zone when they have no date
    const lift = { type: 'lift', takenAt: '2026-10-21T19:00:00Z', data: { exercise: 'Squat', sets: 3, reps: 8, kg: 60 } }; // 00:30 on the 22nd
    expect(todayItems({ ...base(), logs: [water, strength('2026-10-23'), lift] }, today, Z, now)).toEqual([]);
    expect(todayItems({ ...base(), logs: [water, lift] }, today, Z, now)).toEqual([{ kind: 'strength', key: 'strength', days: 1 }]);
  });

  it("lists today's recovery events not yet over, nearest first, with their Start", () => {
    const events = [
      { id: 'e1', title: 'Dim screens', start: at('21:00'), end: at('21:30') },
      { id: 'e2', title: 'Team sync', start: at('15:00'), end: at('16:00'), featureId: 'PB-01' },
      { id: 'e3', title: 'Walk · 30 min', start: at('14:00'), end: at('14:30') },
      { id: 'e4', title: 'Nap', start: at('13:20'), end: at('13:40') }, // under way
      { id: 'e5', title: 'Outdoor light · 15 min', start: at('08:00'), end: at('08:15') }, // over
      { id: 'e6', title: 'Brisk session · 30 min', start: '2026-10-25T18:00:00+05:30', end: '2026-10-25T18:30:00+05:30' }, // tomorrow
      { id: 'e7', title: 'Dentist', start: at('17:00'), end: at('17:30') }, // not planned by a recovery action
    ];
    const items = todayItems({ ...base(), events }, today, Z, now);
    expect(keys(items)).toEqual(['event:e4', 'event:e3', 'event:e2', 'event:e1']);
    expect(items.map((i) => (i.kind === 'event' ? [i.item.start, i.end, i.run] : null))).toEqual([
      [13 * 60 + 20, 13 * 60 + 40, 'nap-10'],
      [14 * 60, 14 * 60 + 30, 'urban-walk'],
      [15 * 60, 16 * 60, null],
      [21 * 60, 21 * 60 + 30, null],
    ]);
  });

  it('keeps the order: alerts, events, check-ins, water, strength', () => {
    const data: TodayData = {
      alerts: [{ id: 'a', featureId: 'DX-07', date: today }],
      alertOff: [],
      checkins: [],
      logs: [],
      events: [{ id: 'e', title: 'Wind-down', start: at('22:00'), end: at('22:10') }],
      me: {},
    };
    expect(keys(todayItems(data, today, Z, now))).toEqual(['alert:a', 'event:e', 'checkin:mood', 'checkin:connection', 'water', 'strength']);
  });

  it('knows a recovery event and what its Start runs', () => {
    expect(isRecoveryEvent({ title: 'Meal with Sam' })).toBe(true);
    expect(isRecoveryEvent({ title: 'Lunch', featureId: 'NI-03' })).toBe(true);
    expect(isRecoveryEvent({ title: 'Walk the dog' })).toBe(false);
    // the titles the recovery actions write, to the mode their Start runs
    expect(eventRun('Recovery day: keep it light')).toBe('recovery-break');
    expect(eventRun('Brisk session · 30 min')).toBe('zone2');
    expect(eventRun('Outdoor light · 15 min')).toBe('morning-light');
    expect(eventRun('Walk · 30 min')).toBe('urban-walk');
    expect(eventRun('Nap')).toBe('nap-10');
    expect(eventRun('Wind-down')).toBe(WIND_DOWN);
    // a mode's own name or an old preset title
    expect(eventRun('Easy walk')).toBe('urban-walk');
    expect(eventRun('Aerobic session')).toBe('zone2');
    // only shown: no mode by that name (not by id either), or a plan mode, which is a calendar block itself
    expect(eventRun('Strength · 40 min')).toBeNull();
    expect(eventRun('Meal with Sam')).toBeNull();
    expect(eventRun(findMode('day-off')!.name)).toBeNull();
  });

  it("every Start is a mode in the list, which Home starts for the event's own length", () => {
    const titles = ['Recovery day: keep it light', 'Brisk session · 30 min', 'Outdoor light · 15 min', 'Walk · 30 min', 'Nap', 'Wind-down', 'Easy walk'];
    for (const title of titles) expect(findMode(eventRun(title)!), title).toBeDefined();
    expect(findMode(eventRun('Wind-down')!)!.run).toBe('winddown');
    expect(findMode(eventRun('Brisk session · 30 min')!)).toBe(findMode('Aerobic session')); // the old preset title, now zone2
  });
});
