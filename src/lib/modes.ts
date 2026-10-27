// One list of the things a person can start: the focus modes, breathing, guided meditation, and the fifty recovery
// presets. Website-owned: taken from the Swift app's GitHub main (commit c022c0d, 21 Oct): the presets from
// backend/src/domain/capacity/recovery.ts (ids, names, default minutes, family), the shelves from
// Views/Recovery/RecoveryView.swift (naps lifted out of Rest into Sleep), breathing and meditations from
// Views/Focus/FocusModels.swift, and the focus lengths from FocusLanding.swift. The "why" lines are rewritten short and
// plain. The old presets and modes of catalog.json are folded in as aliases. This moves to the PyQt core/features.py
// in the port.

import type { IconName } from './catalog';

export type Shelf = 'Focus' | 'Breathe' | 'Meditate' | 'Sleep' | 'Rest' | 'Movement' | 'Mind' | 'Outside' | 'People' | 'Fuel';

export const SHELVES: Shelf[] = ['Focus', 'Breathe', 'Meditate', 'Sleep', 'Rest', 'Movement', 'Mind', 'Outside', 'People', 'Fuel'];

/** Each shelf's icon, colour token and the line under it. */
export const SHELF_LOOK: Record<Shelf, { icon: IconName; accent: string; note: string }> = {
  Focus: { icon: 'timer', accent: 'focus', note: 'Time a block of work, or let it run while it flows.' },
  Breathe: { icon: 'wind', accent: 'cyan', note: 'Paced breathing, a few minutes at a time, anywhere.' },
  Meditate: { icon: 'body', accent: 'purple', note: 'A guided sit: a new prompt every few minutes.' },
  Sleep: { icon: 'sleep', accent: 'sleep', note: 'A short early-afternoon nap gives back the most. Keep it under 30 min.' },
  Rest: { icon: 'recover', accent: 'mint', note: 'Calm down while staying awake, when a nap would leave you groggy.' },
  Movement: { icon: 'walk', accent: 'fitness', note: 'Easy effort clears your head today; hard training pays off tomorrow.' },
  Mind: { icon: 'brain', accent: 'teal', note: 'Resets you can do at your desk or between calls.' },
  Outside: { icon: 'sun', accent: 'recovery', note: 'Time outdoors, ideally somewhere green. Morning light helps you sleep.' },
  People: { icon: 'people', accent: 'blue', note: 'Talking about anything but work takes your mind off it.' },
  Fuel: { icon: 'cup', accent: 'stress', note: 'What and when you eat and drink shapes the afternoon.' },
};

export type RunKind = 'timer' | 'flow' | 'breathe' | 'guided' | 'winddown' | 'plan';

/** One breath, in seconds: in, an optional hold, out, an optional hold after. */
export interface Breath {
  in: number;
  hold?: number;
  out: number;
  hold2?: number;
}

export interface Mode {
  id: string;
  name: string;
  /** The short tile label, unique across the list. */
  word: string;
  shelf: Shelf;
  /** The default length; 0 is open-ended (Flow). */
  minutes: number;
  /** Length options in minutes (none for plans and the wind-down). */
  choices?: number[];
  /** The session kind it saves: recovery, walk, nap, focus or meeting. */
  kind: string;
  run: RunKind;
  /** A colour token in tokens.css. */
  accent: string;
  icon: IconName;
  /** One short plain line. */
  note: string;
  pattern?: Breath;
  prompts?: string[];
  /** Old titles (catalog.json presets and modes) that resolve here. */
  aliases?: string[];
}

const FOCUS_CHOICES = [15, 25, 30, 45, 60, 90, 120];
const BREATHE_CHOICES = [2, 3, 5, 10];
const MEDITATE_CHOICES = [5, 10, 15, 20];
const LENGTHS = [5, 10, 15, 20, 25, 30, 45, 60];

/** Lengths around a recovery preset's default: from half of it to three times it (at least 10), always with it. */
function around(minutes: number): number[] {
  const near = LENGTHS.filter((m) => m >= minutes / 2 && m <= Math.max(minutes * 3, 10));
  return [...new Set([...near, minutes])].sort((a, b) => a - b);
}

