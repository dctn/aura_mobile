// Small pieces the Search-side screens share: the back label hook, a titled list, the water drops, and the editor sheets
// Settings opens (a number with − / +, a choice, a line of text, a birth date). Local stand-ins for kit pieces the
// PyQt app has (kit.TitledGroup, kit.DropRow, profile.NumberSheet, profile.BirthSheet).

import { useState, type ReactNode } from 'react';
import { backLabel, useNav } from '../app/nav';
import { tone } from '../lib/catalog';
import { Group, Icon, Row, SheetFrame, Stepper } from '../kit/kit';
import { Picker } from '../kit/Picker';
import { age } from './logic';
import './search.css';

/** "‹ <page below>" for a pushed page. */
export function useBackLabel(): string {
  return backLabel(useNav());
}

/** A list under a bold title ("Body", "Goals"), the Settings look. */
export function TitledGroup({ title, children }: { title?: string; children: ReactNode }) {
  return (
    <div className="sx-tgroup">
      {title && <div className="sx-ttitle">{title}</div>}
      <div className="list">{children}</div>
    </div>
  );
}

const DROP = 'M12 2.5C14.5 5.5 19 10 19 14.5a7 7 0 0 1-14 0C5 10 9.5 5.5 12 2.5Z';

/** A row of water drops: the first `filled` solid, the rest faint (glasses drunk against the goal). */
export function DropRow({ filled, total, accent = 'blue', size = 24 }: { filled: number; total: number; accent?: string; size?: number }) {
  const n = Math.min(24, Math.max(filled, total));
  return (
    <div className="sx-drops" style={{ ['--tone' as string]: tone(accent) }} aria-label={`${filled} of ${total} glasses`}>
      {Array.from({ length: n }, (_, i) => (
        <svg key={i} width={size} height={size} viewBox="0 0 24 24" className={i < filled ? 'on' : ''} aria-hidden="true">
          <path d={DROP} />
        </svg>
      ))}
    </div>
  );
}

/** A thin rounded progress line. */
export function ProgressLine({ share }: { share: number }) {
  return (
    <div className="sx-progress">
      <span style={{ width: `${Math.min(1, Math.max(0, share)) * 100}%` }} />
    </div>
  );
}

const fmtNumber = (v: number) => v.toLocaleString('en-US');

/** One number with − and +, big, with four jump chips under it; Save hands it back. */
export function NumberSheet({ title, value, min, max, step, unit = '', quick, note, onSave, close }: {
  title: string;
  value: number;
  min: number;
  max: number;
  step: number;
  unit?: string;
  quick: [number, number];
  note?: string;
  onSave: (v: number) => void;
  close: () => void;
}) {
  const [v, setV] = useState(value);
  const clamp = (x: number) => Math.min(max, Math.max(min, x));
  const [small, big] = quick;
  return (
    <SheetFrame title={title} onCancel={close} action="Save" onAction={() => { onSave(v); close(); }}>
      <div className="sx-center">
        <Stepper value={v} min={min} max={max} step={step} format={(x) => `${fmtNumber(x)}${unit ? ` ${unit}` : ''}`} onChange={setV} />
      </div>
      <div className="sx-jumps">
        {[-big, -small, small, big].map((j) => (
          <button key={j} className="chip" onClick={() => setV(clamp(v + j))}>
            {j > 0 ? '+' : '−'}{fmtNumber(Math.abs(j))}
          </button>
        ))}
      </div>
      {note && <div className="foot">{note}</div>}
    </SheetFrame>
  );
}

/** Pick one of a few: a tap answers and closes. */
export function ChoiceSheet<T extends string>({ title, options, value, onPick, close }: {
  title: string;
  options: { value: T; label: string }[];
  value: T;
  onPick: (v: T) => void;
  close: () => void;
}) {
  return (
    <SheetFrame title={title} onCancel={close}>
      <Group>
        {options.map((o) => (
          <Row
            key={o.value}
            title={o.label}
            trailing={o.value === value ? <span className="sx-check"><Icon name="check" size={18} stroke={2.4} /></span> : undefined}
            onClick={() => { onPick(o.value); close(); }}
          />
        ))}
      </Group>
    </SheetFrame>
  );
}

