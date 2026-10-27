// What Recovery puts first: use scores from noted uses and existing rows, Often used, the group and mode order.
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Aura } from '../app/store';
import { EMPTY, addUse } from '../lib/local';
import { MODE_LIST } from '../lib/modes';
import type { Json } from '../lib/types';
import { RECOVERY_FEATURES, noteUse, oftenUsed, orderGroups, scoresFor, topModes, useScores, usedOf, type UseKey, type UseScore } from './usage';

const NOW = new Date('2026-10-27T12:00:00Z');
const ago = (days: number, extraMs = 0) => new Date(NOW.getTime() - days * 86_400_000 + extraMs).toISOString();
const rows = (parts: Partial<Record<'sessions' | 'logs' | 'checkins' | 'events', Json[]>> = {}) => ({ sessions: [], logs: [], checkins: [], events: [], ...parts });
const scoreOf = (scores: Map<UseKey, UseScore>, key: UseKey) => scores.get(key)?.score ?? 0;

describe('useScores', () => {
  it('counts a noted use and the row it wrote within 90 s once, one row per noted use', () => {
    const w = 0.5 ** (1 / 7);
    const log = (ms: number) => ({ type: 'water', takenAt: ago(1, ms), data: { ml: 250 } });
    expect(scoreOf(useScores(rows({ logs: [log(20_000)] }), { 'feature:NI-04': [ago(1)] }, NOW), 'feature:NI-04')).toBeCloseTo(w, 3);
    // 100 s apart: two uses
    expect(scoreOf(useScores(rows({ logs: [log(100_000)] }), { 'feature:NI-04': [ago(1)] }, NOW), 'feature:NI-04')).toBeCloseTo(2 * w, 3);
    // three quick logs, each noted: three uses, not one
    const quick = [0, 1_000, 2_000];
    const scores = useScores(rows({ logs: quick.map(log) }), { 'feature:NI-04': quick.map((ms) => ago(1, ms)) }, NOW);
    expect(scoreOf(scores, 'feature:NI-04')).toBeCloseTo(3 * w, 3);
    // a row with no noted use (seeded history) counts on its own
    expect(scoreOf(useScores(rows({ logs: [log(0), log(5_000)] }), {}, NOW), 'feature:NI-04')).toBeCloseTo(2 * w, 3);
  });

  it('reads sessions, logs, check-ins and planned events', () => {
    const data = rows({
      sessions: [
        { title: 'Nap', startedAt: ago(1) },
        { title: 'Write the report', kind: 'focus', mode: 'flowtime', startedAt: ago(2) },
        { title: 'Box', startedAt: ago(3) },
        { title: 'Something unknown', kind: 'recovery', mode: 'timer', startedAt: ago(1) },
      ],
      logs: [{ type: 'water', takenAt: ago(1) }, { type: 'strength', takenAt: ago(2) }, { date: ago(4).slice(0, 10) }],
      checkins: [{ kind: 'mood', takenAt: ago(1) }, { kind: 'connection', takenAt: ago(1) }, { kind: 'insomnia', takenAt: ago(1) }, { kind: 'other', takenAt: ago(1) }],
      // planned two days ago for later; one with no createdAt (written before it was stamped) doesn't count
      events: [{ title: 'Walk', featureId: 'DX-09', start: ago(-2), createdAt: ago(2) }, { title: 'Later', featureId: 'DX-01', start: ago(1) }, { title: 'Lunch', start: ago(1), createdAt: ago(1) }],
    });
    const scores = useScores(data, {}, NOW);
    expect([...scores.keys()].sort()).toEqual(
      ['feature:DX-03', 'feature:DX-09', 'feature:NI-02', 'feature:NI-03', 'feature:NI-04', 'feature:PB-04', 'mode:box', 'mode:flow', 'mode:nap-10'].sort(),
    );
    expect(scoreOf(scores, 'feature:NI-04')).toBeCloseTo(0.5 ** (1 / 7) + 0.5 ** (4.5 / 7), 2); // a log with only a date
    expect(scores.get('mode:nap-10')?.last).toBe(ago(1));
  });

  it('counts events by when they were planned, one use per plan', () => {
    const w = 0.5 ** (1 / 7);
    // PB-01's Plan light: six events in one go, and its noted use
    const light = Array.from({ length: 6 }, (_, i) => ({ title: 'Outdoor light', featureId: 'PB-01', start: ago(-i), createdAt: ago(1, i * 10) }));
    expect(scoreOf(useScores(rows({ events: light }), { 'feature:PB-01': [ago(1)] }, NOW), 'feature:PB-01')).toBeCloseTo(w, 3);
    // the same plan with nothing noted (seeded history) is still one use; a second plan a day later another
    const again = light.map((e) => ({ ...e, createdAt: ago(0) }));
    expect(scoreOf(useScores(rows({ events: light }), {}, NOW), 'feature:PB-01')).toBeCloseTo(w, 3);
    expect(scoreOf(useScores(rows({ events: [...light, ...again] }), {}, NOW), 'feature:PB-01')).toBeCloseTo(w + 1, 3);
    // the days the events happen on never count: tomorrow's event is no extra use when tomorrow comes
    expect(scoreOf(useScores(rows({ events: light }), {}, new Date(NOW.getTime() + 3 * 86_400_000)), 'feature:PB-01')).toBeCloseTo(0.5 ** (4 / 7), 3);
    // NI-03: a meal planned, noted, and the connection check-in after it: one use
    const meal = { title: 'Meal with Sam', featureId: 'NI-03', start: ago(-2), createdAt: ago(1) };
    const checkin = { kind: 'connection', connection: 4, takenAt: ago(1, 5_000) };
    expect(scoreOf(useScores(rows({ events: [meal], checkins: [checkin] }), { 'feature:NI-03': [ago(1)] }, NOW), 'feature:NI-03')).toBeCloseTo(w, 3);
  });

  it("a session a feature's verb started is that feature's use, never a mode's", () => {
    const w = 0.5 ** (1 / 7);
    const data = rows({
      sessions: [
        { title: 'Nap', kind: 'recovery', featureId: 'DX-04', startedAt: ago(1, 2_000) },
        { title: 'Slow breathing', kind: 'recovery', featureId: 'DX-07', startedAt: ago(2) },
      ],
    });
    const scores = useScores(data, { 'feature:DX-04': [ago(1)] }, NOW);
    expect([...scores.keys()].sort()).toEqual(['feature:DX-04', 'feature:DX-07']);
    expect(scoreOf(scores, 'feature:DX-04')).toBeCloseTo(w, 3); // the noted use and its session: one
  });

  it('a session is the mode it was started as; only Focus and Meeting fall back to their timer', () => {
    const w = 0.5 ** (1 / 7);
    const data = rows({
      sessions: [
        // a Focus timer the visitor titled "Yoga": the timer, not the Yoga mode
        { title: 'Yoga', kind: 'focus', mode: 'timer', modeId: 'timer', startedAt: ago(1) },
        // older, without modeId: "Yoga" is no Focus mode, so its Session-service mode
        { title: 'Yoga', kind: 'focus', mode: 'timer', startedAt: ago(1, 600_000) },
        { title: 'Call', kind: 'meeting', mode: 'meeting', startedAt: ago(1) },
        // anything else unknown counts as nothing (not Focus's timer, not a walk)
        { title: 'Quiet minutes', kind: 'recovery', mode: 'timer', startedAt: ago(1) },
        { title: 'Round the park', kind: 'walk', mode: 'timer', startedAt: ago(1) },
        // the mode wins over a title naming another one
        { title: 'Nap', kind: 'recovery', mode: 'timer', modeId: 'coherent', startedAt: ago(1) },
      ],
    });
    const scores = useScores(data, {}, NOW);
    expect([...scores.keys()].sort()).toEqual(['mode:coherent', 'mode:meeting', 'mode:timer']);
    expect(scoreOf(scores, 'mode:timer')).toBeCloseTo(2 * w, 2);
  });

  it('keeps 30 days, halving every 7, and drops keys with nothing left', () => {
    const scores = useScores(rows(), { 'mode:nap-10': [ago(7)], 'mode:yoga': [ago(14), ago(31)], 'mode:dance': [ago(40)] }, NOW);
    expect(scoreOf(scores, 'mode:nap-10')).toBeCloseTo(0.5, 5);
    expect(scoreOf(scores, 'mode:yoga')).toBeCloseTo(0.25, 5);
    expect(scores.has('mode:dance')).toBe(false);
  });

  it('ignores keys that are not Recovery features or modes', () => {
    const scores = useScores(rows(), { 'feature:BS-01': [ago(0)], 'mode:nope': [ago(0)], other: [ago(0)] }, NOW);
    expect(scores.size).toBe(0);
  });
});

