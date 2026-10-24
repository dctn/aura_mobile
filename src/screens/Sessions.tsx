// Search → Sessions (demo ui/pages/sessions.py): the run going now (pause/play, a red stop, Done), then this week's
// sessions with their totals. A row is the title, its length and the day; a finished recovery session has
// "Stress change" (DX-07).

import { useAura } from '../app/store';
import { backLabel, useNav } from '../app/nav';
import { Disc, Group, Icon, Page, Row } from '../kit/kit';
import { stressChangeText } from '../lib/actions';
import { runControls, useLiveRun } from '../run/live';
import * as S from '../recovery/sessions';
import '../recovery/recovery.css';

function RunningCard() {
  const run = useLiveRun();
  if (!run) return null;
  const ctl = runControls();
  return (
    <div className="card rc-running">
      <button className="rc-rhead" onClick={() => ctl?.expand()} style={{ textAlign: 'left' }} aria-label={`Open ${run.title}`}>
        <Disc icon={run.breathing ? 'wind' : run.night ? 'sleep' : 'timer'} accent={run.accent} size={40} />
        <span className="grow" style={{ flex: 1 }}>
          <div className="rc-rname">{run.title}</div>
          <div className="rc-rstate">{run.status}</div>
        </span>
        <Icon className="chev" name="chevron" size={16} stroke={2} />
      </button>
      <div className="rc-rbuttons">
        <button className="rc-rbtn" onClick={() => ctl?.togglePause()} aria-label={run.paused ? 'Resume' : 'Pause'} title={run.paused ? 'Resume' : 'Pause'}>
          <Icon name={run.paused ? 'playsolid' : 'bars2'} size={22} stroke={2} />
        </button>
        <button className="rc-rbtn stop" onClick={() => ctl?.askEnd()} aria-label="Stop" title="Stop">
          <Icon name="xmark" size={22} stroke={2.6} />
        </button>
        <span className="grow" />
        <button className="btn go" onClick={() => ctl?.complete()} title="Count it as complete">Done</button>
      </div>
    </div>
  );
}

function ServerCard({ session, nowMs }: { session: Record<string, unknown>; nowMs: number }) {
  const [icon, accent] = S.KIND_TILES[String(session.kind)] ?? ['timer', 'blue'];
  const title = String(session.title ?? session.kind ?? 'Session');
  return (
    <div className="card rc-running">
      <div className="rc-rhead">
        <Disc icon={icon} accent={accent} size={40} />
        <span style={{ flex: 1 }}>
          <div className="rc-rname">{title[0].toUpperCase() + title.slice(1)}</div>
          <div className="rc-rstate">{S.sessionStatus(session, nowMs)} · on another device</div>
        </span>
      </div>
    </div>
  );
}

export function Sessions() {
  const aura = useAura();
  const nav = useNav();
  const live = useLiveRun();
  const server = live ? null : S.openServerSession(aura.data.active);
  const week = S.thisWeek(aura.data.sessions, aura.zone, aura.today);
  const sum = S.totals(week);

  const stressChange = () => nav.showAlert({ title: 'Stress before and after', body: stressChangeText(null) });

  return (
    <Page back={backLabel(nav)} title="Sessions">
      {live && <RunningCard />}
      {server && <ServerCard session={server} nowMs={aura.now.getTime()} />}
      <Group
        heading="This week"
        footer={sum.count ? `${sum.count} session${sum.count !== 1 ? 's' : ''} · ${sum.completed} completed · ${sum.minutes} min in all` : undefined}
      >
        {week.length ? (
          week.map((s) => {
            const title = String(s.title ?? s.kind ?? 'Session');
            const [icon, accent] = S.KIND_TILES[String(s.kind)] ?? ['clock', 'blue'];
            const sub = [S.lengthText(s), S.isStopped(s) ? 'Stopped' : ''].filter(Boolean).join(' · ');
            const day = S.whenText(s.startedAt, aura.zone, aura.today).split(' ')[0];
            const stress = s.kind === 'recovery' && !S.isStopped(s);
            return (
              <Row
                key={String(s.id)}
                title={title}
                sub={sub}
                value={day}
                icon={icon}
                accent={accent}
                disc
                trailing={stress ? <button className="link" onClick={stressChange} title="Your stress before and after">Stress change</button> : undefined}
              />
            );
          })
        ) : (
          <Row title={<span className="rc-quiet">None yet</span>} />
        )}
      </Group>
    </Page>
  );
}
