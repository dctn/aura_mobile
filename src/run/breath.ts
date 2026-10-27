// The breathing pacer's pattern (Box 4-4-4-4, 4-7-8, Coherent 5/5 ...), kept pure so it can be tested: which phase
// the run is in, the seconds left in it, and how full the circle is. Breathing runs without a pattern pace Coherent.

import type { Breath } from '../lib/modes';
import { BREATH_SMALL } from './clock';

/** In for 5, out for 5: the old "Slow breathing". */
export const COHERENT: Breath = { in: 5, out: 5 };

export type BreathWord = 'In' | 'Hold' | 'Out';

export interface BreathPhase {
  word: BreathWord;
  /** Whole seconds left in this phase, 1 at the least (the number shown next to the word). */
  left: number;
  /** The circle, BREATH_SMALL (empty) to 1 (full): it grows breathing in, stays full on the hold, shrinks out. */
  scale: number;
  inhaling: boolean;
}

/** The pattern's phases in order, the empty ones (no hold) left out. */
function phases(p: Breath): [BreathWord, number][] {
  const all: [BreathWord, number][] = [['In', p.in], ['Hold', p.hold ?? 0], ['Out', p.out], ['Hold', p.hold2 ?? 0]];
  return all.filter(([, s]) => s > 0);
}

/** Where `seconds` into the run falls in the pattern, cycling round it. */
export function breathPhase(pattern: Breath | undefined, seconds: number): BreathPhase {
  const steps = phases(pattern ?? COHERENT);
  const cycle = steps.reduce((a, [, s]) => a + s, 0);
  if (!steps.length || cycle <= 0) return { word: 'In', left: 1, scale: BREATH_SMALL, inhaling: true };
  let t = ((seconds % cycle) + cycle) % cycle;
  let i = 0;
  while (t >= steps[i][1] && i < steps.length - 1) {
    t -= steps[i][1];
    i++;
  }
  const [word, length] = steps[i];
  // the first hold comes after breathing in (full), the second after breathing out (empty)
  const full = word === 'Hold' ? steps.slice(0, i).some(([w]) => w === 'In') && !steps.slice(0, i).some(([w]) => w === 'Out') : false;
  const x = word === 'In' ? t / length : word === 'Out' ? 1 - t / length : full ? 1 : 0;
  const eased = 0.5 - 0.5 * Math.cos(Math.PI * x);
  return { word, left: Math.max(1, Math.ceil(length - t - 1e-9)), scale: BREATH_SMALL + (1 - BREATH_SMALL) * eased, inhaling: word === 'In' };
}
