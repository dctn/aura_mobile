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
comes from the morning capacity score, which the Planner reads from Capacity itself, so the app never asks for the score.
A day with no score shows "No score yet", and with no work hours it shows "Set work hours". Work hours are edited in a
sheet opened from Calendar or Settings. UI tests pass `--uitesting-no-schedule` to start with none.