type Extra = Partial<Pick<Mode, 'kind' | 'run' | 'accent' | 'pattern' | 'aliases' | 'choices'>>;

/** A recovery preset on `shelf`: timer by default, a calendar plan past two hours. */
function preset(shelf: Shelf, id: string, name: string, word: string, minutes: number, icon: IconName, note: string, extra: Extra = {}): Mode {
  const plan = minutes > 120;
  return {
    id,
    name,
    word,
    shelf,
    minutes,
    choices: plan ? undefined : around(minutes),
    kind: 'recovery',
    run: plan ? 'plan' : 'timer',
    accent: SHELF_LOOK[shelf].accent,
    icon,
    note,
    ...extra,
  };
}

const breathe = (id: string, name: string, word: string, pattern: Breath, note: string, aliases?: string[]): Mode => ({
  id,
  name,
  word,
  shelf: 'Breathe',
  minutes: 5,
  choices: BREATHE_CHOICES,
  kind: 'recovery',
  run: 'breathe',
  accent: SHELF_LOOK.Breathe.accent,
  icon: 'wind',
  note,
  pattern,
  ...(aliases ? { aliases } : {}),
});

const guided = (id: string, name: string, word: string, icon: IconName, note: string, prompts: string[]): Mode => ({
  id,
  name,
  word,
  shelf: 'Meditate',
  minutes: 10,
  choices: MEDITATE_CHOICES,
  kind: 'recovery',
  run: 'guided',
  accent: SHELF_LOOK.Meditate.accent,
  icon,
  note,
  prompts,
});

const walk: Extra = { kind: 'walk' };
const nap: Extra = { kind: 'nap' };

