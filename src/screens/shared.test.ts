// The alert list both tabs draw: one per feature (its newest), cleared once that one is done or switched off.
import { describe, expect, it } from 'vitest';
import { BY_ID } from '../lib/catalog';
import { alertFor, featureStatus, openAlerts } from './shared';

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

describe('alertFor and featureStatus', () => {
  const nap = BY_ID['DX-04'];
  const water = BY_ID['NI-04'];
  const open = [{ id: 'n1', featureId: 'DX-04', date: '2026-10-24' }];

  it("acts on the feature's open alert, else on a stand-in with no id", () => {
    expect(alertFor(nap, open).id).toBe('n1');
    const standIn = alertFor(water, open);
    // no body: the catalog's sample copy ("shows") must not read as a finding about this person
    expect(standIn).toEqual({ featureId: 'NI-04', action: { ref: 'water' }, title: water.name });
    expect(standIn.id).toBeUndefined();
    expect(standIn.body).toBeUndefined();
  });

  it('reads To do, then Done today, then the newest value, then Fine', () => {
    const days = [{ date: '2026-10-23', metrics: { totalSleepMin: 330 } }];
    expect(featureStatus(nap, open, [], '2026-10-24', days)).toEqual({ kind: 'todo', text: 'To do' });
    const doneToday = [{ id: 'n1', featureId: 'DX-04', date: '2026-10-24', done: true }];
    expect(featureStatus(nap, [], doneToday, '2026-10-24', days).text).toBe('Done today');
    const doneBefore = [{ id: 'n0', featureId: 'DX-04', date: '2026-10-20', done: true }];
    expect(featureStatus(nap, [], doneBefore, '2026-10-24', days)).toEqual({ kind: 'value', text: '5.5 h' });
    expect(featureStatus(water, [], [], '2026-10-24', days)).toEqual({ kind: 'fine', text: 'Fine' });
  });
});
