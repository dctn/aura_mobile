// Mirrors demo/tests/test_agenda.py: which day a task or event falls on, the month grid, hours and overlap lanes.
import { describe, expect, it } from 'vitest';
import * as agenda from './agenda';

const TZ = 'Asia/Kolkata';
const TODAY = '2026-10-24'; // a Saturday

describe('agenda', () => {
  it('puts a task on its block, then its due date, then its planned day', () => {
    expect(agenda.taskWhen({ scheduledAt: '2026-10-25T04:30:00Z', dueAt: '2026-10-30T00:00:00Z' }, TZ)).toEqual({ day: '2026-10-25', minute: 600 });
    expect(agenda.taskWhen({ dueAt: '2026-10-26T09:00:00+05:30', hasTime: false }, TZ)).toEqual({ day: '2026-10-26', minute: null });
    expect(agenda.taskWhen({ dueAt: '2026-10-26T09:00:00+05:30', hasTime: true }, TZ)).toEqual({ day: '2026-10-26', minute: 540 });
    expect(agenda.taskWhen({ plannedFor: '2026-10-27' }, TZ)).toEqual({ day: '2026-10-27', minute: null });
    expect(agenda.taskWhen({}, TZ)).toEqual({ day: null, minute: null });
  });

  it('shows a done task on the day it was done and an open undated one on today', () => {
    const done = { status: 'completed', completedAt: '2026-10-23T20:00:00Z', dueAt: '2026-10-20T00:00:00Z' };
    expect(agenda.taskDay(done, TZ, TODAY)).toBe('2026-10-24'); // 01:30 IST the next day
    expect(agenda.taskDay({ status: 'pending' }, TZ, TODAY)).toBe(TODAY);
    expect(agenda.taskDay({ status: 'completed' }, TZ, TODAY)).toBeNull();
  });

  it('splits a day into the timeline and the to-do list', () => {
    const events = [
      { id: 'e1', title: 'Standup', kind: 'meeting', start: '2026-10-24T09:00:00+05:30', end: '2026-10-24T09:15:00+05:30' },
      { id: 'e2', title: 'Tomorrow', start: '2026-10-25T09:00:00+05:30' },
    ];
    const tasks = [
      { id: 't1', title: 'b anytime', status: 'pending' },
      { id: 't2', title: 'Timed', status: 'pending', dueAt: '2026-10-24T11:00:00+05:30', hasTime: true, estimateMinutes: 45 },
      { id: 't3', title: 'A done', status: 'completed', completedAt: '2026-10-24T06:00:00Z' },
    ];
    const day = agenda.dayItems(TODAY, events, tasks, TZ, TODAY);
    expect(day.timed.map(agenda.keyOf)).toEqual(['event:e1', 'task:t2']);
    expect([day.timed[0].minutes, day.timed[0].look, day.timed[1].minutes]).toEqual([15, 'meeting', 45]);
    expect(day.todo.map((i) => i.title)).toEqual(['b anytime', 'A done']); // open first
    const marks = agenda.marks(events, tasks, TZ, TODAY);
    expect([...(marks.get(TODAY) ?? [])].sort()).toEqual(['event', 'task']);
    expect([...(marks.get('2026-10-25') ?? [])]).toEqual(['event']);
  });

  it('builds the month as whole weeks, Monday first', () => {
    const grid = agenda.monthGrid(TODAY);
    expect(grid.length % 7).toBe(0);
    expect(agenda.weekday(grid[0])).toBe(0);
    expect(grid[0] <= '2026-10-01' && '2026-10-31' <= grid.at(-1)!).toBe(true);
    expect(agenda.weekOf(TODAY)[0]).toBe('2026-10-19');
    expect(agenda.addMonths('2026-01-31', 1)).toBe('2026-02-28');
    expect(agenda.addMonths('2026-12-05', 1)).toBe('2027-01-05');
    expect(agenda.addMonths('2026-01-05', -1)).toBe('2025-12-05');
  });

  it('lets overlapping items share lanes', () => {
    const item = (kind: 'event' | 'task', id: string, start: number, minutes: number): agenda.Item => ({
      kind, id, title: id, day: TODAY, start, minutes, done: false, look: kind, raw: {},
    });
    const lanes = agenda.lanes([item('event', 'a', 540, 60), item('task', 'b', 570, 30), item('event', 'c', 600, 30)]);
    expect(lanes.get('event:a')).toEqual([0, 2]);
    expect(lanes.get('task:b')).toEqual([1, 2]);
    expect(lanes.get('event:c')).toEqual([0, 1]); // starts as A ends: a new group
  });

  it('follows the PC lists for horizons', () => {
    expect(agenda.horizonFor(null, TODAY)).toBe('today');
    expect(agenda.horizonFor('2026-10-21', TODAY)).toBe('today');
    expect(agenda.horizonFor('2026-10-30', TODAY)).toBe('week');
    expect(agenda.horizonFor('2026-10-31', TODAY)).toBe('month');
  });

  it('reads Z, offset and plain local times', () => {
    expect(agenda.localParts('2026-10-24T00:00:00Z', TZ)).toEqual({ day: '2026-10-24', minute: 330 });
    expect(agenda.localParts('2026-10-24T08:00:00', TZ)).toEqual({ day: '2026-10-24', minute: 480 });
    expect(agenda.localParts('not a time', TZ)).toBeNull();
    expect(agenda.localParts(null, TZ)).toBeNull();
  });
});
