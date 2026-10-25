// Focus → Quick check: a one-minute attention and memory test in a sheet. Three reaction taps (the dot turns green
// after a random 1-3 s; a tap before that is "too soon" and the try starts again), then a digit span: a row of digits
// shows for a moment and is typed back, one digit longer each time until a miss. Saves the average reaction time and
// the longest row typed back right.

import { useEffect, useRef, useState } from 'react';
import { SheetFrame } from '../kit/kit';
import './logbook.css';

export const TRIES = 3;
export const SPAN_FROM = 3;
export const SPAN_TO = 9;
const WAIT_MIN = 1000;
const WAIT_MAX = 3000;
/** How long a row of digits shows: a little per digit. */
const showMs = (length: number) => 600 + length * 450;

/** A row of `length` digits, no digit twice in a row (easier to read out). */
export function digitRow(length: number, rand: () => number = Math.random): string {
  let out = '';
  while (out.length < length) {
    const d = String(Math.floor(rand() * 10));
    if (d !== out.at(-1)) out += d;
  }
  return out;
}

export const averageMs = (times: number[]) => (times.length ? Math.round(times.reduce((a, b) => a + b, 0) / times.length) : 0);

type Phase = 'intro' | 'wait' | 'go' | 'early' | 'show' | 'recall' | 'done';

export function QuickCheck({ onDone, close }: { onDone: (result: { reactionMs: number; span: number }) => void; close: () => void }) {
  const [phase, setPhase] = useState<Phase>('intro');
  const [times, setTimes] = useState<number[]>([]);
  const [row, setRow] = useState('');
  const [typed, setTyped] = useState('');
  const [span, setSpan] = useState(0);
  const timer = useRef(0);
  const goAt = useRef(0);

  useEffect(() => () => window.clearTimeout(timer.current), []);

  const wait = () => {
    setPhase('wait');
    window.clearTimeout(timer.current);
    timer.current = window.setTimeout(() => {
      goAt.current = performance.now();
      setPhase('go');
    }, WAIT_MIN + Math.random() * (WAIT_MAX - WAIT_MIN));
  };

  const show = (length: number) => {
    setRow(digitRow(length));
    setTyped('');
    setPhase('show');
    window.clearTimeout(timer.current);
    timer.current = window.setTimeout(() => setPhase('recall'), showMs(length));
  };

  const tap = () => {
    if (phase === 'intro' || phase === 'early') wait();
    else if (phase === 'wait') {
      window.clearTimeout(timer.current);
      setPhase('early');
    } else if (phase === 'go') {
      const next = [...times, Math.round(performance.now() - goAt.current)];
      setTimes(next);
      if (next.length < TRIES) wait();
      else show(SPAN_FROM);
    }
  };

  const check = () => {
    if (typed.trim() === row) {
      setSpan(row.length);
      if (row.length < SPAN_TO) show(row.length + 1);
      else setPhase('done');
    } else setPhase('done');
  };

  const reaction = averageMs(times);
  const pad = {
    intro: ['Tap to start', 'Tap the dot the moment it turns green.'],
    wait: ['Wait for green…', times.length ? `${times.at(-1)} ms` : `Try ${times.length + 1} of ${TRIES}`],
    go: ['Tap!', ''],
    early: ['Too soon', 'Tap to try again.'],
  } as Record<Phase, [string, string]>;

  return (
    <SheetFrame title="Quick check" onCancel={close} action="Save" actionDisabled={phase !== 'done'} onAction={() => { onDone({ reactionMs: reaction, span }); close(); }}>
      {(phase === 'intro' || phase === 'wait' || phase === 'go' || phase === 'early') && (
        <>
          <div className="sx-kicker">REACTION · {Math.min(times.length + 1, TRIES)} OF {TRIES}</div>
          <button className={`lb-pad ${phase}`} onPointerDown={tap} onKeyDown={(e) => (e.key === 'Enter' || e.key === ' ') && tap()} aria-label={pad[phase][0]}>
            <span className="dot" />
            <span className="big">{pad[phase][0]}</span>
            <span className="muted">{pad[phase][1]}</span>
          </button>
        </>
      )}
      {phase === 'show' && (
        <>
          <div className="sx-kicker">MEMORY · {row.length} DIGITS</div>
          <div className="lb-digits" aria-live="polite">{row}</div>
          <div className="muted lb-centre">Remember these.</div>
        </>
      )}
      {phase === 'recall' && (
        <>
          <div className="sx-kicker">MEMORY · {row.length} DIGITS</div>
          <input
            className="field lb-recall"
            autoFocus
            inputMode="numeric"
            aria-label="The digits you saw"
            placeholder="Type the digits"
            maxLength={SPAN_TO}
            value={typed}
            onChange={(e) => setTyped(e.target.value.replace(/\D/g, ''))}
            onKeyDown={(e) => e.key === 'Enter' && typed && check()}
          />
          <button className="btn go lb-save" disabled={!typed} onClick={check}>Check</button>
        </>
      )}
      {phase === 'done' && (
        <div className="list">
          <div className="row"><span className="grow"><div className="title">Reaction time</div><div className="sub">Average of {TRIES} taps</div></span><span className="value">{reaction} ms</span></div>
          <div className="row"><span className="grow"><div className="title">Memory span</div><div className="sub">Longest row typed back</div></span><span className="value">{span} digits</span></div>
        </div>
      )}
    </SheetFrame>
  );
}
