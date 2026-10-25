// Pieces Home and Recovery share (ui/pages/home.py: open_alerts, fill_card, AlertSheet; ui/pages/recovery.py:
// AlertCard), and what Recovery's feature rows read (the alert a button acts on, the status line).

import { useAura } from '../app/store';
import { useNav } from '../app/nav';
import { featureFor, type Feature } from '../lib/catalog';
import * as m from '../lib/metrics';
import { newestOf, valueText } from '../search/logic';
import type { Json } from '../lib/types';
import { performAlert, markDone } from '../lib/actions';
import { ActionPill, Disc, Group, MetricCard, Row, SheetFrame } from '../kit/kit';

/** One alert per feature, its newest, while it is not done and not switched off; newest first, high priority ahead
 *  within a day. The app lists every open day; the demo month was sent at once, so that would be ~95 rows. */
export function openAlerts(alerts: Json[], off: string[] = []): Json[] {
  const rank: Record<string, number> = { high: 0, normal: 1, low: 2 };
  const offSet = new Set(off);
  const sorted = [...alerts].sort(
    (a, b) => String(b.date ?? '').localeCompare(String(a.date ?? '')) || (rank[String(a.priority)] ?? 1) - (rank[String(b.priority)] ?? 1),
  );
  const newest = new Map<string, Json>();
  for (const a of sorted) {
    const key = String(a.featureId ?? a.id ?? '');
    if (!newest.has(key)) newest.set(key, a);
  }
  return [...newest.values()].filter((a) => !a.done && !offSet.has(String(a.featureId ?? '')));
}

/** What a feature's button acts on: its open alert, else a stand-in built from the catalog, so the action works on
 *  any day. The stand-in has no id, so performAlert's done() has nothing to mark and no alert row is written. It has
 *  no body either: the catalog's `shows` is sample alert copy, which would read as a finding about this person. */
export function alertFor(f: Feature, open: Json[]): Json {
  return open.find((a) => a.featureId === f.id) ?? { featureId: f.id, action: { ref: f.action }, title: f.name };
}

export interface FeatureStatus {
  kind: 'todo' | 'done' | 'value' | 'fine';
  text: string;
}

/** A feature's line on Recovery: "To do" while its alert is open, "Done today" once an alert of today's was done,
 *  else the newest value of the metric it reads, else "Fine". */
export function featureStatus(f: Feature, open: Json[], alerts: Json[], today: string, days: m.MetricDay[]): FeatureStatus {
  if (open.some((a) => a.featureId === f.id)) return { kind: 'todo', text: 'To do' };
  if (alerts.some((a) => a.featureId === f.id && a.done && String(a.date ?? '').slice(0, 10) === today)) return { kind: 'done', text: 'Done today' };
  const latest = f.metric ? newestOf(days, f.metric) : null;
  if (latest) return { kind: 'value', text: valueText(m.cardFor(f.metric), latest.value) };
  return { kind: 'fine', text: 'Fine' };
}

/** A metric card's props for one column over the last 14 days; null when none of them has a value. */
export function cardProps(c: m.Card, days: m.MetricDay[], today: string, accent?: string) {
  const raw = m.series(days, c.field, today);
  const latest = m.newest(raw);
  if (latest === null) return null;
  return {
    label: c.label,
    value: m.cardShow(c, latest),
    unit: c.unit,
    values: raw.map((v) => (v === null ? null : m.cardNumber(c, v))),
    accent: accent ?? c.accent,
  };
}

/** ⓘ on an alert pill: the feature's icon and name, what the alert says, what to do, and its one-verb button. With
 *  `onLater` (Home), a "Not now" link hides today's alerts until a new one comes. With `withCard` (Recovery's feature
 *  rows), the feature's 14-day metric card sits under the text; a tap on it opens the metric. */
export function AlertSheet({ alert, close, onLater, withCard = false }: { alert: Json; close: () => void; onLater?: () => void; withCard?: boolean }) {
  const aura = useAura();
  const nav = useNav();
  const f = featureFor(alert);
  // a real alert without a body falls back to the catalog's copy; a stand-in (no id) only says what to do (f.does)
  const says = String(alert.body ?? '').trim() || (alert.id ? f?.shows ?? '' : '');
  const card = withCard && f?.metric ? cardProps(m.cardFor(f.metric), aura.data.metrics.days, aura.today, f.accent) : null;
  return (
    <SheetFrame title="" action="Done" onAction={close}>
      <div style={{ display: 'grid', justifyItems: 'center', gap: 8, textAlign: 'center' }}>
        <Disc icon={f?.icon ?? 'bell'} accent={f?.accent} size={56} />
        <div style={{ fontSize: 20, fontWeight: 600 }}>{f?.name ?? String(alert.title ?? 'Alert')}</div>
      </div>
      <Group>
        {says && <Row title={says} icon="info" accent="label2" />}
        {f && <Row title={f.does} icon="check" accent="good" />}
      </Group>
      {card && <MetricCard {...card} chartHeight={52} onClick={() => { close(); nav.push({ name: 'metric', field: f!.metric }); }} />}
      {f && (
        <button className="btn primary wide" style={{ background: `var(--${f.accent})` }} onClick={() => { close(); performAlert(aura, nav, alert); }}>
          {f.verb}
        </button>
      )}
      {onLater && (
        <button className="link" style={{ alignSelf: 'center' }} title="Hide today's alerts until a new one comes" onClick={() => { close(); onLater(); }}>
          Not now
        </button>
      )}
    </SheetFrame>
  );
}

/** One thing to do: the alert pill (icon, name, reason, check, green verb) over the card of the metric it reads. */
export function AlertCard({ alert, withCard = true, onLater }: { alert: Json; withCard?: boolean; onLater?: () => void }) {
  const aura = useAura();
  const nav = useNav();
  const f = featureFor(alert);
  const card = withCard && f?.metric ? cardProps(m.cardFor(f.metric), aura.data.metrics.days, aura.today, f.accent) : null;
  return (
    <div style={{ display: 'grid', gap: 8 }}>
      <ActionPill
        icon={f?.icon ?? 'bell'}
        title={f?.name ?? String(alert.title ?? 'Alert')}
        reason={f?.reason}
        verb={f?.verb ?? 'Open'}
        accent={f?.accent}
        onOpen={() => nav.openSheet({ render: (close) => <AlertSheet alert={alert} close={close} onLater={onLater} /> })}
        onVerb={() => performAlert(aura, nav, alert)}
        onDone={() => markDone(aura, nav, alert)}
      />
      {card && <MetricCard {...card} chartHeight={52} onClick={() => nav.push({ name: 'metric', field: f!.metric })} />}
    </div>
  );
}
