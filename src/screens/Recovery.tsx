// Recovery (demo ui/pages/recovery.py): "Often used" first, four black pills of the features and modes used most
// (recovery/usage.ts; with no history, a mix of the open alerts' features and the default modes); then every scientifically
// backed feature by group, each with its one-verb action and, where you note something by hand, its logging card and
// this week's entries, the busiest group and feature first; then four more modes (never one already in Often used) and
// "Show more" (recovery/ModesPage.tsx). A plan mode (a day off) opens its page rather than going on the calendar at once.
// Everything is worked out from the data on each render, so the page re-orders itself as things are used.

import { useAura } from '../app/store';
import { useNav } from '../app/nav';
import { ActionPill, Disc, Page, Section, SettingsButton, TileGrid, pressKeys } from '../kit/kit';
import { BY_ID, FEATURES, type Feature } from '../lib/catalog';
import { performAlert } from '../lib/actions';
import { findMode, type Mode } from '../lib/modes';
import type { Json } from '../lib/types';
import { LogFor } from '../recovery/logCards';
import { ModeTile, modeReason, modeVerb, startOrOpen } from '../recovery/ModesPage';
import { oftenUsed, orderGroups, scoresFor, topModes, type UseKey } from '../recovery/usage';
import { AlertSheet, alertFor, featureStatus, openAlerts } from './shared';
import '../recovery/recovery.css';

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

/** A feature in Often used: its status as the reason, its verb on the green button, its sheet on a tap. */
function FeaturePill({ f, open }: { f: Feature; open: Json[] }) {
  const aura = useAura();
  const nav = useNav();
  const status = featureStatus(f, open, aura.data.alerts, aura.today, aura.data.metrics.days);
  const alert = alertFor(f, open);
  return (
    <ActionPill
      icon={f.icon}
      title={f.name}
      reason={status.text}
      verb={f.verb}
      accent={f.accent}
      onVerb={() => performAlert(aura, nav, alert)}
      onOpen={() => nav.openSheet({ render: (close) => <AlertSheet alert={alert} close={close} withCard /> })}
    />
  );
}

/** A mode in Often used: "10 min · Sleep", Start (Plan for a plan mode, which opens the page), and its setup page on a
 *  tap. */
function ModePill({ mode }: { mode: Mode }) {
  const aura = useAura();
  const nav = useNav();
  return (
    <ActionPill
      icon={mode.icon}
      title={mode.name}
      reason={modeReason(mode)}
      verb={modeVerb(mode)}
      accent={mode.accent}
      onVerb={() => startOrOpen(aura, nav, mode)}
      onOpen={() => nav.push({ name: 'mode', id: mode.id })}
    />
  );
}

function UsedPill({ use, open }: { use: UseKey; open: Json[] }) {
  const [kind, id] = [use.slice(0, use.indexOf(':')), use.slice(use.indexOf(':') + 1)];
  if (kind === 'feature') {
    const f = BY_ID[id];
    return f ? <FeaturePill f={f} open={open} /> : null;
  }
  const mode = findMode(id);
  return mode ? <ModePill mode={mode} /> : null;
}

export function Recovery() {
  const aura = useAura();
  const nav = useNav();
  const live = openAlerts(aura.data.alerts, aura.data.alertOff);
  const count = live.length;
  // the open alerts come newest first, the order Often used takes them in when there is little history
  const scores = scoresFor(aura);
  const often = oftenUsed(scores, live.map((a) => String(a.featureId ?? '')).filter(Boolean));
  // the Modes tiles skip the modes Often used already shows
  const oftenModes = often.filter((use) => use.startsWith('mode:')).map((use) => use.slice('mode:'.length));

  return (
    <Page root title="Recovery" sub={count ? `${count} thing${count !== 1 ? 's' : ''} to do` : 'All clear'} right={<SettingsButton />}>
      <Section title="Often used" />
      <div className="rc-often">
        {often.map((use) => (
          <div key={use} data-use={use}>
            <UsedPill use={use} open={live} />
          </div>
        ))}
      </div>
      {orderGroups(featureGroups(), scores).map((g) => (
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
      <Section title="Modes" trailing={<button className="link rc-more" onClick={() => nav.push({ name: 'modes' })}>Show more</button>} />
      <TileGrid>
        {topModes(scores, 4, oftenModes).map((mode) => <ModeTile key={mode.id} mode={mode} onClick={() => startOrOpen(aura, nav, mode)} />)}
      </TileGrid>
    </Page>
  );
}
