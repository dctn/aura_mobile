// Recovery draws every feature by group from the bundled sample: its verb works with or without an open alert, its
// logging cards add rows to the visitor's local layer, and the presets include the website's Aerobic session.

import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { AuraProvider, useAura } from '../app/store';
import { NavProvider, useNav } from '../app/nav';
import { GROUP_ORDER, Recovery, featureGroups } from '../screens/Recovery';
import { EMPTY, loadLocal, saveLocal } from '../lib/local';
import { FEATURES, PRESETS } from '../lib/catalog';
import { entriesFor } from './logCards';

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
// jsdom has no element scrolling; the kit Page scrolls to the top on mount
Element.prototype.scrollTo ??= function scrollTo() {};

let host: HTMLDivElement;
let root: Root;
let toasts: string[] = [];

function Toasts() {
  const nav = useNav();
  if (nav.toast && toasts.at(-1) !== nav.toast) toasts.push(nav.toast);
  return (
    <>
      {nav.sheet && <div data-sheet>{nav.sheet.render(nav.closeSheet)}</div>}
      {nav.alert && (
        <div data-alert>
          <p>{nav.alert.body}</p>
          {(nav.alert.buttons ?? []).map((b) => (
            <button key={b.label} onClick={() => { nav.closeAlert(); b.onPress?.(); }}>{b.label}</button>
          ))}
        </div>
      )}
    </>
  );
}

function Ready({ children }: { children: React.ReactNode }) {
  const aura = useAura();
  return aura.status.loading ? null : <>{children}</>;
}

async function show(node: React.ReactNode) {
  await act(async () => {
    root.render(
      <AuraProvider>
        <NavProvider>
          <Ready>{node}</Ready>
          <Toasts />
        </NavProvider>
      </AuraProvider>,
    );
  });
  // the sample loads with a dynamic import
  for (let i = 0; i < 100 && !host.textContent; i++) {
    await act(async () => { await new Promise((r) => setTimeout(r, 20)); });
  }
}

const row = (id: string) => host.querySelector(`[data-feature="${id}"]`) as HTMLElement | null;
/** A feature's row with its logging card and week under it. */
const block = (id: string) => row(id)?.closest('.rc-fblock') as HTMLElement | null;
const buttonIn = (el: Element | null | undefined, label: string) =>
  [...(el?.querySelectorAll('button') ?? [])].find((b) => b.textContent?.trim() === label || b.getAttribute('aria-label') === label);
async function click(el: Element | null | undefined) {
  expect(el).toBeTruthy();
  await act(async () => { (el as HTMLElement).click(); });
}
async function press(el: Element | null | undefined, key: string) {
  expect(el).toBeTruthy();
  await act(async () => { el!.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true })); });
}
const sheet = () => host.querySelector('[data-sheet]');
const feature = (id: string) => FEATURES.find((f) => f.id === id)!;

beforeEach(() => {
  localStorage.clear();
  toasts = [];
  host = document.createElement('div');
  document.body.appendChild(host);
  root = createRoot(host);
});

afterEach(() => {
  act(() => root.unmount());
  host.remove();
});

describe('featureGroups', () => {
  it('puts the 16 features under six headings and leaves the account and band to Home', () => {
    const groups = featureGroups();
    expect(groups.map((g) => g.title)).toEqual(GROUP_ORDER);
    expect(Object.fromEntries(groups.map((g) => [g.title, g.features.map((f) => f.id)]))).toEqual({
      Sleep: ['DX-01', 'DX-02', 'DX-03', 'DX-04', 'PB-01'],
      'Recovery & stress': ['DX-05', 'DX-06', 'DX-07', 'PB-02'],
      Movement: ['DX-09', 'PB-03', 'PB-04'],
      'Mind & people': ['NI-02', 'NI-03'],
      Hydration: ['NI-04'],
      'Health check': ['PB-05'],
    });
  });
});

describe('entriesFor', () => {
  const checkins = [
    { id: 'm1', kind: 'mood', mood: 3, date: '2026-10-24', takenAt: '2026-10-24T08:00:00+05:30' },
    { id: 'm2', kind: 'mood', mood: 2, date: '2026-10-10', takenAt: '2026-10-10T08:00:00+05:30' },
    { id: 'c1', kind: 'connection', connection: 4, date: '2026-10-23', takenAt: '2026-10-23T08:00:00+05:30' },
  ];
  const logs = [
    { id: 'w1', type: 'water', date: '2026-10-22', takenAt: '2026-10-22T03:30:00.000Z', data: { ml: 250 } },
    { id: 's1', type: 'strength', date: '2026-10-21', takenAt: '2026-10-21T12:00:00.000Z', data: { minutes: 30 } },
  ];

  it("keeps one kind's last 7 days", () => {
    expect(entriesFor(checkins, logs, 'mood', '2026-10-24').map((e) => e.text)).toEqual(['Mood 3/5']);
    expect(entriesFor(checkins, logs, 'connection', '2026-10-24').map((e) => e.text)).toEqual(['Connection 4/5']);
    expect(entriesFor(checkins, logs, 'water', '2026-10-24').map((e) => e.text)).toEqual(['Water 250 ml']);
    expect(entriesFor(checkins, logs, 'strength', '2026-10-24').map((e) => e.text)).toEqual(['Strength 30 min']);
    expect(entriesFor(checkins, logs, 'insomnia', '2026-10-24')).toEqual([]);
  });
});

