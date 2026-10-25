// Search → Logging's table: every "App log" / "Manual entry" row of the team's Zoho sheet "Categories and Sources"
// that is not one of the 18 scientific features (those and their own logs live in Recovery). Each kind is one row
// (`zoho` is its S.No) and is one of three things:
//   - `input`: the person types it in; the form saves a log row { type: id, date, takenAt, data }.
//   - `derive`: worked out on the phone from those logs, the calendar and the sessions (derive.ts).
//   - `source`: only the desktop app or the band can work it out (screen time, flow, idle); shown without a number.

export type Input =
  | { type: 'scale5'; words?: [string, string] }
  | { type: 'scale10' }
  | { type: 'number'; unit: string; min: number; max: number; step: number; start: number; quick: [number, number] }
  | { type: 'minutes'; max: number; step: number; start: number }
  | { type: 'choice'; options: string[] }
  /** With `scale`, five faces under the text (the evening reflection). */
  | { type: 'text'; placeholder: string; scale?: string }
  | { type: 'yes'; verb: string }
  | { type: 'meal' }
  | { type: 'lift' }
  /** The 1-minute reaction and memory test (QuickCheck.tsx). */
  | { type: 'quick' };

export interface Kind {
  /** The log row's `type`; never 'water' or 'strength' (those are Recovery's). */
  id: string;
  /** The S.No in the Zoho sheet. */
  zoho: number;
  category: string;
  label: string;
  /** One line under the label on the form. */
  hint?: string;
  input?: Input;
  derive?: true;
  source?: 'desktop' | 'band';
}

export interface Category {
  id: string;
  label: string;
  /** A key of catalog.json "icons". */
  icon: string;
  /** A colour token in tokens.css. */
  accent: string;
}

export const CATEGORIES: Category[] = [
  { id: 'mood', label: 'Mood & energy', icon: 'sun', accent: 'focus' },
  { id: 'stress', label: 'Stress', icon: 'wind', accent: 'stress' },
  { id: 'people', label: 'People & purpose', icon: 'people', accent: 'teal' },
  { id: 'movement', label: 'Movement', icon: 'walk', accent: 'fitness' },
  { id: 'nutrition', label: 'Nutrition', icon: 'cup', accent: 'mint' },
  { id: 'meetings', label: 'Meetings', icon: 'cal', accent: 'recovery' },
  { id: 'focus', label: 'Focus', icon: 'bulb', accent: 'blue' },
  { id: 'learning', label: 'Learning', icon: 'brain', accent: 'purple' },
  { id: 'worklife', label: 'Work-life balance', icon: 'door', accent: 'sleep' },
  { id: 'habits', label: 'Habits', icon: 'flag', accent: 'cyan' },
  { id: 'burnout', label: 'Burnout', icon: 'flame', accent: 'workout' },
  { id: 'body', label: 'Body measurements', icon: 'body', accent: 'cardiac' },
  { id: 'lifting', label: 'Strength training', icon: 'dumbbell', accent: 'fitness' },
];

const AGREE: [string, string] = ['Not at all', 'Very much'];
const LOW_HIGH: [string, string] = ['Very low', 'Very high'];

