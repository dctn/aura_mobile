import { describe, expect, it } from 'vitest';
import { dayItems, taskWhen } from '../lib/agenda';
import { doneChanges, eventTimes, isLocked, newEvent, newTask, taskChanges, taskFields } from './rows';

const Z = 'Asia/Kolkata';
const TODAY = '2026-10-24';

describe('task rows', () => {
  it('writes a day and time the way agenda reads them', () => {
    expect(taskFields(null, null, Z)).toEqual({ dueAt: null, hasTime: false, plannedFor: null });
    expect(taskFields('2026-10-25', null, Z)).toEqual({ dueAt: '2026-10-25T00:00:00+05:30', hasTime: false, plannedFor: null });
    const timed = taskFields('2026-10-25', 15 * 60, Z);
    expect(timed).toEqual({ dueAt: '2026-10-25T15:00:00+05:30', hasTime: true, plannedFor: null });
    expect(taskWhen(timed, Z)).toEqual({ day: '2026-10-25', minute: 900 });
  });

  it('makes a new open task with its list', () => {
    expect(newTask('Call the bank', null, null, null, Z, TODAY)).toEqual({ title: 'Call the bank', status: 'open', horizon: 'today', hasTime: false });
    const t = newTask('Review PR', TODAY, 10 * 60 + 30, 45, Z, TODAY);
    expect(t).toMatchObject({ title: 'Review PR', status: 'open', horizon: 'today', dueAt: '2026-10-24T10:30:00+05:30', hasTime: true, estimateMinutes: 45 });
    expect(newTask('Later', '2026-10-28', null, 45, Z, TODAY)).toMatchObject({ horizon: 'week', hasTime: false });
    expect(newTask('Later', '2026-10-28', null, 45, Z, TODAY)).not.toHaveProperty('estimateMinutes');
    expect(newTask('Far', '2026-11-20', null, null, Z, TODAY).horizon).toBe('month');
  });

  it('shows a new timed task on the timeline and an untimed one on the to-do list', () => {
    const tasks = [
      { id: 'a', ...newTask('Timed', TODAY, 9 * 60, 45, Z, TODAY) },
      { id: 'b', ...newTask('Anytime', null, null, null, Z, TODAY) },
    ];
    const day = dayItems(TODAY, [], tasks, Z, TODAY);
    expect(day.timed.map((i) => [i.title, i.start, i.minutes])).toEqual([['Timed', 540, 45]]);
    expect(day.todo.map((i) => i.title)).toEqual(['Anytime']);
  });

  it('changes only what differs, and nothing on a locked task but the title', () => {
    const task = { id: 'x', title: 'A', horizon: 'today', status: 'open', ...taskFields(TODAY, 600, Z), estimateMinutes: 30 };
    expect(taskChanges(task, 'A', TODAY, 600, 30, false, Z, TODAY)).toBeNull();
    expect(taskChanges(task, 'B', TODAY, 600, 30, false, Z, TODAY)).toMatchObject({ title: 'B' });
    expect(taskChanges(task, 'A', null, null, 30, false, Z, TODAY)).toEqual({ title: 'A', dueAt: null, hasTime: false, plannedFor: null, horizon: 'today' });
    const locked = { ...task, scheduledAt: '2026-10-24T10:00:00+05:30' };
    expect(isLocked(locked)).toBe(true);
    expect(taskChanges(locked, 'A', '2026-10-30', 60, 90, true, Z, TODAY)).toBeNull();
    expect(taskChanges(locked, 'C', '2026-10-30', 60, 90, true, Z, TODAY)).toEqual({ title: 'C' });
  });

  it('ticks and unticks', () => {
    const now = new Date('2026-10-24T07:59:00Z');
    expect(doneChanges(true, now, Z)).toEqual({ status: 'completed', completedAt: '2026-10-24T13:29:00+05:30' });
    expect(doneChanges(false, now, Z)).toEqual({ status: 'open', completedAt: null });
  });
});

describe('event rows', () => {
  it('starts and ends in the zone', () => {
    expect(eventTimes(TODAY, 13 * 60, 60, Z)).toEqual({ start: '2026-10-24T13:00:00+05:30', end: '2026-10-24T14:00:00+05:30' });
    expect(eventTimes(TODAY, 23 * 60 + 30, 60, Z).end).toBe('2026-10-25T00:30:00+05:30');
    const e = { id: 'e', ...newEvent('Lunch with Ana', TODAY, 13 * 60, 60, Z) };
    const day = dayItems(TODAY, [e], [], Z, TODAY);
    expect(day.timed.map((i) => [i.title, i.start, i.minutes, i.look])).toEqual([['Lunch with Ana', 780, 60, 'focus']]);
  });
});
