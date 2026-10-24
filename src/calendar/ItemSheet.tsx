// Add or edit a task or an event (ui/calview.py ItemSheet). A task has a title and, optionally, a day and a time
// on it (no day: "Anytime", on today's list). An event always has a day, a start and a length. Editing a task also
// offers Mark done / not done and Delete; an event offers Delete. A task placed by a calendar block keeps its time.
// Every change goes to aura.edit, so it stays in this visitor's browser.

import { useState, type CSSProperties, type ReactNode } from 'react';
import { useAura } from '../app/store';
import { useNav } from '../app/nav';
import { addDays, localParts, type Item } from '../lib/agenda';
import { Icon, Segmented, SheetFrame, Stepper, Switch } from '../kit/kit';
import { doneChanges, eventTimes, newEvent, newTask, taskChanges } from './rows';
import { shortDay } from './zone';

export interface ItemSheetProps {
  close: () => void;
  /** The day it opens on. */
  day: string;
  kind?: 'task' | 'event';
  /** Set when editing. */
  item?: Item;
  /** The start, in minutes after midnight (a tapped hour, or the item's own time). */
  at?: number | null;
  minutes?: number;
  /** The task is on a day (default: not today, an event, or a time given). */
  dated?: boolean;
  /** A task placed by a calendar block: only its title changes. */
  locked?: boolean;
}

const clampLength = (n: number) => Math.min(480, Math.max(15, Math.round(n) || 30));

function FormRow({ title, icon, tone, children }: { title: string; icon?: string; tone?: string; children?: ReactNode }) {
  return (
    <div className={`row${icon ? ' with-icon' : ''}`}>
      {icon && (
        <span className="cal-ico" style={{ ['--tone' as string]: `var(--${tone})` } as CSSProperties}>
          <Icon name={icon} size={18} stroke={2} />
        </span>
      )}
      <span className="grow">
        <div className="title">{title}</div>
      </span>
      {children}
    </div>
  );
}

/** A grey 24-hour time pill ("15:00"), five-minute steps; the browser's own time field would follow its locale. */
export function TimePill({ value, onChange }: { value: number; onChange: (minute: number) => void }) {
  const h = Math.floor(value / 60);
  const m = value % 60;
  const minutes = [...new Set([...Array.from({ length: 12 }, (_, i) => i * 5), m])].sort((a, b) => a - b);
  const two = (n: number) => String(n).padStart(2, '0');
  return (
    <span className="cal-timepill">
      <select aria-label="Hour" value={h} onChange={(e) => onChange(Number(e.target.value) * 60 + m)}>
        {Array.from({ length: 24 }, (_, i) => <option key={i} value={i}>{two(i)}</option>)}
      </select>
      <span>:</span>
      <select aria-label="Minute" value={m} onChange={(e) => onChange(h * 60 + Number(e.target.value))}>
        {minutes.map((x) => <option key={x} value={x}>{two(x)}</option>)}
      </select>
    </span>
  );
}

/** ‹ Sat 24 Oct ›: a day at a time. */
function DateStepper({ day, onChange }: { day: string; onChange: (day: string) => void }) {
  return (
    <span className="stepper cal-datestep">
      <button aria-label="Day before" onClick={() => onChange(addDays(day, -1))}><Icon name="back" size={14} stroke={2.2} /></button>
      <span className="val">{shortDay(day)}</span>
      <button aria-label="Day after" onClick={() => onChange(addDays(day, 1))}><Icon name="chevron" size={14} stroke={2.2} /></button>
    </span>
  );
}

