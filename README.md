# Aura Clarus for Android

Native Kotlin + Jetpack Compose app, the Android version of the iPhone app in `../aura_ios_app`. It looks like the
iOS app and the Figma exports in `../figma_new_design`, and reads the **dev** backend in us-west-1.

GitHub Actions builds it on every push to `android-app`, runs the tests on stub data, walks the screens on an
emulator and saves screenshots and a debug APK under the run's `android-results` artifact.

## On this PC
The JDK, Android SDK and Gradle caches live in `D:\intellj\android`. In Git Bash:
```
. /d/intellj/android/env.sh
./gradlew :core:test :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
`local.properties` (not in git) points at the SDK: `sdk.dir=D\:/intellj/android/sdk`.
Android Studio can open this folder; point it at the same SDK.

## Layout
- `core`: logic with no Android in it (API, sign-in, run engine, sums), the counterpart of iOS `AuraKit`.
  `./gradlew :core:test` runs it on the JVM in seconds.
- `app`: the screens (Compose) and the Android parts (token store, Health Connect, notifications).
- `app/src/androidTest`: walks the screens on stub data, with no network or sign-in.

## Versions
Android 9 (API 28) and newer. Compiled against API 37, targets API 36. AGP 9 (Kotlin built in), Kotlin 2.4,
Gradle 9.9.1. Package `info.ibtaura.auraclarus`.
