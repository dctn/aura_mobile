// The sheets behind the richer alert actions (demo ui/sheets.py). Each one only collects choices and hands them to
// a callback; lib/actions.ts makes the changes (locally, through aura.edit). The open* functions put one on the phone.

import { useState, type ReactNode } from 'react';
import type { Nav } from '../app/nav';
import { FaceScale, Group, Icon, Row, Segmented, SheetFrame, Switch } from '../kit/kit';
import { tone } from '../lib/catalog';
import { addDays, clock, localParts, weekday } from '../lib/agenda';
import type { Json } from '../lib/types';
import './recovery.css';

const DAY_NAMES = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

/** "Sat 18:00" for an event start. */
export function whenText(start: unknown, zone: string): string {
  const at = localParts(start, zone);
  return at ? `${DAY_NAMES[weekday(at.day)]} ${clock(at.minute)}` : String(start ?? '').slice(0, 16).replace('T', ' ');
}

/** "Sat 31 Oct". */
export function dayText(day: string): string {
  return `${DAY_NAMES[weekday(day)]} ${day.slice(8, 10)} ${MONTHS[Number(day.slice(5, 7)) - 1] ?? ''}`;
}

function Intro({ children }: { children: ReactNode }) {
  return <div className="rc-intro">{children}</div>;
}

function FooterButton({ label, accent, onClick, disabled }: { label: string; accent?: string; onClick: () => void; disabled?: boolean }) {
  return (
    <button className="btn primary wide" style={accent && !disabled ? { background: tone(accent) } : undefined} disabled={disabled} onClick={onClick}>
      {label}
    </button>
  );
}

// -- one time of day ---------------------------------------------------------------------------------------------

function TimeSheet({ footer, initial, onSave, close }: { footer: string; initial: string; onSave: (hhmm: string) => void; close: () => void }) {
  const [value, setValue] = useState(/^\d{2}:\d{2}/.test(initial) ? initial.slice(0, 5) : '07:00');
  return (
    <SheetFrame title="Wake time" onCancel={close} action="Save" actionDisabled={!value} onAction={() => { close(); onSave(value); }}>
      <Group footer={footer}>
        <Row title="Wake up at" icon="alarm" accent="focus" trailing={<input className="timepill" type="time" value={value} onChange={(e) => setValue(e.target.value)} aria-label="Wake up at" />} />
      </Group>
    </SheetFrame>
  );
}

export function openTimeSheet(nav: Nav, footer: string, initial: string, onSave: (hhmm: string) => void): void {
  nav.openSheet({ render: (close) => <TimeSheet footer={footer} initial={initial} onSave={onSave} close={close} /> });
}

// -- DX-05 / DX-06: the hard training to move or cancel -----------------------------------------------------------

interface EasyDayProps {
  events: Json[];
  ill: boolean;
  bedtime: string | null;
  zone: string;
  onSubmit: (eventIds: string[], earlyNight: boolean) => void;
  close: () => void;
}

