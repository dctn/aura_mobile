import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

// usage.ts is Recovery's own; here only which uses were noted counts
const used = vi.hoisted(() => [] as string[]);
vi.mock('../recovery/usage', () => ({ noteUse: (_aura: unknown, key: string) => used.push(key) }));
// the 1-5 sheet and the sleep check answer at once (4, and all ones); the connection and easy-day sheets answer
// with what a test sets, else are closed unanswered
const answers = vi.hoisted(() => ({ connection: null as import('../recovery/sheets').ConnectionPlan | null, easyDay: false }));
vi.mock('../recovery/sheets', async (load) => ({
  ...(await load<typeof import('../recovery/sheets')>()),
  openRating: (_nav: unknown, _kind: unknown, onPick: (v: number) => void) => onPick(4),
  openSleepCheck: (_nav: unknown, onDone: (answers: number[]) => void) => onDone([1, 1, 1, 1, 1, 1, 1]),
  openConnection: (_nav: unknown, _today: unknown, onSubmit: (plan: import('../recovery/sheets').ConnectionPlan) => void) => {
    if (answers.connection) onSubmit(answers.connection);
  },
  openEasyDay: (_nav: unknown, props: { onSubmit: (chosen: string[], earlyNight: boolean) => void }) => {
    if (answers.easyDay) props.onSubmit([], false);
  },
}));

import {
  PLAN_LATEST,
  PLAN_MORNING,
  alertWeeks,
  backfillMinutes,
  eventRow,
  finishFeature,
  hardTraining,
  hhmmMinus,
  lightTimes,
  logWater,
  moodLowThisWeek,
  nextWeekday,
  notYetPlanned,
  performAlert,
  planSlot,
  saveCheckin,
  sessionMode,
  sessionRow,
  sleepCheck,
  sleepMeaning,
  startMode,
  stressChangeText,
} from './actions';
import type { Aura } from '../app/store';
import type { Nav, Run } from '../app/nav';
import type { Mode } from './modes';
import type { Json } from './types';
import { COHERENT } from '../run/breath';
import { onScreen } from '../run/RunView';

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

