// Home (ui/pages/home.py) in the Figma look: the calendar (a week or the month, then the chosen day's timeline and
// to-do list, where tasks and events are added, edited, ticked and deleted), today's alert pills with their 14-day
// cards, Burnout, this week's movement (weeklyActiveMinutes against 150, DX-09), the pins, then "Finish your
// profile" while body details are missing (BS-01) and the band line (BS-02). Every change stays in this browser.

import { useEffect, useRef, useState, type RefObject } from 'react';
import { useAura } from '../app/store';
import { useNav } from '../app/nav';
import * as m from '../lib/metrics';
import type { Json } from '../lib/types';
import { ActionPill, Icon, IconTile, MetricCard, Page, Ring, Row, Section, SettingsButton, SheetFrame, TileGrid } from '../kit/kit';
import { AlertCard, openAlerts } from './shared';
import { CalendarCard } from '../calendar/CalendarCard';
import {
  MAX_PINS, SHOW_ALERTS, WEEKLY_ACTIVE_TARGET, backfillMinutes, burnoutRisk, lastSync, missing, numericFields, pinLook, pinsOf,
  syncText, togglePin, weeklyMinutes,
} from '../calendar/homeParts';
import { longDay } from '../calendar/zone';
import '../calendar/calendar.css';

/** The cards under the Burnout pill (Avg sleep falls back to nightly sleep when the burnout column is missing). */
const BURNOUT_CARDS = ['avg_sleep', 'late_nights', 'recovery'];

/** The page's own height, for the calendar's 60% share. */
function useHeight(ref: RefObject<HTMLDivElement>): number {
  const [height, setHeight] = useState(0);
  useEffect(() => {
    const el = ref.current;
    if (!el) return undefined;
    setHeight(el.clientHeight);
    if (typeof ResizeObserver === 'undefined') return undefined;
    const watch = new ResizeObserver(() => setHeight(el.clientHeight));
    watch.observe(el);
    return () => watch.disconnect();
  }, [ref]);
  return height;
}

/** Choose up to six metrics for the Home tiles. */
function PinsSheet({ fields, chosen, close }: { fields: string[]; chosen: string[]; close: () => void }) {
  const aura = useAura();
  const [picked, setPicked] = useState(() => chosen.filter((f) => fields.includes(f) || !fields.length).slice(0, MAX_PINS));
  return (
    <SheetFrame
      title="Pins"
      onCancel={close}
      action="Done"
      onAction={() => {
        aura.edit.pins(picked.slice(0, MAX_PINS));
        close();
      }}
    >
      <div>
        <div className="head">{`${picked.length} of ${MAX_PINS} pinned`}</div>
        <div className="list">
          {(fields.length ? fields : chosen).map((f) => {
            const [icon, accent] = pinLook(f);
            return (
              <Row
                key={f}
                title={m.labelFor(f)}
                icon={icon}
                accent={accent}
                onClick={() => setPicked((p) => togglePin(p, f))}
                trailing={picked.includes(f) ? <span className="home-pin-tick"><Icon name="check" size={20} stroke={2.4} /></span> : <span style={{ width: 20 }} />}
              />
            );
          })}
        </div>
      </div>
    </SheetFrame>
  );
}

/** A metric card for one CARDS entry, shown even with no values ("—"). */
function cardFor(key: string, days: m.MetricDay[], today: string) {
  let c = m.CARDS[key];
  if (key === 'avg_sleep' && m.newest(m.series(days, c.field, today)) === null) c = m.CARDS.sleep;
  const raw = m.series(days, c.field, today);
  return {
    field: c.field,
    props: {
      label: m.CARDS[key].label,
      value: m.cardShow(c, m.newest(raw)),
      unit: c.unit,
      values: raw.map((v) => (v === null ? null : m.cardNumber(c, v))),
      accent: c.accent,
    },
  };
}

