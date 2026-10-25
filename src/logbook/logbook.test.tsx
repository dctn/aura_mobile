// A category page draws from the bundled sample; its forms add log rows to the visitor's local layer and the computed
// values above them follow.

import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { AuraProvider, useAura } from '../app/store';
import { NavProvider, useNav } from '../app/nav';
import { loadLocal } from '../lib/local';
import { LogbookPage } from './LogbookPage';

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
// jsdom has no element scrolling; the kit Page scrolls to the top on mount
Element.prototype.scrollTo ??= function scrollTo() {};

let host: HTMLDivElement;
let root: Root;
let toasts: string[] = [];

function Layers() {
  const nav = useNav();
  if (nav.toast && toasts.at(-1) !== nav.toast) toasts.push(nav.toast);
  return nav.sheet ? <div data-sheet>{nav.sheet.render(nav.closeSheet)}</div> : null;
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
          <Layers />
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
const button = (label: string, within: ParentNode = host) =>
  [...within.querySelectorAll('button')].find((b) => b.textContent?.trim() === label || b.getAttribute('aria-label') === label);
const rowText = (title: string) => [...host.querySelectorAll('.row')].find((r) => r.querySelector('.title')?.textContent === title)?.textContent ?? '';
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

describe('logbook page', () => {
  it('saves a face-scale entry with a toast and lists it', async () => {
    await show(<LogbookPage category="mood" />);
    expect(text()).toContain('Mood & energy');
    expect(rowText('Your patterns')).toContain('From the desktop app');
    expect(button('Save')?.hasAttribute('disabled')).toBe(true); // nothing picked yet
    await click(button('4 of 5')); // the first card: Energy
    await click(button('Save'));
    const added = loadLocal().lists.logs?.added ?? [];
    expect(added).toHaveLength(1);
    expect(added[0]).toMatchObject({ type: 'energy', date: '2026-10-24', data: { value: 4 } });
    expect(String(added[0].takenAt)).toMatch(/^2026-10-24T\d\d:\d\d:\d\d\+05:30$/);
    expect(toasts).toContain('Saved: Energy 4/5');
    expect(text()).toContain('Energy 4/5');
  });

  it('adds a meal from its sheet and the totals follow', async () => {
    await show(<LogbookPage category="nutrition" />);
    expect(rowText('Calories eaten')).toContain('None yet');
    expect(rowText('Calorie goal')).toContain('0% of 2,000');
    await click(button('Add meal'));
    const sheet = host.querySelector('[data-sheet]') as HTMLElement;
    expect(sheet.textContent).toContain('Calories');
    await click(button('More', sheet)); // 400 -> 425 kcal
    await click(button('Save', sheet));
    expect(host.querySelector('[data-sheet]')).toBeNull();
    expect(loadLocal().lists.logs?.added[0]).toMatchObject({ type: 'meal', data: { kcal: 425, protein: 20, photo: false } });
    expect(rowText('Calories eaten')).toContain('425 kcal');
    expect(text()).toContain('1 today · 425 kcal');
  });

  it('logs a number from the number sheet', async () => {
    await show(<LogbookPage category="body" />);
    expect(rowText('BMI')).toContain('22.4 · healthy'); // the sample profile: 61 kg, 165 cm
    await click(host.querySelector('.lb-card .btn.go') ?? undefined); // Body weight's Log
    await click(button('Save', host.querySelector('[data-sheet]') as HTMLElement));
    expect(loadLocal().lists.logs?.added[0]).toMatchObject({ type: 'weight', data: { value: 65 } });
    expect(rowText('BMI')).toContain('23.9 · healthy');
  });
});