describe('oftenUsed', () => {
  const s = (entries: [UseKey, number, string][]) => new Map(entries.map(([k, score, last]) => [k, { score, last }]));

  it('puts the highest score first, a tie to the newer use', () => {
    const scores = s([['mode:yoga', 1, ago(3)], ['feature:NI-04', 3, ago(1)], ['mode:nap-10', 1, ago(1)], ['feature:DX-01', 2, ago(5)], ['mode:dance', 0.5, ago(0)]]);
    expect(oftenUsed(scores, [])).toEqual(['feature:NI-04', 'feature:DX-01', 'mode:nap-10', 'mode:yoga']);
  });

  it('fills with open-alert features, then the default modes, without repeats', () => {
    expect(oftenUsed(new Map(), [])).toEqual(['mode:coherent', 'mode:nap-10', 'mode:urban-walk', 'mode:wind-down']);
    expect(oftenUsed(new Map(), ['DX-04', 'BS-02', 'NI-04'])).toEqual(['feature:DX-04', 'feature:NI-04', 'mode:coherent', 'mode:nap-10']);
    expect(oftenUsed(s([['mode:coherent', 1, ago(0)]]), ['DX-04'], 3)).toEqual(['mode:coherent', 'feature:DX-04', 'mode:nap-10']);
  });

  it('mixes features and modes while history is short: at most two open alerts, the default modes in the rest', () => {
    // a fresh visitor with every alert open still sees modes
    expect(oftenUsed(new Map(), RECOVERY_FEATURES)).toEqual([`feature:${RECOVERY_FEATURES[0]}`, `feature:${RECOVERY_FEATURES[1]}`, 'mode:coherent', 'mode:nap-10']);
    // with the default modes mostly used already, more features fill the slots the modes can't
    const used = s([['mode:coherent', 3, ago(0)], ['mode:nap-10', 2, ago(0)], ['mode:urban-walk', 1, ago(0)]]);
    expect(oftenUsed(used, ['DX-04', 'NI-04', 'PB-01', 'DX-01'], 7)).toEqual([
      'mode:coherent', 'mode:nap-10', 'mode:urban-walk', 'feature:DX-04', 'feature:NI-04', 'feature:PB-01', 'mode:wind-down',
    ]);
  });
});

