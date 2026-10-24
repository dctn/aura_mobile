import { describe, expect, it } from 'vitest';
import * as S from './sessions';
import { todoItems } from './sheets';

const zone = 'Asia/Kolkata';

describe('sessions', () => {
  const rows = [
    { id: '1', kind: 'walk', title: 'Walk', plannedMinutes: 20, status: 'completed', startedAt: '2026-10-24T08:00:00+05:30', endedAt: '2026-10-24T08:20:00+05:30', actualMinutes: 20 },
    { id: '2', kind: 'focus', title: 'Flowtime', plannedMinutes: null, status: 'stopped', startedAt: '2026-10-19T09:00:00+05:30', endedAt: '2026-10-19T09:42:00+05:30' },
    { id: '3', kind: 'recovery', title: 'Nap', plannedMinutes: 10, status: 'completed', startedAt: '2026-10-18T14:00:00+05:30', endedAt: '2026-10-18T14:10:00+05:30' },
    { id: '4', kind: 'recovery', title: 'Nap', plannedMinutes: 10, status: 'completed', startedAt: '2026-10-23T14:00:00+05:30', endedAt: '2026-10-23T14:10:00+05:30' },
  ];

  it("keeps this week's, newest first", () => {
    // 24 Oct 2026 is a Saturday: the week is Mon 19 to Sun 25
    expect(S.thisWeek(rows, zone, '2026-10-24').map((s) => s.id)).toEqual(['1', '4', '2']);
  });

  it('adds them up', () => {
    expect(S.totals(S.thisWeek(rows, zone, '2026-10-24'))).toEqual({ count: 3, completed: 2, minutes: 72 });
  });

  it('writes the length and the day', () => {
    expect(S.lengthText(rows[0])).toBe('20 min');
    expect(S.lengthText(rows[1])).toBe('42 min');
    expect(S.lengthText({})).toBe('');
    expect(S.whenText(rows[0].startedAt, zone, '2026-10-24')).toBe('Today 08:00');
    expect(S.whenText(rows[3].startedAt, zone, '2026-10-24')).toBe('Yesterday 14:00');
    expect(S.whenText(rows[1].startedAt, zone, '2026-10-24')).toBe('Mon 09:00');
  });

  it("says how a server session is going", () => {
    const now = new Date('2026-10-24T03:00:00Z').getTime();
    expect(S.sessionStatus({ state: 'paused' }, now)).toBe('Paused');
    expect(S.sessionStatus({ state: 'running', startedAt: '2026-10-24T02:50:00Z', plannedMinutes: 25 }, now)).toBe('15 min left');
    expect(S.sessionStatus({ state: 'running', startedAt: '2026-10-24T02:00:00Z', plannedMinutes: 25 }, now)).toBe('Time is up');
    expect(S.sessionStatus({ state: 'running', startedAt: '2026-10-24T02:56:00Z' }, now)).toBe('Running · 4 min');
    expect(S.openServerSession({ state: 'ended' })).toBe(null);
    expect(S.openServerSession(null)).toBe(null);
  });
});

describe('todoItems', () => {
  it('keeps the lines with words, at most 10', () => {
    expect(todoItems('Reply to Ana\n\n  Book the dentist  \n')).toEqual(['Reply to Ana', 'Book the dentist']);
    expect(todoItems(Array.from({ length: 12 }, (_, i) => `t${i}`).join('\n'))).toHaveLength(10);
  });
});