export const MODE_LIST: Mode[] = [
  // Focus (FocusLanding: 25 min by default, the header's menu of lengths; Flow has no countdown)
  { id: 'timer', name: 'Timer', word: 'Timer', shelf: 'Focus', minutes: 25, choices: FOCUS_CHOICES, kind: 'focus', run: 'timer', accent: 'blue', icon: 'timer', note: 'Count down one block of work.' },
  { id: 'flow', name: 'Flow', word: 'Flow', shelf: 'Focus', minutes: 0, kind: 'focus', run: 'flow', accent: 'purple', icon: 'flow', note: 'No countdown: stop when your focus fades.', aliases: ['Flowtime'] },
  { id: 'meeting', name: 'Meeting', word: 'Meeting', shelf: 'Focus', minutes: 30, choices: [15, 30, 45, 60, 90], kind: 'meeting', run: 'timer', accent: 'teal', icon: 'people', note: 'Time a call so it ends when it should.' },

  // Breathe (FocusModels BreathingPattern)
  breathe('box', 'Box', 'Box', { in: 4, hold: 4, out: 4, hold2: 4 }, 'In 4, hold 4, out 4, hold 4. Steadies you fast.'),
  breathe('four-seven-eight', '4-7-8', '4-7-8', { in: 4, hold: 7, out: 8 }, 'A long breath out that helps you settle for sleep.'),
  breathe('diaphragmatic', 'Diaphragmatic', 'Belly', { in: 4, out: 6 }, 'Breathe deep into the belly, longer out than in.'),
  breathe('coherent', 'Coherent', 'Coherent', { in: 5, out: 5 }, 'In for 5, out for 5.', ['Slow breathing']),

  // Meditate (FocusModels GuidedMeditation.all, six prompts each)
  guided('breath-awareness', 'Breath awareness', 'Awareness', 'wind', 'Settle the mind by following your breath.', [
    'Close your eyes and settle in.',
    'Notice the natural rhythm of your breath.',
    'Follow each inhale… and each exhale.',
    'When the mind wanders, gently return to the breath.',
    'Rest in this steady rhythm.',
    'Slowly widen your awareness. Open your eyes when ready.',
  ]),
  guided('body-scan', 'Body scan', 'Body scan', 'body', 'Let go of tension from your head down to your feet.', [
    'Bring attention to the top of your head.',
    'Soften your face, jaw and shoulders.',
    'Let your arms and hands grow heavy.',
    'Relax your chest and belly with each breath.',
    'Release your legs down to your feet.',
    'Feel your whole body at ease.',
  ]),
  guided('loving-kindness', 'Loving-kindness', 'Kindness', 'heart', 'Warm wishes for someone you care about, then for you.', [
    'Picture someone you care about.',
    'Wish them: may you be happy.',
    'May you be healthy.',
    'May you be at peace.',
    'Now offer the same wishes to yourself.',
    'Carry this warmth with you.',
  ]),
  guided('evening-wind-down', 'Evening wind-down', 'Evening', 'evening', 'Let the day go and get ready to rest.', [
    "Let the day's tasks fall away.",
    'Breathe in calm… breathe out tension.',
    "Notice three things you're grateful for.",
    'Let your breathing slow and deepen.',
    'Feel yourself growing calm and drowsy.',
    'Rest easy.',
  ]),
  guided('grounding', 'Grounding', 'Grounding', 'pin', 'Ease worry by noticing where you are right now.', [
    'Feel your feet on the floor.',
    'Notice five things you can sense right now.',
    'Breathe slowly into your belly.',
    'You are safe in this moment.',
    'Let the ground hold you.',
    'Carry this steadiness forward.',
  ]),
  guided('single-point-focus', 'Single-point focus', 'One point', 'bulb', 'Rest your attention on one point to sharpen it.', [
    'Choose one point: your breath at the nostrils.',
    'Rest all attention there.',
    'When it drifts, gently bring it back.',
    'Sharpen the focus, softly.',
    'Stay with this one point.',
    'Notice how steady the mind can be.',
  ]),

  // Sleep (the naps, lifted out of Rest as RecoveryView does) and the wind-down
  preset('Sleep', 'nap-10', 'Ten-minute nap', 'Nap', 10, 'sleep', 'Lie down, eyes closed. Before 3 pm.', { ...nap, aliases: ['Nap'] }),
  preset('Sleep', 'nap-20', 'Twenty-minute nap', 'Nap 20', 20, 'sleep', 'Pays off about half an hour after you wake.', nap),
  preset('Sleep', 'nap-30', 'Thirty-minute nap', 'Nap 30', 30, 'sleep', 'Groggy at first. Leave time before anything that matters.', nap),
  preset('Sleep', 'caffeine-nap', 'Coffee then nap', 'Coffee nap', 20, 'cup', 'Drink a coffee, then nap. You wake as it kicks in.', nap),
  { id: 'wind-down', name: 'Wind-down', word: 'Wind down', shelf: 'Sleep', minutes: 10, kind: 'recovery', run: 'winddown', accent: 'sleep', icon: 'evening', note: "Dim the lights, write tomorrow's list." },

  // Rest
  preset('Rest', 'recovery-break', 'Recovery break', 'Rest', 10, 'recover', 'Ten quiet minutes, no screen.'),
  preset('Rest', 'nsdr', 'Non-sleep deep rest', 'Deep rest', 20, 'recover', "Most of a nap's lift, with no risk of falling asleep."),
  preset('Rest', 'cold-face', 'Cold water on your face', 'Cold face', 2, 'drop', 'Ninety seconds at a cold tap slows your heart fast.'),
  preset('Rest', 'warm-bath', 'Hot bath before bed', 'Hot bath', 20, 'evening', 'One to two hours before bed helps you fall asleep.'),
  preset('Rest', 'sauna', 'Sauna', 'Sauna', 20, 'flame', 'The calm comes in the hour after you step out.'),
  preset('Rest', 'day-off', 'A whole day off', 'Day off', 480, 'cal', 'The lift fades within a week, so take them often.'),

  // Movement
  preset('Movement', 'zone2', 'Easy aerobic session', 'Aerobic', 30, 'heart', 'Brisk pace: you can talk but not sing.', { ...walk, aliases: ['Aerobic session'] }),
  preset('Movement', 'urban-walk', 'Walk round the block', 'Walk', 20, 'walk', 'A relaxed pace you could talk at.', { ...walk, aliases: ['Easy walk', 'Walk'] }),
  preset('Movement', 'stand-reset', 'Stand up and reset', 'Stand up', 3, 'move', 'Stand up, walk, stretch.', { ...walk, aliases: ['Move break'] }),
  preset('Movement', 'stair-break', 'Five minutes of stairs', 'Stairs', 5, 'move', 'More of a lift than a coffee, and the stairs are right there.', walk),
  preset('Movement', 'mobility', 'Stretch and move', 'Stretch', 15, 'body', "Gentle, for days a real workout isn't happening.", walk),
  preset('Movement', 'yoga', 'Yoga', 'Yoga', 45, 'body', 'Half of it is slow breathing, so it calms as it moves.', walk),
  preset('Movement', 'dance', 'Dance', 'Dance', 30, 'playsolid', 'Music and steps to learn leave no room for work.', walk),
  preset('Movement', 'walking-meeting', 'Take the call walking', 'Walk call', 30, 'walk', 'Costs no extra time, though a free walk rests you more.', walk),
  preset('Movement', 'strength', 'Lift something heavy', 'Strength', 45, 'dumbbell', 'Builds you up for later. Expect to feel tired tonight.', walk),
  preset('Movement', 'hard-training', 'Train hard', 'Train hard', 45, 'flame', 'Great for tomorrow, tiring today. Walk if work is left.', walk),

  // Mind
  preset('Mind', 'meditation-break', 'Ten minutes between meetings', 'Pause', 10, 'brain', 'Stress builds across back-to-back calls. Ten minutes stops it.'),
  preset('Mind', 'resonance-breathing', 'Breathe at six a minute', 'Six a min', 10, 'wind', 'Slow breathing for ten minutes, anywhere, nothing needed.', { run: 'breathe', pattern: { in: 5, out: 5 } }),
  preset('Mind', 'box-breathing', 'Box breathing', 'Box reset', 5, 'wind', 'Equal counts in, hold, out, hold. Easy to keep when rattled.', { run: 'breathe', pattern: { in: 4, hold: 4, out: 4, hold2: 4 } }),
  preset('Mind', 'physiological-sigh', 'Double inhale, long exhale', 'Sigh', 5, 'wind', 'Two breaths in through the nose, one slow breath out.'),
  preset('Mind', 'full-disconnect', 'Properly off', 'Off', 15, 'door', 'Phone away. Not thinking about work is the point.'),
  preset('Mind', 'journalling', 'Write it down', 'Journal', 15, 'pencil', 'It can feel worse while writing and better a few hours later.'),
  preset('Mind', 'read-fiction', 'Read something invented', 'Fiction', 20, 'note', 'Fiction only: nothing about work, nothing that turns into a task.'),
  preset('Mind', 'gratitude', 'Name three good things', 'Thankful', 10, 'star', 'Putting a feeling into words turns its volume down.'),
  preset('Mind', 'worry-dump', 'Empty your head onto paper', 'Worry list', 10, 'pencil', "List what's still undone, so it stops keeping you awake."),
  preset('Mind', 'music-break', 'Put music on', 'Music', 15, 'play', "Really listen. Music behind your email doesn't count."),

  // Outside
  preset('Outside', 'morning-light', 'Get outside early', 'Light', 15, 'sun', 'Daylight before 10 am resets the body clock.', { ...walk, aliases: ['Outdoor light'] }),
  preset('Outside', 'nature-walk', 'Walk somewhere green', 'Green walk', 20, 'walk', "A walk in a park restores attention; a busy road doesn't.", walk),
  preset('Outside', 'outdoor-break', 'Step outside', 'Step out', 10, 'door', 'Outside, no screen, nowhere to be.'),
  preset('Outside', 'green-sit', 'Sit somewhere green', 'Green sit', 20, 'sun', 'The green does the work, so sitting counts on tired days.'),
  preset('Outside', 'gardening', 'Gardening', 'Garden', 30, 'sun', 'Hands busy outdoors, and something to show for it.', walk),
  preset('Outside', 'blue-space', 'Time near water', 'Waterside', 30, 'drop', 'Coast, river or canal: likely as good as a park.'),

  // People
  preset('People', 'social-break', 'Talk to someone', 'Talk', 15, 'bubble', 'Chat about anything but work. It stops you replaying problems.'),
  preset('People', 'call-a-friend', 'Call someone (not text)', 'Call', 15, 'bell', "Hearing their voice is what helps. Texting doesn't do it."),
  preset('People', 'coffee-with-colleague', 'Coffee with a colleague', 'Catch-up', 20, 'cup', 'Pleasant, but the talk usually drifts back to work.'),
  preset('People', 'meal-with-someone', 'Eat with someone', 'Shared meal', 45, 'people', 'Food and company together, and it lasts longer than a coffee.'),
  preset('People', 'train-with-someone', 'Exercise with someone', 'Buddy', 45, 'dumbbell', "Worth more than training alone, and you're likelier to go.", walk),
  preset('People', 'family-time', 'Time with people you live with', 'Family', 60, 'home', 'Be there, not just in the room. Phone face down.'),
  preset('People', 'hobby-group', "The thing you do that isn't work", 'Hobby', 90, 'flag', 'Learning something new with others takes you right out of work.'),

  // Fuel
  preset('Fuel', 'hydration', 'Drink a proper glass of water', 'Glass', 5, 'drop', "Only helps if you're short. Been drinking all morning? Skip it."),
  preset('Fuel', 'refuel', 'Actually eat something', 'Eat', 25, 'cup', "Worth most when you haven't eaten for hours."),
  preset('Fuel', 'stable-snack', 'Something to steady the dip', 'Snack', 10, 'cup', 'Protein and fat, not a biscuit. The biscuit brings the next dip.'),
  preset('Fuel', 'protein-meal', 'A meal with real protein in it', 'Protein', 30, 'cup', 'Keeps you steady until the next meal, so no slump.'),
  preset('Fuel', 'light-lunch', 'Keep lunch light before deep work', 'Lunch', 20, 'cup', 'A lighter lunch softens the after-lunch dip.'),
  preset('Fuel', 'caffeine-timing', 'Coffee, before two', 'Coffee', 5, 'cup', "Have it early, so it's worn off by bedtime."),
  preset('Fuel', 'electrolytes', 'Salt and minerals, not just water', 'Minerals', 5, 'flask', 'Only after a real sweat. On a desk day it does little.'),
  preset('Fuel', 'alcohol-free-evening', 'Evening without a drink', 'No drink', 240, 'evening', 'The biggest boost to tomorrow, and it costs no time.'),
];

