// The full-phone timer and the 3-2-1 before it (demo ui/runview.py). A run covers the phone: Hide ⌄ and ⓘ at the
// top; the kicker, the title, a big clock, a note and a thin progress line in the middle; a round pause and a red ✕
// at the bottom. Variants: night (black, the nap), a breathing pacer, open-ended (Flowtime counts up).
//
// Hide slides it into a mini bar 8 px above the tab bar (Phone shows the tab bar again while it is hidden); a tap opens it again. The clock
// reads a wall-clock start, so pauses add up and nothing drifts. run.onEnd is called once, then nav.endRun().

import { useCallback, useEffect, useRef, useState, type CSSProperties } from 'react';
import { useNav, type Run } from '../app/nav';
import { Disc, Icon, Ring } from '../kit/kit';
import { tone } from '../lib/catalog';
import * as C from './clock';
import { publish, type RunControls } from './live';
import './run.css';

/** A short beep when the time is up (the app's QApplication.beep); silent where audio is not allowed. */
function beep(): void {
  try {
    const Ctx = window.AudioContext ?? (window as unknown as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext;
    if (!Ctx) return;
    const ctx = new Ctx();
    const osc = ctx.createOscillator();
    const gain = ctx.createGain();
    osc.frequency.value = 880;
    gain.gain.setValueAtTime(0.08, ctx.currentTime);
    gain.gain.exponentialRampToValueAtTime(0.0001, ctx.currentTime + 0.35);
    osc.connect(gain).connect(ctx.destination);
    osc.start();
    osc.stop(ctx.currentTime + 0.36);
    osc.onended = () => void ctx.close();
  } catch {
    // no audio: the done sheet is enough
  }
}

const whole = (minutes: number) => (Number.isInteger(minutes) ? minutes : Number(minutes.toFixed(1)));

// -- the 3-2-1 ----------------------------------------------------------------------------------------------------

const COUNT_S = 3;

/** 3-2-1 over the whole phone (a green ring running down). Cancel drops the run that follows. */
export function Countdown() {
  const nav = useNav();
  const [start] = useState(() => performance.now());
  const [now, setNow] = useState(start);
  useEffect(() => {
    let id = 0;
    const frame = () => {
      setNow(performance.now());
      id = requestAnimationFrame(frame);
    };
    id = requestAnimationFrame(frame);
    return () => cancelAnimationFrame(id);
  }, []);
  const left = Math.max(0, COUNT_S - (now - start) / 1000);
  return (
    <div className="rv-count" role="timer" aria-live="assertive">
      <span className="grow" />
      <Ring value={left / COUNT_S} size={200} stroke={12} accent="go">
        <span className="rv-count-n">{Math.max(1, Math.ceil(left))}</span>
      </Ring>
      <span className="grow" />
      <button className="link" onClick={nav.cancelCountdown}>Cancel</button>
    </div>
  );
}

// -- the run ------------------------------------------------------------------------------------------------------

export function RunView({ run }: { run: Run }) {
  return <RunBody run={run} />;
}

function BreathingCircle({ scale }: { scale: number }) {
  const size = 230;
  const full = size / 2 - 4;
  const r = full * scale;
  return (
    <svg className="rv-breath" width={size} height={size} viewBox={`0 0 ${size} ${size}`} aria-hidden="true">
      <circle className="track" cx={size / 2} cy={size / 2} r={full} />
      <circle className="lung" cx={size / 2} cy={size / 2} r={r} />
      <circle className="core" cx={size / 2} cy={size / 2} r={7} />
    </svg>
  );
}

function RunBody({ run }: { run: Run }) {
  const nav = useNav();
  const [clock, setClock] = useState(() => C.startClock(run.minutes * 60, Date.now()));
  const [now, setNow] = useState(() => Date.now());
  const [collapsed, setCollapsed] = useState(false);
  /** The time ran out: the "done" sheet shows; closing it completes the run. */
  const [up, setUp] = useState(false);
  const settled = useRef(false);
  const clockRef = useRef(clock);
  clockRef.current = clock;
  const open = C.openEnded(clock);
  const note = run.breathing ? 'In through the nose, out slowly: five seconds each.' : run.note ?? '';
  const accentStyle = { ['--tone' as string]: tone(run.accent) } as CSSProperties;

  // -- time
  useEffect(() => {
    if (up) return undefined;
    if (run.breathing && !collapsed) {
      let id = 0;
      const frame = () => {
        setNow(Date.now());
        id = requestAnimationFrame(frame);
      };
      id = requestAnimationFrame(frame);
      // rAF stops in a hidden tab; the interval below still notices the time running out
      const backup = window.setInterval(() => setNow(Date.now()), 1000);
      return () => {
        cancelAnimationFrame(id);
        window.clearInterval(backup);
      };
    }
    const id = window.setInterval(() => setNow(Date.now()), 250);
    return () => window.clearInterval(id);
  }, [run.breathing, collapsed, up]);

  useEffect(() => {
    if (!up && !settled.current && C.isUp(clock, now)) {
      setUp(true);
      setCollapsed(false);
      beep();
    }
  }, [clock, now, up]);

  // -- ending
  const finish = useCallback(
    (completed: boolean) => {
      if (settled.current) return;
      settled.current = true;
      const seconds = Math.round(C.elapsed(clockRef.current, Date.now()));
      publish(null);
      run.onEnd?.(completed, seconds);
      nav.endRun();
    },
    [run, nav],
  );

  const togglePause = useCallback(() => {
    if (up || settled.current) return;
    const t = Date.now();
    setClock((c) => (c.pausedAt === null ? C.pause(c, t) : C.resume(c, t)));
    setNow(t);
  }, [up]);

  const askEnd = useCallback(() => {
    if (up || settled.current) return;
    const c = clockRef.current;
    const t = Date.now();
    const head = C.openEnded(c) ? `${C.clockText(C.elapsed(c, t))} so far` : C.minutesLeftText(C.left(c, t)).replace(/^./, (x) => x.toUpperCase());
    nav.showAlert({
      title: `End now? ${head}.`,
      buttons: [
        { label: 'Mark as done', role: 'default', onPress: () => finish(true) },
        { label: 'End session', role: 'destructive', onPress: () => finish(false) },
        { label: 'Cancel', role: 'cancel' },
      ],
    });
  }, [up, nav, finish]);

  const showInfo = () => {
    const length = open ? 'Open-ended' : `${whole(run.minutes)} minutes`;
    const parts = [note, `${length}, timed on this phone and saved as a session.`, 'Hide keeps it running above the tabs. End lets you stop early or count it as done.'];
    nav.showAlert({ title: run.title, body: parts.filter(Boolean).join(' ') });
  };

  // -- what the rest of the phone sees (Sessions' running card, "one run at a time")
  const controls = useRef<RunControls>({ expand: () => {}, togglePause: () => {}, askEnd: () => {}, complete: () => {} });
  controls.current.expand = () => setCollapsed(false);
  controls.current.togglePause = togglePause;
  controls.current.askEnd = askEnd;
  controls.current.complete = () => finish(true);
  const status = C.statusText(clock, now);
  useEffect(() => {
    if (settled.current) return;
    publish(
      { title: run.title, accent: run.accent, breathing: Boolean(run.breathing), night: Boolean(run.night), openEnded: open, paused: clock.pausedAt !== null, collapsed, status },
      {
        expand: () => controls.current.expand(),
        togglePause: () => controls.current.togglePause(),
        askEnd: () => controls.current.askEnd(),
        complete: () => controls.current.complete(),
      },
    );
  }, [run, open, clock.pausedAt, collapsed, status]);
  useEffect(() => () => publish(null), []);

  const paused = clock.pausedAt !== null;
  const shown = C.clockSeconds(clock, now);
  const breath = run.breathing ? C.breath(C.elapsed(clock, now)) : null;

  if (collapsed) {
    return (
      <div className="rv-mini" role="button" tabIndex={0} style={accentStyle} onClick={() => setCollapsed(false)} onKeyDown={(e) => e.key === 'Enter' && setCollapsed(false)} aria-label={`Open ${run.title}`}>
        <Disc icon={run.breathing ? 'wind' : run.night ? 'sleep' : 'timer'} accent={run.accent} size={32} />
        <span className="grow">
          <span className="rv-mini-title">{run.title}</span>
          <span className="rv-mini-sub">{status}</span>
        </span>
        <button className="rv-mini-btn" aria-label={paused ? 'Resume' : 'Pause'} title={paused ? 'Resume' : 'Pause'} onClick={(e) => { e.stopPropagation(); togglePause(); }}>
          <Icon name={paused ? 'playsolid' : 'bars2'} size={22} stroke={2} />
        </button>
      </div>
    );
  }

  return (
    <div className={`runview${run.night ? ' night' : ''}`} style={accentStyle} role="dialog" aria-label={run.title}>
      <div className="rv-top">
        <button className="rv-hide" onClick={() => setCollapsed(true)} title="Keep it running in the bar above the tabs">
          Hide <Icon name="down" size={16} stroke={2.4} />
        </button>
        <span className="grow" />
        <button className="rv-info" onClick={showInfo} aria-label="About this timer" title="About this timer">
          <Icon name="info" size={22} />
        </button>
      </div>
      <div className="rv-mid">
        <div className="rv-kicker">{run.label ?? C.kicker('', run.minutes, run.breathing)}</div>
        <div className="rv-title">{run.title}</div>
        {breath && (
          <>
            <BreathingCircle scale={breath.scale} />
            <div className="rv-phase">{breath.text}</div>
          </>
        )}
        <div className="rv-clock" role="timer">{C.clockText(shown)}</div>
        {note && <div className="rv-note">{note}</div>}
        {!open && (
          <div className="rv-line" aria-hidden="true">
            <span style={{ width: `${C.progress(clock, now) * 100}%` }} />
          </div>
        )}
        {run.panel && <div className="rv-panel">{run.panel}</div>}
      </div>
      <div className="rv-buttons">
        <button className="rv-round pause" onClick={togglePause} aria-label={paused ? 'Resume' : 'Pause'} title={paused ? 'Resume' : 'Pause'}>
          <Icon name={paused ? 'playsolid' : 'bars2'} size={30} stroke={2.2} />
        </button>
        <button className="rv-round stop" onClick={askEnd} aria-label="End" title="End">
          <Icon name="xmark" size={30} stroke={2.6} />
        </button>
      </div>
      {up && (
        <>
          <div className="scrim" onClick={() => finish(true)} />
          <div className="sheet rv-done" role="dialog" aria-modal="true">
            <div className="grabber" />
            <div className="sheet-body">
              <span className="rv-badge"><Icon name="check" size={30} stroke={2.8} /></span>
              <div className="rv-done-title">{run.title} done</div>
              <div className="rv-done-text">
                {`${whole(run.minutes)} minute${whole(run.minutes) !== 1 ? 's' : ''}. Saved to your sessions.`}
                {run.breathing ? ' Your stress change shows in a moment.' : ''}
              </div>
              <button className="btn primary wide rv-done-btn" onClick={() => finish(true)}>Done</button>
            </div>
          </div>
        </>
      )}
    </div>
  );
}