function EasyDaySheet({ events, ill, bedtime, zone, onSubmit, close }: EasyDayProps) {
  const [on, setOn] = useState<Record<string, boolean>>(() => Object.fromEntries(events.map((e) => [String(e.id), true])));
  const [early, setEarly] = useState(true);
  const what = ill ? 'cancel' : 'move to tomorrow';
  return (
    <SheetFrame title={ill ? 'Take it easy' : 'Make it an easy day'} onCancel={close} cancel="Not now">
      <Group heading="Hard training" footer={events.length ? `Switched on = ${what}.` : 'No hard training in your calendar today or tomorrow.'}>
        {events.length ? (
          events.map((e) => (
            <Row
              key={String(e.id)}
              title={String(e.title ?? 'Training')}
              sub={whenText(e.start, zone)}
              icon="dumbbell"
              accent="workout"
              trailing={<Switch on={on[String(e.id)] ?? false} label={String(e.title ?? 'Training')} onChange={(v) => setOn((s) => ({ ...s, [String(e.id)]: v }))} />}
            />
          ))
        ) : (
          <Row title={<span className="rc-quiet">Nothing to change</span>} />
        )}
      </Group>
      {ill && bedtime && (
        <Group heading="Tonight">
          <Row title="Bed 30 min earlier" sub={`Bedtime ${bedtime}`} icon="sleep" accent="sleep" trailing={<Switch on={early} label="Bed 30 min earlier" onChange={setEarly} />} />
        </Group>
      )}
      {!ill && (
        <Group heading="Then" footer="Ten quiet minutes now: sit or lie down, breathe slowly.">
          <Row title="Recovery break" value="10 min" icon="recover" accent="recovery" />
        </Group>
      )}
      <FooterButton
        label={ill ? 'Rest today' : 'Easy day'}
        accent={ill ? 'cardiac' : 'recovery'}
        onClick={() => {
          close();
          onSubmit(events.map((e) => String(e.id)).filter((id) => on[id]), Boolean(ill && bedtime && early));
        }}
      />
    </SheetFrame>
  );
}

export function openEasyDay(nav: Nav, props: Omit<EasyDayProps, 'close'>): void {
  nav.openSheet({ tall: true, render: (close) => <EasyDaySheet {...props} close={close} /> });
}

// -- NI-02 / NI-03: a 1-5 check-in --------------------------------------------------------------------------------

export const RATING_WORDS: Record<string, string[]> = {
  mood: ['Very low', 'Low', 'Okay', 'Good', 'Great'],
  connection: ['Alone', 'A little', 'Some', 'Good', 'Very connected'],
};

function RatingSheet({ kind, question, onPick, close }: { kind: string; question: string; onPick: (v: number) => void; close: () => void }) {
  const [value, setValue] = useState<number | null>(null);
  const words = RATING_WORDS[kind] ?? RATING_WORDS.mood;
  return (
    <SheetFrame title="Check-in" onCancel={close} cancel="Not now">
      <div className="rc-question">{question}</div>
      <div className="card rc-faces">
        <FaceScale
          value={value}
          onPick={(v) => {
            setValue(v);
            // a short beat so the picked face shows, then the sheet closes and the check-in is saved
            window.setTimeout(() => { close(); onPick(v); }, 180);
          }}
        />
        <div className="rc-facewords">{words.map((w) => <span key={w}>{w}</span>)}</div>
      </div>
      <div className="foot">Saved as a check-in; it shows in your week.</div>
    </SheetFrame>
  );
}

export function openRating(nav: Nav, kind: 'mood' | 'connection', onPick: (v: number) => void): void {
  const question = kind === 'mood' ? 'How is your mood right now?' : 'How connected to people have you felt lately?';
  nav.openSheet({ render: (close) => <RatingSheet kind={kind} question={question} onPick={onPick} close={close} /> });
}

// -- NI-02: help after a low mood ---------------------------------------------------------------------------------

const HELP: [string, string, string][] = [
  ['Talk to someone you trust', 'A friend, family member or colleague. Saying it out loud helps.', 'people'],
  ['Tele-MANAS · 14416', 'Free, 24×7, in many Indian languages (Government of India).', 'bubble'],
  ['iCall · +91 9152987821', 'Counsellors, Monday to Saturday, 10 am to 8 pm.', 'bubble'],
  ['Emergency · 112', 'If you feel unsafe or might hurt yourself, call now.', 'shield'],
];

