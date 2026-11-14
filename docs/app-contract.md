# Aura Clarus Android: the app contract

The shell (design system, navigation, sign-in, settings, token store) that every screen agent builds on. It is a
port of the SwiftUI app in `aura_ios_app/App/` (read only). Read this before writing a screen; change a shell file
only by agreement, so parallel branches merge cleanly.

- `:core` (`info.ibtaura.aura.core`): models, `AuraApi`, `AuthService`, stubs. Don't edit it from screen work.
- `:app` (`info.ibtaura.auraclarus`): everything below, under `app/src/main/kotlin/info/ibtaura/auraclarus/`.

## Who owns what

| Folder / file | Owner | Notes |
|---|---|---|
| `design/`, `shell/`, `signin/`, `settings/`, `store/`, `MainActivity.kt` | shell | Ask before changing; add new shared components in your own folder first. |
| `home/HomeScreen`, `CalendarScreen`, `TasksScreen`, `EventEditorScreen` | home agent | |
| `recovery/`, `run/` (incl. `RunWiring.kt`, `MiniRunBar`, `OtherSessionAlert`, `RunScreen`) | recovery+run agent | |
| `search/`, `metric/` | search+metric agent | |
| `check/QuickCheckScreen`, `home/FindATimeSheet` | check agent | |
| `home/SessionsScreen`, `home/ReviewInboxScreen`, `health/` (incl. `HealthWiring.kt`) | beta-score+health agent | |
| `shell/StubWiring.kt`, `shell/LiveWiring.kt` | whoever merges core | Then delete `OfflineServices.kt` and the catch in `Services.kt`. |

Each placeholder file already has its final signature, registered in navigation. Replace the body; keep the
signature. Add private files freely inside your own folder. Don't create files in another agent's folder.

## Screen signature

Every screen is `@Composable fun XScreen(app: AppState, nav: AuraNav)`, plus `route: Route.X` when the route carries
arguments. Screens never build services; they use `app.api` and the helpers below.

```kotlin
@Composable
fun MetricScreen(app: AppState, nav: AuraNav, route: Route.Metric) { ... }
```

## AppState (`shell/AppState.kt`), the port of AppModel.swift

One instance for the app's life (held by `AppHolder`, an AndroidViewModel). Every field is Compose state: reading it
in a composable recomposes on change. Mutate on the main thread (screen coroutines already are).

| Member | Use |
|---|---|
| `api: AuraApi`, `auth: AuthService`, `flags: LaunchFlags`, `modes: List<Mode>` | backend, sign-in, UI-test switches, the 65 modes |
| `quickCheckShort` | `flags.shortCheck` |
| `run: RunPort?`, `sleep: SleepPort?`, `health: HealthPort?` | ports, null until that area is built (see Ports) |
| `authState: AuthState?` | null while restoring, then `SignedOut` / `SignedIn(email)` |
| `needsConsent` | true shows the consent sheet; set it when a call throws `AuraError.ConsentRequired` via `note(e)` |
| `dataVersion: Int` | **key every load on it**: `LaunchedEffect(app.dataVersion) { load() }`. Bumped after consent, after a save that changes other screens, on a new day. Bump it (`app.dataVersion += 1`) after your own such saves. |
| `runExpanded` | true shows the run cover |
| `pins: List<PinnedMetric>`, `isPinned(field)`, `togglePin(metric)` | metric pins (saved on the phone) |
| `healthConnected`, `sleepSentToday`, `morningCheckinSent`, `markMorningCheckinSent()` | day flags (saved) |
| `catalogue`, `suspend loadCatalogue(force = false)` | Search catalogue, cached |
| `workSchedule`, `workScheduleLoaded`, `suspend loadWorkSchedule()`, `suspend saveWorkSchedule(s)` | work hours; save bumps `dataVersion` |
| `rememberEvents(list)`, `event(id)` | hand a `CalendarEvent` to `Route.EventEditor(eventId)` without serialising it |
| `mode(id)`, `modeForAlert(ref)` | mode lookup (`nap`, `recovery`, `breathing`, `move` alert refs) |
| `startRun(mode, minutes, title?, recoveryType?)` | starts a run through `app.run` and opens the run cover; no-op while `run` is null |
| `launch { }` | work that must outlive the screen (app scope) |
| `note(error)` | **every caught error goes here**: `SignedOut` signs out, `ConsentRequired` shows consent, others are ignored (show your own error text) |
| `suspend connectHealth()`, `syncSleepIfNeeded()`, `syncHealthIfNeeded()`, `sendTimeZoneIfNeeded()`, `reloadIfNewDay()` | called by RootScreen on launch/resume; screens rarely need them |
| `suspend restore()`, `suspend signOut()`, `suspend grantConsent()` | shell only |

Error pattern in a screen:

```kotlin
try { data = app.api.something() }
catch (e: CancellationException) { throw e }
catch (e: Throwable) { failed = true; app.note(e) }
```

