// "Read real, edit locally": what this visitor changed, kept in their own browser (localStorage) and drawn over the
// shared AWS snapshot. Nothing here is ever sent anywhere. Reset forgets it all.

import { LISTS, type Data, type Json, type ListPart, type Metrics, type Snapshot } from './types';

export interface ListEdits {
  /** Rows the visitor made (ids start with "local-"). */
  added: Json[];
  /** id -> fields changed on a row (an AWS row or an added one). */
  changed: Record<string, Json>;
  /** ids of rows the visitor deleted. */
  removed: string[];
}

export type Theme = 'system' | 'light' | 'dark';

export interface LocalState {
  v: 1;
  lists: Partial<Record<ListPart, ListEdits>>;
  /** Merged over the profile; `healthGoals` is merged one level deeper. */
  me: Json;
  /** The alert switches, when the visitor changed them (feature ids turned off). */
  alertOff: string[] | null;
  /** Metric columns pinned to Home, when the visitor changed them. */
  pins: string[] | null;
  theme: Theme;
  /** When the visitor last pressed Reconnect (BS-02). */
  lastSync: string | null;
  /** The alerts open when "Not now" was pressed: Home hides its pills until one that isn't here arrives. */
  snoozed?: string[] | null;
}

export const EMPTY: LocalState = { v: 1, lists: {}, me: {}, alertOff: null, pins: null, theme: 'system', lastSync: null };

const KEY = 'aura-demo:local';

export function loadLocal(): LocalState {
  try {
    const raw = localStorage.getItem(KEY);
    const value = raw ? (JSON.parse(raw) as LocalState) : null;
    return value && value.v === 1 ? { ...EMPTY, ...value } : EMPTY;
  } catch {
    return EMPTY;
  }
}

export function saveLocal(state: LocalState): void {
  try {
    localStorage.setItem(KEY, JSON.stringify(state));
  } catch {
    // private window or storage off: the changes last until the tab closes
  }
}

let counter = 0;
export const localId = (kind: string) => `local-${kind}-${Date.now().toString(36)}-${(counter++).toString(36)}`;

const edits = (state: LocalState, part: ListPart): ListEdits => state.lists[part] ?? { added: [], changed: {}, removed: [] };

// -- changes (pure: each returns the next state) -----------------------------------------------------------------

export function addRow(state: LocalState, part: ListPart, row: Json): LocalState {
  const e = edits(state, part);
  const id = String(row.id ?? localId(part));
  return { ...state, lists: { ...state.lists, [part]: { ...e, added: [...e.added, { ...row, id }] } } };
}

export function changeRow(state: LocalState, part: ListPart, id: string, changes: Json): LocalState {
  const e = edits(state, part);
  return { ...state, lists: { ...state.lists, [part]: { ...e, changed: { ...e.changed, [id]: { ...e.changed[id], ...changes } } } } };
}

export function removeRow(state: LocalState, part: ListPart, id: string): LocalState {
  const e = edits(state, part);
  const added = e.added.filter((r) => String(r.id) !== id);
  const removed = added.length === e.added.length ? [...e.removed, id] : e.removed;
  const { [id]: _gone, ...changed } = e.changed;
  return { ...state, lists: { ...state.lists, [part]: { added, changed, removed } } };
}

export function changeMe(state: LocalState, changes: Json): LocalState {
  const goals = changes.healthGoals as Json | undefined;
  const me = { ...state.me, ...changes };
  if (goals) me.healthGoals = { ...(state.me.healthGoals as Json | undefined), ...goals };
  return { ...state, me };
}

// -- the merge ---------------------------------------------------------------------------------------------------

function mergeList(rows: Json[], e: ListEdits | undefined): Json[] {
  if (!e) return rows;
  const removed = new Set(e.removed);
  const apply = (r: Json) => (e.changed[String(r.id)] ? { ...r, ...e.changed[String(r.id)] } : r);
  return [...rows.filter((r) => !removed.has(String(r.id))).map(apply), ...e.added.map(apply)];
}

const asList = (v: unknown): Json[] => (Array.isArray(v) ? (v as Json[]) : []);
const asObject = (v: unknown): Json | null => (v && typeof v === 'object' && !Array.isArray(v) ? (v as Json) : null);

/** Each part of the snapshot (empty where it failed or is missing), with the visitor's changes drawn over it. */
export function merge(snapshot: Snapshot | null, local: LocalState): Data {
  const part = (name: keyof Snapshot['parts']) => {
    const p = snapshot?.parts?.[name];
    return p && p.ok ? p.value : undefined;
  };
  const lists = Object.fromEntries(LISTS.map((name) => [name, mergeList(asList(part(name)), local.lists[name])])) as Record<ListPart, Json[]>;
  const metrics = (asObject(part('metrics')) as Metrics | null) ?? { days: [], latest: null };
  const server = asObject(part('me')) ?? {};
  const me = { ...server, ...local.me };
  if (local.me.healthGoals) me.healthGoals = { ...(server.healthGoals as Json | undefined), ...(local.me.healthGoals as Json) };
  return {
    ...lists,
    metrics: { days: asList(metrics.days), latest: asObject(metrics.latest) },
    active: asObject(part('active')),
    me,
    alertOff: local.alertOff ?? asList(part('alertOff')).map(String),
  };
}

/** How many changes the visitor has made (the demo chip shows it next to Reset). */
export function changeCount(state: LocalState): number {
  const lists = Object.values(state.lists).reduce((n, e) => n + (e ? e.added.length + Object.keys(e.changed).length + e.removed.length : 0), 0);
  return lists + Object.keys(state.me).length + (state.alertOff ? 1 : 0) + (state.pins ? 1 : 0) + (state.lastSync ? 1 : 0);
}
