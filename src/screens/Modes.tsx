// Search → Modes (demo ui/pages/modes.py): the Session service's four modes as tiles, then one mode's setup (the
// Figma "Walking" page): the black start pill over a Length row and a Title row. Start shows 3-2-1 over the phone,
// then the run view; the session is recorded when it ends. Flowtime has no length and counts up.

import { useState } from 'react';
import { useAura } from '../app/store';
import { backLabel, useNav } from '../app/nav';
import { Group, IconTile, Page, Row, StartPill, Stepper, TileGrid } from '../kit/kit';
import { MODES } from '../lib/catalog';
import { timedRun } from '../lib/actions';
import { liveRun, runControls } from '../run/live';
import '../recovery/recovery.css';

export function Modes() {
  const nav = useNav();
  return (
    <Page back={backLabel(nav)} title="Modes">
      <TileGrid>
        {MODES.map((mode, i) => (
          <IconTile key={mode.name} title={mode.name} note={mode.minutes ? `${mode.minutes} min` : 'Open'} icon={mode.icon} accent={mode.accent} onClick={() => nav.push({ name: 'mode', index: i })} />
        ))}
      </TileGrid>
    </Page>
  );
}

export function ModeSetup({ index }: { index: number }) {
  const aura = useAura();
  const nav = useNav();
  const mode = MODES[Math.max(0, Math.min(index, MODES.length - 1))];
  const [minutes, setMinutes] = useState(mode.minutes || 25);
  const [title, setTitle] = useState('');

  const start = () => {
    if (liveRun()) {
      runControls()?.expand(); // one run at a time: this brings the running one back
      return;
    }
    timedRun(aura, nav, {
      title: title.trim() || mode.name,
      minutes: mode.minutes ? minutes : 0,
      kind: mode.kind,
      mode: mode.mode,
      accent: mode.accent,
      night: false,
      countdown: true,
    });
  };

  return (
    <Page back={backLabel(nav)} title={mode.name}>
      <StartPill text={mode.start} onClick={start} />
      <Group>
        <Row
          title="Length"
          value={mode.minutes ? undefined : 'Open'}
          trailing={mode.minutes ? <Stepper value={minutes} min={5} max={240} step={5} format={(v) => `${v} min`} onChange={setMinutes} /> : undefined}
        />
        <Row
          title="Title"
          trailing={<input className="rc-titlefield" placeholder="Optional" value={title} maxLength={60} onChange={(e) => setTitle(e.target.value)} onKeyDown={(e) => e.key === 'Enter' && start()} aria-label="Title" />}
        />
      </Group>
    </Page>
  );
}
