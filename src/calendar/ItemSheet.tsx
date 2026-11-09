// Add or edit a task or an event (ui/calview.py ItemSheet). A task has a title and, optionally, a day and a time
// on it (no day: "Anytime", on today's list). An event always has a day, a start and a length. Editing a task also
// offers Mark done / not done and Delete; an event offers Delete. A task placed by a calendar block keeps its time.
// Every change goes to aura.edit, so it stays in this visitor's browser.

import { useState, type CSSProperties, type ReactNode } from 'react';
import { useAura } from '../app/store';
import { useNav } from '../app/nav';
import { addDays, clock, dayItems, firstClash, localParts, type Item } from '../lib/agenda';
import { Icon, Segmented, SheetFrame, Stepper, Switch } from '../kit/kit';
import { Picker } from '../kit/Picker';
import { deadlineFields, doneChanges, eventTimes, newEvent, newTask, taskChanges } from './rows';
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

// How tough the task feels → its demand (0–100). Deep (≥67) triggers a recovery break after it. The coach reads
// demand to pace the day's recovery rhythm; see allocate.ts DEEP_DEMAND.
const TOUGHNESS = [
  { key: 'light', label: 'Light', demand: 25, hint: 'easy, low effort' },
  { key: 'focused', label: 'Focused', demand: 55, hint: 'ordinary work' },
  { key: 'hard', label: 'Hard', demand: 72, hint: 'needs real focus' },
  { key: 'deep', label: 'Deep', demand: 88, hint: 'draining — earns a break' },
] as const;

const rawNum = (raw: Record<string, unknown> | undefined, key: string): number | null => {
  const v = raw?.[key];
  return typeof v === 'number' && Number.isFinite(v) ? v : null;
};

/** The toughness level nearest a stored demand, so editing shows the level the task was saved with. */
const toughnessFor = (demand: number | null) =>
  demand === null ? 'focused' : TOUGHNESS.reduce((a, b) => (Math.abs(b.demand - demand) < Math.abs(a.demand - demand) ? b : a)).key;

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
      <Picker className="bare" label="Hour" value={h} options={Array.from({ length: 24 }, (_, i) => ({ value: i, label: two(i) }))} onChange={(x) => onChange(x * 60 + m)} />
      <span>:</span>
      <Picker className="bare" label="Minute" value={m} options={minutes.map((x) => ({ value: x, label: two(x) }))} onChange={(x) => onChange(h * 60 + x)} />
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
  const [length, setLength] = useState(clampLength(rawNum(item?.raw, 'estimateMinutes') ?? minutes));

  // Auto-allocate inputs (tasks only): how tough it feels, and a deadline the coach plans around.
  const [tough, setTough] = useState<string>(toughnessFor(rawNum(item?.raw, 'demand')));
  const dl = localParts(String(item?.raw?.deadlineAt ?? ''), zone);
  const [hasDeadline, setHasDeadline] = useState(dl !== null);
  const [deadlineDay, setDeadlineDay] = useState(dl?.day ?? startDay);
  const [deadlineHasTime, setDeadlineHasTime] = useState(item?.raw?.deadlineHasTime === true);
  const [deadlineTime, setDeadlineTime] = useState(dl?.minute ?? 17 * 60);

  const task = kind === 'task';
  const isDated = !task || hasDay;
  const isTimed = !task || (isDated && hasTime);
  const ok = title.trim().length > 0;

  const clash = isTimed
    ? firstClash(dayItems(day, aura.data.events, aura.data.tasks, zone, today).timed, start, length, editing ? { kind: item.kind, id: item.id } : undefined)
    : null;

  const pickKind = (k: 'task' | 'event') => {
    setKind(k);
    if (k === 'event') setHasDay(true);
  };

  // The Auto-allocate inputs a task carries: how long it takes, how tough it is, and the deadline to plan around.
  const allocFields = () => ({
    estimateMinutes: length,
    demand: TOUGHNESS.find((t) => t.key === tough)?.demand ?? 55,
    ...(hasDeadline ? deadlineFields(deadlineDay, deadlineHasTime ? deadlineTime : null, zone) : { deadlineAt: null, deadlineHasTime: false }),
  });

  const commit = () => {
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
        aura.edit.add('tasks', newTask(name, d, t, t === null ? null : length, zone, today, allocFields()));
        nav.showToast('Task added');
      }
      return;
    }
    if (item.kind === 'event') {
      aura.edit.change('events', item.id, { title: name, ...eventTimes(day, start, length, zone) });
      return;
    }
    const changes = taskChanges(item.raw, name, d, t, length, locked, zone, today, locked ? {} : allocFields());
    if (changes) aura.edit.change('tasks', item.id, changes);
  };

  const save = () => {
    if (!title.trim()) return;
    if (clash) {
      nav.showAlert({
        title: 'Times overlap',
        body: `This runs over “${clash.title}” at ${clock(clash.start as number)}. Add it anyway?`,
        buttons: [
          { label: 'Cancel', role: 'cancel' },
          { label: 'Add anyway', role: 'default', onPress: commit },
        ],
      });
      return;
    }
    commit();
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
          {task && !isTimed && (
            <FormRow title="Duration" icon="timer" tone="recovery">
              <Stepper value={length} min={15} max={480} step={15} format={(v) => `${v} min`} onChange={setLength} />
            </FormRow>
          )}
          {task && (
            <FormRow title="Toughness" icon="bolt" tone="workout">
              <Picker label="How tough" value={tough} options={TOUGHNESS.map((t) => ({ value: t.key, label: t.label }))} onChange={setTough} />
            </FormRow>
          )}
          {task && (
            <FormRow title="Deadline" icon="flag" tone="stress">
              <Switch on={hasDeadline} onChange={setHasDeadline} label="Give the task a deadline" />
            </FormRow>
          )}
          {task && hasDeadline && (
            <>
              <FormRow title="Due by">
                <DateStepper day={deadlineDay} onChange={setDeadlineDay} />
              </FormRow>
              <FormRow title="At a time">
                <Switch on={deadlineHasTime} onChange={setDeadlineHasTime} label="Finish before a set time" />
              </FormRow>
              {deadlineHasTime && (
                <FormRow title="Before">
                  <TimePill value={deadlineTime} onChange={setDeadlineTime} />
                </FormRow>
              )}
            </>
          )}
          {task && (
            <div className="muted cal-note">{TOUGHNESS.find((t) => t.key === tough)?.hint}{hasDeadline ? ` · Auto-allocate fits it in before ${deadlineHasTime ? `${clock(deadlineTime)} on ` : ''}${shortDay(deadlineDay)}` : ' · Auto-allocate finds it a time'}.</div>
          )}
          {clash && (
            <div className="muted cal-note warn">Overlaps “{clash.title}” · {clock(clash.start as number)}</div>
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
