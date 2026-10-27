// The one Modes list: every Swift preset is there, nothing doubles up, and every old title still finds its mode.
import { describe, expect, it } from 'vitest';
import data from '../data/catalog.json';
import { ICONS } from './catalog';
import { DEFAULT_MODES, MODE_LIST, SHELF_LOOK, SHELVES, breathLine, findMode, modesOn } from './modes';

/** The 50 preset ids of the Swift app's backend/src/domain/capacity/recovery.ts (GitHub main, c022c0d). */
const SWIFT_IDS = [
  'caffeine-nap', 'nap-10', 'nap-30', 'nature-walk', 'nap-20', 'nsdr', 'zone2', 'meditation-break', 'resonance-breathing',
  'full-disconnect', 'social-break', 'urban-walk', 'refuel', 'strength', 'hard-training', 'physiological-sigh',
  'cold-face', 'stair-break', 'box-breathing', 'stand-reset', 'hydration', 'warm-bath', 'sauna', 'alcohol-free-evening',
  'day-off', 'hobby-group', 'meal-with-someone', 'family-time', 'train-with-someone', 'call-a-friend',
  'coffee-with-colleague', 'morning-light', 'green-sit', 'gardening', 'outdoor-break', 'blue-space', 'journalling',
  'read-fiction', 'gratitude', 'worry-dump', 'music-break', 'caffeine-timing', 'protein-meal', 'light-lunch',
  'stable-snack', 'electrolytes', 'yoga', 'dance', 'mobility', 'walking-meeting',
];

const ACCENTS = ['fitness', 'cardiac', 'sleep', 'stress', 'workout', 'recovery', 'blue', 'teal', 'focus', 'cyan', 'purple', 'mint'];

describe('MODE_LIST', () => {
  it('has all 50 Swift presets, on the recovery shelves', () => {
    expect(new Set(SWIFT_IDS).size).toBe(50);
    for (const id of SWIFT_IDS) {
      const mode = MODE_LIST.find((m) => m.id === id);
      expect(mode, id).toBeDefined();
      expect(['Sleep', 'Rest', 'Movement', 'Mind', 'Outside', 'People', 'Fuel']).toContain(mode!.shelf);
    }
    expect(modesOn('Sleep').filter((m) => SWIFT_IDS.includes(m.id)).map((m) => m.id).sort()).toEqual(['caffeine-nap', 'nap-10', 'nap-20', 'nap-30']);
  });

  it('has the focus modes, four breathing patterns and six guided meditations', () => {
    expect(modesOn('Focus').map((m) => m.id)).toEqual(['timer', 'flow', 'meeting']);
    expect(findMode('timer')).toMatchObject({ minutes: 25, choices: [15, 25, 30, 45, 60, 90, 120], run: 'timer', kind: 'focus' });
    expect(findMode('flow')).toMatchObject({ minutes: 0, run: 'flow' });
    expect(findMode('meeting')).toMatchObject({ minutes: 30, kind: 'meeting' });
    expect(modesOn('Breathe').map((m) => m.id)).toEqual(['box', 'four-seven-eight', 'diaphragmatic', 'coherent']);
    for (const m of modesOn('Breathe')) expect(m).toMatchObject({ run: 'breathe', minutes: 5, choices: [2, 3, 5, 10] });
    expect(modesOn('Meditate')).toHaveLength(6);
    for (const m of modesOn('Meditate')) {
      expect(m).toMatchObject({ run: 'guided', minutes: 10, choices: [5, 10, 15, 20] });
      expect(m.prompts).toHaveLength(6);
    }
  });

  it('has unique ids, names and tile words', () => {
    for (const field of ['id', 'name', 'word'] as const) {
      const values = MODE_LIST.map((m) => m[field].toLowerCase());
      expect(new Set(values).size, field).toBe(values.length);
    }
    for (const m of MODE_LIST) expect(m.word.length, m.word).toBeLessThanOrEqual(11);
  });

  it('uses real icons, colour tokens, shelves and short notes', () => {
    expect(SHELVES).toEqual(['Focus', 'Breathe', 'Meditate', 'Sleep', 'Rest', 'Movement', 'Mind', 'Outside', 'People', 'Fuel']);
    for (const s of SHELVES) {
      expect(ICONS[SHELF_LOOK[s].icon], s).toBeDefined();
      expect(ACCENTS).toContain(SHELF_LOOK[s].accent);
      expect(modesOn(s).length, s).toBeGreaterThan(0);
    }
    for (const m of MODE_LIST) {
      expect(ICONS[m.icon], m.id).toBeDefined();
      expect(ACCENTS, m.id).toContain(m.accent);
      expect(m.note.length, m.id).toBeLessThanOrEqual(72);
      if (m.choices) expect(m.choices, m.id).toContain(m.minutes);
      if (m.run === 'breathe') expect(m.pattern, m.id).toBeDefined();
    }
  });

  it('turns everything past two hours into a calendar plan, and only those', () => {
    const plans = MODE_LIST.filter((m) => m.run === 'plan');
    expect(plans.map((m) => m.id).sort()).toEqual(['alcohol-free-evening', 'day-off']);
    for (const m of MODE_LIST) expect(m.run === 'plan', m.id).toBe(m.minutes > 120);
    for (const m of plans) expect(m.choices).toBeUndefined();
  });

  it('marks naps and walks with their session kind', () => {
    for (const m of modesOn('Sleep').filter((x) => x.id.includes('nap'))) expect(m.kind).toBe('nap');
    expect(findMode('urban-walk')?.kind).toBe('walk');
    expect(findMode('wind-down')).toMatchObject({ shelf: 'Sleep', run: 'winddown' });
    expect(findMode('recovery-break')).toMatchObject({ shelf: 'Rest', minutes: 10 });
  });
});

