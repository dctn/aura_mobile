// One store for the phone: the shared snapshot (live, saved or the bundled sample), this visitor's local changes,
// and the two merged into `data`. Screens read `useAura()` and change things only through `edit`.

import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { API, REFRESH_MS, fetchSnapshot, sampleSnapshot, savedSnapshot, saveSnapshot, type Source } from '../lib/api';
import * as L from '../lib/local';
import type { Data, Json, ListPart, Part, Snapshot } from '../lib/types';

export interface Status {
  source: Source | null;
  /** When AWS was read for what is showing (ISO). */
  at: string | null;
  /** The last refresh failed; an older copy is showing. */
  offline: boolean;
  loading: boolean;
}

export interface Edit {
  add(part: ListPart, row: Json): string;
  change(part: ListPart, id: string, changes: Json): void;
  remove(part: ListPart, id: string): void;
  me(changes: Json): void;
  alertOff(ids: string[]): void;
  pins(fields: string[]): void;
  theme(theme: L.Theme): void;
  synced(at: string): void;
  reset(): void;
}

export interface Aura {
  data: Data;
  /** Parts AWS could not read this time (the message is generic). */
  errors: Partial<Record<Part, string>>;
  status: Status;
  /** The demo person's zone and today there. */
  zone: string;
  today: string;
  /** Ticks every 30 s, so clocks and "now" lines move. */
  now: Date;
  local: L.LocalState;
  edit: Edit;
  refresh(): void;
}

const Ctx = createContext<Aura | null>(null);

export function useAura(): Aura {
  const value = useContext(Ctx);
  if (!value) throw new Error('useAura outside <AuraProvider>');
  return value;
}

export function todayIn(zone: string, now: Date): string {
  try {
    return new Intl.DateTimeFormat('en-CA', { timeZone: zone, year: 'numeric', month: '2-digit', day: '2-digit' }).format(now);
  } catch {
    return now.toISOString().slice(0, 10);
  }
}

function useNow(ms = 30_000): Date {
  const [now, setNow] = useState(() => new Date());
  useEffect(() => {
    const id = setInterval(() => setNow(new Date()), ms);
    return () => clearInterval(id);
  }, [ms]);
  return now;
}

function useSnapshot() {
  const [snapshot, setSnapshot] = useState<Snapshot | null>(() => (API ? savedSnapshot() : null));
  const [status, setStatus] = useState<Status>(() => ({ source: snapshot ? 'saved' : null, at: snapshot?.at ?? null, offline: false, loading: true }));
  const busy = useRef(false);

  const load = useCallback(async () => {
    if (busy.current) return;
    busy.current = true;
    setStatus((s) => ({ ...s, loading: true }));
    try {
      if (!API) {
        const sample = await sampleSnapshot();
        setSnapshot(sample);
        setStatus({ source: 'sample', at: sample.at, offline: false, loading: false });
        return;
      }
      const fresh = await fetchSnapshot();
      saveSnapshot(fresh);
      setSnapshot(fresh);
      setStatus({ source: 'live', at: fresh.at, offline: false, loading: false });
    } catch {
      // keep what is showing; with nothing at all, fall back to the sample so the demo still works
      const sample = savedSnapshot() ? null : await sampleSnapshot().catch(() => null);
      if (sample) setSnapshot(sample);
      setStatus((s) => ({ source: sample ? 'sample' : s.source, at: sample?.at ?? s.at, offline: true, loading: false }));
    } finally {
      busy.current = false;
    }
  }, []);

  useEffect(() => {
    void load();
    if (!API) return undefined;
    const id = setInterval(() => {
      if (document.visibilityState === 'visible') void load();
    }, REFRESH_MS);
    const onShow = () => document.visibilityState === 'visible' && void load();
    document.addEventListener('visibilitychange', onShow);
    return () => {
      clearInterval(id);
      document.removeEventListener('visibilitychange', onShow);
    };
  }, [load]);

  return { snapshot, status, load };
}

export function AuraProvider({ children }: { children: ReactNode }) {
  const { snapshot, status, load } = useSnapshot();
  const [local, setLocal] = useState<L.LocalState>(() => L.loadLocal());
  const now = useNow();

  const update = useCallback((next: (s: L.LocalState) => L.LocalState) => {
    setLocal((s) => {
      const value = next(s);
      L.saveLocal(value);
      return value;
    });
  }, []);

  const edit = useMemo<Edit>(() => ({
    add(part, row) {
      const id = String(row.id ?? L.localId(part));
      update((s) => L.addRow(s, part, { ...row, id }));
      return id;
    },
    change: (part, id, changes) => update((s) => L.changeRow(s, part, id, changes)),
    remove: (part, id) => update((s) => L.removeRow(s, part, id)),
    me: (changes) => update((s) => L.changeMe(s, changes)),
    alertOff: (ids) => update((s) => ({ ...s, alertOff: ids })),
    pins: (fields) => update((s) => ({ ...s, pins: fields })),
    theme: (theme) => update((s) => ({ ...s, theme })),
    synced: (at) => update((s) => ({ ...s, lastSync: at })),
    reset: () => update((s) => ({ ...L.EMPTY, theme: s.theme })),
  }), [update]);

  const value = useMemo<Aura>(() => {
    const zone = snapshot?.zone ?? 'Asia/Kolkata';
    // the sample is a moment in time; a live or saved copy is drawn on the real today
    const today = status.source === 'sample' && snapshot ? snapshot.today : todayIn(zone, now);
    const errors: Partial<Record<Part, string>> = {};
    for (const [name, result] of Object.entries(snapshot?.parts ?? {})) if (!result.ok) errors[name as Part] = result.error;
    return { data: L.merge(snapshot, local), errors, status, zone, today, now, local, edit, refresh: () => void load() };
  }, [snapshot, local, status, now, edit, load]);

  useEffect(() => {
    const root = document.documentElement;
    if (local.theme === 'system') root.removeAttribute('data-theme');
    else root.setAttribute('data-theme', local.theme);
  }, [local.theme]);

  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}
