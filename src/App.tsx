// The page: a short story around a working phone. On a desktop the phone sits beside the story; at phone width the
// phone fills the screen first and the story follows.

import { AuraProvider, useAura } from './app/store';
import { NavProvider } from './app/nav';
import { Phone } from './app/Phone';
import { changeCount } from './lib/local';
import { Story, Hero } from './story/Story';

function ago(at: string | null, now: Date): string {
  if (!at) return '';
  const s = Math.max(0, Math.round((now.getTime() - Date.parse(at)) / 1000));
  if (s < 90) return 'just now';
  const m = Math.round(s / 60);
  if (m < 90) return `${m} min ago`;
  const h = Math.round(m / 60);
  return h < 36 ? `${h} h ago` : `${Math.round(h / 24)} days ago`;
}

/** "Live demo · read from AWS 1 min ago · your changes stay on this device · Reset". */
function DemoChip() {
  const aura = useAura();
  const { status } = aura;
  const changes = changeCount(aura.local);
  const source =
    status.source === 'sample' ? 'Sample data' : status.offline ? `Offline · showing data from ${ago(status.at, aura.now)}` : status.source ? `Live from AWS · ${ago(status.at, aura.now)}` : 'Loading…';
  return (
    <div className="demochip" role="status">
      <span className={`dot ${status.offline ? 'off' : status.source === 'live' ? 'live' : ''}`} />
      <span>{source}</span>
      <span className="sep">·</span>
      <span className="chip-note">Changes stay on this device</span>
      {changes > 0 && (
        <>
          <span className="sep">·</span>
          <button className="chiplink" onClick={() => window.confirm('Forget your changes and show the demo account as it is?') && aura.edit.reset()}>
            Reset {changes}
          </button>
        </>
      )}
      <a className="chiplink chip-about" href="#about">About ↓</a>
    </div>
  );
}

function ThemeToggle() {
  const aura = useAura();
  const order = ['system', 'light', 'dark'] as const;
  const next = order[(order.indexOf(aura.local.theme) + 1) % order.length];
  return (
    <button className="themetoggle" onClick={() => aura.edit.theme(next)} title="Switch light / dark">
      {aura.local.theme === 'system' ? 'Auto' : aura.local.theme === 'light' ? 'Light' : 'Dark'}
    </button>
  );
}

export function App() {
  return (
    <AuraProvider>
      <NavProvider>
        <div className="site">
          <header className="topbar" id="about">
            <span className="brand">Aura Clarus</span>
            <span className="tag">phone demo</span>
            <span className="grow" />
            <ThemeToggle />
          </header>
          <main className="stage">
            <Hero />
            <section className="device-col" aria-label="The Aura app, working">
              <DemoChip />
              <div className="device">
                <Phone />
              </div>
            </section>
          </main>
          <Story />
          <footer className="foot-note">
            A demo of the Aura Clarus mobile app against its dev backend. Everyone sees the same demo person, Asha; nothing you do here is sent anywhere.
          </footer>
        </div>
      </NavProvider>
    </AuraProvider>
  );
}
