// The Home calendar (ui/calview.py CalendarCard + the calendar half of ui/pages/home.py): the month with
// Week | Month and ‹ ›, the days with their dots, then the chosen day: its title, Today (on another day) and "+ Add",
// the hours (a box of their own that scrolls, opening at the first thing still ahead) and the to-do list with a
// quick-add field. The card takes about 60% of the page, so the alerts show below it.
//
// Items drag (useDrag.ts): a block moves in 15-minute steps and stretches by its bottom edge, a to-do row drops onto
// an hour to get a time, and anything drops onto a day in the grid to move there. Holding on ‹ › or at the hours'
// side edges flips the week or the day under the drag.

import { useEffect, useLayoutEffect, useMemo, useRef, useState, type CSSProperties, type MouseEvent, type PointerEvent, type RefObject } from 'react';
import { createPortal } from 'react-dom';
import { useAura } from '../app/store';
import { useNav } from '../app/nav';
import * as A from '../lib/agenda';
import { Hair, Icon, RoundButton, Segmented } from '../kit/kit';
import { ItemSheet } from './ItemSheet';
import {
  DAY_HEIGHT, GUTTER, HOUR, PAD, SHOW_TODO, START_HOURS, blocks, countText, focusY, hoursHeight, lookTone, minutesAt,
  spanText, taskSub, yOf,
} from './layout';
import { doneChanges, eventTimes, isLocked, newTask } from './rows';
import { canDrag, moveChanges, targetText, type Target } from './drag';
import { useDrag, type Drag, type DragMode } from './useDrag';
import { monthTitle } from './zone';
import { DEMO_SCHEDULE, asSchedule, hoursRange, hoursText, zoneDay, type DayZone } from './dayZones';
import { WorkHoursSheet } from './WorkHoursSheet';
import { Shades } from './ZonesPage';
import { allocTaskOf, frameFor, placeTasks, recoveryBusy, splitInPlace, RECOVERY_RESTORE, type Busy } from './allocate';
import { AllocateSheet } from './AllocateSheet';
import { DEFAULT_PREVIEW_SCORE } from '../lib/local';
import './calendar.css';

const WEEKDAYS = ['M', 'T', 'W', 'T', 'F', 'S', 'S'];
const toneVar = (token: string) => ({ ['--tone' as string]: `var(--${token})` }) as CSSProperties;

// -- the days ---------------------------------------------------------------------------------------------------

function DayGrid({ days, anchor, selected, today, month, marks, overDay, onPick }: {
  days: string[]; anchor: string; selected: string; today: string; month: boolean;
  marks: Map<string, Set<'event' | 'task'>>; overDay: string | null; onPick: (day: string) => void;
}) {
  return (
    <div className="cal-grid">
      {WEEKDAYS.map((w, i) => <span key={i} className="cal-wd">{w}</span>)}
      {days.map((d) => {
        const chosen = d === selected;
        const isToday = d === today;
        const out = month && d.slice(0, 7) !== anchor.slice(0, 7);
        const dots = (['event', 'task'] as const).filter((k) => marks.get(d)?.has(k));
        return (
          <button
            key={d}
            className={`cal-cell${chosen ? ' chosen' : ''}${isToday ? ' today' : ''}${out ? ' out' : ''}${d === overDay ? ' drop' : ''}`}
            data-day={d}
            onClick={() => onPick(d)}
            aria-label={d}
            aria-pressed={chosen}
          >
            <span className="cal-disc">{Number(d.slice(8))}</span>
            <span className="cal-dots">{dots.map((k) => <i key={k} className={`cal-dot ${k}`} />)}</span>
          </button>
        );
      })}
    </div>
  );
}

// -- the hours --------------------------------------------------------------------------------------------------

type Press = (item: A.Item, mode: DragMode) => (e: PointerEvent) => void;

