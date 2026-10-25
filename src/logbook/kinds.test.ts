// The logbook covers the Zoho sheet: every "App log" / "Manual entry" row outside the 18 features is exactly one kind.

import { describe, expect, it } from 'vitest';
import { ICONS } from '../lib/catalog';
import { CATEGORIES, KINDS, kindsIn } from './kinds';
import { DERIVED } from './derive';

const range = (a: number, b: number) => Array.from({ length: b - a + 1 }, (_, i) => a + i);

/** The S.No of each row in "Categories and Sources" whose source is an app log or a manual entry (25 Oct). */
const ZOHO_ROWS = [
  16, 18, 73, 82, 83, 84,
  ...range(119, 130),
  ...range(135, 142), 144,
  ...range(145, 161),
  ...range(162, 171),
  ...range(172, 200),
  ...range(201, 204),
  212,
  ...range(308, 325),
];
/** Rows that live elsewhere: 119 Mood is Recovery's NI-02 check-in. */
const ELSEWHERE = [119];

describe('kinds', () => {
  it('covers every Zoho app-log row exactly once', () => {
    const wanted = ZOHO_ROWS.filter((n) => !ELSEWHERE.includes(n));
    const covered = KINDS.map((k) => k.zoho);
    for (const n of wanted) expect(covered.filter((z) => z === n), `S.No ${n}`).toHaveLength(1);
    expect(covered.filter((z) => !wanted.includes(z))).toEqual([]);
    expect(covered).toHaveLength(wanted.length);
  });

  it('gives each kind one way to fill it and a unique id', () => {
    for (const k of KINDS) expect([k.input, k.derive, k.source].filter(Boolean), k.id).toHaveLength(1);
    expect(new Set(KINDS.map((k) => k.id)).size).toBe(KINDS.length);
    // Recovery's own log types stay Recovery's
    expect(KINDS.map((k) => k.id)).not.toContain('water');
    expect(KINDS.map((k) => k.id)).not.toContain('strength');
  });

  it('has a computation for every derived kind and nothing else', () => {
    expect(Object.keys(DERIVED).sort()).toEqual(KINDS.filter((k) => k.derive).map((k) => k.id).sort());
  });

  it('puts every kind in a known category with a real icon', () => {
    for (const c of CATEGORIES) {
      expect(ICONS[c.icon], c.icon).toBeTruthy();
      expect(kindsIn(c.id).length, c.id).toBeGreaterThan(0);
    }
    const ids = CATEGORIES.map((c) => c.id);
    for (const k of KINDS) expect(ids, k.id).toContain(k.category);
  });
});
