// Recovery's logging, beside the feature it feeds (lifted from screens/Logging.tsx, same look and behaviour). Water
// (NI-04): today's ml against the goal, a drop per glass, the week as bars, +250 / +500. Mood (NI-02) and Connection
// (NI-03): five faces, a tap saves the check-in; a low mood (or a low week) opens "Help is close". Strength (PB-04):
// minutes and Log. The sleep check (DX-03): its seven questions. Each has its own "this week" list under it. A water
// log or a mood / connection check-in finishes that feature's open alert (actions.finishFeature), as on Home. Everything
// is added in this browser only.

import { useState } from 'react';
import { useAura } from '../app/store';
import { useNav } from '../app/nav';
import { LOGGED_BY, finishFeature } from '../lib/actions';
import type { Json } from '../lib/types';
import { addDays } from '../lib/agenda';
import { Bars, Disc, FaceScale, Group, Row, Stepper } from '../kit/kit';
import {
  FACE_WORDS, WATER_GLASS_ML, history, historyTile, insomniaMeaning, insomniaScore, lastCheckin,
  moodLowThisWeek, strengthWeek, waterGoal, waterOn, waterWeek,
} from '../search/logic';
import { DropRow } from '../search/parts';
import { HelpSheet, SleepCheckSheet } from '../search/sheets';
import { isoIn, whenText } from '../search/when';
import '../search/search.css';

/** What one card logs: a log type (water, strength) or a check-in kind (mood, connection, insomnia). */
export type LogKind = 'water' | 'strength' | 'mood' | 'connection' | 'insomnia';

/** Rows shown at most under a card. */
const WEEK_ROWS = 5;

/** One kind's entries over the last 7 days (to `today`), newest first. The day comes from the row's `date` (the
 *  person's own day), not from `takenAt`, which the server sends in UTC. */
export function entriesFor(checkins: Json[], logs: Json[], kind: LogKind, today: string, count = WEEK_ROWS) {
  const since = addDays(today, -6);
  const inWeek = (x: Json) => {
    const day = String(x.date ?? x.takenAt ?? '').slice(0, 10);
    return day >= since && day <= today;
  };
  const isLog = kind === 'water' || kind === 'strength';
  const ownLogs = isLog ? logs.filter((x) => String(x.type ?? 'water') === kind && inWeek(x)) : [];
  const ownCheckins = isLog ? [] : checkins.filter((c) => c.kind === kind && inWeek(c));
  return history(ownCheckins, ownLogs, count);
}

function CardHead({ icon, accent, name, note }: { icon: string; accent: string; name: string; note?: string }) {
  return (
    <div className="sx-chead">
      <Disc icon={icon} accent={accent} size={32} soft />
      <span className="name">{name}</span>
      {note && <span className="note">{note}</span>}
    </div>
  );
}

/** "Now" as the person's local ISO time on the demo's today (what Logging stamps rows with). */
function useTakenAt(): () => string {
  const { zone, today } = useAura();
  return () => isoIn(zone, new Date(), today);
}

export function WaterCard() {
  const aura = useAura();
  const nav = useNav();
  const takenAt = useTakenAt();
  const { logs, me } = aura.data;
  const ml = waterOn(logs, aura.today);
  const goal = waterGoal(me);
  const water = (amount: number) => {
    aura.edit.add('logs', { type: 'water', date: aura.today, takenAt: takenAt(), data: { ml: amount } });
    finishFeature(aura, LOGGED_BY.water); // any water counts: NI-04's own action is a glass, then done
    nav.showToast(`Logged ${amount} ml of water`);
  };
  return (
    <div className="card sx-card">
      <CardHead icon="drop" accent="blue" name="Water" note="Today" />
      <div className="bignum">
        <span className="n">{ml.toLocaleString('en-US')}</span>
        <span className="u">of {goal.toLocaleString('en-US')} ml</span>
      </div>
      <DropRow filled={Math.floor(ml / WATER_GLASS_ML)} total={Math.ceil(goal / WATER_GLASS_ML)} />
      <Bars values={waterWeek(logs, aura.today)} accent="blue" height={56} />
      <div className="sx-buttons">
        <button className="btn" onClick={() => water(WATER_GLASS_ML)}>+{WATER_GLASS_ML} ml</button>
        <button className="btn" onClick={() => water(2 * WATER_GLASS_ML)}>+{2 * WATER_GLASS_ML} ml</button>
      </div>
    </div>
  );
}

