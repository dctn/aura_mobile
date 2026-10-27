// The intro's try-links: each one changes the phone and, when the app is off screen (at phone width the intro sits below
// it), brings the app's top into view; smoothly, unless the visitor asked for less motion.

import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { NavProvider, useNav } from '../app/nav';
import { Hero } from './Story';

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

let host: HTMLDivElement;
let root: Root;
let app: HTMLElement;
/** Where the app's top is, as the browser would measure it. */
let appTop = 0;
let reduced = false;
const scrolled = vi.fn();

function Where() {
  const nav = useNav();
  return <output data-where>{`${nav.tab}|${nav.stack.map((p) => p.name).join('/')}`}</output>;
}

const links = () => [...host.querySelectorAll<HTMLButtonElement>('.trylink')];
const where = () => host.querySelector('[data-where]')?.textContent;

beforeEach(async () => {
  appTop = 0;
  reduced = false;
  scrolled.mockClear();
  app = document.createElement('section');
  app.id = 'app';
  app.getBoundingClientRect = () => ({ top: appTop }) as DOMRect;
  app.scrollIntoView = scrolled;
  document.body.appendChild(app);
  window.matchMedia = ((query: string) => ({ matches: reduced && query.includes('reduce') })) as unknown as typeof window.matchMedia;
  host = document.createElement('div');
  document.body.appendChild(host);
  root = createRoot(host);
  await act(async () => {
    root.render(<NavProvider><Hero /><Where /></NavProvider>);
  });
});

afterEach(() => {
  act(() => root.unmount());
  host.remove();
  app.remove();
});

describe('the try-links', () => {
  it('each one changes the phone and scrolls the app into view when it is above the screen', async () => {
    expect(links()).toHaveLength(4);
    appTop = -900; // scrolled down to the intro, under the app
    const expected = ['recovery|', 'home|', 'search|metric', 'recovery|modes'];
    for (const [i, link] of links().entries()) {
      await act(async () => link.click());
      expect(where()).toBe(expected[i]);
    }
    expect(scrolled).toHaveBeenCalledTimes(4);
    expect(scrolled).toHaveBeenLastCalledWith({ behavior: 'smooth', block: 'start' });
  });

  it('jumps without the smooth scroll for reduced motion, and leaves an app already in view alone', async () => {
    reduced = true;
    appTop = -900;
    await act(async () => links()[3].click());
    expect(scrolled).toHaveBeenLastCalledWith({ behavior: 'auto', block: 'start' });

    scrolled.mockClear();
    appTop = 40; // a desktop: the app is beside the intro
    await act(async () => links()[0].click());
    expect(scrolled).not.toHaveBeenCalled();
  });
});
