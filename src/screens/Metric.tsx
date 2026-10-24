// One metric (ui/pages/metric.py), opened from a Search category or a Home/Recovery card: the big newest value and its
// unit, the average over the range shown, a 2 Weeks / 1 Month / 2 Months switch over tall rounded bars, a
// "Trending down -10%" card (the last 7 days against the 7 before) and "Pin to Home". All 60 days come with the
// snapshot, so the longer ranges need no fetch.

import { useState } from 'react';
import { useAura } from '../app/store';
import { useNav } from '../app/nav';
import * as m from '../lib/metrics';
import { tone } from '../lib/catalog';
import { Bars, Group, Icon, Page, Row, Segmented, Switch } from '../kit/kit';
import { MAX_PINS, RANGES, newestOf, pinsOf, togglePin, trend, trendWords } from '../search/logic';
import { useBackLabel } from '../search/parts';
import { shortDay } from '../search/when';
import '../search/search.css';

type Range = (typeof RANGES)[number]['value'];

export function Metric({ field }: { field: string }) {
  const aura = useAura();
  const nav = useNav();
  const back = useBackLabel();
  const card = m.cardFor(field);
  const days = aura.data.metrics.days as m.MetricDay[];
  const { today } = aura;

  // open on the shortest range that has anything to draw (the bundled sample's days are a few weeks old)
  const [range, setRange] = useState<Range>(() => RANGES.find((r) => m.newest(m.series(days, card.field, today, r.days)) !== null)?.value ?? '14');
  const count = RANGES.find((r) => r.value === range)?.days ?? 14;

  const values = m.series(days, card.field, today, count);
  const recent = m.series(days, card.field, today, 14);
  const latest = newestOf(days, card.field);
  const mean = m.average(values);
  const pct = trend(recent);
  const words = pct === null ? null : trendWords(pct);
  // the newest value is older than the last two weeks: say when it is from
  const stale = latest !== null && !recent.some((v) => v !== null);

  const pins = pinsOf(aura.local.pins);
  const pinned = pins.includes(card.field);
  const pin = (on: boolean) => {
    const next = togglePin(pins, card.field, on);
    if (next.full) {
      nav.showToast(`Up to ${MAX_PINS} pins: unpin one first`);
      return;
    }
    aura.edit.pins(next.pins);
    nav.showToast(`${card.label} ${on ? 'pinned to' : 'off'} Home`);
  };

  return (
    <Page back={back} title={card.label}>
      <div className="sx-hero" style={{ ['--tone' as string]: tone(card.accent) }}>
        <div className="sx-heronum">
          <span className="n">{latest ? m.cardShow(card, latest.value) : m.NO_VALUE}</span>
          {latest && card.unit && <span className="u">{card.unit}</span>}
        </div>
        <div className="sx-avg">
          <span className="muted">Avg</span>
          <span className="v">{mean !== null ? `${m.cardShow(card, mean)} ${card.unit}`.trim() : m.NO_VALUE}</span>
          {stale && latest && <span className="small">· latest on {shortDay(latest.day)}</span>}
        </div>
      </div>

      <div className="card sx-chart">
        <Segmented options={RANGES.map((r) => ({ value: r.value, label: r.label }))} value={range} onChange={setRange} />
        <Bars values={values.map((v) => (v === null ? null : m.cardNumber(card, v)))} accent={card.accent} height={210} />
        {!values.some((v) => v !== null) && <div className="small sx-empty">No {card.label.toLowerCase()} values in this range</div>}
      </div>

      {words && (
        <div className="card sx-trend" style={{ ['--tone' as string]: tone(card.accent) }}>
          <div className="sx-trendhead">
            <span className={`sx-arrow${words.direction < 0 ? ' down' : ''}`}>
              <Icon name={words.direction ? 'trend' : 'minus'} size={22} stroke={2.4} />
            </span>
            <span className="t">{words.title}</span>
          </div>
          <div className="sx-trendline">
            <span className="big">{words.text}</span>
            <span className="muted">vs last week</span>
          </div>
        </div>
      )}

      <Group>
        <Row title="Pin to Home" trailing={<Switch on={pinned} onChange={pin} label="Show it in Your pins on Home" />} />
      </Group>
    </Page>
  );
}
