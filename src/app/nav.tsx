// Where the phone is: the tab, the pages pushed over it, and the layers on top (a sheet, an alert, the run view,
// the 3-2-1 countdown, a toast). Mirrors the PyQt shell: three tabs, drill pages stack with "‹ <page below>".

import { createContext, useCallback, useContext, useMemo, useRef, useState, type ReactNode } from 'react';
import { MODES } from '../lib/catalog';
import { cardFor } from '../lib/metrics';

export type Tab = 'home' | 'recovery' | 'search';

export type Page =
  | { name: 'metric'; field: string }
  | { name: 'logging' }
  | { name: 'modes' }
  | { name: 'mode'; index: number }
  | { name: 'sessions' }
  | { name: 'settings' }
  | { name: 'inbox' };

export const TAB_TITLES: Record<Tab, string> = { home: 'Home', recovery: 'Recovery', search: 'Search' };

/** A full-phone timer (ui/runview.py). minutes 0 counts up (Flowtime). */
export interface Run {
  title: string;
  minutes: number;
  /** A colour token. */
  accent: string;
  /** The small caps line over the title ("MOVE · 20 MIN"). */
  label?: string;
  note?: string;
  /** Slow-breathing circle instead of the clock bar. */
  breathing?: boolean;
  /** Dark screen (a nap). */
  night?: boolean;
  /** Drawn under the clock while it runs (the wind-down's list for tomorrow). */
  panel?: ReactNode;
  /** Called once: true when the time ran out or Done was pressed, false when it was stopped early. */
  onEnd?: (completed: boolean, seconds: number) => void;
}

export interface SheetSpec {
  /** Rendered inside the sheet; call `close` to dismiss. */
  render: (close: () => void) => ReactNode;
  /** A short sheet sits at the bottom; a tall one stops near the top like a page sheet. */
  tall?: boolean;
}

export interface AlertButton {
  label: string;
  role?: 'cancel' | 'destructive' | 'default';
  onPress?: () => void;
}

export interface AlertSpec {
  title: string;
  body?: string;
  buttons?: AlertButton[];
}

export interface Nav {
  tab: Tab;
  stack: Page[];
  setTab(tab: Tab): void;
  push(page: Page): void;
  back(): void;
  /** Back to the tab's root page. */
  home(): void;
  sheet: SheetSpec | null;
  openSheet(sheet: SheetSpec): void;
  closeSheet(): void;
  alert: AlertSpec | null;
  showAlert(alert: AlertSpec): void;
  closeAlert(): void;
  run: Run | null;
  /** With `countdown`, 3-2-1 shows first. */
  startRun(run: Run, countdown?: boolean): void;
  /** Cancel on the 3-2-1: the run never starts. */
  cancelCountdown(): void;
  endRun(): void;
  counting: boolean;
  toast: string | null;
  showToast(text: string): void;
}

const Ctx = createContext<Nav | null>(null);

export function useNav(): Nav {
  const value = useContext(Ctx);
  if (!value) throw new Error('useNav outside <NavProvider>');
  return value;
}

export function NavProvider({ children }: { children: ReactNode }) {
  const [tab, setTabState] = useState<Tab>('home');
  const [stack, setStack] = useState<Page[]>([]);
  const [sheet, setSheet] = useState<SheetSpec | null>(null);
  const [alert, setAlert] = useState<AlertSpec | null>(null);
  const [run, setRun] = useState<Run | null>(null);
  const [counting, setCounting] = useState(false);
  const [toast, setToast] = useState<string | null>(null);

  const showToast = useCallback((text: string) => {
    setToast(text);
    window.setTimeout(() => setToast((t) => (t === text ? null : t)), 2600);
  }, []);

  const countdownTimer = useRef(0);
  const startRun = useCallback((next: Run, countdown = false) => {
    setSheet(null);
    if (!countdown) {
      setRun(next);
      return;
    }
    setCounting(true);
    countdownTimer.current = window.setTimeout(() => {
      setCounting(false);
      setRun(next);
    }, 3000);
  }, []);
  const cancelCountdown = useCallback(() => {
    window.clearTimeout(countdownTimer.current);
    setCounting(false);
  }, []);

  const value = useMemo<Nav>(() => ({
    tab,
    stack,
    setTab: (t) => {
      setTabState(t);
      setStack([]);
    },
    push: (page) => setStack((s) => [...s, page]),
    back: () => setStack((s) => s.slice(0, -1)),
    home: () => setStack([]),
    sheet,
    openSheet: setSheet,
    closeSheet: () => setSheet(null),
    alert,
    showAlert: setAlert,
    closeAlert: () => setAlert(null),
    run,
    startRun,
    cancelCountdown,
    endRun: () => setRun(null),
    counting,
    toast,
    showToast,
  }), [tab, stack, sheet, alert, run, counting, toast, startRun, cancelCountdown, showToast]);

  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

/** The title a page goes by in the "‹ Back" link of the page above it. */
export function titleOf(page: Page): string {
  switch (page.name) {
    case 'metric':
      return cardFor(page.field).label;
    case 'mode':
      return MODES[page.index]?.name ?? 'Mode';
    default:
      return page.name.charAt(0).toUpperCase() + page.name.slice(1);
  }
}

/** What "‹ Back" says on a pushed page: the title of the page under it. */
export function backLabel(nav: Nav): string {
  const below = nav.stack.at(-2);
  return below ? titleOf(below) : TAB_TITLES[nav.tab];
}
