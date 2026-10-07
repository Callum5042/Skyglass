# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project state

Skyglass is a native Android stargazing app (Kotlin, Jetpack Compose, OpenGL ES 3.0). Phase 1 is implemented: naked-eye stars (V ≤ 6.5, ~8,400) from the Yale Bright Star Catalogue are drawn on a black background in their true positions as the phone is pointed at the sky, using the rotation vector sensor, location, current time and magnetic declination. Also built: constellation lines and names, tap-to-identify (stars by name/designation, and the bodies below), and the Sun, Moon and the seven other planets (Mercury–Neptune), positioned for the current time and drawn distinctively, with the Moon showing its phase. Not yet built: zoom, night (red) mode, planetary moons (the Galilean moons sit within 0.2° of Jupiter, indistinguishable at this field of view until there is zoom), asteroids/comets, horizon/daylight sky.

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
- **Sun, Moon and planets** use the same frame and MVP as the stars but move, so `SolarSystem.positions(julianDateUtc, lat, lon)` (pure Kotlin, `astro/`) is recomputed by `SkyViewModel` on every location fix and every 10 s while resumed, packed by `catalog/BodyVertices` into a separate small VBO and handed over through `SkyRenderInputs.bodyVertices` (the GL thread re-uploads when the array instance changes). Accuracy model:
  - Everything is the *mean* equator and equinox of date, like the stars: no nutation and no annual aberration (each < 20″; the stars omit them too, so everything stays in step). Time is UTC + 69.184 s (`AstroTime.julianEphemerisDay`) for the ephemerides.
  - Sun: Meeus ch. 25 (`SunEphemeris`, ~0.01°), geometric longitude (the Sun is at rest in the heliocentric frame, so this already includes light time). Moon: Meeus ch. 47, full 60+60 term series (`MoonEphemeris`, ~10″), geocentric then made **topocentric** by `Parallax` (vector method; the Moon's parallax is up to ~0.95°, so its direction depends on latitude and local sidereal time). Planets: JPL/Standish Keplerian elements for 1800–2050 (`PlanetEphemeris`; Standish quotes 15–40″ for Mercury–Mars but up to ~7′ for Jupiter and ~10′ for Saturn, i.e. 3–6 px at the current field of view, so **zoom will need a better theory** such as VSOP87), Earth taken as the Earth–Moon barycentre, three light-time iterations, J2000 ecliptic → J2000 equator → `Precession`. Planet parallax (< 0.003°) is ignored. Magnitudes are the Astronomical Almanac formulae (Saturn includes the ring tilt).
  - Rendering: `render/BodyShaders` draws point sprites with a per-kind look (`catalog/BodyAppearance`: planets tinted discs, Sun white-hot core and big glow, Moon a lit sphere). The Moon's lit side is computed on the CPU each frame by `SkyViewMath.spriteLight` (pure, unit-tested, including all display rotations) and passed as the `uMoonLight` uniform: sprite space is screen right/up with +z toward the viewer, z = −cos(elongation), lateral = sin(elongation) toward the Sun as projected on screen. The body pass uses premultiplied-over blending (`GL_ONE, GL_ONE_MINUS_SRC_ALPHA`): glows add, and the Moon is opaque only within its *true* angular radius (`uMoonOccluder`), because its drawn disc is ~4× too big and must not hide nearby stars or planets. Sprite sizes are exaggerated (the true Sun/Moon are ~7 dp). Known limitation: GL drops a point sprite once its centre leaves the screen, so the large Sun and Moon sprites vanish abruptly at the edge; fixing that needs quads instead of points. Tap reach is clamped to the GPU's maximum point size (`SkyRenderInputs.maxPointSizePx`). User-visible body and phase names live in `strings.xml` (`ui/BodyNames.kt` maps the enums).
  - `SkyBody` enum order is draw order (Moon last). Tap-to-identify prefers a body over a star when it is as close (`SkyHitTest.nearestBody`); labels and the selection ring come from the same `BodyPosition`s in `SkyUiState.bodies`.
- **Frame conventions** (pinned by unit tests; sign errors are the main risk when changing this code):
  - Equatorial: +X vernal equinox, +Z north celestial pole.
  - ENU: +X east, +Y north, +Z up. The rotation vector sensor's world frame is ENU with *magnetic* north; `Coordinates.magneticToTrueEnu` corrects it (declination positive east, as `GeomagneticField` reports).
  - Device (portrait) = OpenGL eye frame: +X right, +Y up, +Z out of the screen; the back of the phone looks down −Z.
  - Azimuth is measured from north through east (Meeus measures from south; tests note the conversion).
- Packages:
  - `math/`, `astro/`, `catalog/` (except `StarCatalog`), `sensors/OrientationFilter`, `render/SkyViewMath`: pure Kotlin with no Android calls, so they are covered by JVM unit tests. Keep new math there. (`BodyAppearance`/`BodyVertices` in `catalog/` and `BodyShaders` in `render/` are likewise Android-free strings/arrays.)
  - `sensors/`: `OrientationSensor` (TYPE_ROTATION_VECTOR → `AtomicReference<Quaternion>`), `LocationSource` (framework `LocationManager`, no Play Services; requests GPS directly with one listener per provider), `MagneticDeclination` (`GeomagneticField` wrapper).
  - `render/`: `SkyRenderer` (GL thread) smooths orientation once per frame with a quaternion slerp low-pass (`OrientationFilter`, time-constant based so it is frame-rate independent) and draws constellation lines, then stars as `GL_POINTS` sprites with additive blending, then the Sun/Moon/planets. `SkyRenderInputs` holds `@Volatile` values handed from the main thread.
  - `ui/`: `SkyViewModel` (catalogue load, location → declination, body updates, tap selection, UI state) and `SkyScreen` (permission request, `GLSurfaceView` in `AndroidView`, status overlay). Sensors, location and the GL view are started and stopped via `LifecycleResumeEffect`.
- Star catalogue: `app/src/main/assets/bsc5.dat` is the unmodified CDS V/50 `catalog` file (fixed-width; trailing blanks trimmed, so `BscParser` pads lines). Its pmRA is already projected (cos δ · dα/dt). Visual tuning (sizes, intensity, B−V colours) is all in `catalog/StarAppearance`.
- The activity is locked to portrait; Android 16+ ignores that on large screens, which `SkyViewMath.deviceToScreen` handles.
- Tests: `app/src/test` covers the coordinate math, with expected values taken from Meeus, *Astronomical Algorithms*, worked examples where possible (Sun 25.a, Moon 47.a, Venus 33.a, parallax 40.a, ecliptic→equatorial 13.a, obliquity 22.a), real-world checks (the 12 Aug 2026 total solar eclipse from Burgos, 2024–25 planetary oppositions, Venus's greatest elongation), plus parsing of the real bundled catalogue. `app/src/androidTest` covers asset loading and `GeomagneticField`'s sign convention.
