// What each alert's button does, keyed by the alert's action.ref (demo ui/actions.py `perform` and its steps). Every
// change goes through `aura.edit`, so it stays in this visitor's browser; nothing is sent to AWS.
//
// As in the app, a cancelled sheet leaves the alert open; a timed action marks it done when the time is up (or the
// run is marked done), not when it is ended early.

import type { Aura } from '../app/store';
import type { Nav, Run } from '../app/nav';
import { featureFor } from './catalog';
import type { Json } from './types';
import { addDays, localParts, weekStart, weekday } from './agenda';
import { nowIn, shiftIso, wallIso, type Now } from './when';
import { kicker } from '../run/clock';
import { liveRun, runControls } from '../run/live';
import * as S from '../recovery/sheets';

export const WATER_GLASS_ML = 250;
/** BS-02: what Reconnect backfills, in minutes since the last sync. */
export const BACKFILL_MIN = 30;
export const BACKFILL_MAX = 720;
/** Words that make a calendar event "hard training" (DX-05 moves it, DX-06 cancels it). */
export const TRAINING_WORDS = ['gym', 'training', 'workout', 'run', 'strength', 'hiit', 'interval', 'brisk', 'spin', 'crossfit', 'tempo', 'lift', 'match'];

/** An event to add: its title, day, start (minutes after midnight) and length in minutes. */
export interface PlannedEvent {
  title: string;
  day: string;
  minute: number;
  minutes: number;
}

// -- pure helpers (tested in actions.test.ts) ---------------------------------------------------------------------

/** "23:00" less 30 minutes is "22:30"; wraps past midnight; anything unreadable counts as 23:00. */
export function hhmmMinus(value: unknown, minutes: number): string {
  const m = /^(\d{1,2}):(\d{2})/.exec(String(value ?? ''));
  const hh = m ? Number(m[1]) : 23;
  const mm = m ? Number(m[2]) : 0;
  const total = ((((hh * 60 + mm - minutes) % 1440) + 1440) % 1440);
  return `${String(Math.floor(total / 60)).padStart(2, '0')}:${String(total % 60).padStart(2, '0')}`;
}

/** The next `wd` (Monday 0 … Sunday 6) after `day`, 1 to 7 days on: a Saturday's next Saturday is a week later. */
export function nextWeekday(day: string, wd: number): string {
  const days = ((((wd - weekday(day)) % 7) + 7) % 7) || 7;
  return addDays(day, days);
}

/** PB-01: morning light 30 min after waking (07:30 at the earliest, 09:30 at the latest) and screens dimmed at 21:00,
 *  for the next `days` days; today's morning only when it is still ahead. */
export function lightTimes(day: string, minuteNow: number, wake: unknown, days = 3): PlannedEvent[] {
  const m = /^(\d{1,2}):(\d{2})/.exec(String(wake ?? ''));
  const wakeMin = m ? Number(m[1]) * 60 + Number(m[2]) : 7 * 60;
  const morning = Math.min(Math.max(wakeMin + 30, 7 * 60 + 30), 9 * 60 + 30);
  const out: PlannedEvent[] = [];
  for (let i = 0; i < days; i++) {
    const d = addDays(day, i);
    if (i > 0 || minuteNow < morning) out.push({ title: 'Outdoor light · 15 min', day: d, minute: morning, minutes: 15 });
    out.push({ title: 'Dim screens', day: d, minute: 21 * 60, minutes: 30 });
  }
  return out;
}

/** NI-02: three or more mood check-ins in the last 7 days (from `day`), averaging 2 or less. */
export function moodLowThisWeek(checkins: Json[], day: string): boolean {
  const since = addDays(day, -7);
  const moods = checkins
    .filter((c) => c.kind === 'mood' && typeof c.mood === 'number' && String(c.takenAt ?? '').slice(0, 10) >= since)
    .map((c) => c.mood as number);
  return moods.length >= 3 && moods.reduce((a, b) => a + b, 0) / moods.length <= 2;
}

/** How many ISO weeks `featureId` was raised in, over the alerts on hand. */
export function alertWeeks(alerts: Json[], featureId: string): number {
  const weeks = new Set<string>();
  for (const a of alerts) {
    const day = String(a.date ?? '').slice(0, 10);
    if (a.featureId === featureId && /^\d{4}-\d{2}-\d{2}$/.test(day)) weeks.add(weekStart(day));
  }
  return weeks.size;
}