export function FaceCard({ kind }: { kind: 'mood' | 'connection' }) {
  const aura = useAura();
  const nav = useNav();
  const takenAt = useTakenAt();
  const { checkins } = aura.data;
  const { today } = aura;

  // the same steps as Logging (not actions.saveCheckin), so both places open the same "Help is close" sheet
  const reachOut = () => {
    aura.edit.add('tasks', { title: 'Reach out to someone', status: 'open', horizon: 'today', createdAt: takenAt() });
    nav.showToast('Added to your tasks: Reach out to someone');
  };
  const checkin = (value: number) => {
    const row: Json = { kind, takenAt: takenAt(), date: today, [kind]: value };
    aura.edit.add('checkins', row);
    finishFeature(aura, LOGGED_BY[kind]); // as saveCheckin does, so Home and Recovery agree
    nav.showToast(`Saved: ${kind} ${value}/5`);
    if (kind !== 'mood') return;
    const weekLow = moodLowThisWeek([...checkins, row], today);
    if (value <= 2 || weekLow) nav.openSheet({ tall: true, render: (close) => <HelpSheet weekLow={weekLow} onReachOut={reachOut} close={close} /> });
  };
  const last = lastCheckin(checkins, kind);
  const value = last ? Number(last[kind]) : null;

  return (
    <div className="card sx-card">
      <CardHead
        icon={kind === 'mood' ? 'bubble' : 'people'}
        accent={kind === 'mood' ? 'sleep' : 'teal'}
        name={kind === 'mood' ? 'Mood' : 'Connection'}
        note={value && value >= 1 && value <= 5 ? FACE_WORDS[kind][value - 1] : undefined}
      />
      <FaceScale value={value} onPick={checkin} size={44} />
    </div>
  );
}

export function StrengthCard() {
  const aura = useAura();
  const nav = useNav();
  const takenAt = useTakenAt();
  const [minutes, setMinutes] = useState(30);
  const strength = strengthWeek(aura.data.logs, aura.today);
  const logStrength = () => {
    aura.edit.add('logs', { type: 'strength', date: aura.today, takenAt: takenAt(), data: { minutes } });
    nav.showToast(`Logged ${minutes} min of strength`);
  };
  return (
    <div className="card sx-card">
      <CardHead icon="dumbbell" accent="fitness" name="Strength" note={strength ? `${strength} min this week` : undefined} />
      <div className="sx-strength">
        <Stepper value={minutes} min={5} max={240} step={5} format={(v) => `${v} min`} onChange={setMinutes} />
        <button className="btn go" onClick={logStrength}>Log</button>
      </div>
    </div>
  );
}

export function SleepCheckRow() {
  const aura = useAura();
  const nav = useNav();
  const takenAt = useTakenAt();
  const lastSleep = lastCheckin(aura.data.checkins, 'insomnia');
  const lastScore = lastSleep ? insomniaScore(lastSleep) : null;
  const sleepCheck = () =>
    nav.openSheet({
      tall: true,
      render: (close) => (
        <SleepCheckSheet
          close={close}
          onDone={(answers) => {
            const score = answers.reduce((a, b) => a + b, 0);
            aura.edit.add('checkins', { kind: 'insomnia', takenAt: takenAt(), date: aura.today, insomnia: answers, insomniaScore: score });
            nav.showAlert({ title: 'Your sleep check', body: `Score ${score} of 28. ${insomniaMeaning(score)}` });
          }}
        />
      ),
    });
  return (
    <Group>
      <Row title="Sleep check" icon="sleep" accent="sleep" disc value={lastScore !== null ? `${lastScore}/28` : undefined} chevron onClick={sleepCheck} />
    </Group>
  );
}

/** One kind's last 7 days under its card. */
export function WeekEntries({ kind }: { kind: LogKind }) {
  const aura = useAura();
  const { checkins, logs } = aura.data;
  const entries = entriesFor(checkins, logs, kind, aura.today);
  const error = kind === 'water' || kind === 'strength' ? aura.errors.logs : aura.errors.checkins;
  return (
    <Group heading="This week">
      {entries.length === 0 ? (
        <Row title={<span className="muted">{error ?? 'Nothing yet'}</span>} />
      ) : (
        entries.map((e) => {
          const tile = historyTile(e.text);
          return <Row key={e.id} title={e.text} icon={tile.icon} accent={tile.accent} disc value={whenText(e.at, aura.today, aura.zone)} />;
        })
      )}
    </Group>
  );
}

/** The features logged by hand on Recovery, and what each logs. */
export const LOG_KIND: Record<string, LogKind> = { 'DX-03': 'insomnia', 'PB-04': 'strength', 'NI-02': 'mood', 'NI-03': 'connection', 'NI-04': 'water' };

function LogCard({ kind }: { kind: LogKind }) {
  switch (kind) {
    case 'water':
      return <WaterCard />;
    case 'strength':
      return <StrengthCard />;
    case 'insomnia':
      return <SleepCheckRow />;
    default:
      return <FaceCard kind={kind} />;
  }
}

/** The card a feature logs with on Recovery, with its week; nothing for a feature that logs nothing by hand. */
export function LogFor({ featureId }: { featureId: string }) {
  const kind = LOG_KIND[featureId];
  if (!kind) return null;
  return (
    <div className="rc-log">
      <LogCard kind={kind} />
      <WeekEntries kind={kind} />
    </div>
  );
}
