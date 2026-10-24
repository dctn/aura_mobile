// Reads the shared demo account from the demo-site endpoint (VITE_DEMO_API, a CloudFront URL in front of a read-only
// Lambda in dev). One GET of /snapshot; the answer is the same for every visitor and cached for a minute at the edge.
// The last good answer is kept in localStorage so a reload draws at once and an outage still shows the demo.
// Without VITE_DEMO_API the site draws the bundled sample (a copy of a real snapshot).

import type { Snapshot } from './types';

export const API = String(import.meta.env.VITE_DEMO_API ?? '').replace(/\/+$/, '');
/** How often an open tab asks again. */
export const REFRESH_MS = 60_000;

const KEY = 'aura-demo:snapshot';

export type Source = 'live' | 'saved' | 'sample';

export function isSnapshot(value: unknown): value is Snapshot {
  const s = value as Snapshot | null;
  return !!s && typeof s === 'object' && typeof s.today === 'string' && typeof s.zone === 'string' && !!s.parts && typeof s.parts === 'object';
}

export async function fetchSnapshot(signal?: AbortSignal): Promise<Snapshot> {
  if (!API) throw new Error('No demo endpoint set');
  const reply = await fetch(`${API}/snapshot`, { method: 'GET', signal, credentials: 'omit', cache: 'no-cache' });
  if (!reply.ok) throw new Error(`The demo data answered ${reply.status}`);
  const body: unknown = await reply.json();
  if (!isSnapshot(body)) throw new Error('The demo data came back in an unknown shape');
  return body;
}

export function savedSnapshot(): Snapshot | null {
  try {
    const value: unknown = JSON.parse(localStorage.getItem(KEY) ?? 'null');
    return isSnapshot(value) ? value : null;
  } catch {
    return null;
  }
}

export function saveSnapshot(snapshot: Snapshot): void {
  try {
    localStorage.setItem(KEY, JSON.stringify(snapshot));
  } catch {
    // storage full or off: the next load fetches again
  }
}

export async function sampleSnapshot(): Promise<Snapshot> {
  const module = await import('../data/sample.json');
  return module.default as unknown as Snapshot;
}
