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