const lower = (s: string) => s.trim().toLowerCase();
const BY_ID = new Map(MODE_LIST.map((m) => [lower(m.id), m]));
const BY_NAME = new Map(MODE_LIST.map((m) => [lower(m.name), m]));
const BY_ALIAS = new Map(MODE_LIST.flatMap((m) => (m.aliases ?? []).map((a) => [lower(a), m] as const)));

/** The mode for an id, a name or an old title (case-insensitive), in that order. */
export function findMode(titleOrId: string): Mode | undefined {
  const key = lower(titleOrId);
  return BY_ID.get(key) ?? BY_NAME.get(key) ?? BY_ALIAS.get(key);
}

/** The modes on one shelf, in list order. */
export const modesOn = (shelf: Shelf): Mode[] => MODE_LIST.filter((m) => m.shelf === shelf);

/** What Recovery shows before anything has been used. */
export const DEFAULT_MODES: string[] = ['coherent', 'nap-10', 'urban-walk', 'wind-down'];

/** "In 4 · Hold 7 · Out 8". */
export function breathLine(p: Breath): string {
  const parts = [`In ${p.in}`];
  if (p.hold) parts.push(`Hold ${p.hold}`);
  parts.push(`Out ${p.out}`);
  if (p.hold2) parts.push(`Hold ${p.hold2}`);
  return parts.join(' · ');
}
