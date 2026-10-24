// The phone (ui/shell.py): the tab's root page, the pages pushed over it, the floating pill tab bar, and the layers on
// top: a sheet, an alert, a toast, the 3-2-1 countdown and the full-phone run view.

import { useNav, type Page, type Tab } from './nav';
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

export function Phone() {
  const nav = useNav();
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