/** BS-02: minutes the band kept offline since `last`, clamped to what a band holds (30 min to 12 h). */
export function backfillMinutes(last: string | null, nowMs: number): number {
  const at = last ? new Date(last).getTime() : NaN;
  if (Number.isNaN(at)) return BACKFILL_MIN;
  const gap = Math.floor((nowMs - at) / 60_000);
  return Math.max(BACKFILL_MIN, Math.min(BACKFILL_MAX, gap));
}

/** Events from `today` on, for `days` days, whose title sounds like hard training, earliest first. */
export function hardTraining(events: Json[], today: string, zone: string, days = 2): Json[] {
  const last = addDays(today, days - 1);
  return events
    .filter((e) => {
      const title = String(e.title ?? '').toLowerCase();
      const day = String(e.date ?? '') || localParts(e.start, zone)?.day || '';
      return Boolean(e.id) && day >= today && day <= last && TRAINING_WORDS.some((w) => title.includes(w));
    })
    .sort((a, b) => String(a.start ?? '').localeCompare(String(b.start ?? '')));
}

/** What an Insomnia Severity Index score (0-28) means. */
export function sleepMeaning(score: number): string {
  if (score <= 7) return 'No sign of insomnia.';
  if (score <= 14) return 'Some trouble sleeping. Keep the wind-down going and check again in two weeks.';
  if (score <= 21) return 'Moderate insomnia. Talk to a doctor; CBT for insomnia (CBT-I) works better than sleeping pills over time.';
  return 'Severe insomnia. Please see a doctor soon; CBT for insomnia (CBT-I) helps most people.';
}

/** DX-07: the stress before a recovery session against its last 10 minutes, from the session trace. */
export function stressChangeText(trace: Json | null): string {
  const drop = trace?.stressDrop;
  if (typeof drop === 'number') {
    if (drop > 0) return `Stress fell ${drop.toFixed(0)} points: the 15 min before against the last 10 min of the session.`;
    return "Stress didn't drop this time. Slow breathing works better with practice; try it again tomorrow.";
  }
  if (trace?.status === 'noData') return 'No band data for this session, so there is no stress change to show.';
  return "The stress change shows once today's data is computed (overnight). Find it under Search → sessions.";
}

/** The health goals on the profile (merged with the visitor's own changes). */
export function healthGoals(aura: Aura): Json {
  const goals = aura.data.me.healthGoals;
  return goals && typeof goals === 'object' ? (goals as Json) : {};
}

// -- steps --------------------------------------------------------------------------------------------------------

/** Now in the demo person's zone, on the demo's today. */
export const nowOf = (aura: Aura, at: Date = new Date()): Now => nowIn(aura.zone, aura.today, at);

/** The alert leaves "What you need to do" (no toast: the action already said what it did). */
function finishAlert(aura: Aura, alert: Json | null): void {
  const id = String(alert?.id ?? '');
  if (id) aura.edit.change('alerts', id, { done: true });
}

/** Take the alert off "What you need to do" (locally). */
export function markDone(aura: Aura, nav: Nav, alert: Json): void {
  const id = String(alert.id ?? '');
  if (!id) return;
  aura.edit.change('alerts', id, { done: true });
  nav.showToast('Done');
}

/** Change one health goal (merged into the profile's healthGoals). */
export function updateGoal(aura: Aura, nav: Nav, key: string, value: string): boolean {
  aura.edit.me({ healthGoals: { [key]: value } });
  nav.showToast(`Saved: ${key} ${value}`);
  return true;
}

export function addTasks(aura: Aura, nav: Nav, titles: string[], horizon: 'today' | 'week' | 'month' = 'today'): boolean {
  if (!titles.length) return false;
  for (const title of titles) aura.edit.add('tasks', { title, status: 'open', horizon });
  nav.showToast(titles.length === 1 ? `Added to your tasks: ${titles[0]}` : `Added ${titles.length} tasks`);
  return true;
}

export function eventRow(zone: string, e: PlannedEvent): Json {
  return { title: e.title, start: wallIso(zone, e.day, e.minute), end: wallIso(zone, e.day, e.minute + e.minutes) };
}

export function addEvents(aura: Aura, nav: Nav, events: PlannedEvent[]): boolean {
  if (!events.length) return false;
  for (const e of events) aura.edit.add('events', eventRow(aura.zone, e));
  nav.showToast(`Added ${events.length} to your calendar`);
  return true;
}

export function logWater(aura: Aura, nav: Nav, ml = WATER_GLASS_ML): boolean {
  const now = nowOf(aura);
  aura.edit.add('logs', { type: 'water', date: now.day, takenAt: now.iso, data: { ml } });
  nav.showToast(`Logged ${ml} ml of water`);
  return true;
}