function Hours({ day, timed, zones, now, height, box, column, drag, press, wasDrop, onSlot, onItem }: {
  day: string; timed: A.Item[]; zones: DayZone[]; now: number | null; height: number;
  box: RefObject<HTMLDivElement>; column: RefObject<HTMLDivElement>; drag: Drag | null; press: Press; wasDrop: () => boolean;
  onSlot: (minute: number) => void; onItem: (item: A.Item) => void;
}) {
  /** The day shown and whether it had items: open at the focus on a new day, or when its first items arrive. */
  const seen = useRef<{ day: string | null; had: boolean }>({ day: null, had: false });

  useLayoutEffect(() => {
    const has = timed.length > 0;
    const jump = seen.current.day !== day || (has && !seen.current.had);
    seen.current = { day, had: has };
    if (jump && box.current) box.current.scrollTop = focusY(timed, now);
  }, [day, timed, now]);

  const placed = useMemo(() => blocks(timed), [timed]);
  const lifted = drag ? A.keyOf(drag.item) : null;
  const ghost = drag?.target && drag.target.day === day && drag.target.start !== null ? drag.target : null;

  const tapArea = (e: MouseEvent<HTMLDivElement>) => {
    if (wasDrop()) return;
    const y = e.clientY - e.currentTarget.getBoundingClientRect().top;
    onSlot(minutesAt(y));
  };

  return (
    <div className="cal-hoursbox" style={{ height }}>
      <div className="cal-hours" ref={box}>
        <div className="cal-day" style={{ height: DAY_HEIGHT }} ref={column}>
          <Shades zones={zones} />
          {Array.from({ length: 25 }, (_, h) => (
            <div key={h} className="cal-hour" style={{ top: PAD + h * HOUR }}>
              {h < 24 && <span className="cal-hlabel" style={{ width: GUTTER - 8 }}>{`${String(h).padStart(2, '0')}:00`}</span>}
              <span className="cal-hline" style={{ left: GUTTER }} />
            </div>
          ))}
          <div className="cal-lanes" style={{ left: GUTTER + 4 }} onClick={tapArea} title="Tap an hour to add">
            {placed.map((b) => (
              <button
                key={b.key}
                className={`cal-block${b.item.done ? ' done' : ''}${b.short ? ' short' : ''}${canDrag(b.item) ? ' grab' : ''}${lifted === b.key ? ' lifted' : ''}`}
                style={{
                  ...toneVar(lookTone(b.item.look)),
                  top: b.top,
                  height: b.height,
                  left: `${(b.lane / b.lanes) * 100}%`,
                  width: `calc(${100 / b.lanes}% - 3px)`,
                }}
                onPointerDown={press(b.item, 'move')}
                onClick={(e) => {
                  e.stopPropagation();
                  if (!wasDrop()) onItem(b.item);
                }}
                title={`${b.item.title} · ${spanText(b.item)}${canDrag(b.item) ? ' · drag to move' : ''}`}
              >
                {b.item.kind === 'task' && (
                  <span className={`cal-ring${b.item.done ? ' on' : ''}`}>{b.item.done && <Icon name="check" size={9} stroke={3} />}</span>
                )}
                <span className="cal-btext">
                  <span className="cal-btitle">{b.short ? `${b.item.title} · ${A.clock(b.item.start ?? 0)}` : b.item.title}</span>
                  {!b.short && <span className="cal-bspan">{spanText(b.item)}</span>}
                </span>
                {canDrag(b.item) && !b.short && <span className="cal-stretch" onPointerDown={press(b.item, 'stretch')} title="Drag to change the length" />}
              </button>
            ))}
            {ghost && (
              <div className="cal-block cal-preview" style={{ ...toneVar(lookTone(drag!.item.look)), top: yOf(ghost.start!) + 1, height: Math.max(20, (ghost.minutes * HOUR) / 60 - 2) }} aria-hidden="true">
                <span className="cal-btext">
                  <span className="cal-btitle">{drag!.item.title}</span>
                  <span className="cal-bspan">{`${A.clock(ghost.start!)}–${A.clock(Math.min(ghost.start! + ghost.minutes, 24 * 60))}`}</span>
                </span>
              </div>
            )}
          </div>
          {now !== null && (
            <div className="cal-now" style={{ top: yOf(now), left: GUTTER }} aria-hidden="true">
              <i />
            </div>
          )}
        </div>
      </div>
      {drag?.side ? <div className={`cal-side ${drag.side < 0 ? 'l' : 'r'}`} aria-hidden="true"><Icon name={drag.side < 0 ? 'back' : 'chevron'} size={16} stroke={2.6} /></div> : null}
      {timed.length === 0 && !ghost && (
        <div className="cal-empty" style={{ left: GUTTER }}>
          <span>Nothing planned · tap to add</span>
        </div>
      )}
    </div>
  );
}

