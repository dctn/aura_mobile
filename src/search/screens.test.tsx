// Smoke tests: each screen draws from the bundled sample and its taps change the visitor's local layer.

import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { AuraProvider, useAura } from '../app/store';
import { NavProvider, useNav } from '../app/nav';
import { Search } from '../screens/Search';
import { Metric } from '../screens/Metric';
import { Logging } from '../screens/Logging';
import { Settings } from '../screens/Settings';
import { Inbox } from '../screens/Inbox';
import { loadLocal } from '../lib/local';

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

const text = () => host.textContent ?? '';
const button = (label: string) => [...host.querySelectorAll('button')].find((b) => b.textContent?.trim() === label || b.getAttribute('aria-label') === label);
async function click(el: Element | undefined) {
  expect(el).toBeTruthy();
  await act(async () => { (el as HTMLElement).click(); });
}

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

describe('screens', () => {
  it('Search lists the categories with values and filters', async () => {
    await show(<Search />);
    expect(text()).toContain('Health');
    expect(text()).toMatch(/Sleep\d+\.\d h/); // the newest sleep in the sample
    const input = host.querySelector('input') as HTMLInputElement;
    await act(async () => {
      const set = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!;
      set.call(input, 'vo2');
      input.dispatchEvent(new Event('input', { bubbles: true }));
    });
    expect(text()).toContain('VO2max');
    expect(text()).not.toContain('Logging');
  });

  it('Metric shows the hero, the average and pins', async () => {
    await show(<Metric field="totalSleepMin" />);
    expect(text()).toContain('Sleep');
    expect(text()).toContain('Avg');
    expect(text()).toContain('Pin to Home');
    const sw = host.querySelector('[role="switch"]') as HTMLElement;
    expect(sw.getAttribute('aria-checked')).toBe('true'); // a default pin
    await click(sw);
    expect(loadLocal().pins).not.toContain('totalSleepMin');
  });

  it('Logging adds water, a mood and a strength log', async () => {
    await show(<Logging />);
    expect(text()).toContain('of 2,000 ml');
    const ml = () => Number(host.querySelector('.bignum .n')?.textContent?.replace(/,/g, ''));
    const before = ml(); // the sample may already hold today's water
    await click(button('+250 ml'));
    await click(button('+500 ml'));
    expect(ml()).toBe(before + 750);
    await click(button('4 of 5'));
    expect(text()).toContain('Good');
    await click(button('Log'));
    const local = loadLocal();
    expect(local.lists.logs?.added.map((r) => r.type)).toEqual(['water', 'water', 'strength']);
    expect(local.lists.checkins?.added[0]).toMatchObject({ kind: 'mood', mood: 4 });
    expect(String(local.lists.logs?.added[0].takenAt)).toMatch(/^2026-10-24T\d\d:\d\d:\d\d\+05:30$/);
    expect(toasts).toContain('Logged 30 min of strength');
  });

  it('Settings shows the profile without an email and saves a goal', async () => {
    await show(<Settings />);
    expect(text()).toContain('Asha Rao');
    expect(text()).toContain('Demo account');
    expect(text()).toContain('1 missing: sex');
    expect(text()).not.toMatch(/@/);
    await click([...host.querySelectorAll('button.row')].find((b) => b.textContent?.startsWith('Water')));
    await click(button('More'));
    await click(button('Save'));
    expect((loadLocal().me.healthGoals as { waterGoalMl: number }).waterGoalMl).toBe(2250);
    expect(text()).toContain('2,250 ml');
  });

  it('Inbox: a tap asks, then marks one read, snoozes one or dismisses one', async () => {
    await show(<Inbox />);
    const before = Number(/(\d+) unread/.exec(text())?.[1]);
    expect(before).toBeGreaterThan(0);
    expect(host.querySelectorAll('button.sx-note').length).toBeLessThanOrEqual(10);
    await click(host.querySelector('button.sx-note.unread') ?? undefined);
    await click(button('Mark as read'));
    expect(text()).toContain(`${before - 1} unread`);
    expect(Object.values(loadLocal().lists.inbox?.changed ?? {})[0]).toHaveProperty('readAt');
    await click(host.querySelector('button.sx-note') ?? undefined);
    await click(button('Dismiss'));
    await click(host.querySelector('button.sx-note') ?? undefined);
    await click(button('Snooze 15 min'));
    const changed = Object.values(loadLocal().lists.inbox?.changed ?? {});
    expect(changed.some((c) => c.status === 'dismissed')).toBe(true);
    expect(changed.some((c) => typeof c.snoozedUntil === 'string')).toBe(true);
  });
});
