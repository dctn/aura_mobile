// A rendered run through Recovery: a mode tile and an alert's verb open the run view, pause holds the clock, End asks,
// the time running out shows the done sheet, and a session row is left. Only this port's screens are mounted.

import { StrictMode, act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AuraProvider, useAura } from '../app/store';
import { NavProvider, useNav } from '../app/nav';
import { Recovery } from '../screens/Recovery';
import { findMode } from '../lib/modes';
import { Countdown, RunView } from './RunView';

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
// jsdom has no element scrolling (kit's Page scrolls to the top when it opens)
Element.prototype.scrollTo ??= function scrollTo() {};

function Harness() {
  const nav = useNav();
  const aura = useAura();
  return (
    <div className="screen">
      <Recovery />
      {nav.sheet && <div data-testid="sheet">{nav.sheet.render(nav.closeSheet)}</div>}
      {nav.alert && (
        <div data-testid="alert">
          <b>{nav.alert.title}</b>
          {(nav.alert.buttons ?? []).map((b) => (
            <button key={b.label} onClick={() => { nav.closeAlert(); b.onPress?.(); }}>{b.label}</button>
          ))}
        </div>
      )}
      <button data-testid="mode" onClick={() => nav.startRun({ title: 'Walk', minutes: 20, accent: 'fitness' }, true)}>mode</button>
      {nav.counting && <Countdown />}
      {nav.run && <RunView run={nav.run} />}
      <output data-testid="sessions">{JSON.stringify(aura.data.sessions)}</output>
      <output data-testid="open">{aura.data.alerts.filter((a) => !a.done).length}</output>
    </div>
  );
}

let root: Root;
let host: HTMLDivElement;

const q = (sel: string) => host.querySelector(sel);
const byText = (selector: string, words: string) =>
  [...host.querySelectorAll<HTMLElement>(selector)].find((el) => el.textContent?.trim() === words) ?? null;
const click = async (el: Element | null) => {
  expect(el).not.toBeNull();
  await act(async () => (el as HTMLElement).click());
};
/** Starts a mode from Recovery wherever it shows: its Modes tile, or the Start on its Often used pill (a mode in Often
 *  used isn't repeated in the tiles, and which of the two it is depends on the sample's history). */
async function startFromRecovery(id: string) {
  const mode = findMode(id)!;
  const tile = byText('.tile .tname', mode.word)?.closest('button');
  const pill = byText('.pill .ptitle', mode.name)?.closest('.pill');
  await click(tile ?? pill?.querySelector('.btn.go') ?? null);
}
const tick = async (ms: number) => act(async () => { vi.advanceTimersByTime(ms); });

beforeEach(async () => {
  localStorage.clear();
  host = document.createElement('div');
  document.body.appendChild(host);
  root = createRoot(host);
  await act(async () => {
    root.render(<StrictMode><AuraProvider><NavProvider><Harness /></NavProvider></AuraProvider></StrictMode>);
  });
  // the bundled sample loads by a dynamic import
  for (let i = 0; i < 50 && !host.querySelector('[data-feature] .btn.go'); i++) await act(async () => { await new Promise((r) => setTimeout(r, 10)); });
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval', 'Date'] });
});

afterEach(() => {
  act(() => root.unmount());
  host.remove();
  vi.useRealTimers();
});

describe('the run view', () => {
  it('runs a mode, pauses, and records a stopped session', async () => {
    // a default mode (Coherent, Nap, Walk, Wind down): an Often used pill or a Modes tile
    await startFromRecovery('urban-walk');
    expect(q('.runview')).not.toBeNull();
    expect(q('.rv-kicker')?.textContent).toBe('MOVE · 20 MIN');
    expect(q('.rv-clock')?.textContent).toBe('20:00');
    await tick(60_000);
    expect(q('.rv-clock')?.textContent).toBe('19:00');
    await click(q('.rv-round.pause'));
    await tick(60_000);
    expect(q('.rv-clock')?.textContent).toBe('19:00');
    await click(q('.rv-round.pause'));
    await tick(30_000);
    expect(q('.rv-clock')?.textContent).toBe('18:30');
    await click(q('.rv-round.stop'));
    expect(q('[data-testid=alert] b')?.textContent).toBe('End now? 19 minutes left.');
    await click(byText('[data-testid=alert] button', 'End session'));
    expect(q('.runview')).toBeNull();
    const sessions = JSON.parse(q('[data-testid=sessions]')?.textContent ?? '[]');
    expect(sessions).toHaveLength(1);
    expect(sessions[0]).toMatchObject({ title: 'Walk round the block', kind: 'walk', mode: 'timer', status: 'stopped', plannedMinutes: 20, actualMinutes: 2, device: 'phone' });
  });

  it("hides into the mini bar and comes back", async () => {
    await startFromRecovery('nap-10');
    await click(q('.rv-hide'));
    expect(q('.runview')).toBeNull();
    expect(q('.rv-mini-title')?.textContent).toBe('Ten-minute nap');
    await tick(1_000);
    expect(q('.rv-mini-sub')?.textContent).toBe('09:59 left');
    await click(q('.rv-mini'));
    expect(q('.runview')).not.toBeNull();
  });

  it("does an alert's nap: night look, the done sheet, then the alert is done", async () => {
    const before = Number(q('[data-testid=open]')?.textContent);
    // the verb on the row of DX-04 (short-night nap), which acts on its open alert
    await click(q('[data-feature="DX-04"] .btn.go'));
    expect(q('.runview.night')).not.toBeNull();
    await tick(10 * 60_000 + 500);
    expect(q('.rv-done-title')?.textContent).toBe('Nap done');
    expect(Number(q('[data-testid=open]')?.textContent)).toBe(before);
    await click(byText('.rv-done button', 'Done'));
    expect(q('.runview')).toBeNull();
    expect(Number(q('[data-testid=open]')?.textContent)).toBe(before - 1);
    const sessions = JSON.parse(q('[data-testid=sessions]')?.textContent ?? '[]');
    expect(sessions[0]).toMatchObject({ title: 'Nap', status: 'completed', actualMinutes: 10 });
  });

  it('drops the run when the 3-2-1 is cancelled', async () => {
    await click(q('[data-testid=mode]'));
    expect(q('.rv-count')).not.toBeNull();
    await click(byText('.rv-count button', 'Cancel'));
    await tick(3_100);
    expect(q('.runview')).toBeNull();
    expect(q('[data-testid=sessions]')?.textContent).toBe('[]');
  });

  it('starts the run after the 3-2-1', async () => {
    await click(q('[data-testid=mode]'));
    await tick(3_100);
    expect(q('.rv-title')?.textContent).toBe('Walk');
  });
});