function HelpSheet({ weekLow, onOk, close }: { weekLow: boolean; onOk: (reachOut: boolean) => void; close: () => void }) {
  const [reach, setReach] = useState(true);
  return (
    <SheetFrame title="Help is close" onCancel={close} cancel="Not now" action="OK" onAction={() => { close(); onOk(reach); }}>
      <Intro>{weekLow ? "Your mood has been low most of this week. That's worth taking seriously." : "Thanks for telling us. You don't have to handle a low day alone."}</Intro>
      <Group heading="Reach out">
        {HELP.map(([title, detail, icon], i) => {
          const crisis = i === HELP.length - 1;
          return <Row key={title} title={title} sub={detail} icon={icon} accent={crisis ? 'workout' : 'recovery'} look={crisis ? 'danger' : undefined} />;
        })}
      </Group>
      <Group footer="A small step for today.">
        <Row title="Add 'Reach out to someone'" sub="to today's tasks" trailing={<Switch on={reach} label="Add 'Reach out to someone'" onChange={setReach} />} />
      </Group>
    </SheetFrame>
  );
}

export function openHelp(nav: Nav, weekLow: boolean, onOk: (reachOut: boolean) => void): void {
  nav.openSheet({ tall: true, render: (close) => <HelpSheet weekLow={weekLow} onOk={onOk} close={close} /> });
}

// -- NI-03: call someone today, or a meal this week ---------------------------------------------------------------

export interface ConnectionPlan {
  who: string;
  call: boolean;
  meal: boolean;
  /** The meal's day and time. */
  day: string;
  time: string;
}

const MEAL_TIMES = ['13:00', '19:30'] as const;

function ConnectionSheet({ today, onSubmit, close }: { today: string; onSubmit: (plan: ConnectionPlan) => void; close: () => void }) {
  const days = Array.from({ length: 7 }, (_, i) => addDays(today, i + 1));
  const saturday = (((5 - weekday(today)) % 7) + 7) % 7 || 7; // the coming Saturday, 1-7 days away
  const [name, setName] = useState('');
  const [call, setCall] = useState(true);
  const [meal, setMeal] = useState(false);
  const [day, setDay] = useState(days[saturday - 1]);
  const [time, setTime] = useState<(typeof MEAL_TIMES)[number]>(MEAL_TIMES[0]);
  return (
    <SheetFrame title="Reach out" onCancel={close} cancel="Not now">
      <Intro>A short hello lifts the whole fortnight. Who comes to mind?</Intro>
      <Group>
        <Row title={<input className="field plain rc-name" placeholder="A friend's name" value={name} onChange={(e) => setName(e.target.value)} aria-label="A friend's name" />} icon="people" accent="cardiac" />
      </Group>
      <Group heading="Plan" footer="Afterwards, log how connected you feel.">
        <Row title="Call them today" icon="bubble" accent="recovery" trailing={<Switch on={call} label="Call them today" onChange={setCall} />} />
        <Row title="A meal together this week" icon="cup" accent="stress" trailing={<Switch on={meal} label="A meal together this week" onChange={setMeal} />} />
        <Row
          title="Day"
          trailing={
            <select className="timepill" value={day} disabled={!meal} onChange={(e) => setDay(e.target.value)} aria-label="Day">
              {days.map((d) => <option key={d} value={d}>{dayText(d)}</option>)}
            </select>
          }
        />
        <Row
          title="Time"
          trailing={
            <span className={`rc-seg${meal ? '' : ' off'}`}>
              <Segmented options={MEAL_TIMES.map((t) => ({ value: t, label: t }))} value={time} onChange={setTime} />
            </span>
          }
        />
      </Group>
      <FooterButton
        label="Plan it"
        accent="teal"
        onClick={() => {
          close();
          onSubmit({ who: name.trim() || 'a friend', call, meal, day, time });
        }}
      />
    </SheetFrame>
  );
}

export function openConnection(nav: Nav, today: string, onSubmit: (plan: ConnectionPlan) => void): void {
  nav.openSheet({ tall: true, render: (close) => <ConnectionSheet today={today} onSubmit={onSubmit} close={close} /> });
}

// -- DX-03: the list for tomorrow, after the wind-down ------------------------------------------------------------