// -- tasks without a time ---------------------------------------------------------------------------------------

function TaskRow({ item, sub, lifted, onPress, onTick, onOpen }: {
  item: A.Item; sub: string; lifted: boolean; onPress: (e: PointerEvent) => void; onTick: () => void; onOpen: () => void;
}) {
  return (
    <div
      className={`cal-taskrow${canDrag(item) ? ' grab' : ''}${lifted ? ' lifted' : ''}`}
      role="button"
      tabIndex={0}
      onPointerDown={onPress}
      onClick={onOpen}
      onKeyDown={(e) => e.key === 'Enter' && onOpen()}
      title={canDrag(item) ? 'Tap to edit · drag onto an hour or a day' : 'Tap to edit'}
    >
      <button
        className={`cal-check${item.done ? ' on' : ''}`}
        aria-label={item.done ? 'Mark not done' : 'Mark done'}
        title={item.done ? 'Mark not done' : 'Mark done'}
        onPointerDown={(e) => e.stopPropagation()}
        onClick={(e) => {
          e.stopPropagation();
          onTick();
        }}
      >
        {item.done && <Icon name="check" size={15} stroke={2.6} />}
      </button>
      <span className="grow">
        <div className={item.done ? 'cal-tdone' : 'cal-ttitle'}>{item.title}</div>
        {sub && <div className={sub.startsWith('Overdue') ? 'cal-tsub warn' : 'cal-tsub'}>{sub}</div>}
      </span>
      <Icon className="chev" name="chevron" size={14} stroke={2.4} />
    </div>
  );
}

function QuickAdd({ placeholder, onAdd }: { placeholder: string; onAdd: (title: string) => void }) {
  const [text, setText] = useState('');
  const submit = () => {
    const title = text.trim();
    if (!title) return;
    setText('');
    onAdd(title);
  };
  return (
    <form className="cal-quick" onSubmit={(e) => { e.preventDefault(); submit(); }}>
      <Icon name="plus" size={20} stroke={2.2} color="var(--accent)" />
      <input className="field plain" placeholder={placeholder} value={text} aria-label={placeholder} onChange={(e) => setText(e.target.value)} />
      {text.trim() && <button type="submit" className="btn go sm">Add</button>}
    </form>
  );
}

/** What follows the pointer: the item and where it would land ("Put back" off any target). */
function DragChip({ drag }: { drag: Drag }) {
  return (
    <div className="cal-dragchip" style={{ left: drag.x + 14, top: drag.y + 14 }} aria-hidden="true">
      <b>{drag.item.title}</b>
      <span>{drag.target ? targetText(drag.target) : 'Let go here to put it back'}</span>
    </div>
  );
}

// -- the card ---------------------------------------------------------------------------------------------------

/** The line above today's hours: "Score 62 · 2 h heavy work". */
function zoneLine(status: string, score: number, heavyMinutes: number): string {
  if (status === 'needsSchedule') return 'Set your work hours to see zones';
  if (status === 'dayOff') return `Score ${score} · Day off`;
  return `Score ${score} · ${heavyMinutes ? `${hoursText(heavyMinutes)} heavy work` : 'Light work today'}`;
}

/**
 * `pageHeight`: the height of the page the card sits on (it takes about 60% of it).
 * `full`: on its own Calendar page, the hours box opens tall enough to show the whole working day at once, so the
 * zones and everything Auto-allocate places are visible together in one view instead of a two-hour window.
 */
