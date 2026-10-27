// Recovery draws Often used, every feature by group and four modes from the bundled sample: a verb works with or without
// an open alert, the logging cards add rows to the visitor's local layer, and what is used rises to the top. The Modes
// page (Show more) lists every mode by shelf, and a mode's setup page starts it. Uses are noted and scored on the demo's
// own clock (the sample's frozen day), never the real date, so these pass on any day.

import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { AuraProvider, useAura, type Aura } from '../app/store';
import { NavProvider, useNav, type Nav } from '../app/nav';
import { Phone } from '../app/Phone';
import { GROUP_ORDER, Recovery, featureGroups } from '../screens/Recovery';
import { EMPTY, loadLocal, saveLocal } from '../lib/local';
import { FEATURES } from '../lib/catalog';
import { startMode } from '../lib/actions';
import { DEFAULT_MODES, SHELVES, findMode, modesOn } from '../lib/modes';
import { openAlerts } from '../screens/shared';
import { entriesFor } from './logCards';
import { ModeSetup, ModesPage, modeVerb } from './ModesPage';
import { noteUse, oftenUsed, orderGroups, scoresFor, topModes, type UseKey } from './usage';

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
// jsdom has no element scrolling; the kit Page scrolls to the top on mount
Element.prototype.scrollTo ??= function scrollTo() {};

let host: HTMLDivElement;
let root: Root;
let toasts: string[] = [];
/** The app state and the navigation as of the last render. */
let aura: Aura;
let nav: Nav;