export const KINDS: Kind[] = [
  // -- Mood & energy (119 Mood is Recovery's NI-02)
  { id: 'energy', zoho: 120, category: 'mood', label: 'Energy', input: { type: 'scale5', words: LOW_HIGH } },
  { id: 'onEdge', zoho: 121, category: 'mood', label: 'Feeling on edge', input: { type: 'scale5', words: AGREE } },
  { id: 'alertness', zoho: 122, category: 'mood', label: 'Felt alertness', input: { type: 'scale5', words: LOW_HIGH } },
  { id: 'workWorry', zoho: 123, category: 'mood', label: 'Worry about work', input: { type: 'scale5', words: AGREE } },
  { id: 'reflection', zoho: 124, category: 'mood', label: 'Evening reflection', input: { type: 'text', placeholder: 'What went well, what weighed on you', scale: 'How did today go?' } },
  { id: 'patterns', zoho: 125, category: 'mood', label: 'Your patterns', source: 'desktop' },

  // -- Stress
  { id: 'dailyStress', zoho: 82, category: 'stress', label: 'Daily stress', hint: '0 is none, 10 is the most', input: { type: 'scale10' } },
  { id: 'stressfulMonths', zoho: 83, category: 'stress', label: 'Stressful months', hint: 'In the last 12 months', input: { type: 'number', unit: 'months', min: 0, max: 12, step: 1, start: 0, quick: [1, 3] } },
  { id: 'challengeHindrance', zoho: 84, category: 'stress', label: 'Challenge or hindrance', hint: "Today's pressure felt like…", input: { type: 'choice', options: ['A challenge', 'A hindrance', 'Both', 'Neither'] } },

  // -- People & purpose
  { id: 'conversations', zoho: 128, category: 'people', label: 'Meaningful conversations', hint: 'Today', input: { type: 'number', unit: '', min: 0, max: 30, step: 1, start: 1, quick: [1, 5] } },
  { id: 'workSupport', zoho: 129, category: 'people', label: 'Support at work', input: { type: 'scale5', words: LOW_HIGH } },
  { id: 'workChallenge', zoho: 130, category: 'people', label: 'Work challenge', hint: 'How stretched your work felt', input: { type: 'scale5', words: LOW_HIGH } },

  // -- Movement
  { id: 'walkBeforeWork', zoho: 16, category: 'movement', label: 'Walk before hard work', input: { type: 'minutes', max: 120, step: 5, start: 10 } },
  { id: 'tvTime', zoho: 18, category: 'movement', label: 'TV time', input: { type: 'minutes', max: 600, step: 15, start: 60 } },

  // -- Nutrition: one meal entry feeds 145-160; the pattern is its own choice
  { id: 'meal', zoho: 158, category: 'nutrition', label: 'Meals', hint: 'Each meal counts towards the numbers above', input: { type: 'meal' } },
  { id: 'kcalToday', zoho: 145, category: 'nutrition', label: 'Calories eaten', derive: true },
  { id: 'kcalBreakfast', zoho: 146, category: 'nutrition', label: 'Breakfast', derive: true },
  { id: 'kcalLunch', zoho: 147, category: 'nutrition', label: 'Lunch', derive: true },
  { id: 'kcalSnack', zoho: 148, category: 'nutrition', label: 'Snacks', derive: true },
  { id: 'kcalDinner', zoho: 149, category: 'nutrition', label: 'Dinner', derive: true },
  { id: 'protein', zoho: 150, category: 'nutrition', label: 'Protein', derive: true },
  { id: 'carbs', zoho: 151, category: 'nutrition', label: 'Carbs', derive: true },
  { id: 'fat', zoho: 152, category: 'nutrition', label: 'Fat', derive: true },
  { id: 'calorieGoal', zoho: 153, category: 'nutrition', label: 'Calorie goal', derive: true },
  { id: 'mealTiming', zoho: 154, category: 'nutrition', label: 'Meal timing', derive: true },
  { id: 'eatingWindow', zoho: 155, category: 'nutrition', label: 'Eating window', derive: true },
  { id: 'fasting', zoho: 156, category: 'nutrition', label: 'Overnight fast', derive: true },
  { id: 'proteinPer100', zoho: 157, category: 'nutrition', label: 'Protein per 100 kcal', derive: true },
  { id: 'mealPhotos', zoho: 159, category: 'nutrition', label: 'Photo-logged meals', derive: true },
  { id: 'largestMeal', zoho: 160, category: 'nutrition', label: 'Largest meal', derive: true },
  { id: 'dietPattern', zoho: 161, category: 'nutrition', label: 'Diet pattern', input: { type: 'choice', options: ['Mixed', 'Vegetarian', 'Vegan', 'Pescatarian', 'Low carb', 'High protein', 'Time-restricted'] } },

  // -- Meetings: from the calendar's meeting events
  { id: 'meetingsToday', zoho: 162, category: 'meetings', label: 'Meetings today', derive: true },
  { id: 'meetingTime', zoho: 163, category: 'meetings', label: 'Meeting time', derive: true },
  { id: 'meetingFocusRatio', zoho: 164, category: 'meetings', label: 'Meeting-to-focus ratio', derive: true },
  { id: 'meetingLoad', zoho: 165, category: 'meetings', label: 'Meeting load', derive: true },
  { id: 'backToBack', zoho: 166, category: 'meetings', label: 'Back-to-back meetings', derive: true },
  { id: 'freeBlocks', zoho: 167, category: 'meetings', label: 'Meeting-free blocks', derive: true },
  { id: 'fragmentation', zoho: 168, category: 'meetings', label: 'Schedule fragmentation', derive: true },
  { id: 'makerScore', zoho: 169, category: 'meetings', label: 'Maker-schedule score', derive: true },
  { id: 'heaviestDay', zoho: 170, category: 'meetings', label: 'Heaviest meeting day', derive: true },
  { id: 'meetingStress', zoho: 171, category: 'meetings', label: 'Meeting stress', hint: 'After a meeting: 0 calm, 10 the most', input: { type: 'scale10' } },

  // -- Focus: the phone's timer and flowtime sessions, the after-block questions, and what only the desktop sees
  { id: 'focusToday', zoho: 173, category: 'focus', label: 'Focus minutes today', derive: true },
  { id: 'focus30', zoho: 174, category: 'focus', label: 'Focus minutes (30 days + today)', derive: true },
  { id: 'deepWorkHours', zoho: 178, category: 'focus', label: 'Deep work hours today', derive: true },
  { id: 'sessions7', zoho: 179, category: 'focus', label: 'Sessions in last 7 days', derive: true },
  { id: 'focusStreak', zoho: 180, category: 'focus', label: 'Focus streak', derive: true },
  { id: 'bestDay', zoho: 181, category: 'focus', label: 'Best day', derive: true },
  { id: 'worstDay', zoho: 182, category: 'focus', label: 'Worst day', derive: true },
  { id: 'deepWorkWeek', zoho: 185, category: 'focus', label: 'Weekly deep work', derive: true },
  { id: 'timedLength', zoho: 190, category: 'focus', label: 'Timed session length', derive: true },
  { id: 'flowtimeUsed', zoho: 191, category: 'focus', label: 'Flowtime mode used', derive: true },
  { id: 'focusScore', zoho: 172, category: 'focus', label: 'Focus score', source: 'desktop' },
  { id: 'flowToday', zoho: 175, category: 'focus', label: 'Flow minutes today', source: 'desktop' },
  { id: 'flowShare', zoho: 176, category: 'focus', label: 'Flow percentage', source: 'desktop' },
  { id: 'contextSwitches', zoho: 177, category: 'focus', label: 'Context switches today', source: 'desktop' },
  { id: 'productiveShare', zoho: 183, category: 'focus', label: 'Productive time share', source: 'desktop' },
  { id: 'idleTime', zoho: 184, category: 'focus', label: 'Idle time', source: 'desktop' },
  { id: 'flowState', zoho: 186, category: 'focus', label: 'Flow state detected today', source: 'desktop' },
  { id: 'flowTrend', zoho: 187, category: 'focus', label: 'Daily flow trend', source: 'desktop' },
  { id: 'suggestedBreaks', zoho: 188, category: 'focus', label: 'Suggested breaks', source: 'desktop' },
  { id: 'attentionSpan', zoho: 189, category: 'focus', label: 'Attention span estimate', source: 'desktop' },
  { id: 'sharpestHours', zoho: 195, category: 'focus', label: 'Your sharpest hours', source: 'desktop' },
  { id: 'quickCheck', zoho: 192, category: 'focus', label: 'Quick check', hint: 'One minute: reaction time and a digit memory test', input: { type: 'quick' } },
  { id: 'blockStress', zoho: 197, category: 'focus', label: 'Block stress', hint: 'After a focus block', input: { type: 'scale5', words: LOW_HIGH } },
  { id: 'blockEffort', zoho: 198, category: 'focus', label: 'Block effort', hint: 'After a focus block', input: { type: 'scale5', words: LOW_HIGH } },
  { id: 'interruptions', zoho: 193, category: 'focus', label: 'Interruptions', hint: 'During the last focus block', input: { type: 'number', unit: '', min: 0, max: 50, step: 1, start: 0, quick: [1, 5] } },
  { id: 'mindDrift', zoho: 194, category: 'focus', label: 'Mind drift', hint: 'How often your mind wandered', input: { type: 'scale5', words: ['Never', 'All the time'] } },
  { id: 'focusSound', zoho: 196, category: 'focus', label: 'Sound during focus', input: { type: 'choice', options: ['Silence', 'Instrumental', 'Lyrics', 'Noise'] } },
  { id: 'parkIt', zoho: 199, category: 'focus', label: 'Park-it notes', hint: 'Get a stray thought out of your head', input: { type: 'text', placeholder: 'Something to deal with later' } },
  { id: 'waterBeforeFocus', zoho: 200, category: 'focus', label: 'Water before focus', input: { type: 'yes', verb: 'Had a glass' } },

  // -- Learning
  { id: 'cardsDue', zoho: 201, category: 'learning', label: 'Cards due', input: { type: 'number', unit: 'cards', min: 0, max: 500, step: 5, start: 20, quick: [10, 50] } },
  { id: 'recallStyle', zoho: 202, category: 'learning', label: 'Spaced vs crammed recall', input: { type: 'choice', options: ['Spaced', 'Crammed', 'Mixed'] } },
  { id: 'learningBlocks', zoho: 203, category: 'learning', label: 'Learning blocks', input: { type: 'minutes', max: 240, step: 15, start: 30 } },
  { id: 'skillsNoAi', zoho: 204, category: 'learning', label: 'Skills without AI', hint: 'Practice done without an assistant', input: { type: 'minutes', max: 240, step: 15, start: 30 } },

  // -- Work-life balance
  { id: 'shutdown', zoho: 137, category: 'worklife', label: 'Shutdown checklist', hint: 'Closed the loops and planned tomorrow', input: { type: 'yes', verb: 'Done today' } },
  { id: 'workHours', zoho: 144, category: 'worklife', label: 'Work hours this week', input: { type: 'number', unit: 'h', min: 0, max: 100, step: 1, start: 40, quick: [1, 5] } },
  { id: 'deepWorkAvg', zoho: 135, category: 'worklife', label: 'Deep work (7-day avg)', derive: true },
  { id: 'shutdownDiscipline', zoho: 136, category: 'worklife', label: 'Shutdown discipline', derive: true },
  { id: 'boundaryScore', zoho: 138, category: 'worklife', label: 'Work-life boundary score', derive: true },
  { id: 'overworkStreak', zoho: 139, category: 'worklife', label: 'Overwork streak', derive: true },
  { id: 'longestFocusStreak', zoho: 140, category: 'worklife', label: 'Longest focus streak', derive: true },
  { id: 'badges', zoho: 141, category: 'worklife', label: 'Badges earned', source: 'desktop' },
  { id: 'weeklyChallenge', zoho: 142, category: 'worklife', label: 'Weekly challenge progress', source: 'desktop' },

  // -- Habits (Zoho "Long-term Habits" and "Sleep habits")
  { id: 'habitsKept', zoho: 126, category: 'habits', label: 'Habits kept this week', source: 'desktop' },
  { id: 'longestGap', zoho: 127, category: 'habits', label: 'Longest gap', source: 'desktop' },
  { id: 'hoursAwake', zoho: 73, category: 'habits', label: 'Hours awake', source: 'band' },

  // -- Burnout (the rest of the index is computed by the backend)
  { id: 'exhaustion', zoho: 212, category: 'burnout', label: 'Weekly exhaustion', hint: 'Over the last 7 days', input: { type: 'scale5', words: AGREE } },

  // -- Body measurements
  { id: 'weight', zoho: 309, category: 'body', label: 'Body weight', input: { type: 'number', unit: 'kg', min: 30, max: 250, step: 0.1, start: 65, quick: [0.5, 5] } },
  { id: 'bodyFat', zoho: 310, category: 'body', label: 'Body fat', input: { type: 'number', unit: '%', min: 3, max: 70, step: 0.1, start: 25, quick: [0.5, 5] } },
  { id: 'leanMass', zoho: 311, category: 'body', label: 'Lean body mass', input: { type: 'number', unit: 'kg', min: 20, max: 150, step: 0.1, start: 45, quick: [0.5, 5] } },
  { id: 'bodyWater', zoho: 312, category: 'body', label: 'Body water', input: { type: 'number', unit: '%', min: 30, max: 80, step: 0.1, start: 55, quick: [0.5, 5] } },
  { id: 'boneMass', zoho: 313, category: 'body', label: 'Bone mass', input: { type: 'number', unit: 'kg', min: 1, max: 6, step: 0.1, start: 2.5, quick: [0.1, 0.5] } },
  { id: 'muscleMass', zoho: 314, category: 'body', label: 'Muscle mass', input: { type: 'number', unit: 'kg', min: 15, max: 120, step: 0.1, start: 40, quick: [0.5, 5] } },
  { id: 'visceralFat', zoho: 315, category: 'body', label: 'Visceral fat score', input: { type: 'number', unit: '', min: 1, max: 30, step: 1, start: 6, quick: [1, 5] } },
  { id: 'bpSystolic', zoho: 316, category: 'body', label: 'Blood pressure (systolic)', input: { type: 'number', unit: 'mmHg', min: 70, max: 220, step: 1, start: 120, quick: [1, 10] } },
  { id: 'bpDiastolic', zoho: 317, category: 'body', label: 'Blood pressure (diastolic)', input: { type: 'number', unit: 'mmHg', min: 40, max: 140, step: 1, start: 80, quick: [1, 10] } },
  { id: 'bodyRecord', zoho: 308, category: 'body', label: 'Last measured', derive: true },
  { id: 'bmi', zoho: 318, category: 'body', label: 'BMI', derive: true },

  // -- Strength training: one lift entry feeds 320-325
  { id: 'lift', zoho: 319, category: 'lifting', label: 'Exercises', hint: 'Each exercise: sets, reps and weight', input: { type: 'lift' } },
  { id: 'volume', zoho: 320, category: 'lifting', label: 'Volume, last 7 days', derive: true },
  { id: 'heaviest', zoho: 321, category: 'lifting', label: 'Heaviest lift', derive: true },
  { id: 'groupCount', zoho: 322, category: 'lifting', label: 'Muscle groups trained', derive: true },
  { id: 'groupList', zoho: 323, category: 'lifting', label: 'Groups', derive: true },
  { id: 'liftEntries', zoho: 324, category: 'lifting', label: 'Exercise entries', derive: true },
  { id: 'strengthDays', zoho: 325, category: 'lifting', label: 'Strength days, last 7 days', derive: true },
];

export const MEALS = ['breakfast', 'lunch', 'snack', 'dinner'] as const;
export type Meal = (typeof MEALS)[number];

export const MUSCLE_GROUPS = ['Chest', 'Back', 'Shoulders', 'Arms', 'Core', 'Legs', 'Glutes', 'Full body'];

export const BY_KIND: Record<string, Kind> = Object.fromEntries(KINDS.map((k) => [k.id, k]));

export const categoryOf = (id: string): Category | undefined => CATEGORIES.find((c) => c.id === id);

export const kindsIn = (category: string) => KINDS.filter((k) => k.category === category);

/** The log types the person types in, so history and counts skip Recovery's own logs. */
export const INPUT_TYPES = new Set(KINDS.filter((k) => k.input).map((k) => k.id));
