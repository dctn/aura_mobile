// The words around the phone: what Aura does, how a band reading becomes one tap, and the 18 mobile features.
// Feature codes appear only here, never inside the phone.

import { useMemo } from 'react';
import { FEATURES, tone } from '../lib/catalog';
import { Disc } from '../kit/kit';
import { useNav, type Nav } from '../app/nav';
import './story.css';

/** After a try-link changes the phone, bring the app's top into view when it is off screen or low on it: at phone
 *  width the app is the page and this intro sits below it, so the link would otherwise change a screen nobody sees.
 *  Smooth, unless the visitor asked for less motion. */
export function showApp(): void {
  const app = document.getElementById('app');
  if (!app) return;
  const top = app.getBoundingClientRect().top;
  if (top >= 0 && top < window.innerHeight / 2) return;
  const still = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches;
  app.scrollIntoView({ behavior: still ? 'auto' : 'smooth', block: 'start' });
}

/** The try-links: where each one takes the phone. */
const TRIES: { text: string; go: (nav: Nav) => void }[] = [
  { text: "Tap a black pill's green button: it does the thing", go: (nav) => nav.setTab('recovery') },
  { text: "Add a task or an event to today's calendar", go: (nav) => nav.setTab('home') },
  { text: 'See a month of sleep in Search → Sleep', go: (nav) => { nav.setTab('search'); nav.push({ name: 'metric', field: 'totalSleepMin' }); } },
  { text: 'Start a walk or a nap from Recovery → Modes', go: (nav) => { nav.setTab('recovery'); nav.push({ name: 'modes' }); } },
];

export function Hero() {
  const nav = useNav();
  return (
    <div className="hero">
      <div className="kicker">Aura Clarus · live demo</div>
      <h1>Your body&apos;s signals, turned into one small next step.</h1>
      <p>
        This is the Aura phone app, working. It is reading a real demo account, Asha, and her last month on a band, from Aura&apos;s cloud right now. Tap around. Anything you
        change stays on this device.
      </p>
      <ol className="tries">
        {TRIES.map((t, i) => (
          <li key={t.text}>
            <span>{String(i + 1).padStart(2, '0')}</span>
            <button className="trylink" onClick={() => { t.go(nav); showApp(); }}>{t.text}</button>
          </li>
        ))}
      </ol>
    </div>
  );
}

const STEPS = [
  { icon: 'band', accent: 'workout', title: 'The band measures', text: 'Heart rate, HRV, movement, skin temperature and sleep, minute by minute.' },
  { icon: 'chart', accent: 'blue', title: 'Aura computes the day', text: 'In the cloud: sleep debt, recovery, stress, body clock, burnout risk and more.' },
  { icon: 'bell', accent: 'stress', title: 'One alert, when it matters', text: 'A rule fires only when a signal crosses its line, with a plain reason.' },
  { icon: 'check', accent: 'recovery', title: 'One tap does it', text: 'Set the bedtime, start the nap timer, put two walks in the calendar.' },
];

function HowItWorks() {
  return (
    <div className="steps">
      {STEPS.map((s, i) => (
        <div className="step" key={s.title}>
          <div className="step-top">
            <Disc icon={s.icon} accent={s.accent} size={44} />
            {i < STEPS.length - 1 && <span className="step-line" aria-hidden="true" />}
          </div>
          <h3>{s.title}</h3>
          <p>{s.text}</p>
        </div>
      ))}
    </div>
  );
}

function Features() {
  const groups = useMemo(() => {
    const out = new Map<string, typeof FEATURES>();
    for (const f of FEATURES) out.set(f.group, [...(out.get(f.group) ?? []), f]);
    return [...out.entries()];
  }, []);
  return (
    <div className="fgroups">
      {groups.map(([group, features]) => (
        <div className="fgroup" key={group}>
          <h3>{group}</h3>
          {features.map((f) => (
            <div className="feature" key={f.id} style={{ ['--tone' as string]: tone(f.accent) }}>
              <Disc icon={f.icon} accent={f.accent} size={34} soft />
              <div>
                <div className="fname">
                  {f.name} <span className="fcode">{f.id}</span>
                </div>
                <div className="fshows">{f.shows}</div>
                <div className="fdoes">{f.does}</div>
              </div>
            </div>
          ))}
        </div>
      ))}
    </div>
  );
}

export function Story() {
  return (
    <section className="story">
      <h2>From a band reading to one tap</h2>
      <p className="lede">Aura doesn&apos;t hand you a dashboard to study. It watches the numbers for you and speaks up only when there is something worth doing today.</p>
      <HowItWorks />

      <h2>The 18 mobile features</h2>
      <p className="lede">Every alert in the phone comes from one of these rules on the backend. The demo account&apos;s month was shaped to raise almost all of them.</p>
      <Features />

      <h2>About this demo</h2>
      <div className="about">
        <div>
          <h3>Live, shared, read-only</h3>
          <p>Every visitor sees the same demo person. The site reads her account through one read-only, cached endpoint in Aura&apos;s dev cloud, refreshed every minute. It never sends anything back.</p>
        </div>
        <div>
          <h3>Your changes stay here</h3>
          <p>Tasks you add, alerts you finish and water you log are kept in this browser only. Nobody else sees them, and Reset brings the demo back as it was.</p>
        </div>
        <div>
          <h3>What is left out</h3>
          <p>Sign-in, sign-out, data export, deleting the account and the developer test lab. They exist in the app but don&apos;t belong in a public demo.</p>
        </div>
      </div>
    </section>
  );
}
