# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project state

Skyglass is a native Android stargazing app (Kotlin, Jetpack Compose, OpenGL ES 3.0). Phase 1 is implemented: naked-eye stars (V ≤ 6.5, ~8,400) from the Yale Bright Star Catalogue are drawn on a black background in their true positions as the phone is pointed at the sky, using the rotation vector sensor, location, current time and magnetic declination. Not yet built: constellation lines, labels, planets/Moon/Sun, zoom, night (red) mode.

## Commands

All commands run from the repo root using the Gradle wrapper (`gradlew.bat` on Windows / `gradlew` in Git Bash).

- Build debug APK: `./gradlew assembleDebug`
- Full build (all variants, checks): `./gradlew build`
- Install on a connected device/emulator: `./gradlew installDebug`
- Run unit tests (JVM, `app/src/test`): `./gradlew test`
- Run a single unit test class: `./gradlew test --tests "com.skyglass.astro.CoordinatesTest"`
- Run a single unit test method: `./gradlew test --tests "com.skyglass.astro.CoordinatesTest.meeusExample13b_venusFromWashington"`
- Run instrumented tests (`app/src/androidTest`, needs a connected device/emulator): `./gradlew connectedAndroidTest`
- Lint: `./gradlew lint`
- Clean build outputs: `./gradlew clean`

Gradle is configured with the configuration cache enabled (`org.gradle.configuration-cache=true` in `gradle.properties`), and the daemon JVM toolchain is pinned to version 25 via `gradle/gradle-daemon-jvm.properties`.

## Architecture

- Single module: `app` (`applicationId`/`namespace` = `com.skyglass`). `minSdk` 28, `targetSdk`/`compileSdk` 37, Kotlin 2.2.10, AGP 9.3.3. Versions live in `gradle/libs.versions.toml`; add dependencies there via the version catalog (`libs.*`), not as inline coordinates.
- **Rendering approach:** each star is converted once at load (proper motion + IAU 1976 precession) to a unit vector in the equatorial frame of date and uploaded to a static VBO. Each frame the GPU applies a single MVP built by `render/SkyViewMath`:
  `clip = P · S(displayRotation) · R(q)ᵀ · M(true→magnetic) · H(LST, lat) · v_eq`.
- **Frame conventions** (pinned by unit tests; sign errors are the main risk when changing this code):
  - Equatorial: +X vernal equinox, +Z north celestial pole.
  - ENU: +X east, +Y north, +Z up. The rotation vector sensor's world frame is ENU with *magnetic* north; `Coordinates.magneticToTrueEnu` corrects it (declination positive east, as `GeomagneticField` reports).
  - Device (portrait) = OpenGL eye frame: +X right, +Y up, +Z out of the screen; the back of the phone looks down −Z.
  - Azimuth is measured from north through east (Meeus measures from south; tests note the conversion).
- Packages:
  - `math/`, `astro/`, `catalog/` (except `StarCatalog`), `sensors/OrientationFilter`, `render/SkyViewMath`: pure Kotlin with no Android calls, so they are covered by JVM unit tests. Keep new math there.
  - `sensors/`: `OrientationSensor` (TYPE_ROTATION_VECTOR → `AtomicReference<Quaternion>`), `LocationSource` (framework `LocationManager`, no Play Services; requests GPS directly with one listener per provider), `MagneticDeclination` (`GeomagneticField` wrapper).
  - `render/`: `SkyRenderer` (GL thread) smooths orientation once per frame with a quaternion slerp low-pass (`OrientationFilter`, time-constant based so it is frame-rate independent) and draws `GL_POINTS` sprites with additive blending. `SkyRenderInputs` holds `@Volatile` values handed from the main thread.
  - `ui/`: `SkyViewModel` (catalogue load, location → declination, UI state) and `SkyScreen` (permission request, `GLSurfaceView` in `AndroidView`, status overlay). Sensors, location and the GL view are started and stopped via `LifecycleResumeEffect`.
- Star catalogue: `app/src/main/assets/bsc5.dat` is the unmodified CDS V/50 `catalog` file (fixed-width; trailing blanks trimmed, so `BscParser` pads lines). Its pmRA is already projected (cos δ · dα/dt). Visual tuning (sizes, intensity, B−V colours) is all in `catalog/StarAppearance`.
- The activity is locked to portrait; Android 16+ ignores that on large screens, which `SkyViewMath.deviceToScreen` handles.
- Tests: `app/src/test` covers the coordinate math, with expected values taken from Meeus, *Astronomical Algorithms*, worked examples where possible, plus parsing of the real bundled catalogue. `app/src/androidTest` covers asset loading and `GeomagneticField`'s sign convention.
