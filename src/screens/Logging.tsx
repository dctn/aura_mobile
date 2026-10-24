// Search → Logging (ui/pages/logbook.py): what you note by hand, as cards you tap. Water (NI-04): today's ml against
// the goal, a drop per glass, the week as bars, +250 / +500. Mood (NI-02) and Connection (NI-03): five faces, a tap
// saves the check-in. Strength (PB-04): minutes and Log. The sleep check (DX-03) opens its seven questions. Then this
// week's entries. Everything is added in this browser only.

import { useState } from 'react';
import { useAura } from '../app/store';
import { useNav } from '../app/nav';
import type { Json } from '../lib/types';
import { Bars, Disc, FaceScale, Group, Page, Row, Stepper } from '../kit/kit';
import {
  FACE_WORDS, WATER_GLASS_ML, history, historyTile, insomniaMeaning, insomniaScore, lastCheckin,
  moodLowThisWeek, strengthWeek, waterGoal, waterOn, waterWeek,
} from '../search/logic';
import { DropRow, useBackLabel } from '../search/parts';
import { HelpSheet, SleepCheckSheet } from '../search/sheets';
import { isoIn, whenText } from '../search/when';
import '../search/search.css';

function CardHead({ icon, accent, name, note }: { icon: string; accent: string; name: string; note?: string }) {
  return (
    <div className="sx-chead">
      <Disc icon={icon} accent={accent} size={32} soft />
      <span className="name">{name}</span>
      {note && <span className="note">{note}</span>}
    </div>
  );
}

export function Logging() {
  const aura = useAura();
  const nav = useNav();
  const back = useBackLabel();
  const [minutes, setMinutes] = useState(30);
  const { checkins, logs, me } = aura.data;
  const { today, zone } = aura;
  const takenAt = () => isoIn(zone, new Date(), today);

  // -- water
  const ml = waterOn(logs, today);
  const goal = waterGoal(me);
  const water = (amount: number) => {
    aura.edit.add('logs', { type: 'water', date: today, takenAt: takenAt(), data: { ml: amount } });
    nav.showToast(`Logged ${amount} ml of water`);
  };

  // -- mood and connection
  const reachOut = () => {
    aura.edit.add('tasks', { title: 'Reach out to someone', status: 'open', horizon: 'today', createdAt: takenAt() });
    nav.showToast('Added to your tasks: Reach out to someone');
  };
  const checkin = (kind: 'mood' | 'connection', value: number) => {
    const row: Json = { kind, takenAt: takenAt(), date: today, [kind]: value };
    aura.edit.add('checkins', row);
    nav.showToast(`Saved: ${kind} ${value}/5`);
    if (kind !== 'mood') return;
    const weekLow = moodLowThisWeek([...checkins, row], today);
    if (value <= 2 || weekLow) nav.openSheet({ tall: true, render: (close) => <HelpSheet weekLow={weekLow} onReachOut={reachOut} close={close} /> });
  };
  const faceValue = (kind: 'mood' | 'connection') => {
    const last = lastCheckin(checkins, kind);
    return last ? Number(last[kind]) : null;
  };

  // -- strength
  const strength = strengthWeek(logs, today);
  const logStrength = () => {
    aura.edit.add('logs', { type: 'strength', date: today, takenAt: takenAt(), data: { minutes } });
    nav.showToast(`Logged ${minutes} min of strength`);
  };

  // -- sleep check
  const lastSleep = lastCheckin(checkins, 'insomnia');
  const lastScore = lastSleep ? insomniaScore(lastSleep) : null;
  const sleepCheck = () =>
    nav.openSheet({
      tall: true,
      render: (close) => (
        <SleepCheckSheet
          close={close}
          onDone={(answers) => {
            const score = answers.reduce((a, b) => a + b, 0);
            aura.edit.add('checkins', { kind: 'insomnia', takenAt: takenAt(), date: today, insomnia: answers, insomniaScore: score });
            nav.showAlert({ title: 'Your sleep check', body: `Score ${score} of 28. ${insomniaMeaning(score)}` });
          }}
        />
      ),
    });

  const entries = history(checkins, logs);

  return (
    <Page back={back} title="Logging">
      <div className="card sx-card">
        <CardHead icon="drop" accent="blue" name="Water" note="Today" />
        <div className="bignum">
          <span className="n">{ml.toLocaleString('en-US')}</span>
          <span className="u">of {goal.toLocaleString('en-US')} ml</span>
        </div>
        <DropRow filled={Math.floor(ml / WATER_GLASS_ML)} total={Math.ceil(goal / WATER_GLASS_ML)} />
        <Bars values={waterWeek(logs, today)} accent="blue" height={56} />
        <div className="sx-buttons">
          <button className="btn" onClick={() => water(WATER_GLASS_ML)}>+{WATER_GLASS_ML} ml</button>
          <button className="btn" onClick={() => water(2 * WATER_GLASS_ML)}>+{2 * WATER_GLASS_ML} ml</button>
        </div>
      </div>

      {(['mood', 'connection'] as const).map((kind) => {
        const value = faceValue(kind);
        return (
          <div className="card sx-card" key={kind}>
            <CardHead
              icon={kind === 'mood' ? 'bubble' : 'people'}
              accent={kind === 'mood' ? 'sleep' : 'teal'}
              name={kind === 'mood' ? 'Mood' : 'Connection'}
              note={value && value >= 1 && value <= 5 ? FACE_WORDS[kind][value - 1] : undefined}
            />
            <FaceScale value={value} onPick={(v) => checkin(kind, v)} size={44} />
          </div>
        );
      })}

      <div className="card sx-card">
        <CardHead icon="dumbbell" accent="fitness" name="Strength" note={strength ? `${strength} min this week` : undefined} />
        <div className="sx-strength">
          <Stepper value={minutes} min={5} max={240} step={5} format={(v) => `${v} min`} onChange={setMinutes} />
          <button className="btn go" onClick={logStrength}>Log</button>
        </div>
      </div>

      <Group>
        <Row title="Sleep check" icon="sleep" accent="sleep" disc value={lastScore !== null ? `${lastScore}/28` : undefined} chevron onClick={sleepCheck} />
      </Group>

      <Group heading="This week">
        {entries.length === 0 ? (
          <Row title={<span className="muted">{aura.errors.checkins ?? 'Nothing yet'}</span>} />
        ) : (
          entries.map((e) => {
            const tile = historyTile(e.text);
            return <Row key={e.id} title={e.text} icon={tile.icon} accent={tile.accent} disc value={whenText(e.at, today, zone)} />;
          })
        )}
      </Group>
    </Page>
  );
}

