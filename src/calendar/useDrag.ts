// Dragging calendar items with the mouse or a finger. A mouse drags once it moves a few pixels; a finger first holds
// still for a moment (so a swipe still scrolls the hours). While dragging:
//   - over the hours, the block follows in 15-minute steps (its bottom edge stretches the length instead);
//   - over a day in the grid, the item goes to that day at the same time;
//   - holding on ‹ › flips the week or month, holding at the hours' left or right edge flips the day;
//   - near the top or bottom of the hours, they scroll.
// Esc or dropping anywhere else puts it back. The drop goes through `onDrop`; the card writes it with aura.edit.

import { useCallback, useEffect, useRef, useState, type PointerEvent as ReactPointerEvent, type RefObject } from 'react';
import type { Item } from '../lib/agenda';
import { canDrag, dropStart, minuteAtY, stretchTo, type Target } from './drag';

const MOUSE_SLOP = 5;
const TOUCH_SLOP = 8;
const TOUCH_HOLD_MS = 350;
/** How long a hold on ‹ › or a side edge waits before flipping, and between flips. */
const FLIP_MS = 650;
const SIDE_PX = 22;
const SCROLL_PX = 24;
const SCROLL_STEP = 6;

export type DragMode = 'move' | 'stretch';

export interface Drag {
  item: Item;
  mode: DragMode;
  x: number;
  y: number;
  target: Target | null;
  /** The grid day under the pointer. */
  overDay: string | null;
  /** -1 / 1 while held at the hours' left / right edge. */
  side: -1 | 0 | 1;
}

interface Press {
  item: Item;
  mode: DragMode;
  x: number;
  y: number;
  touch: boolean;
  timer: number;
}

interface Options {
  /** The hours' scroll box and the 24-hour column inside it. */
  box: RefObject<HTMLDivElement>;
  dayColumn: RefObject<HTMLDivElement>;
  /** The day the hours show. */
  day: string;
  stepDay(n: number): void;
  flip(n: number): void;
  onDrop(item: Item, target: Target): void;
}

