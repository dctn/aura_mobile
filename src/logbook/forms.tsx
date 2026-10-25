// One card per typed-in logbook kind (kinds.ts `input`), drawn by its input type: five faces, a 0-10 row, a number
// sheet, a choice, a note, a "done" button, or a sheet for a meal, an exercise or the quick check. Every save adds a
// log row { type: <kind id>, date, takenAt, data } in this browser only and says so in a toast.

import { useState, type ReactNode } from 'react';
import { useAura } from '../app/store';
import { useNav } from '../app/nav';
import { nowOf } from '../lib/actions';
import type { Json } from '../lib/types';
import { FaceScale, Group, Row, Segmented, SheetFrame, Stepper, Switch } from '../kit/kit';
import { Picker } from '../kit/Picker';
import { NumberSheet } from '../search/parts';
import { whenText } from '../search/when';
import { MEALS, MUSCLE_GROUPS, type Input, type Kind, type Meal } from './kinds';
import { entryText, entryValue, latest, logsOf, mealTotals, mealsOn } from './derive';
import { QuickCheck } from './QuickCheck';
import './logbook.css';

const pad = (n: number) => String(n).padStart(2, '0');
const valueOf = (row: Json | null) => (row?.data as Json | undefined)?.value;

/** Save one entry of `kind` now (in the person's zone) and toast what was saved. */
export function useLog() {
  const aura = useAura();
  const nav = useNav();
  return (kind: Kind, data: Json, toast?: string) => {
    const now = nowOf(aura);
    const row: Json = { type: kind.id, date: now.day, takenAt: now.iso, data };
    aura.edit.add('logs', row);
    nav.showToast(toast ?? `Saved: ${entryText(row)}`);
  };
}

function Card({ kind, note, children }: { kind: Kind; note?: string; children: ReactNode }) {
  return (
    <div className="card lb-card">
      <div className="lb-head">
        <span className="name">{kind.label}</span>
        {note && <span className="note">{note}</span>}
      </div>
      {kind.hint && <div className="muted">{kind.hint}</div>}
      {children}
    </div>
  );
}

/** "Today 09:10" for the newest entry of the kind. */
function useLast(kind: Kind): { row: Json | null; when: string | undefined } {
  const aura = useAura();
  const row = latest(aura.data.logs, kind.id);
  return { row, when: row ? whenText(row.takenAt, aura.today, aura.zone) || undefined : undefined };
}

// -- the input types ------------------------------------------------------------------------------------------------

function Scale5Form({ kind, input }: { kind: Kind; input: Extract<Input, { type: 'scale5' }> }) {
  const log = useLog();
  const { when } = useLast(kind);
  const [value, setValue] = useState<number | null>(null);
  return (
    <Card kind={kind} note={when}>
      <FaceScale value={value} onPick={setValue} size={44} />
      {input.words && (
        <div className="lb-words small">
          <span>{input.words[0]}</span>
          <span>{input.words[1]}</span>
        </div>
      )}
      <button className="btn go lb-save" disabled={value === null} onClick={() => { log(kind, { value }); setValue(null); }}>Save</button>
    </Card>
  );
}

function Scale10Form({ kind }: { kind: Kind }) {
  const log = useLog();
  const { when } = useLast(kind);
  const [value, setValue] = useState<number | null>(null);
  return (
    <Card kind={kind} note={when}>
      <div className="lb-ten" role="radiogroup" aria-label={kind.label}>
        {Array.from({ length: 11 }, (_, i) => (
          <button key={i} className={`chip${value === i ? ' on' : ''}`} role="radio" aria-checked={value === i} onClick={() => setValue(i)}>{i}</button>
        ))}
      </div>
      <button className="btn go lb-save" disabled={value === null} onClick={() => { log(kind, { value }); setValue(null); }}>Save</button>
    </Card>
  );
}

/** A number or a length of time: the last value, and Log opens the number sheet there. */
function NumberForm({ kind, input }: { kind: Kind; input: Extract<Input, { type: 'number' | 'minutes' }> }) {
  const log = useLog();
  const nav = useNav();
  const { row, when } = useLast(kind);
  const minutes = input.type === 'minutes';
  const spec = minutes
    ? { min: 0, max: input.max, step: input.step, unit: 'min', quick: [input.step, input.step * 4] as [number, number] }
    : { min: input.min, max: input.max, step: input.step, unit: input.unit, quick: input.quick };
  const last = typeof valueOf(row) === 'number' ? (valueOf(row) as number) : null;
  // a step of 0.1 leaves 61.300000001 behind; keep the step's decimals
  const decimals = (String(spec.step).split('.')[1] ?? '').length;
  const tidy = (v: number) => Number(v.toFixed(decimals));
  const open = () =>
    nav.openSheet({
      render: (close) => (
        <NumberSheet title={kind.label} value={last ?? input.start} min={spec.min} max={spec.max} step={spec.step} unit={spec.unit} quick={spec.quick} note={kind.hint} close={close} onSave={(v) => log(kind, { value: tidy(v) })} />
      ),
    });
  return (
    <Card kind={kind} note={when}>
      <div className="lb-line">
        <span className="lb-value">{row ? entryValue(row) : <span className="muted">Nothing yet</span>}</span>
        <button className="btn go" onClick={open}>Log</button>
      </div>
    </Card>
  );
}