## Ports (`shell/Ports.kt`) and wiring factories

Interfaces for parts built by other agents. Each is filled once at launch by `installPorts` from that area's factory.

| Port | Factory | Owner |
|---|---|---|
| `RunPort` (`hasActive`, `showsMiniBar`, `onError`, `start(...)`, `close()`, `suspend endForSignOut()`) | `run/RunWiring.kt` `makeRunPort(app, context)` | recovery+run |
| `SleepPort` (`suspend requestAccess()`, `suspend lastNight(from, to): SleepNight?`) | `health/HealthWiring.kt` `makeSleepPort(context, flags)` | beta-score+health |
| `HealthPort` (`suspend isConnected()`, `suspend syncRecentDays()`) | `health/HealthWiring.kt` `makeHealthPort(context, app)` | beta-score+health |

Implementations keep observable fields in `mutableStateOf`. In UI tests (`flags.isUiTesting`) return stubs, never
the real Health Connect. `run/` may cast `app.run` to its own class; other areas use only the interface.

## Navigation (`shell/Nav.kt`)

Three tabs (`Tab.Home`, `Tab.Recovery`, `Tab.Search`), each with **its own back stack** kept across tab switches.
Routes are `@Serializable` (type-safe Navigation Compose 2.10).

| `nav.` | Does |
|---|---|
| `push(route)` | push on the current tab |
| `pop(): Boolean` | pop the current tab; false at its root (system back does the same) |
| `popToRoot()` | back to the tab's first page |
| `selectTab(tab)` | show a tab; reselecting pops it to root, as iOS |
| `tab` | the selected tab |
| `openRun()` / `closeRun()` | show/hide the run cover (`app.runExpanded`) |
| `openQuickCheck(onFinished)` / `finishQuickCheck(result)` / `quickCheckOpen` | the Quick check cover; `QuickCheckScreen` calls `finishQuickCheck` |

Route to composable (registered in `shell/RootScreen.kt` `auraDestinations`):

| Route | Composable |
|---|---|
| `Home`, `Recovery`, `Search` | `HomeScreen`, `RecoveryScreen(pushed = false)`, `SearchScreen` (tab roots) |
| `Calendar`, `Tasks`, `Sessions`, `ReviewInbox` | `home/` screens |
| `EventEditor(eventId?)` | `EventEditorScreen(route)`; null id = new event |
| `FindATime(date)` | `FindATimeSheet(route)`; on confirm `app.dataVersion += 1; nav.pop()` |
| `Settings`, `WorkHours` | `SettingsScreen`, `WorkHoursScreen` (done) |
| `Metric(name, field, unit, format?, backTitle)`, `Recorded(...)`, `Sources(name)` | `metric/` screens |
| `ModeDetail(modeId, headline?)` | `ModeDetailScreen(route)` |
| `Modes` | `RecoveryScreen(pushed = true)` (Search > Modes, with a back bar) |
| `SearchCategory(sectionId, title)` | `SearchCategoryScreen(route)` |
| `NotDesigned(title)` | `NotDesignedYet` (pages the Figma lacks) |

Need a new route? Ask the shell owner to add it to `Route` and `auraDestinations` rather than adding it yourself, so
two branches don't both edit those files. Until then, push `Route.NotDesigned("...")`.

**Rules**
- iOS sheets that are whole pages (Work hours, Find a time, Event editor) are **pushed pages** here. Small sheets
  inside one screen (break picker, rating, Done, confirm) stay local: `AuraSheet` or `AuraConfirmDialog`.
- iOS `fullScreenCover`s are the two covers (run, Quick check). They sit above the tabs, block touches beneath, and
  take system back (back closes the run cover; on the Quick check it calls `nav.finishQuickCheck(null)`).
- The mini run bar shows under the content when `app.run?.showsMiniBar == true && !app.runExpanded`.
- The consent sheet is shown by the shell; screens only call `app.note(e)`.
- Home reaches Settings with `nav.push(Route.Settings)`; Calendar reaches Work hours with `nav.push(Route.WorkHours)`.

## Design system (`design/`)

Light only, iOS look. Use these instead of Material defaults.

- `AuraColors`: every iOS colour (`blue` #108DFD, `background`, `gray`, `burnout`, `red`, `track`, shelf colours,
  ...), `Shelf.color`, `Shelf.symbol`, `SearchCategoryStyle.of(sectionId)`.
- `AuraType`: `title` (30 bold), `heading` (20 bold), `row`, `pill`, `field`, `body`/`bodyMedium` (17),
  `callout`/`calloutMedium`/`calloutSemibold` (15), `caption` (13), `tab`, and `system(size, weight, color)`.
- `auraIcon("sf.symbol.name")`: SF Symbol names used by the iOS app mapped to Material icons (falls back to a
  circle). Add missing names to the map in `AuraIcons.kt` (append-only, small conflicts).