describe('notYetPlanned', () => {
  const walk = { title: 'Walk · 30 min', day: '2026-10-31', minute: 9 * 60, minutes: 30 };
  it('skips an event already there with the same title and start, however its start is written', () => {
    const there = [{ id: 'e1', title: 'Walk · 30 min', start: '2026-10-31T03:30:00.000Z', end: '2026-10-31T04:00:00.000Z' }]; // 09:00 in Kolkata
    const sunday = { ...walk, day: '2026-11-01' };
    expect(notYetPlanned(there, [walk, sunday], 'Asia/Kolkata')).toEqual([sunday]);
  });
  it('keeps one at another time or with another title, and drops a repeat within the plan', () => {
    const there = [{ title: 'Walk · 30 min', start: '2026-10-31T10:00:00+05:30' }, { title: 'Run', start: '2026-10-31T09:00:00+05:30' }];
    expect(notYetPlanned(there, [walk, walk], 'Asia/Kolkata')).toEqual([walk]);
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
  it('tags an event with the feature that planned it, and only then', () => {
    const row = eventRow('Asia/Kolkata', { title: 'Nap', day: '2026-10-31', minute: 14 * 60, minutes: 10, featureId: 'DX-01' });
    expect(row.featureId).toBe('DX-01');
    expect('featureId' in eventRow('Asia/Kolkata', { title: 'Nap', day: '2026-10-31', minute: 0, minutes: 10 })).toBe(false);
  });
  it('stamps when it was planned, when given', () => {
    const at = '2026-10-24T10:07:00+05:30';
    expect(eventRow('Asia/Kolkata', { title: 'Nap', day: '2026-10-31', minute: 0, minutes: 10 }, at).createdAt).toBe(at);
    expect('createdAt' in eventRow('Asia/Kolkata', { title: 'Nap', day: '2026-10-31', minute: 0, minutes: 10 })).toBe(false);
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
    // what it was a use of, when known
    const tagged = sessionRow('Asia/Kolkata', '2026-10-24', { title: 'Nap', minutes: 10, featureId: 'DX-04', modeId: 'nap-10' }, true, 600, start, start);
    expect(tagged).toMatchObject({ featureId: 'DX-04', modeId: 'nap-10' });
  });
});

describe('finishFeature', () => {
  /** Just enough of the store and the nav: what was changed, added and said. */
  function fake(alerts: Json[], alertOff: string[] = []) {
    const changed: [string, string, Json][] = [];
    const added: [string, Json][] = [];
    const said: string[] = [];
    const aura = {
      zone: 'Asia/Kolkata',
      today: '2026-10-24',
      data: { alerts, alertOff, checkins: [] as Json[], me: {} },
      edit: {
        change: (part: string, id: string, c: Json) => changed.push([part, id, c]),
        add: (part: string, row: Json) => added.push([part, row]),
      },
    } as unknown as Aura;
    const nav = { showToast: (t: string) => said.push(t), openSheet: () => undefined } as unknown as Nav;
    return { aura, nav, changed, added, said };
  }
  const alerts = [
    { id: 'a-old', featureId: 'NI-02', date: '2026-10-20' },
    { id: 'a-new', featureId: 'NI-02', date: '2026-10-23' },
    { id: 'a-water', featureId: 'NI-04', date: '2026-10-23' },
    { id: 'a-conn', featureId: 'NI-03', date: '2026-10-22' },
  ];

  it("marks the feature's open alert (its newest) done, and nothing when none is open", () => {
    const f = fake(alerts);
    finishFeature(f.aura, 'NI-02');
    expect(f.changed).toEqual([['alerts', 'a-new', { done: true }]]);
    finishFeature(f.aura, 'DX-01');
    expect(f.changed).toHaveLength(1);
    const done = fake([{ ...alerts[1], done: true }]);
    finishFeature(done.aura, 'NI-02');
    expect(done.changed).toEqual([]);
  });

  it('a mood or connection check-in and any water log finish their own feature, wherever they come from', () => {
    const f = fake(alerts);
    saveCheckin(f.aura, f.nav, 'mood', 4);
    saveCheckin(f.aura, f.nav, 'connection', 3);
    logWater(f.aura, f.nav, 500);
    expect(f.changed.map(([, id]) => id)).toEqual(['a-new', 'a-conn', 'a-water']);
    expect(f.added.map(([part]) => part)).toEqual(['checkins', 'checkins', 'logs']);
    expect(f.said).toEqual(['Saved: mood 4/5', 'Saved: connection 3/5', 'Logged 500 ml of water']);
  });

  it("finishing twice (the alert's own done() and the log) only writes done again", () => {
    const f = fake(alerts);
    performAlert(f.aura, f.nav, alerts[2]); // NI-04's Drink: a glass, then done
    expect(f.changed).toEqual([['alerts', 'a-water', { done: true }], ['alerts', 'a-water', { done: true }]]);
  });

  it('leaves a switched-off feature alone', () => {
    const f = fake(alerts, ['NI-04']);
    logWater(f.aura, f.nav);
    expect(f.changed).toEqual([]);
  });
});

// -- modes ---------------------------------------------------------------------------------------------------------

/** A store and nav that keep what was added, said and started (one run at a time, as the phone does). */
function phone(events: Json[] = []) {
  const added: [string, Json][] = [];
  const changed: [string, string, Json][] = [];
  const said: string[] = [];
  const runs: { run: Run; countdown: boolean }[] = [];
  const data = { alerts: [] as Json[], alertOff: [] as string[], checkins: [] as Json[], events, sessions: [] as Json[], me: {} };
  const aura = {
    zone: 'Asia/Kolkata',
    today: '2026-10-24',
    data,
    local: { lastSync: null },
    edit: {
      add: (part: string, row: Json) => {
        added.push([part, row]);
        if (part === 'events') data.events = [...data.events, row];
      },
      change: (part: string, id: string, c: Json) => changed.push([part, id, c]),
      me: () => undefined,
    },
  } as unknown as Aura;
  const nav = {
    run: null as Run | null,
    counting: false,
    showToast: (t: string) => said.push(t),
    showAlert: () => undefined,
    openSheet: () => undefined,
    startRun(run: Run, countdown = false) {
      runs.push({ run, countdown });
      nav.run = run;
    },
  };
  return { aura, nav: nav as unknown as Nav & { run: Run | null }, added, changed, said, runs };
}

const mode = (m: Partial<Mode> & Pick<Mode, 'id' | 'run'>): Mode =>
  ({ name: m.id, word: m.id, shelf: 'Rest', minutes: 10, kind: 'recovery', accent: 'recovery', icon: 'timer', note: 'A note.', ...m }) as Mode;

const TIMER = mode({ id: 'timer', name: 'Timer', shelf: 'Focus', run: 'timer', minutes: 25, kind: 'focus' });
const FLOW = mode({ id: 'flow', name: 'Flow', shelf: 'Focus', run: 'flow', minutes: 0, kind: 'focus' });
const MEETING = mode({ id: 'meeting', name: 'Meeting', shelf: 'Focus', run: 'timer', minutes: 30, kind: 'meeting' });
const NAP = mode({ id: 'nap-10', name: 'Ten-minute nap', shelf: 'Sleep', run: 'timer', kind: 'nap' });
const BOX = mode({ id: 'box', name: 'Box breathing', shelf: 'Breathe', run: 'breathe', minutes: 5, pattern: { in: 4, hold: 4, out: 4, hold2: 4 } });
const GUIDED = mode({ id: 'body-scan', name: 'Body scan', shelf: 'Meditate', run: 'guided', minutes: 6, prompts: ['One', 'Two', 'Three'] });
const WIND = mode({ id: 'wind-down', name: 'Wind-down', shelf: 'Sleep', run: 'winddown', minutes: 15 });
const DAY_OFF = mode({ id: 'day-off', name: 'A whole day off', run: 'plan', minutes: 480 });
const HOBBY = mode({ id: 'hobby', name: 'Hobby', run: 'plan', minutes: 180 });

describe('startMode', () => {
  beforeEach(() => {
    used.length = 0;
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-10-24T04:37:00Z')); // 10:07 in Kolkata
  });
  afterEach(() => vi.useRealTimers());

  /** End the run as the run view does, and give back the session row it leaves. */
  const finish = (p: ReturnType<typeof phone>, completed = true) => {
    p.runs.at(-1)!.run.onEnd?.(completed, 600);
    return p.added.filter(([part]) => part === 'sessions').map(([, row]) => row).at(-1)!;
  };

  it('a Focus timer counts 3-2-1 first and saves a focus timer session under its own title', () => {
    const p = phone();
    expect(startMode(p.aura, p.nav, TIMER, { title: '  Write the deck ', minutes: 40 })).toBe(true);
    const { run, countdown } = p.runs[0];
    expect(countdown).toBe(true);
    expect(run).toMatchObject({ title: 'Write the deck', minutes: 40, night: false });
    expect(run.breathing).toBeUndefined();
    // a use only once the 3-2-1 is over and the run is on screen (Cancel on the 3-2-1 starts nothing)
    expect(used).toEqual([]);
    onScreen(run);
    onScreen(run);
    expect(used).toEqual(['mode:timer']);
    expect(finish(p)).toMatchObject({ title: 'Write the deck', kind: 'focus', mode: 'timer', plannedMinutes: 40, modeId: 'timer' });
  });

  it('a Focus start cancelled on the 3-2-1 is no use', () => {
    const p = phone();
    startMode(p.aura, p.nav, FLOW);
    expect(p.runs[0].countdown).toBe(true);
    expect(used).toEqual([]); // never on screen
  });

  it('another shelf starts at once; a nap is dark and keeps its name', () => {
    const p = phone();
    startMode(p.aura, p.nav, NAP);
    expect(p.runs[0].countdown).toBe(false);
    expect(p.runs[0].run).toMatchObject({ title: 'Ten-minute nap', minutes: 10, night: true, accent: 'recovery', note: 'A note.' });
    expect(finish(p)).toMatchObject({ title: 'Ten-minute nap', kind: 'nap', mode: 'timer', modeId: 'nap-10' });
    expect(used).toEqual(['mode:nap-10']);
  });

  it('Flow counts up and saves as flowtime; Meeting saves as meeting', () => {
    const p = phone();
    startMode(p.aura, p.nav, FLOW, { minutes: 50 });
    expect(p.runs[0].run.minutes).toBe(0);
    expect(finish(p)).toMatchObject({ mode: 'flowtime', plannedMinutes: null, kind: 'focus' });
    p.nav.run = null;
    startMode(p.aura, p.nav, MEETING);
    expect(finish(p)).toMatchObject({ mode: 'meeting', kind: 'meeting', title: 'Meeting' });
    expect(sessionMode(MEETING)).toBe('meeting');
    expect(sessionMode(NAP)).toBe('timer');
  });

  it('a breathing mode paces its own pattern; without one, Coherent', () => {
    const p = phone();
    startMode(p.aura, p.nav, BOX);
    expect(p.runs[0].run).toMatchObject({ breathing: true, pattern: { in: 4, hold: 4, out: 4, hold2: 4 }, label: 'BREATHE · 5 MIN' });
    p.nav.run = null;
    startMode(p.aura, p.nav, { ...BOX, id: 'plain', pattern: undefined });
    expect(p.runs[1].run.pattern).toEqual(COHERENT);
  });

  it('a guided meditation runs with its prompts under the clock', () => {
    const p = phone();
    startMode(p.aura, p.nav, GUIDED);
    expect(p.runs[0].run.panel).toBeTruthy();
    expect(p.runs[0].run.breathing).toBeUndefined();
    expect(p.runs[0].run.label).toBe('MEDITATE · 6 MIN');
    expect(finish(p)).toMatchObject({ title: 'Body scan', mode: 'timer' });
  });

  it('the wind-down keeps its list, for the length asked', () => {
    const p = phone();
    startMode(p.aura, p.nav, WIND, { minutes: 20 });
    expect(p.runs[0].run).toMatchObject({ title: 'Wind-down', minutes: 20 });
    expect(p.runs[0].run.panel).toBeTruthy();
    expect(used).toEqual(['mode:wind-down']);
    expect(finish(p)).toMatchObject({ title: 'Wind-down', modeId: 'wind-down' });
  });

  it('a plan mode goes on the calendar once a day', () => {
    const p = phone();
    expect(startMode(p.aura, p.nav, HOBBY)).toBe(true);
    expect(p.runs).toEqual([]);
    expect(p.added).toEqual([['events', { title: 'Hobby', start: '2026-10-24T10:15:00+05:30', end: '2026-10-24T13:15:00+05:30', createdAt: '2026-10-24T10:07:00+05:30' }]]);
    expect(p.said).toEqual(['Added to your calendar']);
    // later the same day the slot has moved on, but the title is already there that day
    vi.setSystemTime(new Date('2026-10-24T06:00:00Z'));
    startMode(p.aura, p.nav, HOBBY);
    expect(p.added).toHaveLength(1);
    expect(p.said.at(-1)).toBe('Already in your calendar');
    expect(used).toEqual(['mode:hobby', 'mode:hobby']);
  });

  it('a day off past its morning goes on tomorrow, 09:00 to 17:00', () => {
    const p = phone();
    startMode(p.aura, p.nav, DAY_OFF);
    expect(p.added[0][1]).toMatchObject({ title: 'A whole day off', start: '2026-10-25T09:00:00+05:30', end: '2026-10-25T17:00:00+05:30' });
  });

  it('nothing starts (or counts) while another run is going', () => {
    const p = phone();
    startMode(p.aura, p.nav, NAP);
    expect(startMode(p.aura, p.nav, TIMER)).toBe(false);
    expect(p.runs).toHaveLength(1);
    expect(used).toEqual(['mode:nap-10']);
  });
});

describe('planSlot', () => {
  it('takes the next quarter-hour when the block ends by 22:00, else tomorrow morning', () => {
    expect(planSlot('hobby', '2026-10-24', 10 * 60 + 7, 180)).toEqual({ day: '2026-10-24', minute: 10 * 60 + 15, minutes: 180 });
    expect(planSlot('hobby', '2026-10-24', 10 * 60 + 15, 60)).toEqual({ day: '2026-10-24', minute: 10 * 60 + 30, minutes: 60 });
    expect(planSlot('hobby', '2026-10-24', PLAN_LATEST - 60, 180)).toEqual({ day: '2026-10-25', minute: PLAN_MORNING, minutes: 180 });
    expect(planSlot('hobby', '2026-10-24', PLAN_LATEST - 75, 60).day).toBe('2026-10-24');
  });
  it('keeps the fixed slots: today while still ahead, else tomorrow', () => {
    expect(planSlot('day-off', '2026-10-24', 8 * 60, 480)).toEqual({ day: '2026-10-24', minute: 9 * 60, minutes: 480 });
    expect(planSlot('alcohol-free-evening', '2026-10-24', 17 * 60, 0)).toEqual({ day: '2026-10-24', minute: 18 * 60, minutes: 240 });
    expect(planSlot('alcohol-free-evening', '2026-10-24', 18 * 60, 240).day).toBe('2026-10-25');
  });
});

describe('noting uses', () => {
  beforeEach(() => {
    used.length = 0;
    answers.connection = null;
    answers.easyDay = false;
  });

  it('each log notes its feature once', () => {
    const p = phone();
    logWater(p.aura, p.nav);
    saveCheckin(p.aura, p.nav, 'mood', 4);
    saveCheckin(p.aura, p.nav, 'connection', 3);
    sleepCheck(p.aura, p.nav);
    expect(used).toEqual(['feature:NI-04', 'feature:NI-02', 'feature:NI-03', 'feature:DX-03']);
  });

  it("an alert's verb notes its feature, real or Recovery's stand-in", () => {
    const p = phone();
    performAlert(p.aura, p.nav, { id: 'a1', featureId: 'DX-04' }); // the nap
    p.nav.run = null;
    performAlert(p.aura, p.nav, { featureId: 'PB-03' }); // a stand-in: no id
    expect(used).toEqual(['feature:DX-04', 'feature:PB-03']);
  });

  it('a verb that logs (Drink, a check-in) is noted once, by the log', () => {
    const p = phone();
    performAlert(p.aura, p.nav, { id: 'w', featureId: 'NI-04' });
    performAlert(p.aura, p.nav, { id: 'm', featureId: 'NI-02' });
    expect(used).toEqual(['feature:NI-04', 'feature:NI-02']);
  });

  it('notes nothing for a sheet closed, a Not now, or a run refused while another is going', () => {
    const p = phone();
    performAlert(p.aura, p.nav, { id: 's', featureId: 'DX-02' }); // the wake-time sheet, closed
    performAlert(p.aura, p.nav, { id: 'b', featureId: 'PB-05' }); // Not now on the check-up
    performAlert(p.aura, p.nav, { id: 'r', featureId: 'DX-05' }); // the easy-day sheet, closed
    p.nav.run = { title: 'Walk' } as Run;
    performAlert(p.aura, p.nav, { id: 'n', featureId: 'DX-04' }); // the nap, while a walk runs
    expect(used).toEqual([]);
    expect(p.changed).toEqual([]);
  });

  it('a plan is one use when something was added, however many events, and none when all was there', () => {
    const p = phone();
    performAlert(p.aura, p.nav, { id: 'l', featureId: 'PB-01' });
    const events = p.added.filter(([part]) => part === 'events').map(([, e]) => e);
    expect(events.length).toBeGreaterThan(1);
    expect(events.every((e) => e.featureId === 'PB-01' && e.createdAt === events[0].createdAt)).toBe(true);
    performAlert(p.aura, p.nav, { featureId: 'PB-01' }); // again: it is all there
    expect(p.said.at(-1)).toBe('Already in your calendar');
    expect(used).toEqual(['feature:PB-01']);
  });

  it("a feature's timed run is noted when it starts and saved as that feature's session", () => {
    const p = phone();
    performAlert(p.aura, p.nav, { id: 'n', featureId: 'DX-04' });
    expect(used).toEqual(['feature:DX-04']);
    p.runs[0].run.onEnd?.(true, 600);
    const session = p.added.find(([part]) => part === 'sessions')![1];
    expect(session).toMatchObject({ title: 'Nap', featureId: 'DX-04' });
    expect('modeId' in session).toBe(false);
  });

  it("DX-05's easy day is a use once submitted, and its recovery break is DX-05's session", () => {
    answers.easyDay = true;
    const p = phone();
    performAlert(p.aura, p.nav, { id: 'r', featureId: 'DX-05' });
    expect(used).toEqual(['feature:DX-05']);
    expect(p.changed).toEqual([['alerts', 'r', { done: true }]]);
    expect(p.runs[0].run.title).toBe('Recovery break');
    p.runs[0].run.onEnd?.(false, 60);
    expect(p.added.find(([part]) => part === 'sessions')![1]).toMatchObject({ featureId: 'DX-05' });
  });

  it('NI-03: a call or meal added and the check-in after it are one use; with nothing added, the check-in is', () => {
    answers.connection = { who: 'Sam', call: true, meal: true, day: '2026-10-25', time: '19:00' };
    const p = phone();
    performAlert(p.aura, p.nav, { id: 'c', featureId: 'NI-03' });
    expect(p.added.map(([part]) => part)).toEqual(['tasks', 'events', 'checkins']);
    expect(used).toEqual(['feature:NI-03']);
    answers.connection = { who: 'Sam', call: false, meal: false, day: '2026-10-25', time: '19:00' };
    performAlert(p.aura, p.nav, { id: 'c2', featureId: 'NI-03' });
    expect(used).toEqual(['feature:NI-03', 'feature:NI-03']);
  });
});