/** A mood or connection check-in of 1-5; a low mood (or a low week) offers help. */
export function saveCheckin(aura: Aura, nav: Nav, kind: 'mood' | 'connection', value: number): void {
  const now = nowOf(aura);
  const row: Json = { kind, [kind]: value, takenAt: now.iso, date: now.day };
  aura.edit.add('checkins', row);
  nav.showToast(`Saved: ${kind} ${value}/5`);
  if (kind !== 'mood') return;
  const weekLow = moodLowThisWeek([...aura.data.checkins, row], now.day);
  if (value <= 2 || weekLow) {
    S.openHelp(nav, weekLow, (reachOut) => {
      if (reachOut) addTasks(aura, nav, ['Reach out to someone']);
    });
  }
}

/** The 1-5 sheet; `then` runs once a value is saved (not on Not now). */
export function askCheckin(aura: Aura, nav: Nav, kind: 'mood' | 'connection', then?: () => void): void {
  S.openRating(nav, kind, (value) => {
    then?.();
    saveCheckin(aura, nav, kind, value);
  });
}

/** DX-05: hard training to tomorrow, same time. */
export function moveEvents(aura: Aura, nav: Nav, ids: string[]): boolean {
  if (!ids.length) return false;
  const wanted = new Set(ids);
  for (const e of aura.data.events) {
    const id = String(e.id ?? '');
    if (wanted.has(id)) aura.edit.change('events', id, { start: shiftIso(e.start, 1, aura.zone), end: shiftIso(e.end, 1, aura.zone) });
  }
  nav.showToast(`Moved ${ids.length} to tomorrow`);
  return true;
}

/** DX-06: hard training comes off the calendar. */
export function cancelEvents(aura: Aura, nav: Nav, ids: string[]): boolean {
  if (!ids.length) return false;
  for (const id of ids) aura.edit.remove('events', id);
  nav.showToast(`Cancelled ${ids.length} training session${ids.length > 1 ? 's' : ''}`);
  return true;
}

/** BS-02: the band "sends" what it kept offline (simulated here: nothing leaves the browser), then today is fresh. */
export function reconnectBand(aura: Aura, nav: Nav, then?: () => void): boolean {
  const minutes = backfillMinutes(aura.local.lastSync, Date.now());
  nav.showToast(`Band reconnecting… sending ${minutes} minutes`);
  window.setTimeout(() => {
    aura.edit.synced(nowOf(aura).iso);
    nav.showToast(`Band reconnected · ${minutes} minutes synced`);
    then?.();
  }, 1400);
  return true;
}

/** DX-03: the Insomnia Severity Index, saved as an `insomnia` check-in; the score shows at once. */
export function sleepCheck(aura: Aura, nav: Nav): void {
  S.openSleepCheck(nav, (answers) => {
    const score = answers.reduce((a, b) => a + b, 0);
    const now = nowOf(aura);
    aura.edit.add('checkins', { kind: 'insomnia', insomnia: answers, insomniaScore: score, takenAt: now.iso, date: now.day });
    nav.showAlert({ title: 'Your sleep check', body: `Score ${score} of 28. ${sleepMeaning(score)}` });
  });
}

// -- timed runs ---------------------------------------------------------------------------------------------------

export interface TimedRun {
  title: string;
  /** 0 is open-ended (Flowtime counts up). */
  minutes: number;
  /** The session kind: recovery, walk, focus, meeting. */
  kind?: string;
  /** The Session service's mode. */
  mode?: string;
  accent?: string;
  note?: string;
  breathing?: boolean;
  /** The dark look; by default a nap. */
  night?: boolean;
  /** 3-2-1 first (Search's modes). */
  countdown?: boolean;
  /** The time ran out or the run was marked done (after the session is recorded). */
  onComplete?: () => void;
}

/** The session row a finished run leaves in Search → Sessions. */
export function sessionRow(zone: string, today: string, spec: TimedRun, completed: boolean, seconds: number, startedMs: number, endedMs: number): Json {
  return {
    kind: spec.kind ?? 'recovery',
    mode: spec.mode ?? 'timer',
    plannedMinutes: spec.minutes > 0 ? spec.minutes : null,
    title: spec.title,
    device: 'phone',
    status: completed ? 'completed' : 'stopped',
    startedAt: nowIn(zone, today, new Date(startedMs)).iso,
    endedAt: nowIn(zone, today, new Date(endedMs)).iso,
    actualMinutes: Math.max(0, Math.round(seconds / 60)),
  };
}

