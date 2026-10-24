// The app's own drop-down: it opens inside the phone screen, picks with a tap or the keys, and Esc closes only it.

import { act, useState } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { Picker } from './Picker';

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

let screen: HTMLDivElement;
let root: Root;
let picked: number[] = [];

function Hours() {
  const [h, setH] = useState(9);
  return (
    <Picker
      label="Hour"
      value={h}
      options={Array.from({ length: 24 }, (_, i) => ({ value: i, label: String(i).padStart(2, '0') }))}
      onChange={(v) => { picked.push(v); setH(v); }}
    />
  );
}

const button = () => screen.querySelector('button.picker') as HTMLButtonElement;
const list = () => screen.querySelector('[role="listbox"]') as HTMLElement | null;
const key = async (el: Element, k: string) => {
  await act(async () => { el.dispatchEvent(new KeyboardEvent('keydown', { key: k, bubbles: true })); });
};

beforeEach(() => {
  picked = [];
  screen = document.createElement('div');
  screen.className = 'screen';
  document.body.appendChild(screen);
  root = createRoot(screen);
});

afterEach(() => {
  act(() => root.unmount());
  screen.remove();
});

describe('Picker', () => {
  it('opens its list inside the phone screen and a tap picks', async () => {
    await act(async () => root.render(<Hours />));
    expect(button().textContent).toBe('09');
    expect(list()).toBeNull();
    await act(async () => button().click());
    expect(list()?.parentElement).toBe(screen); // not the browser's list, and not outside the phone
    expect(list()?.querySelector('[aria-selected="true"]')?.textContent).toBe('09');
    await act(async () => (list()!.children[14] as HTMLElement).click());
    expect(picked).toEqual([14]);
    expect(list()).toBeNull();
    expect(button().textContent).toBe('14');
  });

  it('steps with the arrow keys, jumps by typing, and Esc closes only the list', async () => {
    let escapes = 0;
    const onEsc = (e: KeyboardEvent) => { if (e.key === 'Escape') escapes += 1; };
    window.addEventListener('keydown', onEsc);
    await act(async () => root.render(<Hours />));
    await act(async () => button().click());
    await key(list()!, 'ArrowDown');
    await key(list()!, 'ArrowDown');
    await key(list()!, 'Enter');
    expect(picked).toEqual([11]);
    await act(async () => button().click());
    await key(list()!, '2');
    await key(list()!, '3');
    await key(list()!, 'Enter');
    expect(picked).toEqual([11, 23]);
    await act(async () => button().click());
    await key(list()!, 'Escape');
    expect(list()).toBeNull();
    expect(escapes).toBe(0); // the phone's Esc (close the sheet) never saw it
    window.removeEventListener('keydown', onEsc);
  });
});
