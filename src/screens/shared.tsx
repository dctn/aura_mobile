// Pieces Home and Recovery share (ui/pages/home.py: open_alerts, fill_card, AlertSheet; ui/pages/recovery.py:
// AlertCard).

import { useAura } from '../app/store';
import { useNav } from '../app/nav';
import { featureFor } from '../lib/catalog';
import * as m from '../lib/metrics';
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
 *  `onLater` (Home), a "Not now" link hides today's alerts until a new one comes. */
export function AlertSheet({ alert, close, onLater }: { alert: Json; close: () => void; onLater?: () => void }) {
  const aura = useAura();
  const nav = useNav();
  const f = featureFor(alert);
  const says = String(alert.body ?? '').trim() || f?.shows || '';
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
