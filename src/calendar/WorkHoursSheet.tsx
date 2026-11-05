// The work and lunch hours of the day zones: asked the first time, changed later from Settings or the zones page.
// They save with the profile as `workSchedule`, in this browser only, the shape planned for the backend.

import { useState } from 'react';
import { useAura } from '../app/store';
import { useNav } from '../app/nav';
import { Group, Row, SheetFrame } from '../kit/kit';
import { Picker } from '../kit/Picker';
import { asSchedule, scheduleProblem, type WorkSchedule } from './dayZones';
import './calendar.css';

const DAY_LETTERS: readonly [number, string, string][] = [
  [1, 'M', 'Monday'], [2, 'T', 'Tuesday'], [3, 'W', 'Wednesday'], [4, 'T', 'Thursday'], [5, 'F', 'Friday'], [6, 'S', 'Saturday'], [7, 'S', 'Sunday'],
];
const LUNCH_LENGTHS = [30, 45, 60, 90];
const pad = (n: number) => String(n).padStart(2, '0');
const QUARTERS = Array.from({ length: 96 }, (_, i) => `${pad(Math.floor(i / 4))}:${pad((i % 4) * 15)}`);
const TIME_CHOICES = QUARTERS.map((value) => ({ value, label: value }));

/** What the sheet opens with when nothing is saved. Nothing is shaded until it's saved. */
const STARTING_HOURS: WorkSchedule = { workdayStart: '09:00', workdayEnd: '18:00', lunchStart: '13:00', lunchMinutes: 60, workDays: [1, 2, 3, 4, 5] };

/** "09:00–18:00 · Lunch 13:00", for Settings. */
export const scheduleText = (s: WorkSchedule | null) => (s ? `${s.workdayStart}–${s.workdayEnd} · Lunch ${s.lunchStart}` : 'Not set');

function TimeRow({ title, value, onChange }: { title: string; value: string; onChange: (v: string) => void }) {
  return <Row title={title} trailing={<Picker className="field cal-timepick" label={title} value={value} options={TIME_CHOICES} onChange={onChange} />} />;
}

export function WorkHoursSheet({ close, first = false }: { close: () => void; first?: boolean }) {
  const aura = useAura();
  const nav = useNav();
  const [draft, setDraft] = useState<WorkSchedule>(asSchedule(aura.data.me.workSchedule) ?? STARTING_HOURS);
  const set = (changes: Partial<WorkSchedule>) => setDraft((d) => ({ ...d, ...changes }));
  const toggle = (day: number) => set({ workDays: draft.workDays.includes(day) ? draft.workDays.filter((d) => d !== day) : [...draft.workDays, day].sort() });
  const problem = scheduleProblem(draft);
  const save = () => {
    aura.edit.me({ workSchedule: draft });
    nav.showToast('Work hours saved');
    close();
  };

  return (
    <SheetFrame title="Work hours" onCancel={close} cancel={first ? 'Later' : 'Cancel'} action="Save" onAction={save} actionDisabled={problem !== null}>
      <p className="cal-sheetnote">Your calendar is shaded inside these hours, with lunch as your main recovery.</p>
      <Group>
        <TimeRow title="Work starts" value={draft.workdayStart} onChange={(v) => set({ workdayStart: v })} />
        <TimeRow title="Work ends" value={draft.workdayEnd} onChange={(v) => set({ workdayEnd: v })} />
        <TimeRow title="Lunch starts" value={draft.lunchStart} onChange={(v) => set({ lunchStart: v })} />
        <Row
          title="Lunch length"
          trailing={
            <Picker
              className="field cal-timepick"
              label="Lunch length"
              value={draft.lunchMinutes}
              options={LUNCH_LENGTHS.map((m) => ({ value: m, label: `${m} min` }))}
              onChange={(v) => set({ lunchMinutes: v })}
            />
          }
        />
      </Group>
      <div className="cal-workdays" role="group" aria-label="Work days">
        {DAY_LETTERS.map(([day, letter, name]) => (
          <button key={day} className={`cal-workday${draft.workDays.includes(day) ? ' on' : ''}`} aria-label={name} aria-pressed={draft.workDays.includes(day)} onClick={() => toggle(day)}>
            {letter}
          </button>
        ))}
      </div>
      {problem && <p className="cal-sheetwarn" role="alert">{problem}</p>}
    </SheetFrame>
  );
}
