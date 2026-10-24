// The calendar card's measures and the day timeline's math, ported from ui/calview.py (DayTimeline, HoursView,
// CalendarCard.fit_to) and ui/pages/home.py (CALENDAR_SHARE, SHOW_TODO).

import { DAY_FROM, clock, keyOf, lanes, taskWhen, type Item } from '../lib/agenda';
import { weekdayShort } from './zone';

/** One hour on the timeline, in px. */
export const HOUR = 40;
/** The time labels on the left. */
export const GUTTER = 46;
/** Room above 00:00 and below 24:00. */
export const PAD = 8;
/** The whole day's height. */
export const DAY_HEIGHT = 2 * PAD + 24 * HOUR;
/** The hours box before the card is measured. */
export const START_HOURS = 5 * HOUR;
/** The hours box never gets less than two and a half hours. */
export const MIN_HOURS = (5 * HOUR) / 2;
/** The calendar card takes about this much of the page when Home opens, so the alerts show below it. */
export const CALENDAR_SHARE = 0.6;
/** Open tasks without a time listed under the hours; the rest (and the done ones) wait behind "Show all". */
export const SHOW_TODO = 2;
/** A block is never shorter than this. */
const MIN_BLOCK = 22;
/** Below this height a block has no room for its time line: the start goes after the title. */
export const SHORT_BLOCK = 38;

/** Where `minute` after midnight sits on the timeline. */
export const yOf = (minute: number) => PAD + (minute * HOUR) / 60;

/** The half hour under `y` (a tap on an empty spot adds a task there). */
export function minutesAt(y: number): number {
  const raw = ((y - PAD) * 60) / HOUR;
  return Math.max(0, Math.min(24 * 60 - 30, Math.floor(raw / 30) * 30));
}

/** Where an item ends on its own day (one running past midnight stops at 23:59). */
export const endOf = (item: Item) => Math.min((item.start ?? 0) + item.minutes, 24 * 60 - 1);

export interface Block {
  item: Item;
  key: string;
  lane: number;
  lanes: number;
  top: number;
  height: number;
  short: boolean;
}

/** The timed items as blocks: side by side where they overlap. */
export function blocks(items: Item[]): Block[] {
  const timed = items.filter((i) => i.start !== null);
  const sides = lanes(timed);
  return timed.map((item) => {
    const [lane, count] = sides.get(keyOf(item)) ?? [0, 1];
    const top = yOf(item.start!);
    const bottom = Math.max(top + MIN_BLOCK, yOf(endOf(item)));
    const height = bottom - top - 2;
    return { item, key: keyOf(item), lane, lanes: count, top: top + 1, height, short: height < SHORT_BLOCK };
  });
}

/** Where the hours box opens (its scrollTop): the first thing planned (on today, the first not over yet), else an
 *  hour before now on today, else 08:00. `now` is the minute of day, given only for today. */
export function focusY(items: Item[], now: number | null): number {
  const timed = items.filter((i) => i.start !== null);
  if (timed.length) {
    const ahead = now === null ? timed : timed.filter((i) => i.start! + i.minutes > now);
    const first = Math.min(...(ahead.length ? ahead : timed).map((i) => i.start!));
    return Math.max(0, yOf(first) - HOUR / 2);
  }
  if (now !== null) return Math.max(0, yOf(now) - HOUR);
  return yOf(DAY_FROM * 60) - PAD;
}

/** The hours box's height so the whole card is about CALENDAR_SHARE of the page: what the rest of the card leaves
 *  (`rest`: the card's height without the box), never under MIN_HOURS, so a long to-do list makes the card taller. */
export function hoursHeight(pageHeight: number, rest: number): number {
  return Math.max(MIN_HOURS, Math.round(pageHeight * CALENDAR_SHARE - rest));
}

/** An item's colour token: meetings green, recovery purple, tasks blue, anything else desk-work amber. */
export function lookTone(look: string): string {
  if (look === 'task') return 'accent';
  if (look === 'meeting') return 'recovery';
  if (look === 'recovery') return 'purple';
  return 'stress';
}

/** "13:00–14:00". */
export const spanText = (item: Item) => (item.start === null ? '' : `${clock(item.start)}–${clock(item.start + item.minutes)}`);

const LIST_WORD: Record<string, string> = { today: 'Today', week: 'This week', month: 'This month' };

/** What sits under a task's title: "Done", "Overdue · Thu 23", "Anytime · This week", or nothing. */
export function taskSub(item: Item, day: string, today: string, zone: string): string {
  if (item.done) return 'Done';
  const when = taskWhen(item.raw, zone).day;
  if (when !== null && when < today && day === today) return `Overdue · ${weekdayShort(when)} ${Number(when.slice(8))}`;
  if (when === null) return `Anytime · ${LIST_WORD[String(item.raw.horizon)] ?? 'Today'}`;
  return '';
}

/** "Nothing yet", "1 item", "6 items". */
export const countText = (n: number) => (n === 0 ? 'Nothing yet' : n === 1 ? '1 item' : `${n} items`);
