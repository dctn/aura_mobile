---
name: aura-ios-app
description: aura_ios_app/ (Nov 2026) — native SwiftUI iPhone app from the Figma PNGs, on dev us-west-1; written on Windows, built only by GitHub Actions, never yet signed in live
metadata:
  type: project
---

`aura_ios_app/` is the native iPhone app: SwiftUI, iOS 26 minimum, light mode only, Swift 5 language mode. It is its own
git repo with remote `dctn/aura_mobile`, branch `ios-app`. The user decided its scope: only what is in Figma plus
sign-in, with tabs Home, Recovery and Search, and all 65 modes with shelves that scroll sideways.

- **Design source:** the PNGs in `figma_new_design/`. The Figma file can't be read through MCP on the Starter plan.
- **Layout:** `Packages/AuraKit` holds logic with no UI, `App` holds the screens, `UITests` walks them on stub data.
  `project.yml` (XcodeGen) generates the project, so never edit the `.xcodeproj`.
- **Building:** this PC has no Swift compiler, so CI is the only compile check (about 16 minutes a run).
  `.github/workflows/ios.yml` runs on every push to `ios-app`: AuraKit tests, then the UI walk on the iPhone 17 Pro
  simulator with signing off. Screenshots are in the run's `ios-results` artifact. CI uses stubs, no secrets, no sign-in.
- **Data:** `LiveServices` reads `App/Resources/aura-dev.json` (public config). Cognito email codes, tokens in Keychain.
  UI tests launch with `--uitesting`; `--uitesting-short-run` makes timed runs last 3 seconds.
- **The running app makes writes** against dev: session start and actions, mood, tasks, consent, and
  `PUT /v1/me/profile` with `timezone` only, when the profile has none.
- **Day zones (8 Nov 2026):** Calendar (Home → Work → Calendar, the page the user chose) shows today's zones from
  Planner `GET /v1/planner/zones`, with a Work hours form in a sheet opened from Calendar and Settings. Saving it writes
  `PUT /v1/me/profile` with `workSchedule` only (If-Match). The score inside each day is the morning capacity score that
  the Planner reads from Capacity; the app doesn't call Capacity. The colours are the Figma blue (work) and green (recovery).
- **Session already running (8 Nov 2026):** a start that comes back `joined: true` shows an alert ("Another session is
  running") with "Stop it and start mine" (stop that id, restart with a new startId) or "Keep it" (close the run).
  The joined id never gets this run's actions. Commit `499ca28`; UI flag `--uitesting-joined-session`.
- **Planner 9 Nov 2026 (backend `feature/recovery-rhythm`, dev only):** zone `level` is Capacity's openingBand
  (89/79/68/57); the stub day is score 72 (steady, 2 h heavy). Find a time is built in AuraKit (`allocate`, `plan`,
  recovery blocks, parts, warnings, `TaskItem.autoAllocate`, `StubAPI(needsQuickCheck:)`). The UI went where the
  user chose: a Find a time button under Calendar's zone line, a preview sheet, then Add to calendar (commit 52eb7f0,
  CI green, screenshots 50-52).
- **Quick check 9 Nov 2026:** the user first said not to build new screens from the design, then "ok build quick
  check too" once it was clear the Planner gates today's plan on it. Built from the concepts design's reaction and
  memory frames, but the reaction step is the backend's 3-minute PVT-B (the design's "3 tries" wouldn't be scored),
  steps read 1 OF 2 / 2 OF 2 (no rule-switch frame), no heart recording. Commits c10d50c (AuraKit: submitCheck,
  ReactionTest, SpanTest) and 55d2702 (screens in App/Check, opened from Find a time). invalidReason comes as server
  codes (not_3_minutes, too_few_responses, too_many_false_starts) mapped to words on the result screen.
- **Find a time for later days (9 Nov 2026):** day chips in the sheet (Today, Tomorrow, 5 more); a later day has no
  Quick check gate. The stub plans "Book the dentist" 15:00-15:30 on a later day. Commit c3b18e6, CI green
  (222 AuraKit, 18 UI tests), screenshots 57-58.
- **Beta day score (10 Nov 2026, backend `feature/beta-day-score`, dev only):** the phone supplies difficulty, recovery
  breaks and sleep. The user chose: rating on the Done sheet goes by PUT /difficulty after the end action; minimal
  screens (Work page sessions list + review inbox, morning check-in card on Home); HealthKit now (read sleepAnalysis,
  unproven on a device). Every recovery-kind mode sends a `recoveryType` (Mode.recoveryType) or dev answers 400.
  The "You rated it Hard." line is Capacity's ledger detail (GET /v1/capacity/today), not in any session answer.
  Commits f9a70bb (AuraKit) and 257b6dd (screens). UI flags `--uitesting-checkin`, `--uitesting-health-night`;
  screenshots 33, 59-68. Then eff675e (difficulty chips in a 3-column grid so "Very hard" fits) and 888b455 (Recovery
  walk taps the Now card with `open(_:until:)` after margin drags; it had flaked). CI green 11 Nov, run 42857010219.
  A one-off "Failed to terminate" in setUp is a simulator flake, not a code fault.

**Not proven yet:** sign-in and every live call have only been tested against stubs. The first live check needs a
rented Mac (Scaleway M4-S, about €5.28 a day, 24-hour minimum, delete it afterwards), and no signing keys go on it.
`aura-dev.json` uses client `54fbmqoiqktq2c9k202s234h5v`, which `/v1/config` listed; `cognito-sign-in.md` has a
different id. If it can't sign in, fall back to the public Windows client.

**How to apply:**
- Commit and push only to `ios-app`, as dctn with no Co-Authored-By lines, and check `.github/workflows` holds only
  `ios.yml` first.
- Compare screenshots with the Figma after every UI change, because a green run doesn't show layout problems.
- XCUITest's `app.swipeUp()` starts mid-screen and can land on Home's mood slider. Drag in the page margin instead.
- TestFlight waits for the user's Apple Developer account. Signing would run in GitHub Actions, with the key as a
  protected secret.
- See [[demo-website]] for where the modes came from and [[dev-backend-state]] for the backend.