export function ItemSheet({ close, day: startDay, kind: startKind = 'task', item, at = null, minutes = 30, dated, locked = false }: ItemSheetProps) {
  const aura = useAura();
  const nav = useNav();
  const { zone, today } = aura;
  const editing = item !== undefined;

  const nowMinute = localParts(aura.now.toISOString(), zone)?.minute ?? 0;
  const [kind, setKind] = useState<'task' | 'event'>(startKind);
  const [title, setTitle] = useState(item?.title ?? '');
  const [hasDay, setHasDay] = useState(dated ?? (startDay !== today || startKind === 'event' || at !== null));
  const [day, setDay] = useState(startDay);
  const [hasTime, setHasTime] = useState(at !== null);
  const [start, setStart] = useState(at ?? Math.min(23, Math.floor(nowMinute / 60) + 1) * 60);
  const [length, setLength] = useState(clampLength(minutes));

  const task = kind === 'task';
  const isDated = !task || hasDay;
  const isTimed = !task || (isDated && hasTime);
  const ok = title.trim().length > 0;

  const pickKind = (k: 'task' | 'event') => {
    setKind(k);
    if (k === 'event') setHasDay(true);
  };

  const save = () => {
    const name = title.trim();
    if (!name) return;
    const d = isDated ? day : null;
    const t = isTimed ? start : null;
    close();
    if (!editing) {
      if (kind === 'event') {
        aura.edit.add('events', newEvent(name, day, start, length, zone));
        nav.showToast('Added 1 to your calendar');
      } else {
        aura.edit.add('tasks', newTask(name, d, t, t === null ? null : length, zone, today));
        nav.showToast('Task added');
      }
      return;
    }
    if (item.kind === 'event') {
      aura.edit.change('events', item.id, { title: name, ...eventTimes(day, start, length, zone) });
      return;
    }
    const changes = taskChanges(item.raw, name, d, t, length, locked, zone, today);
    if (changes) aura.edit.change('tasks', item.id, changes);
  };

  const toggleDone = () => {
    if (!item) return;
    close();
    aura.edit.change('tasks', item.id, doneChanges(!item.done, new Date(), zone));
  };

  const remove = () => {
    if (!item) return;
    const what = item.kind;
    nav.showAlert({
      title: `Delete this ${what}?`,
      body: 'It goes from this browser only; the shared demo data stays as it is.',
      buttons: [
        { label: 'Cancel', role: 'cancel' },
        {
          label: 'Delete',
          role: 'destructive',
          onPress: () => {
            close();
            aura.edit.remove(what === 'task' ? 'tasks' : 'events', item.id);
            nav.showToast(what === 'task' ? 'Task deleted' : 'Event deleted');
          },
        },
      ],
    });
  };

  const heading = !editing ? 'New' : item.kind === 'task' ? 'Task' : 'Event';

  return (
    <SheetFrame title={heading} onCancel={close} action={editing ? 'Save' : 'Add'} onAction={save} actionDisabled={!ok}>
      {!editing && (
        <Segmented options={[{ value: 'task', label: 'Task' }, { value: 'event', label: 'Event' }]} value={kind} onChange={pickKind} />
      )}
      <div className="list">
        <div className="row">
          <input
            className="field plain cal-title"
            placeholder="Title"
            value={title}
            autoFocus={!editing}
            aria-label="Title"
            onChange={(e) => setTitle(e.target.value)}
            onKeyDown={(e) => e.key === 'Enter' && save()}
          />
        </div>
      </div>
      {locked ? (
        <div className="muted cal-note">Its time comes from its calendar block.</div>
      ) : (
        <div className="list">
          {task && (
            <FormRow title="Date" icon="cal" tone="blue">
              <Switch on={hasDay} onChange={setHasDay} label="Put the task on a day" />
            </FormRow>
          )}
          {isDated && (
            <FormRow title="Day">
              <DateStepper day={day} onChange={setDay} />
            </FormRow>
          )}
          {task && isDated && (
            <FormRow title="Time" icon="clock" tone="stress">
              <Switch on={hasTime} onChange={setHasTime} label="Give the task a time" />
            </FormRow>
          )}
          {isTimed && (
            <>
              <FormRow title="Starts">
                <TimePill value={start} onChange={setStart} />
              </FormRow>
              <FormRow title="Length">
                <Stepper value={length} min={15} max={480} step={15} format={(v) => `${v} min`} onChange={setLength} />
              </FormRow>
            </>
          )}
        </div>
      )}
      {editing && (
        <div className="list">
          {item.kind === 'task' && (
            <button className="row with-icon" onClick={toggleDone}>
              <span className="cal-ico" style={{ ['--tone' as string]: 'var(--recovery)' } as CSSProperties}><Icon name="check" size={18} stroke={2.2} /></span>
              <span className="grow"><div className="title link">{item.done ? 'Mark not done' : 'Mark done'}</div></span>
            </button>
          )}
          <button className="row with-icon" onClick={remove}>
            <span className="cal-ico" style={{ ['--tone' as string]: 'var(--workout)' } as CSSProperties}><Icon name="trash" size={18} stroke={2} /></span>
            <span className="grow"><div className="title danger">Delete {item.kind}</div></span>
          </button>
        </div>
      )}
    </SheetFrame>
  );
}
