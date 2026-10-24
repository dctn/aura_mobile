// Settings (ui/pages/profile.py, the Settings part), pushed from the gear on every tab: who you are and the inbox,
// body details, goals, the band, one switch per alert, the theme, and resetting this browser's changes. Every change
// saves at once with aura.edit, in this browser only. Sign out, export, delete account and the Test lab are left out:
// each would talk to AWS.

import type { ReactNode } from 'react';
import { useAura } from '../app/store';
import { useNav } from '../app/nav';
import { FEATURES } from '../lib/catalog';
import { changeCount, type Theme } from '../lib/local';
import type { Json } from '../lib/types';
import { Group, Icon, Page, RoundButton, Row, Segmented, SheetFrame, Switch } from '../kit/kit';
import { SEX, age, alertAreas, hours, inboxRows, missing, sexText, switchAlert } from '../search/logic';
import { BirthSheet, ChoiceSheet, NumberSheet, TextSheet, TimeSheet, TitledGroup, dateText, useBackLabel } from '../search/parts';
import { isoIn, whenText } from '../search/when';
import '../search/search.css';

const ABOUT: [string, string][] = [
  ['me', 'Body details and goals save as soon as you set them, in this browser only.'],
  ['alarm', 'Bedtime rings the wind-down reminder; wake time rings the alarm.'],
  ['bell', 'Switch an alert off and Home stops showing it.'],
  ['band', 'The band and its data come from the shared demo account; Sync now only records the time here.'],
  ['trash', 'Reset my changes forgets everything you changed; the shared demo data stays as it is.'],
];

const THEMES: { value: Theme; label: string }[] = [
  { value: 'system', label: 'Auto' },
  { value: 'light', label: 'Light' },
  { value: 'dark', label: 'Dark' },
];

const AREAS = alertAreas(FEATURES);
const fmt = (n: number) => n.toLocaleString('en-US');