function Toasts() {
  nav = useNav();
  aura = useAura();
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
/** The section headings, without a trailing link. */
const sections = () => [...host.querySelectorAll('.section')].map((s) => s.firstElementChild?.textContent);
/** Often used's items, as usage keys. */
const often = () => [...host.querySelectorAll('.rc-often [data-use]')].map((el) => el.getAttribute('data-use'));
/** The tiles' words, in order. */
const tiles = (el: Element | null | undefined = host) => [...(el?.querySelectorAll('.tile .tname') ?? [])].map((t) => t.textContent);
/** Recovery's Modes tiles (the last grid on the page). */
const modesGrid = () => [...host.querySelectorAll('.tilegrid')].at(-1);
const scoresNow = () => scoresFor(aura);
/** The modes Often used shows, by id. */
const oftenModes = () => often().filter((k) => k?.startsWith('mode:')).map((k) => k!.slice('mode:'.length));
/** Note `times` uses of `key` now, on the demo's clock. */
async function use(key: UseKey, times = 1) {
  for (let i = 0; i < times; i++) await act(async () => { noteUse(aura, key); });
}
const openIds = () => openAlerts(aura.data.alerts, aura.data.alertOff).map((a) => String(a.featureId));

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
  it('shows Often used, all 16 features grouped by use, then Modes', async () => {
    await show(<Recovery />);
    const ids = [...host.querySelectorAll('[data-feature]')].map((el) => el.getAttribute('data-feature'));
    expect(ids).toHaveLength(16);
    expect(ids).not.toContain('BS-01');
    expect(ids).not.toContain('BS-02');
    const groups = orderGroups(featureGroups(), scoresNow());
    expect(sections()).toEqual(['Often used', ...groups.map((g) => g.title), 'Modes']);
    expect(sections().slice(1, -1).sort()).toEqual([...GROUP_ORDER].sort());
    // the rows follow the same order, group by group
    expect(ids).toEqual(groups.flatMap((g) => g.features.map((f) => f.id)));
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

  it('Often used: four black pills, the most used first, with no metric cards', async () => {
    await show(<Recovery />);
    const list = host.querySelector('.rc-often');
    expect(list?.querySelectorAll('.pill')).toHaveLength(4);
    expect(list?.querySelector('.mcard')).toBeNull();
    expect(often()).toEqual(oftenUsed(scoresNow(), openIds()));
    // a feature pill is the feature: its name, its status and its verb
    const first = often().find((k) => k?.startsWith('feature:'));
    expect(first).toBeTruthy();
    const f = feature(first!.slice('feature:'.length));
    const pill = host.querySelector(`[data-use="${first}"] .pill`);
    expect(pill?.querySelector('.ptitle')?.textContent).toBe(f.name);
    expect(pill?.querySelector('.btn.go')?.textContent).toBe(f.verb);
  });

  it('with no history, Often used is the open alerts (newest first), then the default modes', () => {
    expect(oftenUsed(new Map(), ['DX-04', 'NI-04'])).toEqual(['feature:DX-04', 'feature:NI-04', `mode:${DEFAULT_MODES[0]}`, `mode:${DEFAULT_MODES[1]}`]);
    expect(oftenUsed(new Map(), [])).toEqual(DEFAULT_MODES.map((id) => `mode:${id}`));
    expect(topModes(new Map()).map((m) => m.id)).toEqual(DEFAULT_MODES);
  });

  it('a mode started three times rises into Often used and leaves the Modes tiles', async () => {
    await show(<Recovery />);
    const sauna = findMode('sauna')!;
    expect(often()).not.toContain('mode:sauna');
    expect(tiles(modesGrid())).not.toContain(sauna.word);
    for (let i = 0; i < 3; i++) {
      await act(async () => { startMode(aura, nav, sauna); });
      await act(async () => { nav.endRun(); });
    }
    expect(loadLocal().used['mode:sauna']).toHaveLength(3);
    expect(often()).toContain('mode:sauna');
    // the Modes tiles never repeat a mode Often used already shows
    expect(tiles(modesGrid())).not.toContain(sauna.word);
    // its pill reads its length and shelf; Start runs it, a tap opens its page
    const pill = host.querySelector('[data-use="mode:sauna"] .pill');
    expect(pill?.querySelector('.preason')?.textContent).toBe('20 min · Rest');
    await click(buttonIn(pill, 'Start'));
    expect(nav.run?.title).toBe(sauna.name);
    await act(async () => { nav.endRun(); });
    await click(pill);
    expect(nav.stack.at(-1)).toEqual({ name: 'mode', id: 'sauna' });
  });

  it('logging strength by hand moves Strength into Often used and Movement up', async () => {
    await show(<Recovery />);
    const before = sections().indexOf('Movement');
    for (let i = 0; i < 4; i++) await click(buttonIn(block('PB-04'), 'Log'));
    expect(loadLocal().used['feature:PB-04']).toHaveLength(4);
    expect(often()).toContain('feature:PB-04');
    expect(sections().indexOf('Movement')).toBeLessThan(before);
    // Strength leads its group
    const movement = [...host.querySelectorAll('.rc-group')].find((g) => g.querySelector('.section')?.textContent === 'Movement');
    expect(movement?.querySelector('[data-feature]')?.getAttribute('data-feature')).toBe('PB-04');
  });

  it('Modes: four tiles, none of them in Often used, that start straight away; Show more opens the Modes page', async () => {
    await show(<Recovery />);
    const shown = topModes(scoresNow(), 4, oftenModes());
    expect(tiles(modesGrid())).toEqual(shown.map((m) => m.word));
    expect(tiles(modesGrid())).toHaveLength(4);
    for (const id of oftenModes()) expect(shown.map((m) => m.id)).not.toContain(id);
    const first = shown.find((m) => m.run !== 'plan')!;
    await click([...(modesGrid()?.querySelectorAll('.tile') ?? [])].find((t) => t.querySelector('.tname')?.textContent === first.word));
    expect(nav.run?.title).toBe(first.name);
    expect(loadLocal().used[`mode:${first.id}`]).toHaveLength(1);
    await act(async () => { nav.endRun(); });
    await click(buttonIn(host, 'Show more'));
    expect(nav.stack.at(-1)).toEqual({ name: 'modes' });
  });

  it("a mood or connection from its card and any water finish that feature's open alert", async () => {
    await show(<Recovery />);
    // the sample has an open alert for all 16; the header counts them
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
    // Often used's feature pills (ActionPill) work the same way
    const pill = host.querySelector('.rc-often [data-use^="feature:"] .pill') as HTMLElement;
    await press(pill.querySelector('button.btn.go'), 'Enter');
    expect(sheet()).toBeNull();
    await press(pill, 'Enter');
    expect(sheet()).toBeTruthy();
  });

  it("a plan mode's Plan in Often used opens its page instead of adding a calendar block", async () => {
    await show(<Recovery />);
    await use('mode:day-off', 6);
    expect(often()).toContain('mode:day-off');
    const pill = host.querySelector('[data-use="mode:day-off"] .pill');
    expect(pill?.querySelector('.preason')?.textContent).toBe('Plan · Rest');
    expect(buttonIn(pill, 'Start')).toBeUndefined();
    await click(buttonIn(pill, 'Plan'));
    expect(nav.stack.at(-1)).toEqual({ name: 'mode', id: 'day-off' });
    expect(nav.run).toBeNull();
    expect(loadLocal().lists.events).toBeUndefined();
    expect(toasts).not.toContain('Added to your calendar');
  });

  it("a plan mode's tile opens its page too; Add to calendar there is the one way to plan it", async () => {
    await show(<Recovery />);
    // four features fill Often used, so the day off shows as a tile
    for (const id of ['PB-04', 'NI-04', 'NI-02', 'NI-03']) await use(`feature:${id}`, 8);
    await use('mode:day-off', 3);
    expect(oftenModes()).toEqual([]);
    const dayOff = findMode('day-off')!;
    const tile = [...(modesGrid()?.querySelectorAll('.tile') ?? [])].find((t) => t.querySelector('.tname')?.textContent === dayOff.word);
    await click(tile);
    expect(nav.stack.at(-1)).toEqual({ name: 'mode', id: 'day-off' });
    expect(nav.run).toBeNull();
    expect(loadLocal().lists.events).toBeUndefined();
    expect(modeVerb(dayOff)).toBe('Plan');
    expect(modeVerb(findMode('urban-walk')!)).toBe('Start');
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

});

describe('Modes page', () => {
  const chip = (label: string) => [...host.querySelectorAll('.rc-shelves button')].find((b) => b.textContent === label);
  const shelves = () => [...host.querySelectorAll('[data-shelf]')].map((el) => el.getAttribute('data-shelf'));

  it('lists every shelf under All, and a chip keeps one', async () => {
    await show(<ModesPage />);
    expect([...host.querySelectorAll('.rc-shelves button')].map((b) => b.textContent)).toEqual(['All', ...SHELVES]);
    expect(chip('All')?.getAttribute('aria-pressed')).toBe('true');
    expect(shelves()).toEqual(SHELVES);
    // nothing used yet: no "Used most" strip
    expect(sections()).not.toContain('Used most');
    // the old Aerobic session preset is Movement's easy aerobic session
    expect(findMode('Aerobic session')?.id).toBe('zone2');
    expect(tiles(host.querySelector('[data-shelf="Movement"]'))).toContain(findMode('zone2')!.word);

    await click(chip('Breathe'));
    expect(chip('Breathe')?.getAttribute('aria-pressed')).toBe('true');
    expect(shelves()).toEqual(['Breathe']);
    expect(tiles()).toEqual(modesOn('Breathe').map((m) => m.word));
    await click(host.querySelectorAll('.tile')[1]);
    expect(nav.stack.at(-1)).toEqual({ name: 'mode', id: modesOn('Breathe')[1].id });
  });

  it('opens on one shelf, and shows Used most once a mode was used', async () => {
    await show(<ModesPage shelf="Sleep" />);
    await use('mode:sauna'); // on the demo's clock, not the real date
    expect(shelves()).toEqual(['Sleep']);
    expect(sections()).not.toContain('Used most');
    await click(chip('All'));
    expect(sections()[0]).toBe('Used most');
    expect(tiles()[0]).toBe(findMode('sauna')!.word);
  });

  it("a breathing mode's page shows its breath and starts the pacer", async () => {
    await show(<ModeSetup id="four-seven-eight" />);
    expect(host.querySelector('.largetitle')?.textContent).toBe('4-7-8');
    expect(host.textContent).toContain('In 4 · Hold 7 · Out 8');
    expect(host.querySelector('.rc-titlefield')).toBeNull(); // a title is for Focus only
    await click(host.querySelector('.startpill'));
    expect(nav.run).toMatchObject({ title: '4-7-8', breathing: true });
    expect(loadLocal().used['mode:four-seven-eight']).toHaveLength(1);
  });

  it("a guided meditation's page counts its prompts; Focus's timer takes a title and any length", async () => {
    await show(<ModeSetup id="body-scan" />);
    expect(host.textContent).toContain('6 prompts');
    act(() => root.unmount());
    root = createRoot(host);
    await show(<ModeSetup id="timer" />);
    expect(host.querySelector('.rc-titlefield')).toBeTruthy();
    expect(host.querySelector('.stepper')).toBeTruthy();
  });

  it("a plan mode's page adds it to the calendar instead of starting a timer", async () => {
    await show(<ModeSetup id="day-off" />);
    expect(host.querySelector('.startpill')?.textContent).toBe('Add to calendar');
    await click(host.querySelector('.startpill'));
    expect(nav.run).toBeNull();
    expect(loadLocal().lists.events?.added).toHaveLength(1);
    expect(toasts).toContain('Added to your calendar');
    expect(loadLocal().used['mode:day-off']).toHaveLength(1);
  });
});

describe('the phone', () => {
  const shelves = () => [...host.querySelectorAll('[data-shelf]')].map((el) => el.getAttribute('data-shelf'));
  const chip = (label: string) => [...host.querySelectorAll('.rc-shelves button')].find((b) => b.textContent === label);
  /** A tab switch and a push in one go, as the story's links and Search's places do. */
  const go = (page: Parameters<Nav['push']>[0]) => act(async () => { nav.setTab('recovery'); nav.push(page); });

  it('a Modes page pushed in place of another starts fresh, not on the old shelf', async () => {
    await show(<Phone />);
    await go({ name: 'modes', shelf: 'Sleep' });
    expect(shelves()).toEqual(['Sleep']);
    // the story's "Recovery → Modes" link while Sleep is showing: every shelf
    await go({ name: 'modes' });
    expect(shelves()).toEqual(SHELVES);
    // a chip kept one shelf; the same link again shows them all
    await click(chip('Breathe'));
    expect(shelves()).toEqual(['Breathe']);
    await go({ name: 'modes' });
    expect(shelves()).toEqual(SHELVES);
    expect(chip('All')?.getAttribute('aria-pressed')).toBe('true');
    // and a mode page swapped for another mode shows the new one
    await go({ name: 'mode', id: 'sauna' });
    await go({ name: 'mode', id: 'four-seven-eight' });
    // the page on top comes after the tab's own page (Recovery) in the phone
    expect([...host.querySelectorAll('.largetitle')].at(-1)?.textContent).toBe('4-7-8');
  });
});