export function Home() {
  const aura = useAura();
  const nav = useNav();
  const scroll = useRef<HTMLDivElement>(null);
  const pageHeight = useHeight(scroll);
  const { data, today, zone, now } = aura;
  const days = data.metrics.days as m.MetricDay[];
  const latest = data.metrics.latest;
  const values = ((latest?.metrics as Json | undefined) ?? {}) as Json;

  // -- today's alerts
  const alerts = openAlerts(data.alerts, data.alertOff);
  const shown = alerts.slice(0, SHOW_ALERTS);
  const toRecovery = () => nav.setTab('recovery');

  // -- movement
  const minutes = weeklyMinutes(latest);
  const share = minutes / WEEKLY_ACTIVE_TARGET;

  // -- pins
  const pins = pinsOf(aura.local.pins);
  const editPins = () => {
    const fields = numericFields(values);
    nav.openSheet({ tall: true, render: (close) => <PinsSheet fields={fields.length ? fields : Object.keys(m.KNOWN)} chosen={pins} close={close} /> });
  };

  // -- profile and band
  const gaps = missing(data.me);
  const hasBand = Boolean(latest) || days.length > 0;
  const last = lastSync(aura.local.lastSync, data.metrics.days, latest);
  const band = hasBand || aura.local.lastSync ? syncText(last, now, zone) : { text: 'No band data', stale: true };
  const reconnect = () => {
    const sent = backfillMinutes(last, new Date());
    aura.edit.synced(new Date().toISOString());
    nav.showToast(`Band reconnected · ${sent} minutes synced`);
  };

  return (
    <Page root title="Home" sub={longDay(today)} right={<SettingsButton />} scrollRef={scroll}>
      <CalendarCard pageHeight={pageHeight} />

      <div className="home-stack">
        <Section
          title="Today"
          trailing={
            <span className="home-trail">
              {alerts.length > SHOW_ALERTS && <button className="link" onClick={toRecovery}>See all</button>}
              <button className="home-plus" aria-label="Open Recovery" title="Open Recovery" onClick={toRecovery}>
                <Icon name="plus" size={22} stroke={2} />
              </button>
            </span>
          }
        />
        {shown.length ? (
          shown.map((a, i) => <AlertCard key={String(a.id ?? i)} alert={a} />)
        ) : (
          <ActionPill icon="check" title="All clear" reason="Nothing to do" accent="go" onOpen={toRecovery} />
        )}
      </div>

      <div className="home-stack tight">
        <Section title="Burnout" />
        <ActionPill
          icon="flame"
          title="Burnout risk"
          reason={burnoutRisk(days, latest, today)}
          verb="See more"
          accent="workout"
          onOpen={() => nav.push({ name: 'metric', field: 'burnoutIndexScore' })}
          onVerb={() => nav.push({ name: 'metric', field: 'burnoutIndexScore' })}
        />
        {BURNOUT_CARDS.map((key) => {
          const c = cardFor(key, days, today);
          return <MetricCard key={key} {...c.props} chartHeight={52} onClick={() => nav.push({ name: 'metric', field: c.field })} />;
        })}
      </div>

      <button className="card home-move" onClick={() => nav.push({ name: 'metric', field: 'weeklyActiveMinutes' })}>
        <Ring value={share} size={72} stroke={10} accent="fitness">
          <span className="pct">{`${Math.round(share * 100)}%`}</span>
        </Ring>
        <span>
          <div className="mlabel">Movement</div>
          <div className="bignum">
            <span className="n">{`${minutes} of ${WEEKLY_ACTIVE_TARGET}`}</span>
            <span className="u">min</span>
          </div>
          <div className="note">{minutes >= WEEKLY_ACTIVE_TARGET ? 'Target met' : 'This week'}</div>
        </span>
      </button>

      <div className="home-stack tight">
        <Section title="Pins" trailing={<button className="link home-link" onClick={editPins} title="Choose the metrics on these tiles">Edit</button>} />
        {pins.length ? (
          <TileGrid>
            {pins.map((field) => {
              const [icon, accent] = pinLook(field);
              return <IconTile key={field} title={m.formatValue(field, values[field])} note={m.labelFor(field)} icon={icon} accent={accent} onClick={editPins} />;
            })}
          </TileGrid>
        ) : (
          <div className="list">
            <Row title="Pin a metric" look="link" icon="pin" onClick={editPins} />
          </div>
        )}
      </div>

      <div className="home-stack tight">
        {gaps.length > 0 && (
          <ActionPill
            icon="me"
            title="Finish your profile"
            reason={`${gaps.length} missing`}
            verb="Fix"
            accent="blue"
            onOpen={() => nav.push({ name: 'settings' })}
            onVerb={() => nav.push({ name: 'settings' })}
          />
        )}
        <div className={`home-band${band.stale ? ' warn' : ''}`}>
          <Icon name="band" size={20} stroke={2} />
          <span className="say">{band.text}</span>
          <button className="link" onClick={reconnect} title="Send what the band kept offline">Reconnect</button>
        </div>
      </div>
    </Page>
  );
}
