// Home (ui/pages/home.py) in the Figma look: the calendar (a week or the month, then the chosen day's timeline and
// to-do list, where tasks and events are added, edited, ticked and deleted), Today (only what is due today: today's
// alert pills with their 14-day cards, the check-ins, water, strength and the recovery events still ahead),
// Burnout, this week's movement (weeklyActiveMinutes against 150, DX-09), the pins, then "Finish your profile" while
// body details are missing (BS-01) and the band line (BS-02). Every change stays in this browser.

import { useEffect, useRef, useState, type RefObject } from 'react';
import { featureFor } from '../lib/catalog';
import { findMode } from '../lib/modes';
import { noteUse } from '../recovery/usage';
import { askCheckin, logWater, nowOf, performAlert, sleepCheck, startMode, timedRun } from '../lib/actions';
import { clock } from '../lib/agenda';
import { useAura, type Aura } from '../app/store';
import { useNav, type Nav } from '../app/nav';
import * as m from '../lib/metrics';
import type { Json } from '../lib/types';
import { ActionPill, Group, Icon, IconTile, MetricCard, Page, Ring, Row, Section, SettingsButton, SheetFrame, Stepper, TileGrid } from '../kit/kit';
import { AlertCard, openAlerts } from './shared';
import { CalendarCard } from '../calendar/CalendarCard';
import { ItemSheet } from '../calendar/ItemSheet';
import {
  MAX_PINS, SHOW_TODAY, STRENGTH_DAYS, WEEKLY_ACTIVE_TARGET, backfillMinutes, burnoutRisk, lastSync, minuteNow, missing,
  numericFields, pinLook, pinsOf, seeAllAt, syncText, todayItems, togglePin, weeklyMinutes, type TodayItem,
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

/** "+" (and All clear): the top alert's verb, a check-in, a short breather or move, and water. The step runs once the
 *  sheet has closed, so a timer or a check-in opens over the phone, not over the sheet. */
function QuickSheet({ top, close }: { top: Json | null; close: () => void }) {
  const aura = useAura();
  const nav = useNav();
  const f = top ? featureFor(top) : null;
  const then = (step: () => void) => {
    close();
    window.setTimeout(step, 0);
  };
  const ml = aura.data.logs
    .filter((l) => l.type === 'water' && String(l.date ?? String(l.takenAt ?? '').slice(0, 10)) === aura.today)
    .reduce((n, l) => n + (Number((l.data as Json | undefined)?.ml) || 0), 0);
  return (
    <SheetFrame title="Quick" action="Done" onAction={close}>
      {top && f && (
        <button className="btn primary wide" style={{ background: `var(--${f.accent})` }} onClick={() => then(() => performAlert(aura, nav, top))}>
          {f.verb}
        </button>
      )}
      <Group heading="Check in">
        <Row title="Mood" icon="bubble" accent="sleep" chevron onClick={() => then(() => askCheckin(aura, nav, 'mood'))} />
        <Row title="Connection" icon="people" accent="teal" chevron onClick={() => then(() => askCheckin(aura, nav, 'connection'))} />
      </Group>
      <Group heading="Quick">
        <Row title="Breathe" value="5 min" icon="wind" accent="stress" chevron onClick={() => then(() => timedRun(aura, nav, { title: 'Slow breathing', minutes: 5, accent: 'stress', breathing: true }))} />
        <Row title="Move" value="3 min" icon="walk" accent="fitness" chevron onClick={() => then(() => timedRun(aura, nav, { title: 'Move break', minutes: 3, kind: 'walk', accent: 'fitness' }))} />
      </Group>
      <Group heading="Water">
        <Row
          title={`${ml.toLocaleString('en-US')} ml today`}
          icon="drop"
          accent="blue"
          trailing={
            <span style={{ display: 'flex', gap: 6 }}>
              {[250, 500].map((n) => (
                <button key={n} className="chip" title={`Log ${n} ml of water`} onClick={() => logWater(aura, nav, n)}>{`+${n} ml`}</button>
              ))}
            </span>
          }
        />
      </Group>
    </SheetFrame>
  );
}

/** Today's Strength "Log": minutes (5 to 240), saved as a strength log as Search → Logging does. */
function StrengthSheet({ close }: { close: () => void }) {
  const aura = useAura();
  const nav = useNav();
  const [minutes, setMinutes] = useState(30);
  const log = () => {
    const now = nowOf(aura);
    aura.edit.add('logs', { type: 'strength', date: now.day, takenAt: now.iso, data: { minutes } });
    noteUse(aura, 'feature:PB-04');
    nav.showToast(`Logged ${minutes} min of strength`);
    close();
  };
  return (
    <SheetFrame title="Strength" onCancel={close} action="Log" onAction={log}>
      <Group heading="How long" footer={`Muscle-strengthening on ${STRENGTH_DAYS} or more days a week (WHO).`}>
        <Row title="Minutes" icon="dumbbell" accent="fitness" trailing={<Stepper value={minutes} min={5} max={240} step={5} format={(v) => `${v} min`} onChange={setMinutes} />} />
      </Group>
    </SheetFrame>
  );
}

/** A recovery event's pill look by its title's start: [icon, accent]; a run's mode look comes first. */
const EVENT_LOOK: [string, string, string][] = [
  ['Dim screens', 'evening', 'sleep'],
  ['Strength', 'dumbbell', 'fitness'],
  ['Brisk session', 'flame', 'fitness'],
  ['Meal with', 'people', 'teal'],
  ['Wind-down', 'evening', 'sleep'],
];

/** The pill for one of today's non-alert to-dos: what it says and what its button does. */
function todayPill(item: Exclude<TodayItem, { kind: 'alert' }>, aura: Aura, nav: Nav, nowMinute: number) {
  switch (item.kind) {
    case 'checkin': {
      const { check } = item;
      if (check === 'sleep') {
        const go = () => sleepCheck(aura, nav);
        return { icon: 'sleep', accent: 'sleep', title: 'Sleep check', reason: 'Trouble sleeping for 3 weeks', verb: 'Start', onVerb: go, onOpen: go };
      }
      const go = () => askCheckin(aura, nav, check);
      return check === 'mood'
        ? { icon: 'bubble', accent: 'sleep', title: 'Mood', reason: 'How do you feel today?', verb: 'Check in', onVerb: go, onOpen: go }
        : { icon: 'people', accent: 'teal', title: 'Connection', reason: 'How connected do you feel?', verb: 'Log', onVerb: go, onOpen: go };
    }
    case 'water':
      return { icon: 'drop', accent: 'blue', title: 'Water', reason: `${item.ml.toLocaleString('en-US')} of ${item.goal.toLocaleString('en-US')} ml`, verb: '+250', onVerb: () => logWater(aura, nav), onOpen: () => nav.setTab('recovery') };
    case 'strength': {
      const go = () => nav.openSheet({ render: (close) => <StrengthSheet close={close} /> });
      // a rolling 7 days (todayItems), so "this week" would be wrong on a Monday
      return { icon: 'dumbbell', accent: 'fitness', title: 'Strength', reason: `${item.days} of ${STRENGTH_DAYS} days in the last 7 days`, verb: 'Log', onVerb: go, onOpen: go };
    }
    case 'event': {
      const { item: ev, end, run } = item;
      const start = ev.start ?? 0;
      const mode = run ? findMode(run) : undefined;
      const look = EVENT_LOOK.find(([t]) => ev.title.startsWith(t));
      const icon = mode?.icon ?? look?.[1] ?? 'cal';
      const accent = mode?.accent ?? look?.[2] ?? 'blue';
      const reason = start <= nowMinute ? `Now · until ${clock(end)}` : `${clock(start)}–${clock(end)}`;
      // the Start runs the mode for the event's own length (it may have been stretched); the wind-down keeps its list
      const begin = mode ? () => startMode(aura, nav, mode, ev.minutes > 0 ? { minutes: ev.minutes } : {}) : undefined;
      const open = () => nav.openSheet({ render: (close) => <ItemSheet close={close} kind="event" item={ev} day={ev.day ?? aura.today} at={ev.start} minutes={ev.minutes} dated /> });
      return { icon, accent, title: ev.title, reason, verb: begin ? 'Start' : undefined, onVerb: begin, onOpen: open };
    }
  }
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

  // -- today: what is due today; every other open alert waits in Recovery
  const alerts = openAlerts(data.alerts, data.alertOff);
  // "Not now" hides the pills until an alert arrives that wasn't open then
  const snoozed = new Set(aura.local.snoozed ?? []);
  const fresh = alerts.some((a) => !snoozed.has(String(a.id)));
  const live = alerts.length && !fresh ? [] : alerts;
  const items = todayItems(data, today, zone, now, !live.length);
  const shown = items.slice(0, SHOW_TODAY);
  const todayAlerts = items.filter((i) => i.kind === 'alert').length;
  const seeAll = seeAllAt(alerts.length, todayAlerts, items.length);
  const nowMinute = minuteNow(now, zone);
  const toRecovery = () => nav.setTab('recovery');
  const notNow = () => aura.edit.snooze(alerts.map((a) => String(a.id)));
  const quick = () => nav.openSheet({ tall: true, render: (close) => <QuickSheet top={live[0] ?? null} close={close} /> });
  const { snooze } = aura.edit;
  const stale = Boolean(aura.local.snoozed?.length) && fresh;
  useEffect(() => {
    if (stale) snooze(null); // something new came: forget the old snooze
  }, [stale, snooze]);

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
    nav.showToast(`Band reconnecting… sending ${sent} minutes`);
    window.setTimeout(() => {
      aura.edit.synced(new Date().toISOString());
      nav.showToast(`Band reconnected · ${sent} minutes synced`);
    }, 1400);
  };

  return (
    <Page root title="Home" sub={longDay(today)} right={<SettingsButton />} scrollRef={scroll}>
      <CalendarCard pageHeight={pageHeight} />

      <div className="home-stack">
        <Section
          title="Today"
          trailing={
            <span className="home-trail">
              {seeAll === 'top' && <button className="link" onClick={toRecovery}>See all</button>}
              <button className="home-plus" aria-label="Quick: check in, breathe or log water" title="Check in, breathe or log water" onClick={quick}>
                <Icon name="plus" size={22} stroke={2} />
              </button>
            </span>
          }
        />
        {shown.length ? (
          shown.map((item) =>
            item.kind === 'alert' ? (
              <AlertCard key={item.key} alert={item.alert} onLater={notNow} />
            ) : (
              <ActionPill key={item.key} {...todayPill(item, aura, nav, nowMinute)} />
            ),
          )
        ) : (
          <ActionPill icon="check" title="All clear" reason={alerts.length ? `${alerts.length} in Recovery` : 'Nothing to do'} accent="go" onOpen={quick} />
        )}
        {seeAll === 'bottom' && (
          <button className="link home-more" onClick={toRecovery}>See all</button>
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