/** One line of text (the name). */
export function TextSheet({ title, value, placeholder, onSave, close }: { title: string; value: string; placeholder?: string; onSave: (v: string) => void; close: () => void }) {
  const [v, setV] = useState(value);
  const ok = v.trim().length > 0;
  const save = () => { if (ok) { onSave(v.trim()); close(); } };
  return (
    <SheetFrame title={title} onCancel={close} action="Save" onAction={save} actionDisabled={!ok}>
      <input className="field" autoFocus value={v} placeholder={placeholder} maxLength={60} onChange={(e) => setV(e.target.value)} onKeyDown={(e) => e.key === 'Enter' && save()} />
    </SheetFrame>
  );
}

/** A time of day as hour and minute lists (24-hour, five-minute steps, like the PyQt time pill). */
export function TimeSheet({ title, value, onSave, close }: { title: string; value: string; onSave: (hhmm: string) => void; close: () => void }) {
  const [h0, m0] = /^\d{1,2}:\d{2}/.test(value) ? value.split(':').map(Number) : [7, 0];
  const [h, setH] = useState(h0);
  const [m, setM] = useState(m0 - (m0 % 5));
  return (
    <SheetFrame title={title} onCancel={close} action="Save" onAction={() => { onSave(`${pad(h)}:${pad(m)}`); close(); }}>
      <div className="sx-clock">
        <Picker className="field" label="Hour" value={h} options={Array.from({ length: 24 }, (_, i) => ({ value: i, label: pad(i) }))} onChange={setH} />
        <span className="sx-colon">:</span>
        <Picker className="field" label="Minute" value={m} options={Array.from({ length: 12 }, (_, i) => ({ value: i * 5, label: pad(i * 5) }))} onChange={setM} />
      </div>
    </SheetFrame>
  );
}

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
const LONG_MONTHS = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December'];
const daysIn = (y: number, m: number) => new Date(Date.UTC(y, m, 0)).getUTCDate();
const pad = (n: number) => String(n).padStart(2, '0');

/** "12 Jun 1994"; `long` gives "12 June 1994". */
export function dateText(day: string, long = false): string {
  const [y, m, d] = day.slice(0, 10).split('-').map(Number);
  return `${d} ${(long ? LONG_MONTHS : MONTHS)[m - 1]} ${y}`;
}

/** Day, month and year as three lists: quicker than a calendar for a birth date. */
export function BirthSheet({ value, today, onSave, close }: { value: string | null; today: string; onSave: (day: string) => void; close: () => void }) {
  const latestYear = Number(today.slice(0, 4)) - 5;
  const start = value && /^\d{4}-\d{2}-\d{2}/.test(value) ? value.slice(0, 10) : '1990-01-01';
  const [y0, m0, d0] = start.split('-').map(Number);
  const [y, setY] = useState(Math.min(y0, latestYear));
  const [m, setM] = useState(m0);
  const [d, setD] = useState(d0);
  const day = `${y}-${pad(m)}-${pad(Math.min(d, daysIn(y, m)))}`;
  const years = Array.from({ length: latestYear - 1899 }, (_, i) => latestYear - i);
  return (
    <SheetFrame title="Birth date" onCancel={close} action="Save" onAction={() => { onSave(day); close(); }}>
      <div className="sx-birth">
        <Picker className="field" label="Day" value={Math.min(d, daysIn(y, m))} options={Array.from({ length: daysIn(y, m) }, (_, i) => ({ value: i + 1, label: String(i + 1) }))} onChange={setD} />
        <Picker className="field" label="Month" value={m} options={MONTHS.map((name, i) => ({ value: i + 1, label: name }))} onChange={setM} />
        <Picker className="field" label="Year" value={y} options={years.map((yr) => ({ value: yr, label: String(yr) }))} onChange={setY} />
      </div>
      <div className="foot">{dateText(day, true)} · age {age(day, today)}</div>
    </SheetFrame>
  );
}
