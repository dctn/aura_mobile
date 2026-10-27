// The phone's fixed copy, exported from the demo package by tools/export_catalog.py (catalog.json is never edited by
// hand). The website's own list of things to start is lib/modes.ts; the phone's old presets and modes in catalog.json
// are only read by modes.test.ts, which checks that each still finds its mode.

import data from '../data/catalog.json';
import type { Json } from './types';

export interface Feature {
  id: string;
  name: string;
  group: string;
  /** A colour token in tokens.css (sleep, recovery, fitness, ...). */
  accent: string;
  shows: string;
  does: string;
  /** The alert's action.ref this feature's button performs. */
  action: string;
  actionLabel: string;
  rule: string;
  hasAlert: boolean;
  /** Two or three words under the pill's title ("Short night"). */
  reason: string;
  /** The one-word green button ("Nap"). */
  verb: string;
  /** The daily metric column it reads ("" when none). */
  metric: string;
  icon: string;
}

export const FEATURES = data.features as Feature[];
export const ICONS = data.icons as Record<string, string>;
/** A name ICONS has ("sleep", "walk", ...). */
export type IconName = keyof typeof data.icons;

export const BY_ID: Record<string, Feature> = Object.fromEntries(FEATURES.map((f) => [f.id, f]));
const BY_ACTION: Record<string, Feature> = Object.fromEntries(FEATURES.map((f) => [f.action, f]));

/** The feature behind an alert: by featureId, else by its action's ref. */
export function featureFor(alert: Json): Feature | null {
  const byId = BY_ID[String(alert.featureId ?? '')];
  if (byId) return byId;
  const action = alert.action as Json | undefined;
  return BY_ACTION[String(action?.ref ?? '')] ?? null;
}

/** A colour token as a CSS value: "sleep" -> var(--sleep). */
export const tone = (accent: string | undefined) => `var(--${accent || 'accent'})`;
