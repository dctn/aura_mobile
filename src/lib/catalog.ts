// The phone's fixed copy, exported from the demo package by tools/export_catalog.py (never edited by hand).

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

export interface Preset {
  title: string;
  word: string;
  minutes: number;
  /** The Session kind: recovery or walk. */
  kind: string;
  accent: string;
  note: string;
  icon: string;
}

export interface Mode {
  name: string;
  mode: string;
  kind: string;
  /** 0 is open-ended (Flowtime counts up). */
  minutes: number;
  start: string;
  icon: string;
  accent: string;
}

export const FEATURES = data.features as Feature[];
export const PRESETS = data.presets as Preset[];
export const MODES = data.modes as Mode[];
export const ICONS = data.icons as Record<string, string>;

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