export function Settings() {
  const aura = useAura();
  const nav = useNav();
  const back = useBackLabel();
  const me = aura.data.me;
  const goals = (me.healthGoals as Json | undefined) ?? {};
  const name = String(me.displayName ?? '').trim();
  const gaps = missing(me);
  const inboxCount = inboxRows(aura.data.inbox).length;
  const off = aura.data.alertOff;
  const settings = (me.settings as Json | undefined) ?? {};
  const band = settings.pairedBandId ? String(settings.pairedBandId) : null;
  const changes = changeCount(aura.local);

  const saveMe = (fields: Json) => {
    aura.edit.me(fields);
    nav.showToast('Saved');
  };
  const saveGoal = (key: string, value: unknown) => {
    aura.edit.me({ healthGoals: { [key]: value } });
    nav.showToast('Saved');
  };
  const sheet = (render: (close: () => void) => ReactNode) => nav.openSheet({ render });

  const num = (v: unknown, fallback: number) => (typeof v === 'number' && Number.isFinite(v) ? v : Number(v) || fallback);
  const height = typeof me.heightCm === 'number' ? me.heightCm : null;
  const weight = typeof me.weightKg === 'number' ? me.weightKg : null;
  const birth = typeof me.birthDate === 'string' && me.birthDate ? me.birthDate : null;
  const sleepNeed = num(goals.sleepNeedMinutes, 480);
  const water = num(goals.waterGoalMl, 2000);
  const steps = num(goals.stepsPerDay, 8000);

  const numberRow = (title: string, value: string, open: () => void) => <Row title={title} value={value} chevron onClick={open} />;

  // a grey 24-hour pill (the browser's own time field would follow the visitor's 12/24-hour locale)
  const timePill = (key: 'bedtime' | 'wakeTime', fallback: string, label: string) => {
    const value = String(goals[key] || fallback);
    return (
      <button
        className="timepill sx-time"
        aria-label={`${label} ${value}`}
        onClick={() => sheet((close) => <TimeSheet title={label} value={value} close={close} onSave={(v) => saveGoal(key, v)} />)}
      >
        {value}
      </button>
    );
  };

  const info = () =>
    sheet((close) => (
      <SheetFrame title="Settings" action="Done" onAction={close}>
        <Group>
          {ABOUT.map(([icon, text]) => <Row key={icon} title={<span className="sx-wrap">{text}</span>} icon={icon} accent="label2" />)}
        </Group>
      </SheetFrame>
    ));

  const reset = () =>
    nav.showAlert({
      title: 'Reset my changes?',
      body: 'Everything you changed in this browser goes. The shared demo data stays as it is.',
      buttons: [
        { label: 'Cancel', role: 'cancel' },
        { label: 'Reset', role: 'destructive', onPress: () => { aura.edit.reset(); nav.showToast('Back to the demo data'); } },
      ],
    });

  return (
    <Page back={back} title="Settings" right={<RoundButton icon="info" label="About this screen" onClick={info} />}>
      <div className="list">
        <div className="sx-account">
          <span className="sx-avatar" aria-hidden="true">{(name[0] ?? '?').toUpperCase()}</span>
          <span className="grow">
            <div className="sx-who">{name || 'Your profile'}</div>
            <div className="muted">Demo account</div>
            {gaps.length > 0 && <div className="sx-gaps">{gaps.length} missing: {gaps.join(', ')}</div>}
          </span>
        </div>
        <Row title="Inbox" value={String(inboxCount)} chevron onClick={() => nav.push({ name: 'inbox' })} />
      </div>

      <TitledGroup title="Body">
        <Row
          title="Name"
          value={name || 'Not set'}
          chevron
          onClick={() => sheet((close) => <TextSheet title="Name" value={name} placeholder="Your name" close={close} onSave={(v) => saveMe({ displayName: v })} />)}
        />
        <Row
          title="Birth date"
          value={birth ? `${dateText(birth)} · age ${age(birth, aura.today)}` : 'Not set'}
          chevron
          onClick={() => sheet((close) => <BirthSheet value={birth} today={aura.today} close={close} onSave={(v) => saveMe({ birthDate: v })} />)}
        />
        <Row
          title="Sex"
          value={sexText(me.sex)}
          chevron
          onClick={() =>
            sheet((close) => (
              <ChoiceSheet
                title="Sex"
                options={SEX.map((v) => ({ value: v, label: sexText(v) }))}
                value={(SEX as readonly string[]).includes(String(me.sex)) ? (String(me.sex) as (typeof SEX)[number]) : 'notSet'}
                close={close}
                onPick={(v) => saveMe({ sex: v })}
              />
            ))
          }
        />
        {numberRow('Height', height ? `${height} cm` : 'Not set', () =>
          sheet((close) => <NumberSheet title="Height" value={height ?? 170} min={50} max={272} step={1} unit="cm" quick={[5, 10]} note="Without shoes." close={close} onSave={(v) => saveMe({ heightCm: v })} />),
        )}
        {numberRow('Weight', weight ? `${weight} kg` : 'Not set', () =>
          sheet((close) => <NumberSheet title="Weight" value={Math.round(weight ?? 70)} min={20} max={400} step={1} unit="kg" quick={[5, 10]} note="Morning weight is the steadiest." close={close} onSave={(v) => saveMe({ weightKg: v })} />),
        )}
      </TitledGroup>

      <TitledGroup title="Goals">
        <Row title="Bedtime" trailing={timePill('bedtime', '23:00', 'Bedtime')} />
        <Row title="Wake time" trailing={timePill('wakeTime', '07:00', 'Wake time')} />
        {numberRow('Sleep need', hours(sleepNeed), () =>
          sheet((close) => <NumberSheet title="Sleep need" value={sleepNeed} min={420} max={540} step={15} unit="min" quick={[15, 30]} close={close} onSave={(v) => saveGoal('sleepNeedMinutes', v)} />),
        )}
        {numberRow('Water', `${fmt(water)} ml`, () =>
          sheet((close) => <NumberSheet title="Water" value={water} min={0} max={10000} step={250} unit="ml" quick={[250, 500]} close={close} onSave={(v) => saveGoal('waterGoalMl', v)} />),
        )}
        {numberRow('Steps a day', fmt(steps), () =>
          sheet((close) => <NumberSheet title="Steps a day" value={steps} min={0} max={100000} step={500} quick={[500, 1000]} close={close} onSave={(v) => saveGoal('stepsPerDay', v)} />),
        )}
      </TitledGroup>

      <TitledGroup title="Band">
        <Row title="Band" value={band ?? 'None'} />
        <Row title="Last sync" value={aura.local.lastSync ? whenText(aura.local.lastSync, aura.today, aura.zone) : 'Never'} />
        <Row
          title="Sync now"
          look="link"
          onClick={() => {
            aura.edit.synced(isoIn(aura.zone, new Date()));
            nav.showToast('Band synced');
          }}
        />
        {AREAS.filter((a) => a.area === 'Band').flatMap((a) => a.features).map((f) => (
          <Row key={f.id} title={f.name} icon={f.icon} accent={f.accent} trailing={<AlertSwitch id={f.id} name={f.name} off={off} />} />
        ))}
      </TitledGroup>

      {AREAS.filter((a) => a.area !== 'Band').map((a) => (
        <TitledGroup key={a.area} title={`${a.area} alerts`}>
          {a.features.map((f) => (
            <Row key={f.id} title={f.name} icon={f.icon} accent={f.accent} trailing={<AlertSwitch id={f.id} name={f.name} off={off} />} />
          ))}
        </TitledGroup>
      ))}

      <TitledGroup title="Appearance">
        <Row title="Theme" trailing={<span className="sx-theme"><Segmented options={THEMES} value={aura.local.theme} onChange={aura.edit.theme} /></span>} />
      </TitledGroup>

      <TitledGroup title="Demo">
        <Row title="Your changes" value={changes ? `${changes} in this browser` : 'None'} />
        <Row title="Reset my changes" look="danger" onClick={reset} trailing={<Icon className="chev" name="trash" size={18} />} />
      </TitledGroup>
      <div className="foot">Nothing you change here is sent anywhere: it stays in this browser, drawn over the shared demo data.</div>
    </Page>
  );
}

function AlertSwitch({ id, name, off }: { id: string; name: string; off: string[] }) {
  const aura = useAura();
  const nav = useNav();
  const on = !off.includes(id);
  return (
    <Switch
      on={on}
      label={name}
      onChange={(next) => {
        aura.edit.alertOff(switchAlert(off, id, next));
        nav.showToast(`${name} ${next ? 'on' : 'off'}`);
      }}
    />
  );
}
