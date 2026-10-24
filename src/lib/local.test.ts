// The visitor's changes drawn over the shared snapshot: add, change, remove, the profile merge, and storage.
import { beforeEach, describe, expect, it } from 'vitest';
import * as L from './local';
import type { Snapshot } from './types';

const snapshot = (parts: Partial<Record<string, unknown>>): Snapshot => ({
  at: '2026-10-24T08:00:00Z',
  zone: 'Asia/Kolkata',
  today: '2026-10-24',
  parts: Object.fromEntries(Object.entries(parts).map(([k, v]) => [k, { ok: true, value: v }])) as Snapshot['parts'],
});

describe('local overlay', () => {
  beforeEach(() => localStorage.clear());

  it('adds, changes and removes rows over the snapshot without touching it', () => {
    const snap = snapshot({ tasks: [{ id: 'a', title: 'A' }, { id: 'b', title: 'B' }] });
    let s = L.addRow(L.EMPTY, 'tasks', { id: 'local-1', title: 'Mine' });
    s = L.changeRow(s, 'tasks', 'a', { status: 'completed' });
    s = L.changeRow(s, 'tasks', 'local-1', { title: 'Mine, renamed' });
    s = L.removeRow(s, 'tasks', 'b');
    const tasks = L.merge(snap, s).tasks;
    expect(tasks).toEqual([{ id: 'a', title: 'A', status: 'completed' }, { id: 'local-1', title: 'Mine, renamed' }]);
    expect((snap.parts.tasks as { value: unknown[] }).value).toHaveLength(2);
  });

  it('forgets an added row outright when it is removed', () => {
    let s = L.addRow(L.EMPTY, 'events', { id: 'local-e', title: 'E' });
    s = L.changeRow(s, 'events', 'local-e', { title: 'F' });
    s = L.removeRow(s, 'events', 'local-e');
    expect(s.lists.events).toEqual({ added: [], changed: {}, removed: [] });
  });

  it('merges profile changes, health goals one level deeper', () => {
    const snap = snapshot({ me: { displayName: 'Asha', healthGoals: { bedtime: '23:00', wakeTime: '07:00' } } });
    const s = L.changeMe(L.changeMe(L.EMPTY, { healthGoals: { bedtime: '22:30' } }), { heightCm: 166 });
    expect(L.merge(snap, s).me).toEqual({ displayName: 'Asha', heightCm: 166, healthGoals: { bedtime: '22:30', wakeTime: '07:00' } });
  });

  it('reads a failed or missing part as empty', () => {
    const snap: Snapshot = { ...snapshot({}), parts: { alerts: { ok: false, error: 'x' } } as unknown as Snapshot['parts'] };
    const data = L.merge(snap, L.EMPTY);
    expect(data.alerts).toEqual([]);
    expect(data.metrics).toEqual({ days: [], latest: null });
    expect(data.active).toBeNull();
    expect(L.merge(null, L.EMPTY).me).toEqual({});
  });

  it('takes the alert switches from the visitor once changed', () => {
    const snap = snapshot({ alertOff: ['DX-01'] });
    expect(L.merge(snap, L.EMPTY).alertOff).toEqual(['DX-01']);
    expect(L.merge(snap, { ...L.EMPTY, alertOff: [] }).alertOff).toEqual([]);
  });

  it('keeps the changes in localStorage and counts them', () => {
    const s = L.changeRow(L.addRow(L.EMPTY, 'logs', { id: 'w', type: 'water' }), 'alerts', 'x', { done: true });
    L.saveLocal(s);
    expect(L.loadLocal()).toEqual(s);
    expect(L.changeCount(s)).toBe(2);
    localStorage.setItem('aura-demo:local', '{not json');
    expect(L.loadLocal()).toEqual(L.EMPTY);
  });
});