function ToDoSheet({ onSave, close }: { onSave: (items: string[]) => void; close: () => void }) {
  const [text, setText] = useState('');
  const items = todoItems(text);
  return (
    <SheetFrame title="Wind-down" onCancel={close} cancel="Skip" action="Add" actionDisabled={!items.length} onAction={() => { close(); onSave(items); }}>
      <Intro>Dim the lights. Write down what is on your mind for tomorrow.</Intro>
      <Group heading="For tomorrow" footer="One thing a line; they go on your task list for the week.">
        <div className="rc-todo">
          <textarea className="field plain" rows={5} placeholder={'Reply to Ana\nBook the dentist'} value={text} onChange={(e) => setText(e.target.value)} aria-label="For tomorrow" autoFocus />
        </div>
      </Group>
    </SheetFrame>
  );
}

/** The lines of the to-do box, trimmed, empty ones dropped, at most 10. */
export function todoItems(text: string): string[] {
  return text.split('\n').map((l) => l.trim()).filter(Boolean).slice(0, 10);
}

export function openToDo(nav: Nav, onSave: (items: string[]) => void): void {
  nav.openSheet({ render: (close) => <ToDoSheet onSave={onSave} close={close} /> });
}

// -- DX-03: the sleep check (Insomnia Severity Index) -------------------------------------------------------------

const SEVERITY = ['None', 'Mild', 'Moderate', 'Severe', 'Very severe'];
const AMOUNT = ['Not at all', 'A little', 'Somewhat', 'Much', 'Very much'];
export const SLEEP_QUESTIONS: [string, string[]][] = [
  ['Trouble falling asleep', SEVERITY],
  ['Trouble staying asleep', SEVERITY],
  ['Waking up too early', SEVERITY],
  ['How satisfied are you with your sleep now?', ['Very satisfied', 'Satisfied', 'Neutral', 'Dissatisfied', 'Very dissatisfied']],
  ['How much do others notice your sleep problem?', AMOUNT],
  ['How worried are you about your sleep?', AMOUNT],
  ['How much does it get in the way of your day?', AMOUNT],
];

function SleepCheckSheet({ onDone, close }: { onDone: (answers: number[]) => void; close: () => void }) {
  const [step, setStep] = useState(0);
  const [picked, setPicked] = useState<(number | null)[]>(() => SLEEP_QUESTIONS.map(() => null));
  const n = SLEEP_QUESTIONS.length;
  const [question, words] = SLEEP_QUESTIONS[step];
  const chosen = picked[step];
  const next = () => {
    if (chosen === null) return;
    if (step < n - 1) setStep(step + 1);
    else {
      close();
      onDone(picked.map((v) => v ?? 0));
    }
  };
  return (
    <SheetFrame
      title={`${step + 1} of ${n}`}
      cancel={step === 0 ? 'Cancel' : 'Back'}
      onCancel={() => (step === 0 ? close() : setStep(step - 1))}
      action={step === n - 1 ? 'See result' : 'Next'}
      actionDisabled={chosen === null}
      onAction={next}
    >
      <div className="rc-line"><span style={{ width: `${((step + 1) / n) * 100}%` }} /></div>
      <div className="rc-head">
        <div className="rc-kicker">{step < 3 ? 'SLEEP CHECK' : 'HOW IT AFFECTS YOU'}</div>
        <div className="rc-question">{question}</div>
        {step === 0 && <div className="muted">Over the last two weeks. About 2 minutes in all.</div>}
      </div>
      <Group>
        {words.map((w, i) => (
          <Row
            key={w}
            title={w}
            onClick={() => setPicked((p) => p.map((v, j) => (j === step ? i : v)))}
            trailing={chosen === i ? <span className="rc-check" aria-label="Chosen"><Icon name="check" size={18} stroke={2.4} /></span> : undefined}
          />
        ))}
      </Group>
    </SheetFrame>
  );
}

export function openSleepCheck(nav: Nav, onDone: (answers: number[]) => void): void {
  nav.openSheet({ tall: true, render: (close) => <SleepCheckSheet onDone={onDone} close={close} /> });
}
