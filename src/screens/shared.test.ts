// The alert list both tabs draw: one per feature (its newest), cleared once that one is done or switched off.
import { describe, expect, it } from 'vitest';
import { openAlerts } from './shared';

describe('openAlerts', () => {
  const rows = [
    { id: 'a1', featureId: 'DX-01', date: '2026-10-18' },
    { id: 'a2', featureId: 'DX-01', date: '2026-10-19', priority: 'normal' },
    { id: 'b1', featureId: 'NI-03', date: '2026-10-19', priority: 'high' },
    { id: 'c1', featureId: 'PB-01', date: '2026-10-12' },
  ];

  it('keeps the newest per feature, newest first, high priority first within a day', () => {
    expect(openAlerts(rows).map((a) => a.id)).toEqual(['b1', 'a2', 'c1']);
  });

  it('clears a feature when its newest is done, and hides switched-off features', () => {
    const done = rows.map((a) => (a.id === 'a2' ? { ...a, done: true } : a));
    expect(openAlerts(done, ['PB-01']).map((a) => a.id)).toEqual(['b1']);
  });
});
