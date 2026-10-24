// The phone (ui/shell.py): the tab's root page, the pages pushed over it, the floating pill tab bar, and the layers on
// top: a sheet, an alert, a toast, the 3-2-1 countdown and the full-phone run view.

import { useEffect, useRef } from 'react';
import { useNav, type Page, type Tab } from './nav';
import { useAura } from './store';
import { CHECK_MS, beep, due, newAlerts } from '../lib/alarms';
import { healthGoals, nowOf } from '../lib/actions';
import { openAlerts } from '../screens/shared';
import { Icon } from '../kit/kit';
import { Home } from '../screens/Home';
import { Recovery } from '../screens/Recovery';
import { Search } from '../screens/Search';
import { Metric } from '../screens/Metric';
import { Logging } from '../screens/Logging';
import { Modes, ModeSetup } from '../screens/Modes';
import { Sessions } from '../screens/Sessions';
import { Settings } from '../screens/Settings';
import { Inbox } from '../screens/Inbox';
import { Countdown, RunView } from '../run/RunView';
import { useLiveRun } from '../run/live';

const TABS: { tab: Tab; label: string; icon: string }[] = [
  { tab: 'home', label: 'Home', icon: 'home' },
  { tab: 'recovery', label: 'Recovery', icon: 'recover' },
  { tab: 'search', label: 'Search', icon: 'search' },
];

function PageView({ page }: { page: Page }) {
  switch (page.name) {
    case 'metric':
      return <Metric field={page.field} />;
    case 'logging':
      return <Logging />;
    case 'modes':
      return <Modes />;
    case 'mode':
      return <ModeSetup index={page.index} />;
    case 'sessions':
      return <Sessions />;
    case 'settings':
      return <Settings />;
    case 'inbox':
      return <Inbox />;
  }
}

function Root({ tab }: { tab: Tab }) {
  if (tab === 'recovery') return <Recovery />;
  if (tab === 'search') return <Search />;
  return <Home />;
}

function Layers() {
  const nav = useNav();
  return (
    <>
      {nav.sheet && (
        <>
          <div className="scrim" onClick={nav.closeSheet} />
          <div className={`sheet${nav.sheet.tall ? ' tall' : ''}`} role="dialog" aria-modal="true">
            {nav.sheet.render(nav.closeSheet)}
          </div>
        </>
      )}
      {nav.alert && (
        <>
          <div className="scrim strong" />
          <div className="alertbox" role="alertdialog" aria-modal="true">
            <div className="ab-text">
              <div className="ab-title">{nav.alert.title}</div>
              {nav.alert.body && <div className="ab-body">{nav.alert.body}</div>}
            </div>
            <div className={`ab-buttons${(nav.alert.buttons?.length ?? 1) > 2 ? ' stack' : ''}`}>
              {(nav.alert.buttons ?? [{ label: 'OK', role: 'default' as const }]).map((b) => (
                <button
                  key={b.label}
                  className={b.role === 'destructive' ? 'danger' : b.role === 'default' ? 'bold' : ''}
                  onClick={() => {
                    nav.closeAlert();
                    b.onPress?.();
                  }}
                >
                  {b.label}
                </button>
              ))}
            </div>
          </div>
        </>
      )}
      {nav.counting && <Countdown />}
      {nav.run && <RunView run={nav.run} />}
      {nav.toast && <div className="toast" role="status">{nav.toast}</div>}
    </>
  );
}

/** The phone's own bells (ui/alarms.py, shell.py): the bedtime and wake alarms while the page is open, "New alert"
 *  when a refresh brings one, and Esc closing the alert, the sheet or the page on top. */
function useBells() {
  const aura = useAura();
  const nav = useNav();
  const latest = useRef({ aura, nav });
  latest.current = { aura, nav };
  const ring = (text: string) => {
    latest.current.nav.showToast(text);
    beep();
  };

  useEffect(() => {
    const fired = new Set<string>();
    const check = () => {
      const { aura: a } = latest.current;
      const now = nowOf(a);
      for (const text of due(now.day, now.minute, healthGoals(a), fired)) ring(text);
    };
    check();
    const id = window.setInterval(check, CHECK_MS);
    return () => window.clearInterval(id);
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  const seen = useRef<Set<string> | null>(null);
  const loaded = !aura.status.loading;
  const open = openAlerts(aura.data.alerts, aura.data.alertOff);
  const key = open.map((a) => String(a.id)).join(',');
  useEffect(() => {
    if (!loaded) return;
    const next = newAlerts(open, seen.current);
    seen.current = next.seen;
    if (next.message) ring(next.message);
  }, [key, loaded]); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const { nav: n } = latest.current;
      if (e.key !== 'Escape' || document.body.classList.contains('cal-dragging') || n.run || n.counting) return;
      if (n.alert) n.closeAlert();
      else if (n.sheet) n.closeSheet();
      else if (n.stack.length) n.back();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);
}

export function Phone() {
  const nav = useNav();
  useBells();
  const top = nav.stack.at(-1);
  const live = useLiveRun();
  return (
    <div className="screen">
      <Root tab={nav.tab} />
      {nav.stack.map((page, i) => (
        <div key={`${i}-${page.name}`} style={{ display: page === top ? 'contents' : 'none' }}>
          <PageView page={page} />
        </div>
      ))}
      {((!nav.run && !nav.counting) || live?.collapsed) && (
        <nav className="tabbar" aria-label="Tabs">
          {TABS.map((t) => (
            <button key={t.tab} className={nav.tab === t.tab ? 'on' : ''} onClick={() => nav.setTab(t.tab)} aria-current={nav.tab === t.tab}>
              <Icon name={t.icon} size={24} stroke={nav.tab === t.tab ? 2 : 1.8} />
              {t.label}
            </button>
          ))}
        </nav>
      )}
      <Layers />
    </div>
  );
}
