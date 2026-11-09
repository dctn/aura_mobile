# Aura Clarus for iPhone

Native SwiftUI app (iOS 26) built from the Figma exports in `../figma_new_design`. It reads the **dev** backend in
us-west-1.

This repo is written on Windows. GitHub Actions builds it on every push to `ios-app`, runs the tests on stub data and
saves screenshots under the run's `ios-results` artifact.

## On a Mac
```
brew install xcodegen
xcodegen generate
open AuraClarus.xcodeproj
```
The `.xcodeproj` is generated from `project.yml`, so change `project.yml` and never the project. Xcode 26.0 or newer.

## Layout
- `Packages/AuraKit`: logic with no UI (API, sign-in, run engine, sums). `swift test` runs it.
- `App`: the screens. `DesignSystem` holds the shared colours and parts.
- `UITests`: walks the screens on stub data (`--uitesting`), with no network or sign-in.

## Before the first live check
Everything so far has run on stub data, so sign-in and the live calls are unproven. The app talks only to dev us-west-1
and writes to it as a normal user: sessions, mood, tasks, consent, the profile's time zone when it has none,
and the work hours when the person saves the Work hours form (`PUT /v1/me/profile` with `workSchedule` only).

## Day zones
Home → Work → Calendar shows today's zones from `GET /v1/planner/zones`: heavy work, light work, short recovery and
heavy recovery, shaded behind the hours. The Planner places them inside the profile's work hours. The amount of heavy work
comes from the morning capacity band (Capacity's `openingBand`: peak 89+, focused 79, steady 68, shallow 57, depleted
under 57, since 9 Nov 2026), which the Planner reads from Capacity itself. The app shows `level` and `heavyMinutes`
as sent and never works them out from the score.
A day with no score shows "No score yet", and with no work hours it shows "Set work hours". Work hours are edited in a
sheet opened from Calendar or Settings. UI tests pass `--uitesting-no-schedule` to start with none.

## A session already running
The backend keeps one running session per person. When a run's start answers `joined: true`, the session belongs to
another run, maybe on another device. The app doesn't adopt it: an alert names it ("“Deep work” on your computer is
still going, until 16:30") and offers "Stop it and start mine" (stops that session, then starts this run's own with a
new id) or "Keep it" (this run closes on the phone and nothing is sent). UI tests pass `--uitesting-joined-session`.

## Find a time
On Home → Work → Calendar, the **Find a time** button under the zone line opens a preview of today's plan
(`allocate(dryRun: true)`, which writes nothing). **Add to calendar** runs it for real and the page reloads with the
blocks on the hour grid. Allocation places tasks marked `autoAllocate`, using the profile's work hours as the day
window (otherwise the Planner uses 09:00-18:00). With work hours, a morning band and a work day, the plan follows the
recovery rhythm: zones are kept free, a 10-min `recovery` block (green) comes after deep work, a task can be split
into "Title (1/2)" and "Title (2/2)", and `warnings` counts the person's own blocks that sat on recovery time. Tasks
that didn't fit are listed with the Planner's reason. For today the Planner wants a valid Quick check first (409
`quick_check_required`): the sheet offers **Do the Quick check**, **Plan anyway** (retry with `skipCheck`) or Not now.

## Quick check
Opened from Find a time. Dark full screen, after the concepts design: an intro, then the reaction test (the 3-minute
PVT-B the backend scores: tap the card when it turns green; a dot every 1-4 s, about 62 in all), then the memory part
(a row of digits to type back, one longer after each right answer; Skip sends reaction only). It goes to Health Data
`POST /v1/checks` (`kind` pvtB, or full with memory) and the result says whether it counts. A check counts only if
it ran 170-200 s with 30+ answers and at most 20% early taps. A valid check lets the preview plan with it. Leaving the
app mid-reaction starts the test again. The rule-switch part (the design's step 3) and the heart recording aren't
built. UI tests use `--uitesting-needs-check` and `--uitesting-short-check` (an 8-second run that the stub accepts).

