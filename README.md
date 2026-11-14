# Aura Clarus for Android

Native Kotlin + Jetpack Compose app, the Android version of the iPhone app in `../aura_ios_app`. It follows the iOS
app and the Figma exports in `../figma_new_design`, and reads the **dev** backend in us-west-1.

GitHub Actions builds it on every push to `android-app`, runs the tests on stub data, walks the screens on an
emulator and saves screenshots and a debug APK under the run's `android-results` artifact.

Package `info.ibtaura.auraclarus`, version 0.1.0. Android 9 (API 28) and newer; compiled against API 37, targets
API 36. AGP 9 (Kotlin built in), Kotlin 2.4, Gradle 9.9.1, JDK 21.

## Setup on this PC
The JDK, Android SDK and Gradle caches live in `D:\intellj\android`, so nothing lands on C:. In Git Bash:
```
. /d/intellj/android/env.sh
bash /d/intellj/android/build-slot.sh :core:test :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
- `env.sh` sets `JAVA_HOME`, `ANDROID_HOME`, `GRADLE_USER_HOME` and puts the JDK and `adb` on `PATH`.
- `build-slot.sh` runs `./gradlew` with `-Xmx2g` when one of two build slots is free, so parallel worktrees don't run
  out of memory. Plain `./gradlew` works too when nothing else is building.
- `local.properties` (not in git) points at the SDK: `sdk.dir=D\:/intellj/android/sdk`.
- Install on a phone over USB with USB debugging on. Android Studio can open this folder; point it at the same SDK.

## Layout
- `core` (`info.ibtaura.aura.core`): plain Kotlin/JVM with no Android in it, the counterpart of iOS `AuraKit`.
  Models, the `AuraApi` interface, `LiveApi` and `StubApi`, Cognito sign-in, the run and Quick check engines, the
  sleep and daily-totals builders, metric sums and the 65 modes (`modes.json`). Built against the **Java 8 API**
  (`-Xjdk-release=1.8`), because Android 9-13 lack newer `java.time` calls. Tests in `core/src/test`.
- `app` (`info.ibtaura.auraclarus`), under `app/src/main/kotlin/info/ibtaura/auraclarus/`:
  - `design/`: colours, theme, icons, the slider and shared parts.
  - `shell/`: app state, navigation, tabs, live and stub wiring, consent sheet, launch flags.
  - `signin/`: email and code sign-in.
  - `settings/`: Settings (Work hours, Goals, Units, Health Connect, account) and the Work hours form.
  - `home/`: Home sections, Work calendar, zones, Tasks and the task editor, event editor, Find a time, review
    inbox, check-in.
  - `logbook/`: Logging (water, mood, connection, log categories, this week) and the Calorie Tracker.
  - `goals/`: Goals (daily goals, sleep need, habits), Units and `UnitFormat`.
  - `sessions/`: Sessions history and a session's detail page.
  - `recovery/`: the Recovery tab, mode pages and the break sheets.
  - `run/`: the run controller and screen, the Done sheet, the end-of-run alarm and notification.
  - `search/`: Search, its categories and Clinical Documents.
  - `metric/`: a metric's page, chart, recorded data and sources.
  - `check/`: the Quick check.
  - `health/`: Health Connect reading, permissions and the privacy page.
  - `store/`: `KeystoreTokenStore`.
- `app/src/androidTest`: walks the screens on stub data, with no network or sign-in.
- `docs/`: `app-contract.md` (the shell every screen builds on) and prompts for the backend session.

## Config
`app/src/main/assets/aura-dev.json` is public config: stage, region, user pool, client id and one URL per service.
It is dev us-west-1 only. `LiveServices` refuses a config with stage `prod`, region `ap-south-1` or any service URL
in ap-south-1.

## Sign-in
Cognito email code: type the email, then the code from the email. An unconfirmed account can come back as a sign-in
step, so that step takes both code lengths. Tokens are kept only by `KeystoreTokenStore`: sealed with AES-256-GCM
under an Android Keystore key, in a private prefs file excluded from backup and device transfer. A store that can't
be opened reads as signed out. The email, the code and the tokens are never logged.

## What the running app writes to dev
Sessions and their actions, mood, tasks (create, edit, complete, reopen, delete), logs and check-ins from
Logging and the Calorie Tracker, habits and their check-offs, health goals, settings (`units`,
`focus.dailyGoalMinutes`, `nutrition.calorieGoal`), consent, check-ins, Quick checks, difficulty ratings, logged breaks,
calendar events, plans from Find a time, sleep nights and Health Connect days. `PUT /v1/me/profile` sends `timezone`
only when the profile has none, and `workSchedule` only when the person saves the Work hours form.

## Home
Insights, Today, Burnout, Work and Pins, each loading on its own. Pins open a metric's page.

## Work calendar
Home → Work → Calendar shows today's zones from `GET /v1/planner/zones` (heavy work, light work, short and heavy
recovery) behind the hours, with the events listed under the grid. With no score it says so; with no work hours it
offers to set them. Work hours are edited from Calendar or Settings. Day chips pick yesterday, today or up to 13 days
ahead; each day gets its own hour grid and list. On today, events that already ended sit dimmed under "Earlier today"
and the next 6 days follow. A block on the hour grid, an event row, or an Upcoming row on Home opens the event editor.

**Tasks.** Home → Work → Task: Today / Week / Month with open counts, the open list (sorted by calendar block or due
date, then priority) and a folding Done list. Tick to complete (`POST /v1/tasks/{id}/complete`; a repeating task's
next copy is added), tick again to reopen. "+" opens the editor: title, notes, list, priority, due date and time,
estimate, flag; editing adds Delete with a confirm. Saves are `PATCH /v1/tasks/{id}` with only the changed fields and
`If-Match`, retried once on 412.

Tasks feed auto-allocation, but only when the person asks. The editor's Work kind is Focus or Meeting; **Auto-schedule**
(`autoAllocate`, on for a new focus task, off and greyed for a meeting) lets Find a time place it, with How hard
(Light 25, Medium 50, Hard 80, or Auto: `demand` null) and Work type (`workType`) for the Planner; without a length
Aura plans 30 min. **Schedule** saves the task with Auto-schedule on and opens Find a time for just that task.
**Plan my day** on the Tasks page opens Find a time with every open auto-scheduled task ticked. Each open row shows
"Scheduled today, 16:00" (its block, or today's or tomorrow's stored plan), "Didn't fit: <reason>", or "Auto-schedule ·
waiting for Plan my day". Completing or deleting a task removes its future planner blocks (the Planner leaves them):
`GET /v1/calendar/events?source=planner` for the next 14 days, then `DELETE` each one still ahead that carries the
task's `taskId` or has Planner's id for it (`plan-<date>-<task>`, `-p<n>` pieces, and the `-rest` break, which Planner
writes with no `taskId`). With Auto-schedule off, the editor says Aura leaves the task where it is.

**Event editor.** New event, or a tap on an event, opens title, start and end, kind (Focus, Meeting, Recovery),
work type (deep focus, detail-heavy, ideas, routine, learning), high stakes, location and notes. Add is
`POST /v1/calendar/events`; Save is `PATCH /v1/calendar/events/{id}` with only the changed fields and `If-Match`,
retried once with the latest version on 412; Delete asks first. Quick moves (next free slot, tomorrow, next workday)
are `POST /v1/calendar/events/{id}/move`; the editor stays open and says where it went. A block Aura placed for a task
shows the task (tap to edit it); changing, moving or deleting it turns off auto-placing for that task so Aura won't
move it back. An Apple Calendar event only takes a new kind, work type and high stakes here; a repeating series and
its occurrences can't be moved. Over the day's work limit, the error names the minutes.

**Find a time.** Day chips pick today or one of the next six days. "Tasks to place" lists the open tasks marked
Auto-schedule, each with a tick (one task from its Schedule, else all ticked). The page first shows a preview of the
plan for the ticked tasks (`POST /v1/planner/allocate` as a dry run, which writes nothing; `taskIds` only when some are
unticked); **Add to calendar** runs it for real with the same tasks, says "Added N blocks" and closes. Tasks that
didn't fit are listed in plain words (No free time left that day, Too demanding for your energy that day, Over the
day's work limit, Planned for a later day, Longer than any free gap, Needs a length). With no task marked, the page
says how to mark one and links to Tasks. For today the Planner may want a Quick check first (409
`quick_check_required`): **Do the Quick check** or **Plan anyway**.

## Recovery and runs
The Recovery tab has the break pills, the Now carousel and shelves of all 65 modes. A mode page starts a run.
**Edit** shows, hides and reorders Now and the 10 shelves (kept on the phone per account, under a hash of the email,
never the email itself). **Filter** narrows by length and shelf for the session; a blue pill shows what's on.
A run that answers `joined: true` belongs to another session; the app asks "Stop it and start mine" or "Keep it".
- While the run page shows, the screen stays on (`keepScreenOn`).
- An alarm is set for the run's end: exact when the phone allows it, otherwise within a minute. If the app isn't on
  screen then, a "Run finished" notification says "<title> done"; on screen, the run page beeps.
- `POST_NOTIFICATIONS` and `SCHEDULE_EXACT_ALARM` are in the manifest for this.

## Logging, Goals, Sessions
- **Logging** (Search): water against the goal (+250 / +500 ml), mood and connection faces, seven log categories
  (mood and energy, stress, people and purpose, body, movement and strength, nutrition, sleep check) and this week's
  entries. `POST /v1/logs`, `POST /v1/checkins`. The backend can't edit or delete logs yet, so the page says so.
- **Calorie Tracker** (Search, or Logging → Nutrition): calories against a goal, macros, four meals, meal timing,
  diet pattern and 7 days of bars. Totals are added up on the phone from food logs.
- **Goals** (Search or Settings): steps, active calories, stand hours, water and focus minutes with today's progress;
  sleep need, bedtime and wake; habits with check-offs and streaks.
- **Units** (Settings, or a metric's Units link): metric or imperial, saved as the `units` setting and applied on
  metric pages.
- **Sessions** (Search): this week's focus and sessions, level and XP, 30 days of sessions by day with a filter,
  Log a break and Export CSV (shared as text). A session's page shows its numbers, capacity change, heart rate and
  stress trace, notes and a difficulty rating.
- **Clinical Documents**: an empty page that says what it will hold. The backend has no document routes yet.

## Search and metrics
Search lists 25 categories and the other places it reaches (Logging, Goals, Sessions, Modes, Calorie Tracker,
Settings, Clinical Documents). Typing shows
matching categories, metrics and places. A metric's page has the newest value, a bar chart with three ranges, the
trend, Pin to Home, all recorded data (daily points, up to a year) and its source.

## Quick check
Dark full screen: an intro, a **45-second** reaction test (tap the card when it turns green), then a **45-second**
memory part (type back a row of digits, one longer after each right answer; Skip sends reaction only). The lengths
are `REACTION_TEST_SECONDS` and `MEMORY_TEST_SECONDS` in `core/.../QuickCheckEngine.kt`. The screen stays on, and
leaving the app mid-reaction starts the test again. It goes to `POST /v1/checks`.

Dev accepts it since 14 Nov 2026: `durationSeconds` 30-600, a 40-59 s run with 8+ answers counts as the 45-second
check and a 170-200 s run with 30+ as the 3-minute one, and the answer carries `testSeconds` (45, 180 or none). The
stub uses the same windows. A 400 now only means the run itself was off; the app asks for a fresh check.

## Beta day score
Beta has no band, so the phone supplies what the day score needs.
- **How hard was it?** A focus run's Done sheet asks Easy to Very hard (`PUT /v1/sessions/{id}/difficulty`) and shows
  Capacity's line for the session. Home → Work lists today's and yesterday's sessions to rate later.
- **Recovery breaks.** "Take a recovery break" picks a kind and length, with the lift from
  `GET /v1/capacity/recovery-options`, and runs a recovery session. "Add a break I took" sends `POST /v1/sessions/log`.
- **Review inbox.** Home → Work shows the blocks to review.
- **Morning check-in.** At the top of Today: how alert you feel and, unless Health Connect already sent last night,
  when you fell asleep and woke (`POST /v1/checkins`).

## Health Connect
Read only. The manifest asks for sleep, steps, distance, active calories, exercise, resting heart rate, heart rate
variability, respiratory rate, oxygen saturation, weight, skin temperature and mindfulness. The check-in offers
"Use Health Connect for sleep", or "Install Health Connect" when it is missing (Android 13 needs the app from Play).
The permission screen's privacy link opens the app's own Health data page.
- **Sleep:** once each morning, last night goes to `PUT /v1/sleep/nights/{date}`.
- **Daily totals:** yesterday and today on each sync (at most every 15 minutes) go to
  `POST /v1/health-connect/days/batch` (`PUT /v1/health-connect/days/{date}` for one day). Deployed on dev on
  2026-11-14. A `not_available` answer from an older server is skipped and tried again in 6 hours.
- **Backfill:** the first time an account syncs, older days come 7 at a time until 30 days are covered, never a
  month in one go.
- **Quota:** Health Connect limits requests per app. A rate-limited read waits an hour, other failures 15 minutes,
  and the wait is kept across launches.
- **Settings → Health Connect** shows connected or not, how many kinds of data are allowed, when days were last sent
  (or "no data found" when Health Connect had nothing), the 30-day history, and when and why the next read waits.
  Sync now skips an ordinary wait but keeps a rate-limit wait; Permissions opens Health Connect's own page.
- Debug builds log counts only (never a health value) under the tag `AuraHealth`. Nothing reads Health Connect at
  launch: an earlier debug read on every launch used up the quota while the app was reinstalled many times.

`docs/backend-health-connect-prompt.md` asks the backend for these routes.

## Tests
- `./gradlew :core:test` runs about 400 tests on the JVM in seconds; `:app:testDebugUnitTest` runs the app's
  logic tests (tasks, units, recovery layout).
- The UI walks under `app/src/androidTest` run on CI's emulator with stub data. Launch flags in
  `shell/LaunchFlags.kt`, passed as the string extra `aura.flags`: `--uitesting` (stub data, no network),
  `--uitesting-short-run`, `--uitesting-no-schedule`, `--uitesting-joined-session`, `--uitesting-needs-check`,
  `--uitesting-short-check`, `--uitesting-health-night`, `--uitesting-checkin`. Screenshots are named as on iOS.

## CI
`.github/workflows/android.yml` runs on every push to `android-app` and by hand. Actions are pinned to commit SHAs,
`permissions: contents: read`, no secrets and no sign-in. It runs the core tests, builds the debug APK and test APK,
then walks the screens on an API 35 emulator. Screenshots, reports, logs and the APK go to the `android-results`
artifact for 14 days.

## Rules
- No secrets in files: no tokens, keys, keystores or personal data. Client ids and API URLs are public config.
- Dev us-west-1 only. Never prod, never ap-south-1.
- Commits by dctn only, with no Co-Authored-By lines.
- `.github/workflows` holds only `android.yml`. Anything else there is not ours.

## Still open
- A public privacy-policy URL for Health Connect and the Play listing.
- Play Console setup.
- A release signing key (kept out of the repo).
- Both backend prompts in `docs/` are done (Health Connect routes and the 45-second Quick check, live on dev
  14 Nov 2026).
- Planner gaps the app covers itself: completing a task doesn't remove its blocks on the server, a block moved by hand
  isn't pinned there, and a break has no `taskId`. Turning Auto-schedule off keeps blocks already placed; clean-up
  looks 14 days ahead.
