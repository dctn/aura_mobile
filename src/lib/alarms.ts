// DX-01 and DX-02 in the browser (ui/alarms.py): a bedtime reminder 30 minutes before bed and a wake alarm, from the
// health goals. Like a phone's own alarm clock there is no server call; each rings once a day, within two minutes of
// its time, and only while the page is open. Also the "New alert" ring when a refresh brings alerts not seen before.

import type { Json } from './types';

export const CHECK_MS = 30_000;
export const REMIND_BEFORE_BED_MIN = 30;

function minutes(value: unknown): number | null {
  const m = /^(\d{1,2}):(\d{2})/.exec(String(value ?? ''));
  if (!m) return null;
  const hh = Number(m[1]);
  const mm = Number(m[2]);
  return hh < 24 && mm < 60 ? hh * 60 + mm : null;
}

/** The alarms due at `minute` on `day` that haven't rung yet: their messages. Adds "key day" to `fired`. */
export function due(day: string, minute: number, goals: Json, fired: Set<string>): string[] {
  const plan: [string, number, string][] = [];
  const bed = minutes(goals.bedtime);
  const wake = minutes(goals.wakeTime);
  if (bed !== null) {
    plan.push(['bedtime', (bed - REMIND_BEFORE_BED_MIN + 1440) % 1440, `Bed at ${String(goals.bedtime)}: start winding down. Dim the lights, screens away.`]);
  }
  if (wake !== null) {
    plan.push(['wake', wake, `Good morning. The same wake time every day (${String(goals.wakeTime)}) keeps your clock steady.`]);
  }
  const out: string[] = [];
  for (const [key, at, text] of plan) {
    const id = `${key} ${day}`;
    if (!fired.has(id) && (((minute - at) % 1440) + 1440) % 1440 < 2) {
      fired.add(id);
      out.push(text);
    }
  }
  return out;
}

/** Alerts in `ids` not in `seen` (null: the first look, which only remembers). Adds them all to `seen`. */
export function newAlerts(alerts: Json[], seen: Set<string> | null): { seen: Set<string>; message: string | null } {
  const ids = alerts.map((a) => String(a.id));
  if (!seen) return { seen: new Set(ids), message: null };
  const fresh = alerts.filter((a) => !seen.has(String(a.id)));
  const next = new Set([...seen, ...ids]);
  if (!fresh.length) return { seen: next, message: null };
  const more = fresh.length > 1 ? ` (+${fresh.length - 1} more)` : '';
  return { seen: next, message: `New alert: ${String(fresh[0].title ?? 'Aura')}${more}` };
}

/** A short soft tone, where the browser allows sound (it may not until the visitor has tapped the page). */
export function beep(): void {
  try {
    const Audio = window.AudioContext ?? (window as unknown as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext;
    if (!Audio) return;
    const ctx = new Audio();
    const osc = ctx.createOscillator();
    const gain = ctx.createGain();
    osc.frequency.value = 880;
    gain.gain.setValueAtTime(0.08, ctx.currentTime);
    gain.gain.exponentialRampToValueAtTime(0.0001, ctx.currentTime + 0.35);
    osc.connect(gain).connect(ctx.destination);
    osc.start();
    osc.stop(ctx.currentTime + 0.36);
    osc.onended = () => void ctx.close();
  } catch {
    // no sound: the toast still shows
  }
}