- `ZoneText`: `clock`, `range`, `hours`, `days`, `schedule`, as iOS ZoneText.

Components (`design/Components.kt`):

| Component | When |
|---|---|
| `AuraPage(title?, onBack?, trailing, content)` | any scrolling page; with both `title` and `onBack` it shows the `BackBar` (white circle chevron + 30pt title, tag `back`). Pads for the tab bar. |
| `AuraLazyPage(...)` | long lists |
| `AuraCard(padding = 24, spacing = 12)` | white radius-30 card |
| `SectionTitle`, `Footnote(text, linkTitle, onLink)`, `CardDivider` | text pieces |
| `PillButton(title, onClick, style = Primary/Secondary, fills, enabled)` | 48dp pill buttons |
| `ListRowCard(title, symbol, symbolColor, value, unit, showsChevron, onClick)` | Home/metric rows |
| `SearchCapsule`, `SearchGroupCard`, `SearchGroupRow`, `SearchRowDivider` | Search |
| `AuraTextField`, `AuraMenuPicker(options, selected, label, onSelect, tag)` | inputs; picker options are tagged `<tag>-option-<label>` |
| `IconTile`, `ModeTile(mode)`, `GlassCircleButton`, `Chevron` | icons |
| `MetricEmptyState`, `MetricRetryState(onRetry)` (tag `metric-retry`), `LoadingPage`, `NotDesignedYet` | states |
| `WorkHoursRow(title, value, onClick)` | work-hours summary row |
| `AuraSheet(onDismiss, dismissible)` | in-screen bottom sheet |
| `AuraConfirmDialog(title, message, confirmTitle, onConfirm, onDismiss, destructive, confirmTag)` | confirmations |
| `LocalBottomInset` / `bottomPadding()` | space for the tab bar + mini bar; `AuraPage` already applies it |

## Test tags and walks

- Tag elements with the **iOS accessibility identifier**: `Modifier.testTag("settings-sign-out")`. MainActivity sets
  `testTagsAsResourceId`, so UiAutomator finds `By.res(tag)`. `AuraSheet` and `AuraConfirmDialog` set it again for
  their own windows; a new `Dialog`/`Popup` must do the same (`Modifier.semantics { testTagsAsResourceId = true }`).
- Shell tags: `tab-home`, `tab-recovery`, `tab-search`, `back`, `run-cover`, `quick-check-cover`, `mini-run-bar`,
  `signin-*`, `settings-work-hours`, `settings-sign-out`, `settings-sign-out-confirm`, `work-hours-*`, `work-day-1..7`,
  `consent-not-now`, `consent-allow`, `coming-soon-<title>`.
- UI-test flags (`shell/LaunchFlags.kt`), same strings as iOS, passed as the intent extra `aura.flags`:
  `--uitesting` (stub data, own prefs file), `--uitesting-short-run`, `--uitesting-no-schedule`,
  `--uitesting-joined-session`, `--uitesting-needs-check`, `--uitesting-short-check`, `--uitesting-health-night`,
  `--uitesting-checkin`.
- Walks live in `app/src/androidTest/kotlin/info/ibtaura/auraclarus/`, one file per area (`HomeWalkTest.kt`, ...).
  Extend `WalkTestCase` (launches with `--uitesting`; override `flags`) and use `Walk.find/tap/tapText/type/exists/
  waitUntilGone/openTab/back/dragPage/bringIntoView/open` and `Shots.save(name)`.
- Screenshot names follow the iOS walks (`aura_ios_app/docs/screenshots/2026-11-10_888b455/`): `00-launch`;
  `01-home`, `02-recovery`, `03-search`; 10–18 Home/Calendar/Settings/Work hours (`14-settings`, `15-work-hours`,
  `17-work-hours-new`); 20–34 Recovery and runs; 40–48 Search and metrics; 50–58 Find a time and Quick check;
  59–62 breaks; 63–66 sessions and review; 67–68 check-in; 69–72 event editor.
- No emulator locally; CI runs the walks.

## Build

```sh
cd /d/intellj/aura/aura_mobile/aura_android_wt_shell && . /d/intellj/android/env.sh && \
./gradlew --console=plain --no-daemon -Dorg.gradle.jvmargs=-Xmx2g -Pkotlin.compiler.execution.strategy=in-process \
  :app:assembleDebug :app:assembleDebugAndroidTest
```

## Security

- Tokens: `store/KeystoreTokenStore` (AES-256-GCM, key in Android Keystore, prefs `aura_tokens` excluded from backup
  and device transfer). Never log tokens or put them in files.
- Live wiring talks only to dev us-west-1.

## Temporary until core is merged

`StubWiring.kt` and `LiveWiring.kt` are `TODO()`; `makeServices` falls back to `OfflineApi`/`OfflineAuth`, so the app
opens on Sign in and every call fails with `AuraError.Offline`. `LaunchWalkTest` checks only that Sign in shows; move
it to `WalkTestCase` once stub wiring lands.
