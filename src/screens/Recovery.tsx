// Recovery (demo ui/pages/recovery.py): what to do (each open alert as a pill over the card of the metric it reads),
// then the recovery presets. A preset starts the full-phone timer and leaves a session (completed or stopped).

import { useAura } from '../app/store';
import { useNav } from '../app/nav';
import { ActionPill, IconTile, Page, Section, SettingsButton, TileGrid } from '../kit/kit';
import { PRESETS, type Preset } from '../lib/catalog';
import { timedRun, windDown } from '../lib/actions';
import { AlertCard, openAlerts } from './shared';
import '../recovery/recovery.css';

/** Alerts shown at most (the app's limit). */
const MAX_ALERTS = 12;

export function Recovery() {
  const aura = useAura();
  const nav = useNav();
  const live = openAlerts(aura.data.alerts, aura.data.alertOff);
  const count = live.length;

  const startPreset = (p: Preset) => {
    if (p.title === 'Wind-down') {
      windDown(aura, nav, p.accent); // the full DX-03 run: the list for tomorrow is saved after it
      return;
    }
    timedRun(aura, nav, { title: p.title, minutes: p.minutes, kind: p.kind, accent: p.accent, note: p.note, breathing: p.title === 'Slow breathing' });
  };

  return (
    <Page root title="Recovery" sub={count ? `${count} thing${count !== 1 ? 's' : ''} to do` : 'All clear'} right={<SettingsButton />}>
      <Section title="To do" />
      <div className="rc-todo-list">
        {count ? (
          live.slice(0, MAX_ALERTS).map((alert) => <AlertCard key={String(alert.id)} alert={alert} />)
        ) : (
          <ActionPill icon="check" title="All clear" reason="Nothing to do" accent="go" />
        )}
      </div>
      <Section title="Presets" />
      <TileGrid>
        {PRESETS.map((p) => (
          <IconTile key={p.title} title={p.word} note={`${p.minutes} min`} icon={p.icon} accent={p.accent} onClick={() => startPreset(p)} />
        ))}
      </TileGrid>
    </Page>
  );
}
