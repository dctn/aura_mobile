// The result of Auto-allocate (O6): the tasks the planner placed into today's free hours, with the time and the
// reason each got, and the ones it couldn't fit, with why. Mirrors the backend's PlanView (placed / unplaced).
// Everything here was already written to this visitor's browser; this sheet just explains what happened.

import type { CSSProperties } from 'react';
import { useNav } from '../app/nav';
import { Icon, SheetFrame } from '../kit/kit';
import { clock } from '../lib/agenda';
import type { PlaceResult } from './allocate';

const tone = (token: string) => ({ ['--tone' as string]: `var(--${token})` }) as CSSProperties;

function summary(result: PlaceResult, committedSplit: number): string {
  const p = new Set(result.placed.map((x) => x.task.id)).size;
  // Split to-do tasks plus committed blocks the coach split around a recovery period — both are "split to fit".
  const split = new Set(result.placed.filter((x) => x.part).map((x) => x.task.id)).size + committedSplit;
  const u = result.unplaced.length;
  const b = result.breaks.length;
  const rest = b > 0 ? ` with ${b} recovery break${b === 1 ? '' : 's'}` : '';
  const splits = split > 0 ? ` (${split} split to fit)` : '';
  if (p === 0 && committedSplit === 0) return u === 1 ? "Couldn't find a time for it" : `Couldn't find a time for ${u}`;
  const put = p === 0 ? `Split ${committedSplit} block${committedSplit === 1 ? '' : 's'} around your recovery time${rest}` : `Found a time for ${p} task${p === 1 ? '' : 's'}${splits}${rest}`;
  return u === 0 ? put : `${put} · ${u} didn't fit`;
}

export function AllocateSheet({ close, result, committedSplit = 0, meetingsOnRecovery = 0 }: { close: () => void; result: PlaceResult; committedSplit?: number; meetingsOnRecovery?: number }) {
  const nav = useNav();
  const placed = [...result.placed].sort((a, b) => a.start - b.start);
  // How many distinct tasks were split into pieces (a split task shows as its part-1 … part-N rows).
  const split = new Set(result.placed.filter((p) => p.part).map((p) => p.task.id)).size;
  return (
    <SheetFrame title="Auto-allocate" onCancel={close} cancel="Done">
      <div className="muted cal-note">{summary(result, committedSplit)}. Times live in this browser only — drag any block to change it.</div>

      {committedSplit > 0 && (
        <div className="muted cal-note">A work block sitting on a recovery period was split around it, so the rest between heavy work stays free. Each piece shows as a work session on the day.</div>
      )}
      {meetingsOnRecovery > 0 && (
        <div className="muted cal-note warn">{meetingsOnRecovery} meeting{meetingsOnRecovery === 1 ? '' : 's'} overlap{meetingsOnRecovery === 1 ? 's' : ''} a recovery period — left as {meetingsOnRecovery === 1 ? 'it is' : 'they are'}, since a meeting can't be moved.</div>
      )}

      {placed.length > 0 && (
        <div className="list cal-alloc">
          {placed.map((p) => (
            <div key={`${p.task.id}-${p.start}`} className="row with-icon">
              <span className="cal-ico" style={tone('recovery')}><Icon name="clock" size={18} stroke={2} /></span>
              <span className="grow">
                <div className="title">{p.task.title}{p.part ? ` · part ${p.part.index}/${p.part.total}` : ''}</div>
                <div className="cal-tsub">{`${clock(p.start)}–${clock(p.end)}`} · {p.reason}</div>
              </span>
            </div>
          ))}
        </div>
      )}
      {split > 0 && (
        <div className="muted cal-note">A task too big for one gap is split into pieces that fit around your recovery time, so it still finishes before it is due. Split tasks stay in your to-do list — tick the task off once all its pieces are done.</div>
      )}

      {result.breaks.length > 0 && (
        <>
          <div className="cal-todohead"><span>Recovery breaks</span></div>
          <div className="list cal-alloc">
            {[...result.breaks].sort((a, b) => a.start - b.start).map((b) => (
              <div key={`${b.start}-${b.title}`} className="row with-icon">
                <span className="cal-ico" style={tone('recovery')}><Icon name="heart" size={18} stroke={2} /></span>
                <span className="grow">
                  <div className="title">{b.title}</div>
                  <div className="cal-tsub">{`${clock(b.start)}–${clock(b.end)}`} · kept free after heavy work — tap it on the day when you've done it</div>
                </span>
              </div>
            ))}
          </div>
          <div className="muted cal-note">No task is placed over these — they're your time to recover. Do them and your capacity score rises (about +{result.restored}), so the next plan fits more in.</div>
        </>
      )}

      {result.unplaced.length > 0 && (
        <>
          <div className="cal-todohead"><span>Not scheduled</span></div>
          <div className="list cal-alloc">
            {result.unplaced.map((u) => (
              <div key={u.taskId} className="row with-icon">
                <span className="cal-ico" style={tone('stress')}><Icon name="info" size={18} stroke={2} /></span>
                <span className="grow">
                  <div className="title">{u.title}</div>
                  <div className="cal-tsub warn">{u.detail}</div>
                </span>
              </div>
            ))}
          </div>
        </>
      )}

      <button className="btn wide cal-allocbtn" onClick={() => { close(); nav.push({ name: 'zones' }); }}>See today's zones</button>
    </SheetFrame>
  );
}