describe('orderGroups', () => {
  const groups = [
    { title: 'Sleep', features: [{ id: 'DX-01' }, { id: 'DX-04' }] },
    { title: 'Mind', features: [{ id: 'NI-02' }, { id: 'NI-03' }] },
    { title: 'Hydration', features: [{ id: 'NI-04' }] },
  ];

  it('puts the busiest group and feature first, ties in the given order, without changing the input', () => {
    const before = JSON.stringify(groups);
    const scores = new Map<UseKey, UseScore>([['feature:NI-04', { score: 2, last: ago(0) }], ['feature:NI-03', { score: 1, last: ago(0) }], ['feature:DX-04', { score: 0.5, last: ago(0) }]]);
    const out = orderGroups(groups, scores);
    expect(out.map((g) => g.title)).toEqual(['Hydration', 'Mind', 'Sleep']);
    expect(out[1].features.map((f) => f.id)).toEqual(['NI-03', 'NI-02']);
    expect(out[2].features.map((f) => f.id)).toEqual(['DX-04', 'DX-01']);
    expect(JSON.stringify(groups)).toBe(before);
    expect(out[0]).not.toBe(groups[2]);
  });

  it('keeps the order when nothing was used', () => {
    expect(orderGroups(groups, new Map()).map((g) => g.title)).toEqual(['Sleep', 'Mind', 'Hydration']);
  });

  it("counts the modes on a group's shelves: a nap moves Sleep up, Focus moves nothing", () => {
    const shelves = [
      { title: 'Sleep', features: [{ id: 'DX-01' }] },
      { title: 'Recovery & stress', features: [{ id: 'DX-07' }] },
      { title: 'Movement', features: [{ id: 'DX-09' }] },
      { title: 'Mind & people', features: [{ id: 'NI-03' }] },
      { title: 'Hydration', features: [{ id: 'NI-04' }] },
    ];
    const score = (entries: [UseKey, number][]) => new Map(entries.map(([k, n]) => [k, { score: n, last: ago(0) }]));
    expect(orderGroups(shelves, score([['feature:NI-04', 1], ['mode:nap-10', 2], ['mode:timer', 9]])).map((g) => g.title)).toEqual(
      ['Sleep', 'Hydration', 'Recovery & stress', 'Movement', 'Mind & people'],
    );
    // Breathe and Meditate count for Recovery & stress, Outside for Movement, Fuel for Hydration
    const many = score([['mode:coherent', 1], ['mode:morning-light', 2], ['feature:DX-01', 0.5]]);
    expect(orderGroups(shelves, many).map((g) => g.title)).toEqual(['Movement', 'Recovery & stress', 'Sleep', 'Mind & people', 'Hydration']);
  });
});