export function useDrag({ box, dayColumn, day, stepDay, flip, onDrop }: Options) {
  const [drag, setDrag] = useState<Drag | null>(null);
  const live = useRef<Drag | null>(null);
  const press = useRef<Press | null>(null);
  const grab = useRef(0);
  const hold = useRef<{ key: string; timer: number } | null>(null);
  const justDropped = useRef(false);
  const opts = useRef({ day, stepDay, flip, onDrop });
  opts.current = { day, stepDay, flip, onDrop };

  const show = (next: Drag | null) => {
    live.current = next;
    setDrag(next);
  };

  const stopHold = () => {
    if (hold.current) window.clearInterval(hold.current.timer);
    hold.current = null;
  };
  /** Run `fn` after FLIP_MS of holding on the same spot, then again every FLIP_MS. */
  const holdOn = (key: string | null, fn: () => void) => {
    if (hold.current?.key === key) return;
    stopHold();
    if (key) hold.current = { key, timer: window.setInterval(fn, FLIP_MS) };
  };

  /** Where the drag would land with the pointer at (x, y). */
  const aim = useCallback((x: number, y: number) => {
    const d = live.current;
    if (!d) return;
    const { item, mode } = d;
    let target: Target | null = null;
    let overDay: string | null = null;
    let side: -1 | 0 | 1 = 0;
    const under = document.elementFromPoint(x, y);
    const cell = under?.closest<HTMLElement>('[data-day]');
    const flipper = under?.closest<HTMLElement>('[data-flip]');
    const rect = box.current?.getBoundingClientRect();
    const inHours = rect && x >= rect.left && x <= rect.right && y >= rect.top && y <= rect.bottom;

    if (cell && mode === 'move') {
      overDay = cell.dataset.day ?? null;
      if (overDay) target = { day: overDay, start: item.start, minutes: item.minutes };
      holdOn(null, () => {});
    } else if (flipper && mode === 'move') {
      const n = Number(flipper.dataset.flip);
      holdOn(`flip:${n}`, () => opts.current.flip(n));
    } else if (inHours && rect && dayColumn.current) {
      const yInDay = y - dayColumn.current.getBoundingClientRect().top;
      if (mode === 'stretch' && item.start !== null) {
        target = { day: item.day ?? opts.current.day, start: item.start, minutes: stretchTo(yInDay, item.start) };
      } else {
        target = { day: opts.current.day, start: dropStart(yInDay, grab.current, item.minutes), minutes: item.minutes };
        side = x < rect.left + SIDE_PX ? -1 : x > rect.right - SIDE_PX ? 1 : 0;
      }
      holdOn(side ? `side:${side}` : null, () => opts.current.stepDay(side));
    } else {
      holdOn(null, () => {});
    }
    show({ ...d, x, y, target, overDay, side });
  }, [box, dayColumn]);

  const begin = (p: Press) => {
    const { item, mode } = p;
    window.clearTimeout(p.timer);
    press.current = null;
    // where in the block it was picked up, so it doesn't jump under the pointer
    const col = dayColumn.current?.getBoundingClientRect();
    const fromRow = item.start === null;
    grab.current = fromRow || !col ? Math.min(15, item.minutes / 2) : Math.max(0, minuteAtY(p.y - col.top) - item.start!);
    if (p.touch) navigator.vibrate?.(12);
    document.body.classList.add('cal-dragging');
    show({ item, mode, x: p.x, y: p.y, target: null, overDay: null, side: 0 });
    aim(p.x, p.y);
  };

  const end = (drop: boolean) => {
    const d = live.current;
    if (press.current) window.clearTimeout(press.current.timer);
    press.current = null;
    stopHold();
    if (!d) return;
    document.body.classList.remove('cal-dragging');
    show(null);
    // the click that follows a mouse drop must not open the item or add one where it landed
    justDropped.current = true;
    window.setTimeout(() => (justDropped.current = false), 0);
    if (drop && d.target) opts.current.onDrop(d.item, d.target);
  };

  /** Hand to a block's, a stretch handle's or a to-do row's onPointerDown. */
  const pressOn = (item: Item, mode: DragMode) => (e: ReactPointerEvent) => {
    if (e.button !== 0 || !canDrag(item) || live.current) return;
    if (mode === 'stretch') e.stopPropagation();
    const touch = e.pointerType !== 'mouse';
    const p: Press = { item, mode, x: e.clientX, y: e.clientY, touch, timer: 0 };
    if (touch || mode === 'stretch') p.timer = window.setTimeout(() => press.current === p && begin(p), touch ? TOUCH_HOLD_MS : 0);
    press.current = p;
  };

  useEffect(() => {
    const move = (e: PointerEvent) => {
      const p = press.current;
      if (live.current) {
        aim(e.clientX, e.clientY);
        return;
      }
      if (!p) return;
      const far = Math.hypot(e.clientX - p.x, e.clientY - p.y);
      if (p.touch && far > TOUCH_SLOP) end(false); // a swipe: let the hours scroll
      else if (!p.touch && far > MOUSE_SLOP) begin({ ...p, x: e.clientX, y: e.clientY });
    };
    const up = () => end(true);
    const cancel = () => end(false);
    const key = (e: KeyboardEvent) => e.key === 'Escape' && end(false);
    // once a finger drag has started, the page must not scroll under it
    const touchMove = (e: TouchEvent) => live.current && e.cancelable && e.preventDefault();
    window.addEventListener('pointermove', move);
    window.addEventListener('pointerup', up);
    window.addEventListener('pointercancel', cancel);
    window.addEventListener('keydown', key);
    window.addEventListener('touchmove', touchMove, { passive: false });
    return () => {
      window.removeEventListener('pointermove', move);
      window.removeEventListener('pointerup', up);
      window.removeEventListener('pointercancel', cancel);
      window.removeEventListener('keydown', key);
      window.removeEventListener('touchmove', touchMove);
    };
  }, [aim]); // eslint-disable-line react-hooks/exhaustive-deps

  // scroll the hours while the pointer rests near their top or bottom, and keep aiming as they move under it
  useEffect(() => {
    if (!drag) return;
    let id = 0;
    const tick = () => {
      const d = live.current;
      const el = box.current;
      if (d && el) {
        const r = el.getBoundingClientRect();
        const inside = d.x >= r.left && d.x <= r.right;
        // faster the deeper into the band (up to SCROLL_STEP px a frame at the edge and past it)
        const up = r.top + SCROLL_PX - d.y;
        const down = d.y - (r.bottom - SCROLL_PX);
        const into = !inside ? 0 : up > 0 && d.y > r.top - SCROLL_PX ? -up : down > 0 && d.y < r.bottom + SCROLL_PX ? down : 0;
        if (into) {
          const before = el.scrollTop;
          el.scrollTop += Math.sign(into) * Math.max(1, Math.round((SCROLL_STEP * Math.min(SCROLL_PX, Math.abs(into))) / SCROLL_PX));
          if (el.scrollTop !== before) aim(d.x, d.y);
        }
      }
      id = requestAnimationFrame(tick);
    };
    id = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(id);
  }, [Boolean(drag), aim, box]); // eslint-disable-line react-hooks/exhaustive-deps

  // the hours changed day under a held drag (a side flip): aim again so the preview follows
  useEffect(() => {
    const d = live.current;
    if (d) aim(d.x, d.y);
  }, [day, aim]);

  return { drag, pressOn, wasDrop: () => justDropped.current };
}