function ChoiceForm({ kind, input }: { kind: Kind; input: Extract<Input, { type: 'choice' }> }) {
  const log = useLog();
  const { row, when } = useLast(kind);
  const [value, setValue] = useState(String(valueOf(row) ?? input.options[0]));
  return (
    <Card kind={kind} note={when}>
      <div className="lb-line">
        <Picker className="field lb-pick" label={kind.label} value={value} options={input.options.map((o) => ({ value: o, label: o }))} onChange={setValue} />
        <button className="btn go" onClick={() => log(kind, { value })}>Save</button>
      </div>
    </Card>
  );
}

function TextForm({ kind, input }: { kind: Kind; input: Extract<Input, { type: 'text' }> }) {
  const log = useLog();
  const { when } = useLast(kind);
  const [text, setText] = useState('');
  const [value, setValue] = useState<number | null>(null);
  const ok = text.trim().length > 0 && (!input.scale || value !== null);
  const save = () => {
    log(kind, input.scale ? { text: text.trim(), value } : { text: text.trim() });
    setText('');
    setValue(null);
  };
  return (
    <Card kind={kind} note={when}>
      {input.scale && (
        <>
          <div className="lb-ask">{input.scale}</div>
          <FaceScale value={value} onPick={setValue} size={44} />
        </>
      )}
      <textarea className="field lb-text" aria-label={kind.label} placeholder={input.placeholder} maxLength={280} value={text} onChange={(e) => setText(e.target.value)} />
      <button className="btn go lb-save" disabled={!ok} onClick={save}>Save</button>
    </Card>
  );
}

function YesForm({ kind, input }: { kind: Kind; input: Extract<Input, { type: 'yes' }> }) {
  const aura = useAura();
  const log = useLog();
  const done = logsOf(aura.data.logs, kind.id).some((x) => String(x.date ?? '').slice(0, 10) === aura.today);
  return (
    <Card kind={kind}>
      <button className="btn go lb-save" disabled={done} onClick={() => log(kind, { value: true })}>{done ? 'Done today' : input.verb}</button>
    </Card>
  );
}

// -- meal -----------------------------------------------------------------------------------------------------------

const MEAL_OPTIONS = MEALS.map((m) => ({ value: m, label: m.charAt(0).toUpperCase() + m.slice(1) }));

/** The meal the time of day suggests. */
export const mealAt = (minute: number): Meal => (minute < 11 * 60 ? 'breakfast' : minute < 15 * 60 ? 'lunch' : minute < 18 * 60 ? 'snack' : 'dinner');

function MealSheet({ minute, onSave, close }: { minute: number; onSave: (data: Json) => void; close: () => void }) {
  const [meal, setMeal] = useState<Meal>(mealAt(minute));
  const [kcal, setKcal] = useState(400);
  const [protein, setProtein] = useState(20);
  const [carbs, setCarbs] = useState(50);
  const [fat, setFat] = useState(15);
  const [h, setH] = useState(Math.floor(minute / 60));
  const [m, setM] = useState(minute % 60 - ((minute % 60) % 5));
  const [photo, setPhoto] = useState(false);
  const grams = (v: number) => `${v} g`;
  return (
    <SheetFrame title="Add meal" onCancel={close} action="Save" onAction={() => { onSave({ meal, kcal, protein, carbs, fat, time: `${pad(h)}:${pad(m)}`, photo }); close(); }}>
      <Segmented options={MEAL_OPTIONS} value={meal} onChange={setMeal} />
      <Group>
        <Row title="Calories" trailing={<Stepper value={kcal} min={0} max={3000} step={25} format={(v) => `${v} kcal`} onChange={setKcal} />} />
        <Row title="Protein" trailing={<Stepper value={protein} min={0} max={300} step={5} format={grams} onChange={setProtein} />} />
        <Row title="Carbs" trailing={<Stepper value={carbs} min={0} max={500} step={5} format={grams} onChange={setCarbs} />} />
        <Row title="Fat" trailing={<Stepper value={fat} min={0} max={200} step={5} format={grams} onChange={setFat} />} />
        <Row
          title="Time"
          trailing={
            <span className="lb-time">
              <Picker className="field" label="Hour" value={h} options={Array.from({ length: 24 }, (_, i) => ({ value: i, label: pad(i) }))} onChange={setH} />
              <span>:</span>
              <Picker className="field" label="Minute" value={m} options={Array.from({ length: 12 }, (_, i) => ({ value: i * 5, label: pad(i * 5) }))} onChange={setM} />
            </span>
          }
        />
        <Row title="Photo taken" trailing={<Switch on={photo} onChange={setPhoto} label="Photo taken" />} />
      </Group>
      <div className="foot">The demo keeps no photo; it only notes that one was taken.</div>
    </SheetFrame>
  );
}

