// Recovery → Modes (Show more): every mode from the Swift app on its shelf (Focus, Breathe, Meditate, Sleep, Rest,
// Movement, Mind, Outside, People, Fuel), a chip row to keep one shelf, and the modes used most on top. A tile opens the
// mode's setup page (the Figma "Walking" page): the black start pill over the note, the length, a title for Focus, the
// breath for a breathing mode and the prompt count for a guided one. Start runs it through actions.startMode; a plan
// mode (a day off, an evening) goes on the calendar instead, and only from here: Recovery's tiles and pills open this
// page for one (startOrOpen), so a 4-8 h block is never added by a single tap.

import { useMemo, useState } from 'react';
import { useAura, type Aura } from '../app/store';
import { backLabel, useNav, type Nav } from '../app/nav';
import { Group, IconTile, Page, Row, Section, StartPill, Stepper, TileGrid } from '../kit/kit';
import { Picker } from '../kit/Picker';
import { startMode } from '../lib/actions';
import { SHELF_LOOK, SHELVES, breathLine, findMode, modesOn, type Mode, type Shelf } from '../lib/modes';
import { liveRun, runControls } from '../run/live';
import { scoresFor, topModes } from './usage';
import './recovery.css';

/** A length in words: "25 min", "2 h", "1 h 30 m". */
export function lengthText(minutes: number): string {
  if (minutes < 60) return `${minutes} min`;
  const h = Math.floor(minutes / 60);
  const m = minutes % 60;
  return m ? `${h} h ${m} m` : `${h} h`;
}

/** A tile's small line: its length, "Open" for a flow, "Plan" for one that goes on the calendar. */
export function modeNote(mode: Mode): string {
  if (mode.run === 'flow' || !mode.minutes) return 'Open';
  if (mode.run === 'plan') return 'Plan';
  return `${mode.minutes} min`;
}

/** A pill's reason: "10 min · Sleep", "Open · Focus", "Plan · Rest". */
export const modeReason = (mode: Mode) => `${modeNote(mode)} · ${mode.shelf}`;

/** The button a mode gets in a pill: Start, or Plan for one that goes on the calendar. */
export const modeVerb = (mode: Mode) => (mode.run === 'plan' ? 'Plan' : 'Start');

/** A tile's or a pill's Start: runs the mode at once, but a plan mode (a day off, an alcohol-free evening) opens its
 *  page, where "Add to calendar" is the visitor's own choice. */
export function startOrOpen(aura: Aura, nav: Nav, mode: Mode): void {
  if (mode.run === 'plan') nav.push({ name: 'mode', id: mode.id });
  else startMode(aura, nav, mode);
}

/** A mode as a tile. */
export function ModeTile({ mode, onClick }: { mode: Mode; onClick: () => void }) {
  return <IconTile title={mode.word} note={modeNote(mode)} icon={mode.icon} accent={mode.accent} onClick={onClick} />;
}

type Filter = 'All' | Shelf;

export function ModesPage({ shelf }: { shelf?: Shelf }) {
  const aura = useAura();
  const nav = useNav();
  const [filter, setFilter] = useState<Filter>(shelf ?? 'All');
  const scores = scoresFor(aura);
  const used = useMemo(() => [...scores].some(([key, s]) => key.startsWith('mode:') && s.score > 0), [scores]);
  const open = (mode: Mode) => nav.push({ name: 'mode', id: mode.id });
  const shown = filter === 'All' ? SHELVES : [filter];

  return (
    <Page back={backLabel(nav)} title="Modes">
      <div className="rc-shelves" role="group" aria-label="Shelves">
        {(['All', ...SHELVES] as Filter[]).map((f) => (
          <button key={f} className={`chip${f === filter ? ' on' : ''}`} aria-pressed={f === filter} onClick={() => setFilter(f)}>
            {f}
          </button>
        ))}
      </div>
      {filter === 'All' && used && (
        <div className="rc-shelf">
          <Section title="Used most" />
          <TileGrid>
            {topModes(scores, 4).map((mode) => <ModeTile key={mode.id} mode={mode} onClick={() => open(mode)} />)}
          </TileGrid>
        </div>
      )}
      {shown.map((s) => (
        <div key={s} className="rc-shelf" data-shelf={s}>
          <Section title={s} />
          <div className="rc-shelfnote">{SHELF_LOOK[s].note}</div>
          <TileGrid>
            {modesOn(s).map((mode) => <ModeTile key={mode.id} mode={mode} onClick={() => open(mode)} />)}
          </TileGrid>
        </div>
      ))}
    </Page>
  );
}

/** The length choices for a mode, with its default among them. */
function choicesOf(mode: Mode): number[] {
  const list = mode.choices?.length ? mode.choices : [];
  return list.includes(mode.minutes) ? list : [...list, mode.minutes].sort((a, b) => a - b);
}

export function ModeSetup({ id }: { id: string }) {
  const aura = useAura();
  const nav = useNav();
  const mode = findMode(id);
  const [minutes, setMinutes] = useState(mode?.minutes || 25);
  const [title, setTitle] = useState('');
  if (!mode) {
    return (
      <Page back={backLabel(nav)} title="Mode">
        <div className="muted rc-intro">This mode is not in the list any more.</div>
      </Page>
    );
  }

  const plan = mode.run === 'plan';
  const open = mode.run === 'flow' || !mode.minutes;
  const start = () => {
    if (!plan && liveRun()) {
      runControls()?.expand(); // one run at a time: this brings the running one back
      return;
    }
    startMode(aura, nav, mode, { minutes: open ? 0 : minutes, title: title.trim() || undefined });
  };

  // a Focus timer keeps today's free stepper; a mode with choices offers those; the rest have one length
  const length = open ? (
    <Row title="Length" value="Open" />
  ) : mode.run === 'timer' && mode.shelf === 'Focus' ? (
    <Row title="Length" trailing={<Stepper value={minutes} min={5} max={240} step={5} format={(v) => `${v} min`} onChange={setMinutes} />} />
  ) : mode.choices?.length ? (
    <Row
      title="Length"
      trailing={<Picker label="Length" value={minutes} options={choicesOf(mode).map((v) => ({ value: v, label: lengthText(v) }))} onChange={setMinutes} />}
    />
  ) : (
    <Row title="Length" value={lengthText(mode.minutes)} />
  );

  return (
    <Page back={backLabel(nav)} title={mode.name}>
      <StartPill text={plan ? 'Add to calendar' : 'Start'} onClick={start} />
      <div className="rc-intro rc-modenote">{mode.note}</div>
      <Group>
        {length}
        {mode.run === 'breathe' && mode.pattern && <Row title="Breath" value={breathLine(mode.pattern)} />}
        {mode.run === 'guided' && mode.prompts?.length ? <Row title="Prompts" value={`${mode.prompts.length} prompts`} /> : null}
        {mode.shelf === 'Focus' && (
          <Row
            title="Title"
            trailing={<input className="rc-titlefield" placeholder="Optional" value={title} maxLength={60} onChange={(e) => setTitle(e.target.value)} onKeyDown={(e) => e.key === 'Enter' && start()} aria-label="Title" />}
          />
        )}
      </Group>
    </Page>
  );
}
