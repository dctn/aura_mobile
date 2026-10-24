// What the demo-site Lambda sends (aws_docs services/demo-site: GET /snapshot), and the merged data the screens draw.

export type Json = Record<string, unknown>;

export const PARTS = ['alerts', 'inbox', 'events', 'tasks', 'metrics', 'checkins', 'logs', 'active', 'sessions', 'me', 'alertOff'] as const;
export type Part = (typeof PARTS)[number];

export type PartResult = { ok: true; value: unknown } | { ok: false; error: string };

export interface Snapshot {
  /** When AWS was read (ISO). */
  at: string;
  /** The demo person's IANA zone; "today" and every day are counted there. */
  zone: string;
  today: string;
  parts: Record<Part, PartResult>;
}

export interface Metrics {
  /** The last 60 days (status ok / pending / ...), oldest first or any order. */
  days: Json[];
  /** The newest computed day in full. */
  latest: Json | null;
}

/** Every part as the screens read it: the snapshot with this visitor's own changes drawn over it. */
export interface Data {
  alerts: Json[];
  inbox: Json[];
  events: Json[];
  tasks: Json[];
  metrics: Metrics;
  checkins: Json[];
  logs: Json[];
  active: Json | null;
  sessions: Json[];
  me: Json;
  alertOff: string[];
}

/** The rows a visitor can add to, change and remove. */
export const LISTS = ['alerts', 'events', 'tasks', 'checkins', 'logs', 'sessions', 'inbox'] as const;
export type ListPart = (typeof LISTS)[number];