describe('topModes', () => {
  it('shows the modes used most, filled with the defaults', () => {
    expect(topModes(new Map()).map((m) => m.id)).toEqual(['coherent', 'nap-10', 'urban-walk', 'wind-down']);
    const scores = new Map<UseKey, UseScore>([['mode:yoga', { score: 2, last: ago(0) }], ['mode:nap-10', { score: 3, last: ago(0) }], ['feature:NI-04', { score: 9, last: ago(0) }]]);
    expect(topModes(scores).map((m) => m.id)).toEqual(['nap-10', 'yoga', 'coherent', 'urban-walk']);
    expect(topModes(scores, 2).map((m) => m.id)).toEqual(['nap-10', 'yoga']);
  });

  it('leaves out the modes already shown, then fills from the list (no plan modes)', () => {
    const scores = new Map<UseKey, UseScore>([['mode:yoga', { score: 2, last: ago(0) }], ['mode:nap-10', { score: 3, last: ago(0) }]]);
    const ids = topModes(scores, 4, ['nap-10', 'coherent', 'urban-walk']).map((m) => m.id);
    expect(ids).toEqual(['yoga', 'wind-down', MODE_LIST[0].id, MODE_LIST[1].id]);
    const all = topModes(new Map(), MODE_LIST.length);
    expect(all.some((m) => m.run === 'plan')).toBe(false);
    expect(all).toHaveLength(MODE_LIST.filter((m) => m.run !== 'plan').length);
  });
});

describe('scoresFor', () => {
  afterEach(() => vi.useRealTimers());

  it("scores on the demo's own day: a use noted now is fresh, though the sample's day is a week back", () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-10-27T06:00:00Z'));
    let local = EMPTY;
    const aura = {
      zone: 'Asia/Kolkata',
      today: '2026-10-20', // the bundled sample's fixed day
      now: new Date(),
      data: rows(),
      get local() { return local; },
      edit: { used: (key: string, at: string) => { local = addUse(local, key, at); } },
    } as unknown as Aura;
    noteUse(aura, 'mode:nap-10');
    expect(scoresFor(aura).get('mode:nap-10')?.score).toBeCloseTo(1, 5);
    // against the real date the same use looks a week old
    expect(useScores(aura.data, usedOf(aura), aura.now).get('mode:nap-10')?.score).toBeCloseTo(0.5, 5);
  });
});

describe('noteUse', () => {
  it('writes through the store and keeps the last 20', () => {
    let local = EMPTY;
    const aura = { zone: 'Asia/Kolkata', today: '2026-10-27', get local() { return local; }, edit: { used: (key: string, at: string) => { local = addUse(local, key, at); } } } as unknown as Aura;
    noteUse(aura, 'mode:nap-10');
    expect(usedOf(aura)['mode:nap-10']).toHaveLength(1);
    expect(Date.parse(usedOf(aura)['mode:nap-10'][0])).not.toBeNaN();
    for (let i = 0; i < 25; i += 1) local = addUse(local, 'feature:NI-04', ago(0, i));
    expect(usedOf(aura)['feature:NI-04']).toHaveLength(20);
    expect(usedOf(aura)['feature:NI-04'][19]).toBe(ago(0, 24));
    expect(RECOVERY_FEATURES).toHaveLength(16);
  });

  it('does nothing on a store without `used` (the tests’ small fakes)', () => {
    expect(() => noteUse({ zone: 'Asia/Kolkata', today: '2026-10-27', edit: {} } as unknown as Aura, 'mode:nap-10')).not.toThrow();
  });
});
