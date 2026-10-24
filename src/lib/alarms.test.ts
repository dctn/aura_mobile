import { describe, expect, it } from 'vitest';
import { due, newAlerts } from './alarms';

describe('alarms', () => {
  const goals = { bedtime: '23:00', wakeTime: '07:00' };

  it('rings the wind-down 30 minutes before bed, once a day', () => {
    const fired = new Set<string>();
    expect(due('2026-10-24', 22 * 60 + 30, goals, fired)).toHaveLength(1);
    expect(due('2026-10-24', 22 * 60 + 31, goals, fired)).toHaveLength(0);
    expect(due('2026-10-25', 22 * 60 + 30, goals, fired)).toHaveLength(1);
  });

  it('rings the wake alarm within two minutes, and wraps past midnight', () => {
    expect(due('2026-10-24', 7 * 60 + 1, goals, new Set())[0]).toContain('07:00');
    expect(due('2026-10-24', 7 * 60 + 2, goals, new Set())).toHaveLength(0);
    expect(due('2026-10-24', 23 * 60 + 50, { bedtime: '00:20' }, new Set())[0]).toContain('00:20');
  });

  it('ignores goals it cannot read', () => {
    expect(due('2026-10-24', 0, { bedtime: 'late', wakeTime: null }, new Set())).toEqual([]);
  });

  it('names the first new alert after the first look', () => {
    const a = [{ id: '1', title: 'Sleep debt' }];
    const first = newAlerts(a, null);
    expect(first.message).toBeNull();
    const next = newAlerts([...a, { id: '2', title: 'Nap' }, { id: '3', title: 'Stress' }], first.seen);
    expect(next.message).toBe('New alert: Nap (+1 more)');
    expect(newAlerts(a, next.seen).message).toBeNull();
  });
});
