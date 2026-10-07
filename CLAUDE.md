# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project state

Skyglass is a native Android app scaffolded from Android Studio's default "Empty Activity" (Jetpack Compose) template. At present it contains only the generated template code (`MainActivity`, the `ui.theme` package, default resources) — no app-specific features have been implemented yet. There is no `README.md` and this is not yet a git repository.

## Commands

All commands run from the repo root using the Gradle wrapper (`gradlew.bat` on Windows / `gradlew` in Git Bash).

- Build debug APK: `./gradlew assembleDebug`
- Full build (all variants, checks): `./gradlew build`
- Install on a connected device/emulator: `./gradlew installDebug`
- Run unit tests (JVM, `app/src/test`): `./gradlew test`
- Run a single unit test class: `./gradlew test --tests "com.skyglass.ExampleUnitTest"`
- Run a single unit test method: `./gradlew test --tests "com.skyglass.ExampleUnitTest.addition_isCorrect"`
- Run instrumented tests (`app/src/androidTest`, needs a connected device/emulator): `./gradlew connectedAndroidTest`
- Lint: `./gradlew lint`
- Clean build outputs: `./gradlew clean`

Gradle is configured with the configuration cache enabled (`org.gradle.configuration-cache=true` in `gradle.properties`), and the daemon JVM toolchain is pinned to version 25 via `gradle/gradle-daemon-jvm.properties`.

## Architecture

- Single module: `app` (`applicationId`/`namespace` = `com.skyglass`). `settings.gradle.kts` only includes `:app`.
- `minSdk` 28, `targetSdk`/`compileSdk` 37, Kotlin 2.2.10, AGP 9.3.3 — versions are centralized in `gradle/libs.versions.toml` and referenced via the version catalog (`libs.*`) in both `build.gradle.kts` files. Add new dependencies there, not as inline coordinates.
- UI is 100% Jetpack Compose (no XML layouts, no Fragments/Views). `MainActivity` is the sole activity and sets its content with `setContent { SkyglassTheme { ... } }`, using `Scaffold` for the root layout.
- `com.skyglass.ui.theme` holds the Compose theme (`Theme.kt` — `SkyglassTheme` composable, `Color.kt`, `Type.kt`) following the standard Compose Material3 template structure (light/dark color schemes, dynamic color support).
- Tests follow standard Android project layout: `app/src/test` for local JVM unit tests (JUnit4), `app/src/androidTest` for instrumented tests (JUnit4 + Espresso + Compose UI test), both currently containing only template placeholder tests.
