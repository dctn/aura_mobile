import { describe, expect, it } from 'vitest';
import type { Item } from '../lib/agenda';
import { canDrag, dropStart, minuteAtY, moveChanges, snap, stretchTo, targetText } from './drag';
import { HOUR, PAD } from './layout';

const Z = 'Asia/Kolkata';
const TODAY = '2026-10-24';
const yAt = (minute: number) => PAD + (minute * HOUR) / 60;

const task = (over: Partial<Item> = {}): Item => ({
  kind: 'task', id: 't1', title: 'Review PR', day: TODAY, start: 10 * 60, minutes: 30, done: false, look: 'task', raw: {}, ...over,
});
const event = (over: Partial<Item> = {}): Item => ({
  kind: 'event', id: 'e1', title: 'Standup', day: TODAY, start: 9 * 60, minutes: 45, done: false, look: 'meeting', raw: {}, ...over,
});

describe('drag maths', () => {
  it('snaps to quarter hours', () => {
    expect(snap(7)).toBe(0);
    expect(snap(8)).toBe(15);
    expect(snap(622)).toBe(615);
    expect(minuteAtY(yAt(600))).toBe(600);
  });

  it('keeps a moved block inside the day, less where it was grabbed', () => {
    expect(dropStart(yAt(10 * 60 + 20), 15, 30)).toBe(10 * 60);
    expect(dropStart(yAt(5), 15, 30)).toBe(0);
    expect(dropStart(yAt(23 * 60 + 50), 0, 60)).toBe(23 * 60);
  });

  it('stretches to at least 15 minutes and not past midnight', () => {
    expect(stretchTo(yAt(11 * 60 + 10), 10 * 60)).toBe(75);
    expect(stretchTo(yAt(10 * 60), 10 * 60)).toBe(15);
    expect(stretchTo(yAt(24 * 60 + 60), 23 * 60)).toBe(60);
  });
});

describe('what a drop writes', () => {
  it('moves a timed task to a new day and time with its list and length', () => {
    expect(moveChanges(task(), { day: '2026-10-27', start: 14 * 60 + 15, minutes: 45 }, Z, TODAY)).toEqual({
      dueAt: '2026-10-27T14:15:00+05:30', hasTime: true, plannedFor: null, horizon: 'week', estimateMinutes: 45,
    });
  });

  it('moves a to-do without a time to another day, still without a time', () => {
    const todo = task({ start: null });
    expect(moveChanges(todo, { day: '2026-11-20', start: null, minutes: 30 }, Z, TODAY)).toEqual({
      dueAt: '2026-11-20T00:00:00+05:30', hasTime: false, plannedFor: null, horizon: 'month',
    });
  });

  it('gives a to-do dropped in the hours a time', () => {
    expect(moveChanges(task({ start: null }), { day: TODAY, start: 16 * 60, minutes: 30 }, Z, TODAY)).toMatchObject({
      dueAt: '2026-10-24T16:00:00+05:30', hasTime: true, horizon: 'today', estimateMinutes: 30,
    });
  });

  it('moves and stretches an event by its start and end', () => {
    expect(moveChanges(event(), { day: '2026-10-25', start: 9 * 60, minutes: 45 }, Z, TODAY)).toEqual({
      start: '2026-10-25T09:00:00+05:30', end: '2026-10-25T09:45:00+05:30',
    });
    expect(moveChanges(event(), { day: TODAY, start: 9 * 60, minutes: 90 }, Z, TODAY)).toEqual({
      start: '2026-10-24T09:00:00+05:30', end: '2026-10-24T10:30:00+05:30',
    });
  });

  it('writes nothing when it lands where it was', () => {
    expect(moveChanges(task(), { day: TODAY, start: 600, minutes: 30 }, Z, TODAY)).toBeNull();
    expect(moveChanges(task({ start: null }), { day: TODAY, start: null, minutes: 60 }, Z, TODAY)).toBeNull();
  });

  it('leaves done and block-placed tasks alone', () => {
    expect(canDrag(task())).toBe(true);
    expect(canDrag(event())).toBe(true);
    expect(canDrag(task({ done: true }))).toBe(false);
    expect(canDrag(task({ raw: { blockId: 'b1' } }))).toBe(false);
    expect(canDrag(task({ raw: { scheduledAt: '2026-10-24T10:00:00+05:30' } }))).toBe(false);
  });

  it('says where it lands', () => {
    expect(targetText({ day: '2026-10-25', start: 10 * 60 + 15, minutes: 45 })).toBe('Sun 25 Oct · 10:15–11:00');
    expect(targetText({ day: '2026-10-25', start: null, minutes: 30 })).toBe('Sun 25 Oct');
  });
});