describe('Recovery', () => {
  it('shows all 16 features grouped, with To do kept on top and the presets at the bottom', async () => {
    await show(<Recovery />);
    const ids = [...host.querySelectorAll('[data-feature]')].map((el) => el.getAttribute('data-feature'));
    expect(ids).toHaveLength(16);
    expect(ids).not.toContain('BS-01');
    expect(ids).not.toContain('BS-02');
    const sections = [...host.querySelectorAll('.section')].map((s) => s.textContent);
    expect(sections).toEqual(['To do', ...GROUP_ORDER, 'Presets']);
    // the sample has an open alert for every one of them
    expect(row('NI-04')?.textContent).toContain('To do');
    // the logged-by-hand features carry their card
    expect(block('NI-04')?.textContent).toContain('of 2,000 ml');
    expect(block('PB-04')?.textContent).toContain('Strength');
    expect(block('DX-03')?.textContent).toContain('Sleep check');
    expect(block('DX-01')?.querySelector('.rc-log')).toBeNull();
  });

  it('runs the verb on a stand-in when the feature has no open alert, without writing alert rows', async () => {
    saveLocal({ ...EMPTY, alertOff: ['NI-04'] }); // switched off: no open alert for it
    await show(<Recovery />);
    expect(row('NI-04')?.textContent).not.toContain('To do');
    await click(buttonIn(row('NI-04'), 'Drink'));
    const local = loadLocal();
    expect(local.lists.logs?.added).toHaveLength(1);
    expect(local.lists.logs?.added[0]).toMatchObject({ type: 'water', data: { ml: 250 } });
    expect(local.lists.alerts).toBeUndefined();
    expect(toasts).toContain('Logged 250 ml of water');
  });

  it('marks the open alert done when the verb runs on it', async () => {
    await show(<Recovery />);
    await click(buttonIn(row('NI-04'), 'Drink'));
    const changed = Object.entries(loadLocal().lists.alerts?.changed ?? {});
    expect(changed).toHaveLength(1);
    expect(changed[0][0]).toMatch(/NI-04/);
    expect(changed[0][1]).toEqual({ done: true });
  });

  it('keeps To do compact: pills only, the metric cards stay in the rows below', async () => {
    await show(<Recovery />);
    const todo = host.querySelector('.rc-todo-list');
    expect(todo?.querySelectorAll('.pill').length).toBeGreaterThan(0);
    expect(todo?.querySelector('.mcard')).toBeNull();
  });

  it("a mood or connection from its card and any water finish that feature's open alert", async () => {
    await show(<Recovery />);
    // the sample has an open alert for all 16; the header counts them (To do itself shows at most 12)
    expect(host.textContent).toContain('16 things to do');
    for (const id of ['NI-02', 'NI-03', 'NI-04']) expect(row(id)?.textContent).toContain('To do');
    await click(buttonIn(block('NI-02'), '4 of 5'));
    expect(row('NI-02')?.textContent).not.toContain('To do');
    await click(buttonIn(block('NI-03'), '3 of 5'));
    expect(row('NI-03')?.textContent).not.toContain('To do');
    await click(buttonIn(block('NI-04'), '+500 ml'));
    expect(row('NI-04')?.textContent).not.toContain('To do');
    expect(host.textContent).toContain('13 things to do');
    const done = Object.entries(loadLocal().lists.alerts?.changed ?? {});
    expect(done.map(([id]) => id.match(/NI-0\d/)?.[0]).sort()).toEqual(['NI-02', 'NI-03', 'NI-04']);
    expect(done.every(([, c]) => c.done === true)).toBe(true);
  });

  it('a tap on the row opens its sheet with the 14-day metric card', async () => {
    await show(<Recovery />);
    await click(row('DX-04'));
    const sheet = host.querySelector('[data-sheet]');
    expect(sheet?.textContent).toContain('Short-night nap');
    expect(sheet?.querySelector('.mcard')).toBeTruthy();
  });

  it('logs water and a low mood from Recovery; the low mood offers the reach-out task', async () => {
    await show(<Recovery />);
    const ml = () => Number(block('NI-04')?.querySelector('.bignum .n')?.textContent?.replace(/,/g, ''));
    const before = ml();
    await click(buttonIn(block('NI-04'), '+250 ml'));
    expect(ml()).toBe(before + 250);
    expect(block('NI-04')?.textContent).toContain('Water 250 ml');

    await click(buttonIn(block('NI-02'), '2 of 5'));
    expect(host.querySelector('[data-sheet]')?.textContent).toContain('Reach out');
    await click(buttonIn(host.querySelector('[data-sheet]'), 'OK'));
    const local = loadLocal();
    expect(local.lists.logs?.added.map((r) => r.type)).toEqual(['water']);
    expect(local.lists.checkins?.added[0]).toMatchObject({ kind: 'mood', mood: 2 });
    expect(local.lists.tasks?.added[0]).toMatchObject({ title: 'Reach out to someone' });
    expect(block('NI-02')?.textContent).toContain('Mood 2/5');

    await click(buttonIn(block('NI-03'), '4 of 5'));
    expect(loadLocal().lists.checkins?.added[1]).toMatchObject({ kind: 'connection', connection: 4 });
  });

  it("a stand-in's sheet says what to do, not the catalog's sample finding", async () => {
    saveLocal({ ...EMPTY, alertOff: ['DX-01'] });
    await show(<Recovery />);
    await click(row('DX-01'));
    expect(sheet()?.textContent).toContain(feature('DX-01').does);
    expect(sheet()?.textContent).not.toContain(feature('DX-01').shows);
  });

  it('the health check without an alert speaks of a yearly check-up, not of signs', async () => {
    saveLocal({ ...EMPTY, alertOff: ['PB-05'] });
    await show(<Recovery />);
    await click(buttonIn(row('PB-05'), 'Book'));
    const said = host.querySelector('[data-alert] p')?.textContent ?? '';
    expect(said).toBe('A yearly check-up is worth booking. Show the doctor your Trends screen.');
    expect(said).not.toContain('These signs');
  });

  it("a stand-in's plan goes on the calendar once, however often it is tapped", async () => {
    saveLocal({ ...EMPTY, alertOff: ['DX-09'] });
    await show(<Recovery />);
    await click(buttonIn(row('DX-09'), 'Add walks'));
    await click(buttonIn(row('DX-09'), 'Add walks'));
    expect(loadLocal().lists.events?.added.map((e) => e.title)).toEqual(['Walk · 30 min', 'Walk · 30 min']);
    expect(toasts).toEqual(['Added 2 to your calendar', 'Already in your calendar']);
  });

  it("a stand-in's Bedtime asks first, 30 min earlier; a real alert's moves it at once", async () => {
    saveLocal({ ...EMPTY, alertOff: ['DX-01'] });
    await show(<Recovery />);
    await click(buttonIn(row('DX-01'), 'Bedtime'));
    expect(sheet()?.textContent).toContain('Go to bed at');
    // the sample has no bedtime goal, so 23:00 less 30 min
    expect(sheet()?.querySelector('[aria-label="Hour 22"]')).toBeTruthy();
    expect(sheet()?.querySelector('[aria-label="Minute 30"]')).toBeTruthy();
    expect(loadLocal().me.healthGoals).toBeUndefined(); // nothing moved yet
    await click(buttonIn(sheet(), 'Save'));
    expect(loadLocal().me.healthGoals).toEqual({ bedtime: '22:30' });

    act(() => root.unmount());
    root = createRoot(host);
    localStorage.clear();
    await show(<Recovery />);
    await click(buttonIn(row('DX-01'), 'Bedtime')); // the sample's open alert
    expect(sheet()).toBeNull();
    expect(loadLocal().me.healthGoals).toEqual({ bedtime: '22:30' });
  });

  it('Enter or Space on a row opens it; a key on its verb only runs the verb', async () => {
    await show(<Recovery />);
    await press(buttonIn(row('DX-04'), 'Nap'), 'Enter');
    expect(sheet()).toBeNull();
    await press(row('DX-04'), ' ');
    expect(sheet()?.textContent).toContain('Short-night nap');
    act(() => { (buttonIn(sheet(), 'Done') as HTMLElement).click(); });
    expect(sheet()).toBeNull();
    // the To do pills (ActionPill) work the same way
    const pill = host.querySelector('.pill') as HTMLElement;
    await press(pill.querySelector('button.btn.go'), 'Enter');
    expect(sheet()).toBeNull();
    await press(pill, 'Enter');
    expect(sheet()).toBeTruthy();
  });

  it("Strength's Log adds a strength log taken in the person's zone", async () => {
    await show(<Recovery />);
    await click(buttonIn(block('PB-04'), 'Log'));
    const added = loadLocal().lists.logs?.added ?? [];
    expect(added).toHaveLength(1);
    expect(added[0]).toMatchObject({ type: 'strength', data: { minutes: 30 } });
    // the sample's zone is Asia/Kolkata: the time carries its offset, not Z
    expect(String(added[0].takenAt)).toMatch(/^2026-10-24T\d\d:\d\d:\d\d\+05:30$/);
    expect(toasts).toContain('Logged 30 min of strength');
  });

  it('lists the Aerobic session preset', async () => {
    expect(PRESETS.map((p) => p.title)).toContain('Aerobic session');
    expect(PRESETS.find((p) => p.title === 'Aerobic session')).toMatchObject({ word: 'Aerobic', minutes: 20, kind: 'walk', accent: 'fitness' });
    await show(<Recovery />);
    const tile = [...host.querySelectorAll('.tile')].find((t) => t.textContent?.includes('Aerobic'));
    expect(tile?.textContent).toContain('20 min');
  });
});