/** Open the full-phone timer; when it ends, a session row is added (completed or stopped). One run at a time: with
 *  one going, it comes back up instead. Returns false when nothing was started. */
export function timedRun(aura: Aura, nav: Nav, spec: TimedRun): boolean {
  const running = liveRun();
  if (nav.run || nav.counting || running) {
    runControls()?.expand();
    nav.showToast(`${running?.title ?? nav.run?.title ?? 'A session'} is still running; end it first.`);
    return false;
  }
  const kind = spec.kind ?? 'recovery';
  const startedMs = Date.now() + (spec.countdown ? 3000 : 0);
  const run: Run = {
    title: spec.title,
    minutes: spec.minutes,
    accent: spec.accent ?? 'recovery',
    label: kicker(kind, spec.minutes, spec.breathing),
    note: spec.note,
    breathing: spec.breathing,
    night: spec.night ?? spec.title.toLowerCase().includes('nap'),
    onEnd: (completed, seconds) => {
      aura.edit.add('sessions', sessionRow(aura.zone, aura.today, spec, completed, seconds, startedMs, Date.now()));
      if (completed) spec.onComplete?.();
    },
  };
  nav.startRun(run, spec.countdown);
  return true;
}

/** DX-03: ten quiet minutes, then what is on your mind goes on the week's task list. After 3 weeks of it, the sleep
 *  check is offered. End early saves nothing. */
export function windDown(aura: Aura, nav: Nav, accent: string, then?: () => void): boolean {
  return timedRun(aura, nav, {
    title: 'Wind-down',
    minutes: 10,
    accent,
    note: 'Dim the lights. Write down what is on your mind for tomorrow.',
    onComplete: () => {
      then?.();
      S.openToDo(nav, (items) => {
        addTasks(aura, nav, items, 'week');
        if (alertWeeks(aura.data.alerts, 'DX-03') >= 3) {
          nav.showAlert({
            title: 'Sleep check',
            body: 'Trouble falling asleep has come up for 3 weeks now. A 2-minute sleep check shows how much it affects you.',
            buttons: [
              { label: 'Not now', role: 'cancel' },
              { label: 'Take the sleep check', role: 'default', onPress: () => sleepCheck(aura, nav) },
            ],
          });
        }
      });
    },
  });
}

/** DX-05 moves today's and tomorrow's hard training to the next day, then a recovery break. DX-06 cancels it and
 *  can set bedtime 30 min earlier. */
export function easyDay(aura: Aura, nav: Nav, accent: string, ill: boolean, then?: () => void): void {
  const events = hardTraining(aura.data.events, aura.today, aura.zone);
  const bedtime = ill ? hhmmMinus(healthGoals(aura).bedtime ?? '23:00', 30) : null;
  S.openEasyDay(nav, {
    events,
    ill,
    bedtime,
    zone: aura.zone,
    onSubmit: (chosen, earlyNight) => {
      if (ill) {
        cancelEvents(aura, nav, chosen);
        if (bedtime && earlyNight) updateGoal(aura, nav, 'bedtime', bedtime);
        then?.();
        return;
      }
      moveEvents(aura, nav, chosen);
      then?.(); // the easy day is planned; the break itself is optional
      timedRun(aura, nav, { title: 'Recovery break', minutes: 10, accent, note: 'Ten quiet minutes: sit or lie down, breathe slowly.' });
    },
  });
}

/** NI-03: call someone today and/or plan a meal this week, then log how connected you feel. */
export function connectionPlan(aura: Aura, nav: Nav, then?: () => void): void {
  S.openConnection(nav, aura.today, (plan) => {
    let planned = false;
    if (plan.call) planned = addTasks(aura, nav, [`Call ${plan.who}`]);
    if (plan.meal) {
      const [hh, mm] = plan.time.split(':').map(Number);
      planned = addEvents(aura, nav, [{ title: `Meal with ${plan.who}`, day: plan.day, minute: hh * 60 + mm, minutes: 90 }]) || planned;
    }
    if (!planned) nav.showToast('Nothing planned; tick a call or a meal next time.');
    then?.();
    askCheckin(aura, nav, 'connection');
  });
}