export function CalendarCard({ pageHeight, full = false }: { pageHeight: number; full?: boolean }) {
  const aura = useAura();
  const nav = useNav();
  const { today, zone } = aura;
  const { events, tasks } = aura.data;

  const [month, setMonth] = useState(false);
  const [day, setDay] = useState(today);
  const [anchor, setAnchor] = useState(today);
  const [todoAll, setTodoAll] = useState(false);

  // when "today" moves (midnight, or the live snapshot replacing the sample) and today was showing, follow it
  const lastToday = useRef(today);
  useEffect(() => {
    if (lastToday.current === today) return;
    if (day === lastToday.current) {
      setDay(today);
      setAnchor(today);
    }
    lastToday.current = today;
  }, [today, day]);

  const days = month ? A.monthGrid(anchor) : A.weekOf(anchor);
  const marks = useMemo(() => A.marks(events, tasks, zone, today), [events, tasks, zone, today]);
  const items = useMemo(() => A.dayItems(day, events, tasks, zone, today), [day, events, tasks, zone, today]);
  // What the day shows: everything except a block the coach has split and hidden (auto:'split-src'). The source stays
  // in the data so re-running Auto-allocate can rebuild or restore it, but on screen its amber "part" pieces stand in
  // for it. Auto-allocate itself reads the full items.timed (so it sees the whole source), not this filtered list.
  const shownTimed = useMemo(() => items.timed.filter((i) => i.raw.auto !== 'split-src'), [items.timed]);
  const nowMinute = day === today ? (A.localParts(aura.now.toISOString(), zone)?.minute ?? null) : null;

  // -- the day zones. The demo shades every working day with the preview score (the real app would compute a score per
  // day from band data), so the zones show whichever day the visitor looks at — the same score Auto-allocate plans with,
  // so the two features always agree. The schedule falls back to the demo default (all seven days); a visitor's own
  // hours override it.
  const schedule = useMemo(() => asSchedule(aura.data.me.workSchedule) ?? DEMO_SCHEDULE, [aura.data.me.workSchedule]);
  const score = aura.local.previewScore ?? DEFAULT_PREVIEW_SCORE;
  const shades = useMemo(() => zoneDay(schedule, score, day), [schedule, score, day]);
  // On the full Calendar page the hours box is sized to the working day (an hour either side of the shaded zones).
  const workRange = useMemo(() => hoursRange(shades.zones), [shades.zones]);
  const fullHours = (workRange[1] - workRange[0]) * HOUR + 2 * PAD;
  const askHours = (first = false) => nav.openSheet({ render: (close) => <WorkHoursSheet close={close} first={first} /> });
  const asked = aura.local.zonesAsked === true;
  useEffect(() => {
    if (schedule || asked) return;
    aura.edit.zonesAsked();
    askHours(true);
  }, [schedule, asked]);

  // -- the card's size: about CALENDAR_SHARE of the page; the hours box gets what the rest leaves
  const card = useRef<HTMLDivElement>(null);
  const hoursWrap = useRef<HTMLDivElement>(null);
  const [hoursH, setHoursH] = useState(START_HOURS);
  useLayoutEffect(() => {
    if (full) return; // full mode sizes to the working day below, not to a share of the page
    if (!pageHeight || !card.current || !hoursWrap.current) return;
    const rest = card.current.offsetHeight - hoursWrap.current.offsetHeight;
    const next = hoursHeight(pageHeight, rest);
    if (next !== hoursH) setHoursH(next);
  });

  // -- moving around
  const pick = (d: string) => {
    setTodoAll((all) => all && d === day);
    setDay(d);
    if (!month || d.slice(0, 7) !== anchor.slice(0, 7)) setAnchor(d);
  };
  const step = (n: number) => {
    if (month) {
      const next = A.addMonths(anchor, n);
      const d = next.slice(0, 7) === today.slice(0, 7) ? today : `${next.slice(0, 7)}-01`;
      setDay(d);
      setAnchor(d);
    } else {
      const d = A.addDays(day, 7 * n);
      setDay(d);
      setAnchor(d);
    }
    setTodoAll(false);
  };
  const goToday = () => {
    setDay(today);
    setAnchor(today);
  };
  const setMode = (m: 'week' | 'month') => {
    setMonth(m === 'month');
    setAnchor(day);
  };

  // -- adding and editing
  const add = (minute: number | null = null) =>
    nav.openSheet({ render: (close) => <ItemSheet close={close} day={day} at={minute} /> });

  // A recovery break the planner laid out. Tapping it is the recovery action: the person rests, so their capacity
  // score rises (by RECOVERY_RESTORE, capped at 100). Allocation reads the score each run, so re-allocating then fits
  // a little more in. Doing it again does nothing — the break is marked so the score can't be tapped up twice.
  const recover = (item: A.Item) => {
    if (item.raw.auto !== 'recovery') return;
    const next = Math.min(100, score + RECOVERY_RESTORE);
    aura.edit.previewScore(next);
    aura.edit.change('events', item.id, { title: `✓ ${item.title}`, auto: 'recovered' });
    nav.showToast(`Recovered · capacity ${next}. Re-allocate to fit more in.`);
  };

  const open = (item: A.Item) => {
    if (isMyBreak(item)) {
      recover(item);
      return;
    }
    if (item.kind === 'task') {
      // a done task is edited on its own day, not the day it was ticked
      const when = A.taskWhen({ ...item.raw, status: 'open' }, zone);
      nav.openSheet({
        render: (close) => (
          <ItemSheet close={close} kind="task" item={item} day={when.day ?? day} at={when.minute} minutes={item.minutes} dated={when.day !== null} locked={isLocked(item.raw)} />
        ),
      });
      return;
    }
    nav.openSheet({
      render: (close) => <ItemSheet close={close} kind="event" item={item} day={item.day ?? day} at={item.start} minutes={item.minutes} dated />,
    });
  };

  const tick = (item: A.Item) => aura.edit.change('tasks', item.id, doneChanges(!item.done, new Date(), zone));

  // -- dragging: a drop writes the new day/time/length and shows the day it went to
  const hoursBox = useRef<HTMLDivElement>(null);
  const dayColumn = useRef<HTMLDivElement>(null);
  // Full page: make the hours box as tall as the working day and open it at the start of work, so the whole day shows.
  useLayoutEffect(() => {
    if (!full) return;
    if (hoursH !== fullHours) setHoursH(fullHours);
    if (hoursBox.current) hoursBox.current.scrollTop = Math.max(0, yOf(workRange[0] * 60) - PAD);
  }, [full, fullHours, workRange, day, hoursH]);
  const drop = (item: A.Item, target: Target) => {
    const changes = moveChanges(item, target, zone, today);
    if (!changes) return;
    aura.edit.change(item.kind === 'task' ? 'tasks' : 'events', item.id, changes);
    nav.showToast(`${item.title} · ${targetText(target)}`);
    if (target.day !== day) pick(target.day);
  };
  const { drag, pressOn, wasDrop } = useDrag({ box: hoursBox, dayColumn, day, stepDay: (n) => pick(A.addDays(day, n)), flip: step, onDrop: drop });

  const quickAdd = (title: string) => aura.edit.add('tasks', newTask(title, day === today ? null : day, null, null, zone, today));

  // -- auto-allocate (O6): fit the day's undated open tasks into the free working hours, then show what went where.
  // Uses the same work schedule and capacity score as the zones, so hard tasks land in the morning heavy-work zone.
  const openTodo = items.todo.filter((i) => !i.done && i.raw.workMode !== 'meeting' && i.raw.parked !== true);
  const isMyBreak = (i: A.Item) => i.kind === 'event' && (i.raw.auto === 'recovery' || i.raw.auto === 'recovered');
  // Blocks the planner laid out last run: recovery breaks and the extra pieces of a split task. All are cleared and
  // laid out fresh each run, so re-running never stacks them, and they are not treated as busy.
  const isAutoBlock = (i: A.Item) => i.kind === 'event' && (isMyBreak(i) || i.raw.auto === 'part');
  // A committed block the coach may split around a recovery period: a timed task, or a focus (deep-work) event. Not a
  // meeting, not a personal recovery/rest block, not one of the coach's own auto blocks.
  const isFlexibleWork = (i: A.Item) => i.start !== null && !isAutoBlock(i) && (i.kind === 'task' || i.look === 'focus');
  // A committed block the coach leaves where it is (only warns if it sits on a recovery period): meetings, personal
  // recovery/rest events, and anything else already timed.
  const isFixedCommit = (i: A.Item) => i.start !== null && !isAutoBlock(i) && !isFlexibleWork(i);
  const crosses = (start: number, end: number, spans: readonly Busy[]) => spans.some((s) => start < s.end && end > s.start);
  const autoAllocate = () => {
    const frame = frameFor({ schedule, score, day, today, nowMinute });
    // Every recovery period the zones draw (short recoveries + lunch) is rest that must stay free. Meetings and other
    // fixed commitments are busy too. A previous run's own breaks and split pieces are not busy — they are cleared and
    // laid out fresh below.
    const recovery = recoveryBusy(schedule, score);
    const fixedBusy = items.timed.filter(isFixedCommit).map((i) => ({ start: i.start as number, end: (i.start as number) + i.minutes }));
    const work = items.timed.filter(isFlexibleWork).sort((a, b) => (a.start as number) - (b.start as number));

    // Pass A: a committed work block (a deep-work event or a timed task) that crosses a recovery period is split in
    // place around it, so the rest stays free. The source is kept but hidden (auto:'split-src') while its amber "part"
    // pieces stand in for it; a block that no longer crosses is shown whole again. `blocked` grows as each block is
    // resolved, so later work (and the to-do queue) flow around what is already laid.
    const blocked: Busy[] = [...recovery, ...fixedBusy];
    const parts: { title: string; start: number; end: number }[] = [];
    let committedSplit = 0;
    work.forEach((w, idx) => {
      const start = w.start as number;
      const whole = { start, end: start + w.minutes };
      const list = w.kind === 'task' ? 'tasks' : 'events';
      const show = () => { if (w.raw.auto === 'split-src') aura.edit.change(list, w.id, { auto: null }); blocked.push(whole); };
      if (!crosses(start, whole.end, recovery)) { show(); return; }
      // Split around the recovery, anchored at the block's own start; keep the other work blocks (future ones by their
      // whole span) out of the way so a tail never runs into them.
      const future = work.slice(idx + 1).map((o) => ({ start: o.start as number, end: (o.start as number) + o.minutes }));
      const { pieces } = splitInPlace({ start, minutes: w.minutes }, [...blocked, ...future], frame.dayEnd);
      if (pieces.length <= 1) { show(); return; } // couldn't split usefully: leave it where it is
      aura.edit.change(list, w.id, { auto: 'split-src' });
      committedSplit += 1;
      pieces.forEach((pc, i) => {
        parts.push({ title: `${w.title} · part ${i + 1}/${pieces.length}`, start: pc.start, end: pc.end });
        blocked.push({ start: pc.start, end: pc.end });
      });
    });

    // Pass B: fit the undated to-do tasks into what's left, now also around the committed-work pieces.
    const result = placeTasks(openTodo.map((i) => allocTaskOf(i.raw, day, zone)), blocked, frame);

    // Clear last run's own blocks (recovery breaks + all split pieces), then lay this run's out fresh.
    for (const i of items.timed.filter(isAutoBlock)) aura.edit.remove('events', i.id);
    for (const pc of parts) {
      aura.edit.add('events', { title: pc.title, ...eventTimes(day, pc.start, pc.end - pc.start, zone), kind: 'focus', auto: 'part' });
    }
    // Group the to-do placements by task: a task placed as one block is timed in place (it leaves the to-do list); a
    // task split into pieces keeps its to-do row (so re-allocate can re-split it) and its pieces show as work sessions.
    const byId = new Map(openTodo.map((i) => [i.id, i] as const));
    const byTask = new Map<string, typeof result.placed>();
    for (const p of result.placed) byTask.set(p.task.id, [...(byTask.get(p.task.id) ?? []), p]);
    for (const [id, segs] of byTask) {
      const item = byId.get(id);
      if (!item) continue;
      const pcs = [...segs].sort((a, b) => a.start - b.start);
      if (pcs.length === 1 && !pcs[0].part) {
        const changes = moveChanges(item, { day, start: pcs[0].start, minutes: item.minutes }, zone, today);
        if (changes) aura.edit.change('tasks', item.id, changes);
        continue;
      }
      pcs.forEach((pc, i) =>
        aura.edit.add('events', {
          title: `${item.title} · part ${i + 1}/${pcs.length}`,
          ...eventTimes(day, pc.start, pc.end - pc.start, zone), kind: 'focus', auto: 'part',
        }));
    }
    // The recovery breaks become purple events on the day, so the rest between hard tasks is there to see.
    for (const b of result.breaks) {
      aura.edit.add('events', { title: b.title, ...eventTimes(day, b.start, b.end - b.start, zone), kind: 'recovery', auto: 'recovery' });
    }
    // Meetings the coach left on a recovery period — surfaced as a note, never moved.
    const meetingsOnRecovery = items.timed.filter((i) => isFixedCommit(i) && i.look === 'meeting' && crosses(i.start as number, (i.start as number) + i.minutes, recovery)).length;
    nav.openSheet({ render: (close) => <AllocateSheet close={close} result={result} committedSplit={committedSplit} meetingsOnRecovery={meetingsOnRecovery} />, tall: true });
  };

  // -- the to-do list: two open tasks, the rest behind "Show all"
  const short = items.todo.filter((i) => !i.done).slice(0, SHOW_TODO);
  const foldable = short.length < items.todo.length;
  const shown = todoAll ? items.todo : short;
  const title = A.dayLabel(day, today);

  return (
    <div className="card cal-card" ref={card}>
      <div className="cal-head">
        <span className="cal-month">{monthTitle(anchor)}</span>
        <div className="cal-mode">
          <Segmented options={[{ value: 'week', label: 'Week' }, { value: 'month', label: 'Month' }]} value={month ? 'month' : 'week'} onChange={setMode} />
        </div>
        <span className="cal-flip" data-flip="-1"><RoundButton icon="back" label="Earlier" onClick={() => step(-1)} /></span>
        <span className="cal-flip" data-flip="1"><RoundButton icon="chevron" label="Later" onClick={() => step(1)} /></span>
      </div>

      <DayGrid days={days} anchor={anchor} selected={day} today={today} month={month} marks={marks} overDay={drag?.overDay ?? null} onPick={pick} />
      <Hair />

      <div className="cal-dayhead">
        <span className="grow">
          <div className="cal-daytitle">{title}</div>
          <div className="cal-count">{countText(shownTimed.length + items.todo.length)}</div>
        </span>
        {day !== today && <button className="chip" onClick={goToday} title="Back to today">Today</button>}
        {!full && <button className="chip" onClick={() => nav.push({ name: 'calendar' })} title="Open the full calendar">Full view</button>}
        <button className="btn go cal-add" onClick={() => add()} title="Add a task or an event">+&nbsp;&nbsp;Add</button>
      </div>

      {day === today && (
        <button
          className="cal-zoneline"
          onClick={() => (schedule ? nav.push({ name: 'zones' }) : askHours())}
          title={schedule ? 'See the whole day' : 'Set your work hours'}
        >
          <span>{zoneLine(shades.status, score, shades.heavyMinutes)}</span>
          <Icon name="chevron" size={13} stroke={2.4} />
        </button>
      )}

      <div ref={hoursWrap}>
        <Hours
          day={day} timed={shownTimed} zones={shades.zones} now={nowMinute} height={hoursH} box={hoursBox} column={dayColumn}
          drag={drag} press={pressOn} wasDrop={wasDrop} onSlot={(m) => add(m)} onItem={open}
        />
      </div>

      {items.todo.length > 0 && (
        <div className="cal-todohead">
          <span>To do</span>
          <div className="cal-todoacts">
            {openTodo.length > 0 && (
              <button className="link" onClick={autoAllocate} title="Fit these into the day's free hours">Auto-allocate</button>
            )}
            {foldable && (
              <button className="link" onClick={() => setTodoAll((v) => !v)}>
                {todoAll ? 'Show less' : `Show all ${items.todo.length}`}
              </button>
            )}
          </div>
        </div>
      )}
      {shown.length > 0 && (
        <div className="cal-todo">
          {shown.map((item) => (
            <TaskRow
              key={A.keyOf(item)} item={item} sub={taskSub(item, day, today, zone)} lifted={drag !== null && A.keyOf(drag.item) === A.keyOf(item)}
              onPress={pressOn(item, 'move')} onTick={() => tick(item)} onOpen={() => !wasDrop() && open(item)}
            />
          ))}
        </div>
      )}
      {drag && createPortal(<DragChip drag={drag} />, document.body)}
      <QuickAdd placeholder={day === today ? 'Add a task for today' : `Add a task for ${title}`} onAdd={quickAdd} />
    </div>
  );
}
