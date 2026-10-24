// A drop-down drawn by the app instead of the browser's <select>. The browser's list ignores the site's theme (a light
// list on a dark phone) and opens outside the phone frame; this one opens inside the phone screen, below the button
// or above it when there is no room, and scrolls to the chosen value. Arrow keys, Home/End, Enter and typing the
// first characters work as in a <select>; Esc or a tap elsewhere closes it.

import { useLayoutEffect, useRef, useState, type CSSProperties, type KeyboardEvent } from 'react';
import { createPortal } from 'react-dom';

export interface Choice<T extends string | number> {
  value: T;
  label: string;
}

const GAP = 4;
const EDGE = 8;
const MAX_LIST = 280;
const TYPE_MS = 700;

export function Picker<T extends string | number>({ value, options, onChange, label, className = '', disabled = false }: {
  value: T;
  options: Choice<T>[];
  onChange: (value: T) => void;
  /** Read out for the button and the list ("Hour"). */
  label: string;
  className?: string;
  disabled?: boolean;
}) {
  const button = useRef<HTMLButtonElement>(null);
  const list = useRef<HTMLUListElement>(null);
  const typed = useRef({ text: '', at: 0 });
  const [open, setOpen] = useState<{ screen: HTMLElement; style: CSSProperties } | null>(null);
  const chosen = Math.max(0, options.findIndex((o) => o.value === value));
  const [active, setActive] = useState(chosen);

  const close = (refocus = true) => {
    setOpen(null);
    if (refocus) button.current?.focus();
  };
  const pick = (i: number) => {
    const o = options[i];
    if (o && o.value !== value) onChange(o.value);
    close();
  };

  const show = () => {
    const b = button.current;
    const screen = b?.closest<HTMLElement>('.screen') ?? document.body;
    if (!b) return;
    const s = screen.getBoundingClientRect();
    const r = b.getBoundingClientRect();
    const width = Math.max(r.width, 88);
    const left = Math.max(EDGE, Math.min(r.left - s.left, s.width - width - EDGE));
    const below = s.bottom - r.bottom - GAP - EDGE;
    const above = r.top - s.top - GAP - EDGE;
    const down = below >= Math.min(MAX_LIST, 160) || below >= above;
    const style: CSSProperties = down
      ? { left, width, top: r.bottom - s.top + GAP, maxHeight: Math.min(MAX_LIST, below) }
      : { left, width, bottom: s.bottom - r.top + GAP, maxHeight: Math.min(MAX_LIST, above) };
    setActive(chosen);
    setOpen({ screen, style });
  };

  // open at the chosen value, in the middle of the list
  useLayoutEffect(() => {
    const el = list.current;
    if (!open || !el) return;
    el.focus();
    const row = el.children[chosen] as HTMLElement | undefined;
    if (row) el.scrollTop = row.offsetTop - el.clientHeight / 2 + row.offsetHeight / 2;
  }, [open]); // eslint-disable-line react-hooks/exhaustive-deps

  // keep the active row in view while stepping with the keys
  useLayoutEffect(() => {
    const el = list.current;
    const row = el?.children[active] as HTMLElement | undefined;
    if (!el || !row) return;
    if (row.offsetTop < el.scrollTop) el.scrollTop = row.offsetTop;
    else if (row.offsetTop + row.offsetHeight > el.scrollTop + el.clientHeight) el.scrollTop = row.offsetTop + row.offsetHeight - el.clientHeight;
  }, [active]);

  // the layout under it moved: the list would float in the wrong place
  useLayoutEffect(() => {
    if (!open) return;
    const shut = () => setOpen(null);
    window.addEventListener('resize', shut);
    return () => window.removeEventListener('resize', shut);
  }, [open]);

  const onKey = (e: KeyboardEvent) => {
    const last = options.length - 1;
    const step: Record<string, number> = { ArrowDown: active + 1, ArrowUp: active - 1, Home: 0, End: last, PageDown: active + 8, PageUp: active - 8 };
    if (e.key in step) setActive(Math.max(0, Math.min(last, step[e.key])));
    else if (e.key === 'Enter' || e.key === ' ') pick(active);
    else if (e.key === 'Escape') close();
    else if (e.key === 'Tab') close(false);
    else if (e.key.length === 1) {
      const now = Date.now();
      const text = (now - typed.current.at < TYPE_MS ? typed.current.text : '') + e.key.toLowerCase();
      typed.current = { text, at: now };
      const hit = options.findIndex((o) => o.label.toLowerCase().startsWith(text));
      if (hit >= 0) setActive(hit);
    } else return;
    // the phone's own Esc (close the sheet) and the page must not see these
    e.preventDefault();
    e.stopPropagation();
  };

  const onButtonKey = (e: KeyboardEvent) => {
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp' || e.key === 'Enter' || e.key === ' ') {
      e.preventDefault();
      show();
    }
  };

  return (
    <>
      <button
        ref={button}
        type="button"
        className={`picker ${className}`}
        disabled={disabled}
        aria-label={`${label} ${options[chosen]?.label ?? ''}`}
        aria-haspopup="listbox"
        aria-expanded={Boolean(open)}
        onClick={() => (open ? close() : show())}
        onKeyDown={onButtonKey}
      >
        <span className="picker-val">{options[chosen]?.label ?? ''}</span>
        <svg className="picker-caret" width="10" height="10" viewBox="0 0 10 10" aria-hidden="true">
          <path d="M2 3.5 5 6.5 8 3.5" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" />
        </svg>
      </button>
      {open &&
        createPortal(
          <>
            {/* closes on the click, not the press: the tap's click must not land on what is under the list */}
            <div className="picker-scrim" onClick={() => close()} />
            <ul ref={list} className="picker-list" role="listbox" aria-label={label} tabIndex={-1} style={open.style} onKeyDown={onKey}>
              {options.map((o, i) => (
                <li
                  key={String(o.value)}
                  role="option"
                  aria-selected={i === chosen}
                  className={`${i === chosen ? 'on' : ''}${i === active ? ' active' : ''}`}
                  onPointerEnter={() => setActive(i)}
                  onClick={() => pick(i)}
                >
                  {o.label}
                  {i === chosen && (
                    <svg width="14" height="14" viewBox="0 0 24 24" aria-hidden="true">
                      <path d="M5 12.5 10 17.5 19 7" fill="none" stroke="currentColor" strokeWidth="2.6" strokeLinecap="round" strokeLinejoin="round" />
                    </svg>
                  )}
                </li>
              ))}
            </ul>
          </>,
          open.screen,
        )}
    </>
  );
}
