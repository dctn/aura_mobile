import { describe, expect, it } from 'vitest';
import { DAY_FROM, type Item } from '../lib/agenda';
import { CALENDAR_SHARE, HOUR, MIN_HOURS, PAD, blocks, countText, endOf, focusY, hoursHeight, lookTone, minutesAt, spanText, taskSub, yOf } from './layout';

const item = (start: number | null, minutes = 30, extra: Partial<Item> = {}): Item => ({
  kind: 'event', id: `e${start}-${minutes}`, title: 'Thing', day: '2026-10-24', start, minutes, done: false, look: 'meeting', raw: {}, ...extra,
});

describe('the timeline', () => {
  it('places minutes and finds the half hour under a tap', () => {
    expect(yOf(0)).toBe(PAD);
    expect(yOf(60)).toBe(PAD + HOUR);
    expect(minutesAt(yOf(13 * 60 + 40))).toBe(13 * 60 + 30);
    expect(minutesAt(-50)).toBe(0);
    expect(minutesAt(99_999)).toBe(24 * 60 - 30);
  });

  it('opens at 08:00 on an empty day that is not today', () => {
    expect(focusY([], null)).toBe(yOf(DAY_FROM * 60) - PAD);
  });

  it('opens an hour before now on an empty today', () => {
    expect(focusY([], 15 * 60)).toBe(yOf(15 * 60) - HOUR);
  });

  it('opens at the first item, half an hour above it', () => {
    expect(focusY([item(14 * 60), item(9 * 60)], null)).toBe(yOf(9 * 60) - HOUR / 2);
  });

  it('on today, opens at the first item not over yet', () => {
    const items = [item(9 * 60, 60), item(13 * 60, 60), item(16 * 60, 30)];
    expect(focusY(items, 13 * 60 + 30)).toBe(yOf(13 * 60) - HOUR / 2); // 13:00-14:00 is still running
    expect(focusY(items, 14 * 60 + 10)).toBe(yOf(16 * 60) - HOUR / 2);
    // all over: the first of the day
    expect(focusY(items, 20 * 60)).toBe(yOf(9 * 60) - HOUR / 2);
  });

  it('ignores untimed items and never scrolls above the top', () => {
    expect(focusY([item(null), item(0)], null)).toBe(0);
  });

  it('puts overlapping items side by side', () => {
    const placed = blocks([item(9 * 60, 60), item(9 * 60 + 30, 60), item(12 * 60, 15)]);
    const byStart = Object.fromEntries(placed.map((b) => [b.item.start, b]));
    expect([byStart[540].lane, byStart[540].lanes]).toEqual([0, 2]);
    expect([byStart[570].lane, byStart[570].lanes]).toEqual([1, 2]);
    expect([byStart[720].lane, byStart[720].lanes]).toEqual([0, 1]);
    expect(byStart[540].top).toBe(yOf(540) + 1);
    expect(byStart[540].height).toBe(HOUR - 2);
    expect(byStart[540].short).toBe(false);
    // a 15-minute block keeps a minimum height and puts its start after the title
    expect(byStart[720].height).toBe(20);
    expect(byStart[720].short).toBe(true);
  });

  it('stops an item at midnight', () => {
    expect(endOf(item(23 * 60, 120))).toBe(24 * 60 - 1);
    expect(spanText(item(23 * 60 + 30, 60))).toBe('23:30–00:30');
    expect(spanText(item(13 * 60, 60))).toBe('13:00–14:00');
  });
});

describe('the card size', () => {
  it('gives the hours box what the rest of a 60% card leaves', () => {
    expect(CALENDAR_SHARE).toBe(0.6);
    // the phone is 844 px: the card is ~506 px, so with 300 px of everything else the hours get 206
    expect(hoursHeight(844, 300)).toBe(206);
  });

  it('never shrinks the hours under two and a half hours', () => {
    expect(hoursHeight(844, 480)).toBe(MIN_HOURS);
    expect(hoursHeight(0, 0)).toBe(MIN_HOURS);
  });
});

describe('words', () => {
  it('colours by kind', () => {
    expect(lookTone('task')).toBe('accent');
    expect(lookTone('meeting')).toBe('recovery');
    expect(lookTone('recovery')).toBe('purple');
    expect(lookTone('focus')).toBe('stress');
  });

  it('counts items', () => {
    expect(countText(0)).toBe('Nothing yet');
    expect(countText(1)).toBe('1 item');
    expect(countText(6)).toBe('6 items');
  });

  it('says what list a task is on', () => {
    const task = (raw: Record<string, unknown>, done = false): Item => ({ ...item(null), kind: 'task', look: 'task', done, raw });
    expect(taskSub(task({ horizon: 'week' }), '2026-10-24', '2026-10-24', 'Asia/Kolkata')).toBe('Anytime · This week');
    expect(taskSub(task({}), '2026-10-24', '2026-10-24', 'Asia/Kolkata')).toBe('Anytime · Today');
    expect(taskSub(task({}, true), '2026-10-24', '2026-10-24', 'Asia/Kolkata')).toBe('Done');
    expect(taskSub(task({ plannedFor: '2026-10-22' }), '2026-10-24', '2026-10-24', 'Asia/Kolkata')).toBe('Overdue · Thu 22');
    expect(taskSub(task({ plannedFor: '2026-10-25' }), '2026-10-25', '2026-10-24', 'Asia/Kolkata')).toBe('');
  });
});