describe('findMode', () => {
  it('finds every old preset title and old mode name', () => {
    const expected: Record<string, string> = {
      Nap: 'nap-10',
      'Slow breathing': 'coherent',
      'Move break': 'stand-reset',
      'Outdoor light': 'morning-light',
      'Recovery break': 'recovery-break',
      'Easy walk': 'urban-walk',
      'Aerobic session': 'zone2',
      'Wind-down': 'wind-down',
      Timer: 'timer',
      Flowtime: 'flow',
      Walk: 'urban-walk',
      Meeting: 'meeting',
    };
    for (const [title, id] of Object.entries(expected)) expect(findMode(title)?.id, title).toBe(id);
    for (const p of data.presets) expect(findMode(p.title), p.title).toBeDefined();
    for (const m of data.modes) expect(findMode(m.name), m.name).toBeDefined();
    expect(findMode('Aerobic session')?.note).toBe('Brisk pace: you can talk but not sing.');
  });

  it('checks id, then name, then aliases, ignoring case', () => {
    expect(findMode('NAP-10')?.id).toBe('nap-10');
    expect(findMode('walk round the block')?.id).toBe('urban-walk');
    expect(findMode('  slow BREATHING ')?.id).toBe('coherent');
    expect(findMode('Coffee then nap')?.id).toBe('caffeine-nap');
    expect(findMode('nope')).toBeUndefined();
  });

  it('knows the default modes', () => {
    expect(DEFAULT_MODES).toEqual(['coherent', 'nap-10', 'urban-walk', 'wind-down']);
    for (const id of DEFAULT_MODES) expect(findMode(id)).toBeDefined();
  });
});

describe('breathLine', () => {
  it('reads a breath out', () => {
    expect(breathLine({ in: 4, hold: 7, out: 8 })).toBe('In 4 · Hold 7 · Out 8');
    expect(breathLine({ in: 5, out: 5 })).toBe('In 5 · Out 5');
    expect(breathLine({ in: 4, hold: 4, out: 4, hold2: 4 })).toBe('In 4 · Hold 4 · Out 4 · Hold 4');
    expect(breathLine(findMode('four-seven-eight')!.pattern!)).toBe('In 4 · Hold 7 · Out 8');
  });
});
