import { describe, expect, it } from 'vitest';
import { COHERENT, breathPhase } from './breath';
import { promptAt } from './guided';
import { BREATH_SMALL } from './clock';

const words = (p: Parameters<typeof breathPhase>[0], seconds: number[]) => seconds.map((s) => { const b = breathPhase(p, s); return `${b.word} ${b.left}`; });

describe('breathPhase', () => {
  it('paces Coherent 5/5 when there is no pattern, as the old slow breathing did', () => {
    expect(breathPhase(undefined, 0)).toEqual({ word: 'In', left: 5, scale: BREATH_SMALL, inhaling: true });
    expect(breathPhase(COHERENT, 5)).toMatchObject({ word: 'Out', left: 5, inhaling: false });
    expect(breathPhase(COHERENT, 5).scale).toBeCloseTo(1);
    expect(breathPhase(COHERENT, 2.5).scale).toBeCloseTo((1 + BREATH_SMALL) / 2);
    expect(breathPhase(COHERENT, 10)).toMatchObject({ word: 'In', left: 5 });
  });

  it('counts each phase of 4-7-8 down and cycles round', () => {
    const p = { in: 4, hold: 7, out: 8 };
    expect(words(p, [0, 3.2, 4, 10.5, 11, 18.9, 19, 23])).toEqual(['In 4', 'In 1', 'Hold 7', 'Hold 1', 'Out 8', 'Out 1', 'In 4', 'Hold 7']);
  });

  it('holds full after breathing in and empty after breathing out (box breathing)', () => {
    const box = { in: 4, hold: 4, out: 4, hold2: 4 };
    expect(words(box, [0, 4, 8, 12, 16])).toEqual(['In 4', 'Hold 4', 'Out 4', 'Hold 4', 'In 4']);
    expect(breathPhase(box, 5).scale).toBeCloseTo(1);
    expect(breathPhase(box, 13).scale).toBeCloseTo(BREATH_SMALL);
  });

  it('leaves out an empty hold and survives a broken pattern', () => {
    expect(words({ in: 4, hold: 0, out: 6 }, [3, 4, 9.5])).toEqual(['In 1', 'Out 6', 'Out 1']);
    expect(breathPhase({ in: 0, out: 0 }, 12)).toMatchObject({ word: 'In', left: 1 });
  });
});

describe('promptAt', () => {
  const prompts = ['Settle', 'Breathe', 'Scan', 'Let go', 'Rest', 'Return'];
  it('moves on every (minutes × 60) / n seconds and stays on the last', () => {
    // 12 minutes, 6 prompts: one every 2 minutes
    expect(promptAt(prompts, 12, 0)).toEqual({ index: 0, n: 6, text: 'Settle' });
    expect(promptAt(prompts, 12, 119).index).toBe(0);
    expect(promptAt(prompts, 12, 120)).toEqual({ index: 1, n: 6, text: 'Breathe' });
    expect(promptAt(prompts, 12, 11 * 60).text).toBe('Return');
    expect(promptAt(prompts, 12, 99 * 60).index).toBe(5);
  });
  it('shows the first when open-ended, and nothing without prompts', () => {
    expect(promptAt(prompts, 0, 500).index).toBe(0);
    expect(promptAt([], 10, 30)).toEqual({ index: 0, n: 0, text: '' });
  });
});