/** PB-05: the signs, and a task to book a doctor's visit. */
export function healthCheck(aura: Aura, nav: Nav, alert: Json, then?: () => void): void {
  const body = String(alert.body ?? '').trim() || 'These signs are worth checking with a doctor.';
  nav.showAlert({
    title: String(alert.title ?? '') || 'Worth a check-up',
    body: `${body} Book a visit and show the doctor your Trends screen.`,
    buttons: [
      { label: 'Not now', role: 'cancel' },
      {
        label: "Add 'Book a doctor visit'",
        role: 'default',
        onPress: () => {
          addTasks(aura, nav, ['Book a doctor visit'], 'week');
          then?.();
        },
      },
    ],
  });
}

// -- the dispatcher -----------------------------------------------------------------------------------------------

/** Do what the alert asks, then mark it done. */
export function performAlert(aura: Aura, nav: Nav, alert: Json): void {
  const f = featureFor(alert);
  const action = alert.action && typeof alert.action === 'object' ? (alert.action as Json) : null;
  const ref = String(action?.ref ?? '') || f?.action || '';
  const accent = f?.accent ?? 'blue';
  const goals = healthGoals(aura);
  const now = nowOf(aura);
  const done = () => finishAlert(aura, alert);

  const ended = (ok: boolean) => {
    if (ok) done();
  };

  switch (ref) {
    case 'bedtime':
      ended(updateGoal(aura, nav, 'bedtime', hhmmMinus(goals.bedtime ?? '23:00', 30)));
      return;
    case 'settings':
      S.openTimeSheet(nav, 'One wake time for every day', String(goals.wakeTime ?? '07:00'), (hhmm) => ended(updateGoal(aura, nav, 'wakeTime', hhmm)));
      return;
    case 'wind-down':
      windDown(aura, nav, accent, done);
      return;
    case 'nap':
      timedRun(aura, nav, { title: 'Nap', minutes: 10, accent, night: true, note: 'Lie down, eyes closed. A gentle alarm ends it.', onComplete: done });
      return;
    case 'recovery':
      easyDay(aura, nav, accent, false, done);
      return;
    case 'breathing':
      timedRun(aura, nav, {
        title: 'Slow breathing',
        minutes: 5,
        accent,
        breathing: true,
        onComplete: () => {
          done();
          // there is no session trace in the browser, so this is the "computed overnight" answer
          window.setTimeout(() => nav.showToast(stressChangeText(null)), 2000);
        },
      });
      return;
    case 'move':
      timedRun(aura, nav, { title: 'Move break', minutes: 3, kind: 'walk', accent, note: 'Stand up, walk around, stretch.', onComplete: done });
      return;
    case 'add-walks': {
      const sat = nextWeekday(now.day, 5);
      ended(addEvents(aura, nav, [
        { title: 'Walk · 30 min', day: sat, minute: 9 * 60, minutes: 30 },
        { title: 'Walk · 30 min', day: addDays(sat, 1), minute: 9 * 60, minutes: 30 },
      ]));
      return;
    }
    case 'light':
      ended(addEvents(aura, nav, lightTimes(now.day, now.minute, goals.wakeTime ?? '07:00')));
      return;
    case 'scores':
      if (addEvents(aura, nav, [{ title: 'Recovery day: keep it light', day: addDays(now.day, 1), minute: 9 * 60, minutes: 60 }])) {
        done();
        askCheckin(aura, nav, 'mood');
      }
      return;
    case 'add-sessions':
      ended(addEvents(aura, nav, [
        { title: 'Strength · 40 min', day: addDays(now.day, 1), minute: 18 * 60, minutes: 40 },
        { title: 'Brisk session · 30 min', day: addDays(now.day, 3), minute: 18 * 60, minutes: 30 },
        { title: 'Strength · 40 min', day: addDays(now.day, 5), minute: 18 * 60, minutes: 40 },
      ]));
      return;
    case 'checkin/mood':
      askCheckin(aura, nav, 'mood', done);
      return;
    case 'checkin/connection':
      connectionPlan(aura, nav, done);
      return;
    case 'trends':
      healthCheck(aura, nav, alert, done);
      return;
    case 'water':
      ended(logWater(aura, nav, WATER_GLASS_ML));
      return;
    case 'reconnect':
      reconnectBand(aura, nav);
      done(); // the app hides it at once; the sync finishes a moment later
      return;
  }
  if (ref === 'rest' || f?.id === 'DX-06') {
    easyDay(aura, nav, accent, true, done);
    return;
  }
  // anything else: an acknowledgement
  nav.showAlert({
    title: String(alert.title ?? '') || 'Aura',
    body: String(alert.body ?? '') || f?.does || '',
    buttons: [{ label: 'OK', role: 'default', onPress: done }],
  });
}