function MealForm({ kind }: { kind: Kind }) {
  const aura = useAura();
  const nav = useNav();
  const log = useLog();
  const t = mealTotals(mealsOn(aura.data.logs, aura.today, aura.zone));
  const add = () => nav.openSheet({ tall: true, render: (close) => <MealSheet minute={nowOf(aura).minute} close={close} onSave={(data) => log(kind, data)} /> });
  return (
    <Card kind={kind}>
      <div className="lb-line">
        <span className="lb-value">{t.count ? `${t.count} today · ${t.kcal.toLocaleString('en-US')} kcal` : <span className="muted">No meals today</span>}</span>
        <button className="btn go" onClick={add}>Add meal</button>
      </div>
    </Card>
  );
}

// -- lift -----------------------------------------------------------------------------------------------------------

function LiftSheet({ onSave, close }: { onSave: (data: Json) => void; close: () => void }) {
  const [exercise, setExercise] = useState('');
  const [group, setGroup] = useState(MUSCLE_GROUPS[0]);
  const [sets, setSets] = useState(3);
  const [reps, setReps] = useState(8);
  const [kg, setKg] = useState(20);
  const ok = exercise.trim().length > 0;
  const save = () => {
    if (!ok) return;
    onSave({ exercise: exercise.trim(), group, sets, reps, kg });
    close();
  };
  return (
    <SheetFrame title="Add exercise" onCancel={close} action="Save" onAction={save} actionDisabled={!ok}>
      <input className="field" aria-label="Exercise" placeholder="Exercise (squat, bench press…)" maxLength={40} value={exercise} onChange={(e) => setExercise(e.target.value)} onKeyDown={(e) => e.key === 'Enter' && save()} />
      <Group>
        <Row title="Muscle group" trailing={<Picker className="field lb-group" label="Muscle group" value={group} options={MUSCLE_GROUPS.map((g) => ({ value: g, label: g }))} onChange={setGroup} />} />
        <Row title="Sets" trailing={<Stepper value={sets} min={1} max={10} onChange={setSets} />} />
        <Row title="Reps" trailing={<Stepper value={reps} min={1} max={30} onChange={setReps} />} />
        <Row title="Weight" trailing={<Stepper value={kg} min={0} max={300} step={2.5} format={(v) => `${v} kg`} onChange={setKg} />} />
      </Group>
    </SheetFrame>
  );
}

function LiftForm({ kind }: { kind: Kind }) {
  const nav = useNav();
  const log = useLog();
  const { row, when } = useLast(kind);
  const add = () => nav.openSheet({ tall: true, render: (close) => <LiftSheet close={close} onSave={(data) => log(kind, data)} /> });
  return (
    <Card kind={kind} note={when}>
      <div className="lb-line">
        <span className="lb-value">{row ? entryValue(row) : <span className="muted">Nothing yet</span>}</span>
        <button className="btn go" onClick={add}>Add</button>
      </div>
    </Card>
  );
}

// -- quick check ----------------------------------------------------------------------------------------------------

function QuickForm({ kind }: { kind: Kind }) {
  const nav = useNav();
  const log = useLog();
  const { row, when } = useLast(kind);
  const start = () => nav.openSheet({ tall: true, render: (close) => <QuickCheck close={close} onDone={(r) => log(kind, { ...r })} /> });
  return (
    <Card kind={kind} note={when}>
      <div className="lb-line">
        <span className="lb-value">{row ? entryValue(row) : <span className="muted">Not taken yet</span>}</span>
        <button className="btn go" onClick={start}>Start</button>
      </div>
    </Card>
  );
}

/** The card for one typed-in kind. */
export function KindForm({ kind }: { kind: Kind }) {
  const input = kind.input;
  if (!input) return null;
  switch (input.type) {
    case 'scale5':
      return <Scale5Form kind={kind} input={input} />;
    case 'scale10':
      return <Scale10Form kind={kind} />;
    case 'number':
    case 'minutes':
      return <NumberForm kind={kind} input={input} />;
    case 'choice':
      return <ChoiceForm kind={kind} input={input} />;
    case 'text':
      return <TextForm kind={kind} input={input} />;
    case 'yes':
      return <YesForm kind={kind} input={input} />;
    case 'meal':
      return <MealForm kind={kind} />;
    case 'lift':
      return <LiftForm kind={kind} />;
    case 'quick':
      return <QuickForm kind={kind} />;
  }
}
