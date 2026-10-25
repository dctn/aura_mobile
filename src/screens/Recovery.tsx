// Recovery (demo ui/pages/recovery.py): what to do (each open alert as a pill; the metric card is in its row's sheet),
// then every scientifically backed feature by group, each with its one-verb action and, where you note something by
// hand, its logging card and this week's entries; then the recovery presets. A preset starts the full-phone timer and
// leaves a session (completed or stopped).

import { useAura } from '../app/store';
import { useNav } from '../app/nav';
import { ActionPill, Disc, IconTile, Page, Section, SettingsButton, TileGrid, pressKeys } from '../kit/kit';
import { FEATURES, PRESETS, type Feature, type Preset } from '../lib/catalog';
import { performAlert, timedRun, windDown } from '../lib/actions';
import type { Json } from '../lib/types';
import { LogFor } from '../recovery/logCards';
import { AlertCard, AlertSheet, alertFor, featureStatus, openAlerts } from './shared';
import '../recovery/recovery.css';

/** Alerts shown at most (the app's limit). */
const MAX_ALERTS = 12;

/** The catalog's group -> the heading it sits under here; one-feature groups join a near one. Account and Band
 *  (BS-01, BS-02) are not listed: they stay on Home. */
const GROUP_OF: Record<string, string> = {
  Sleep: 'Sleep',
  'Recovery & stress': 'Recovery & stress',
  Burnout: 'Recovery & stress',
  Movement: 'Movement',
  Mood: 'Mind & people',
  Connection: 'Mind & people',
  Hydration: 'Hydration',
  'Health check': 'Health check',
};

export const GROUP_ORDER = ['Sleep', 'Recovery & stress', 'Movement', 'Mind & people', 'Hydration', 'Health check'];

/** The features Recovery lists, under their headings, in catalog order within each. */
export function featureGroups(features: Feature[] = FEATURES): { title: string; features: Feature[] }[] {
  return GROUP_ORDER.map((title) => ({ title, features: features.filter((f) => GROUP_OF[f.group] === title) }));
}

/** One feature: its icon, name and status; a tap opens its sheet (with the metric card), the button does its action
 *  (on its open alert, or a stand-in when there is none). */
function FeatureRow({ f, open }: { f: Feature; open: Json[] }) {
  const aura = useAura();
  const nav = useNav();
  const status = featureStatus(f, open, aura.data.alerts, aura.today, aura.data.metrics.days);
  const alert = alertFor(f, open);
  const show = () => nav.openSheet({ render: (close) => <AlertSheet alert={alert} close={close} withCard /> });
  return (
    // a div, not a button: the verb is a button of its own inside it (the ActionPill pattern)
    <div className="card rc-feature" data-feature={f.id} role="button" tabIndex={0} onClick={show} onKeyDown={pressKeys(show)}>
      <Disc icon={f.icon} accent={f.accent} size={40} soft />
      <span className="grow">
        <div className="rc-fname">{f.name}</div>
        <div className={`rc-fstatus ${status.kind}`}>{status.text}</div>
      </span>
      <button className="btn go" onClick={(e) => { e.stopPropagation(); performAlert(aura, nav, alert); }}>{f.verb}</button>
    </div>
  );
}

export function Recovery() {
  const aura = useAura();
  const nav = useNav();
  const live = openAlerts(aura.data.alerts, aura.data.alertOff);
  const count = live.length;

  const startPreset = (p: Preset) => {
    if (p.title === 'Wind-down') {
      windDown(aura, nav, p.accent); // the full DX-03 run, with the list for tomorrow under the clock
      return;
    }
    timedRun(aura, nav, { title: p.title, minutes: p.minutes, kind: p.kind, accent: p.accent, note: p.note, breathing: p.title === 'Slow breathing' });
  };

  return (
    <Page root title="Recovery" sub={count ? `${count} thing${count !== 1 ? 's' : ''} to do` : 'All clear'} right={<SettingsButton />}>
      <Section title="To do" />
      <div className="rc-todo-list">
        {count ? (
          // compact: just the pills; each feature's metric card is one tap away in its row's sheet below
          live.slice(0, MAX_ALERTS).map((alert) => <AlertCard key={String(alert.id)} alert={alert} withCard={false} />)
        ) : (
          <ActionPill icon="check" title="All clear" reason="Nothing to do" accent="go" />
        )}
      </div>
      {featureGroups().map((g) => (
        <div key={g.title} className="rc-group">
          <Section title={g.title} />
          <div className="rc-features">
            {g.features.map((f) => (
              <div key={f.id} className="rc-fblock">
                <FeatureRow f={f} open={live} />
                <LogFor featureId={f.id} />
              </div>
            ))}
          </div>
        </div>
      ))}
      <Section title="Presets" />
      <TileGrid>
        {PRESETS.map((p) => (
          <IconTile key={p.title} title={p.word} note={`${p.minutes} min`} icon={p.icon} accent={p.accent} onClick={() => startPreset(p)} />
        ))}
      </TileGrid>
    </Page>
  );
}
