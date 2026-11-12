---
name: demo-app
description: The aura_mobile/demo PyQt6 phone app — layout, screens from the user's sketch, how to run, code rules
metadata:
  type: project
---

Built 21 Oct 2026 at the user's request: "demo mobile app in Python" to test the AWS backend with every feature
marked mobile in the Zoho "mobile features" tab. User choices: PyQt6 window sized like a phone (390×844), a built-in
band simulator ("Test lab"), dev us-west-1 with real Cognito sign-in, the Mac/iOS Aura Clarus look.

**Screens (from the user's sketch — "UI must look according to it"):** three tabs.
- Home: notification strip (top open alert), "Finish your profile" link, Calendar card, Task card, Movement this week
  (DX-09 bar), User pin cards (+ last band sync / Reconnect).
- Recovery: open alerts with their action + Done, then 7 recovery presets (Session timers).
- Search: filter field, Logging card (water in glasses, mood, connection, Strength, Sleep check), Modes strip (Timer,
  Flowtime, Walk, Meeting), Sessions card (recovery sessions have "Stress change").
- A gear on every tab's nav bar opens **Settings** (page `profile.py`, BS-01; user 22 Oct: "i can't find setting"):
  body details as tap rows with sheets (BirthSheet day/month/year + age, NumberSheet for cm/kg) that save at once,
  goals, band pairing, 17 alert switches, inbox, export, delete, dark mode, sign out. Flask on Home opens Test lab.
  Esc = back.
- Health data is on by default (user: "turn on them as default"): `ctx.after_sign_in` runs `api.ensure_consent`;
  `ServiceClient` grants consent on a 403 `consent_required` and resends after `CONSENT_WAITS` 1/2/3/4 s, because
  Health Data keeps its own consent copy that `identity.consent.changed` updates a few seconds later.
- No flashing windows: use `widgets.set_shown(w, on)`, never `setVisible(True)` on a widget that has no parent yet
  (it blinks as its own window: "setGeometry 84x28 QPushButtonClassWindow"). The `stray_windows` fixture in
  `tests/conftest.py` fails any UI test that shows one.
- Shell: banner + taskbar flash on a new alert; `ui/alarms.py` rings the bedtime reminder and wake alarm locally.

**Look (rebuilt 22 Oct 2026, user: "build according [old design] with current stage" + the 21 Oct wireframe
`OneDrive\Pictures\Screenshots\Screenshot 2026-10-21 194529.png`):** the old "Aura Clarus Mobile" iPhone prototype
(artifact L3EWp9zmdrNpYNRBiYa5AL) look on the wireframe's tabs; its 93-feature content was NOT taken. Design system:
`ui/tokens.py` (palette light/dark, `t.current()`), `theme.py` (QSS by `class`/`tint` property, no inline styles),
`icons.py` (SVG line icons via QtSvg), `kit.py` (Row/Group/Switch/Segmented/Stepper/Chips/ChartCard/Ring/Bars/
ModeTile/CoachCard/NavBar), `layers.py` (Sheet/Alert/ActionSheet as frameless QDialogs over the phone, so `.exec()`
stays synchronous; Hud). `pages/base.py`: every Page has a nav bar + large title that compacts after 34 px.
Floating tab bar; Settings (gear, every tab) and Lab (flask, Home) push over the tabs. `runview.py`: timers are a non-blocking full-phone view
(night for nap, breathing pacer 5 s/5 s, wind-down list) that hides into a MiniBar; `actions.run_timed_session`
returns None and settles via callbacks. Built by 4 parallel agents (user asked "use multi agents").

**Code:** `demo/src/aura_mobile/` layered `ui → services → core`, no Qt in `core`.
- `services/`: `auth.py` (Cognito JSON API), `http.py` (ServiceClient: one base URL per service, Bearer, X-Time-Zone,
  refresh once on 401, 503 retry; logs `service METHOD path: status code` on every refused call), `api.py` (routes),
  `sim/` (band.py port of `aws_docs/services/health-data/test/e2e/band.ts`, scenarios.py, runner.py LabRunner).
- `ui/`: `shell.py` PhoneWindow, `pages/` (signin, home, recovery, search, profile, lab), `actions.py` (alert
  steps), `sheets.py` (their sheets), `runview.py`, `alarms.py`, `store.py`, `tasks.py`. `core/profile.py`: profile gaps.
- Qt rules copied from aura_window_app: keep workers in `_in_flight`, connect signals to bound methods, `hide()` before `deleteLater()`.
- Local state in `%APPDATA%\AuraMobileDemo`: `prefs.json`, `lab-<sub>.json`, `aura-mobile.log`.
- Screenshots in `demo/docs/screenshots/` (00-signin … 11-lab, each with a `-dark` twin). 215 tests, ruff line length 120.

**Redesign (24 Oct 2026, team: "so much text content and confuse to use … redesign to more simple with visual"):**
took the old Figma "AURA CLARUS ANR APP" visual language (PNG exports in `aura_mobile/figma/`; Figma MCP was rate-limited
on the Starter plan) but kept the Home/Recovery/Search tabs (no Chat). Alerts are black `ActionPill`s (name, 2-word
`Feature.reason`, green `Feature.verb` button) over a `MetricCard` (14 track bars of `Feature.metric`); Home has the
calendar (below), Today, Burnout; Search = health categories (`core/metrics.CARDS`) → `pages/metric.py` detail, plus
`logbook.py` (Logging), `modes.py` (setup + StartPill → 3-2-1 CountdownView → RunView), `sessions.py`; overlays
stack in `shell.py` (Back names the page beneath). Settings has no footers and names instead of codes; inbox is a row
→ sheet. Lab: ring x/16 + 16 chips + one-line rows; all status text moved unchanged into the row sheet. Text rules:
no footers, 1–3 word subtitles, one-verb buttons, codes only in the Lab, explanations behind ⓘ. Store fetches 14
days of metrics. Built by 3 parallel agents (user: "use multi-agents") on a foundation built first.

**Home calendar (24 Oct 2026, user: "upper calendar in home not working … add task can't find … replace calendar text
to real calendar view … sync them in show in calendar like how we show in PC"):** the display-only day strip, Tasks
pills and Events rows were replaced by one `CalendarCard` at the top of Home (`ui/calview.py`; Qt-free rules in
`core/agenda.py`), after the PC's Calendar day view: Week|Month `DayGrid` with dots, ‹ › Today, an hour `DayTimeline`
(PC colours: desk amber, meeting green, recovery purple, tasks blue; lanes for overlaps; red now line), a "To do" list
of untimed tasks with checks, a quick-add field, a green **+ Add**. `ItemSheet` adds/edits a task or event (Date,
Time, Length; Mark done; Delete with confirm). Actions in `actions.py` (`add_task`, `edit_task`, `set_task_done`,
`delete_task`, `edit_event`, `delete_event`) patch the store first; API got `update_task`/`delete_task`/`task`,
`update_event`/`event` with If-Match and one re-read on 409/412. `Store.show_span` loads the events the grid shows
(≤62 days). The shell's minute poll now reloads TASKS and CALENDAR too. Sync caveat told to the user: the Windows app
is on old eu-north-1 backend-v2, so PC↔phone sync needs the Windows app moved to the new backend.
Follow-up (user: "the calendar full when i open home page, make it cover 60% only and auto scroll to time where task
is avaible …"): `CalendarCard.fit_to` sizes the card to 60% of Home's height (`home.CALENDAR_SHARE`, measured on the
next event-loop turn); the timeline is 0–24 h inside `HoursView` (a scroll area) that jumps to the first item not
over / now−1h / 08:00 only on a new day or first items, so the minute poll keeps the user's place. The empty-day hint
is a bordered "calempty" pill centred over the lines. To fit, the ⟳ button went, Week|Month moved into the month row,
the Today chip shows beside + Add only off today, and To do shows 2 open tasks with "Show all N".

**Drag and drop (24 Oct, ported from the website after the user's go-ahead "comeplete website then move to pyqt"):**
`core/drag.py` (snap 15, `drop_start`, `stretch_to`, `can_drag`, `same`, `task_changes`, `event_start`,
`target_text`; tests `tests/test_drag.py`) and `ui/caldrag.py` `CalDrag` (event filter on the timeline and each
TaskRow; 5 px mouse slop; a `DragChip` label grabs mouse+keyboard while dragging so rows can be rebuilt under it;
holds 650 ms for side-step/‹ › flip; autoscroll 24 px band). Home `_drop` writes via `actions.edit_event` /
`edit_task` and toasts "Title · Sun 25 Oct · 10:15–11:00"; `_drag_day` keeps the hours' scroll. UI tests at the end
of `tests/test_ui_home.py`; screenshots 15–17 in `docs/screenshots`. Pushed as `dada08d` on `demo`.

**Repo:** `demo/` is a git repo, remote `github.com/dctn/aura_mobile` (private), branch `demo`; first commit `18b81c8` pushed 22 Oct.

**Run:** `cd demo; uv sync; uv run aura-mobile`. The README has a per-feature test guide.

**Speed (21 Oct, user: "take more 10 sec to see update in screen make it less 500ms"):** `ui/store.py` Store caches
11 parts (alerts, inbox, events, tasks, metrics, checkins, logs, active, sessions, me, alert_off) and loads them in
parallel; pages list `parts` and override `render(names)`. A tap = `ctx.store.patch(part, fn)` (drawn in 1–3 ms)
then `ctx.write(fn, area, on_done)` (server call, then reload of that area to confirm; failure → toast + reload).
HTTP keep-alive 120 s; at most 6 calls open at once (`MAX_IN_FLIGHT`, because dev allows 10 Lambdas at once), and
no `warm()` pings at sign-in (they used up the Lambda slots and caused the 503 "throttled" lines). Measured on dev: all parts
parallel 0.8–1.3 s vs 4.8 s one after another; confirm reloads 0.3–0.7 s. Only the Test lab still uses `areas`.

**How to apply:** match this layout when adding features. New data goes in `store.FETCHERS`; an action patches the
store first, then `ctx.write`. Never block the UI thread, and never make the screen wait for the server. See [[mobile-features-map]].
