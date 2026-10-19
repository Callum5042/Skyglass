# Skyglass design notes

What the code cannot tell you: why each feature is built the way it is, the traps, where the data comes from and under what licence, and what is not built. The code is the reference for everything else. Everything here has been tested on a phone (Samsung Galaxy S26+). Read a feature's section before changing it, and keep each entry to the decision and its reason, with no history.

## Settings

SharedPreferences `display_settings`, written at once (the slider when the drag ends) and included in backups: `limiting_magnitude`, `show_horizon`, `show_grid`, `show_deep_sky`, `show_milky_way`, `show_constellations`, `show_constellation_art`, `night_mode`. `camera_permission_state` is separate and excluded from backup and device transfer. All the switches and the slider live in the Display card, because the top bar has no room for more.

## Top bar

"Tonight" with its name on the left, then Search, Display and the camera view as 48 dp icon buttons on the right: four labelled buttons don't fit across 360 dp, and Tonight keeps its label because it is where a beginner starts. The glyphs are drawn on a `Canvas` (`TopBar`), so there is no icon dependency. An open panel or the camera view gets a lit border. Only one of Tonight, Search and Display is open at a time, so opening one closes the others; Tonight and Search share the place under the bar (12 dp below the buttons) and hide the details card.

## Sun, Moon and planets

- Recomputed (`SolarSystem.positions`) on every location fix and every 10 s, into their own VBO (`SkyRenderInputs.bodyVertices`, re-uploaded when the array instance changes).
- Accuracy: mean equator and equinox of date, with no nutation or aberration (each under 20″, and the stars leave them out too). Sun: Meeus ch. 25 (~0.01°). Moon: Meeus ch. 47 full series (~10″), made topocentric by `Parallax` (up to ~0.95°). Planets: Standish Keplerian elements for 1800–2050, with Earth taken as the Earth–Moon barycentre and three light-time iterations. They are good to 15–40″ for Mercury–Mars but only ~7′ for Jupiter and ~10′ for Saturn, so **zoom will need a better theory (VSOP87)**. Magnitudes use the Astronomical Almanac formulae; Saturn's includes the ring tilt.
- Drawn as screen-aligned quads (`BodyVertices`, six vertices each), not point sprites: a sprite vanishes once its centre leaves the screen and can't grow past the GPU's largest point size, which the true-size Moon at 1° (~1,200 px) would. At the default view the sizes are exaggerated (the true Sun and Moon would be ~7 dp). Zoomed in, the disc is the nominal or the true size, whichever is larger, and the halo keeps its nominal width outside it (`BodyAppearance.drawn`, repeated in `BodyShaders`), so nothing changes at 1× and the growth is continuous. The shader's edges are in pixels, so a huge disc still ends crisply; the Moon's terminator narrows as it grows.
- The Moon's lit side comes from `SkyViewMath.spriteLight`, computed on the CPU each frame. The Moon is opaque only within its *true* radius (`vOccluder`), because at 1× the drawn disc is ~4× too big and must not hide nearby stars.

## Planets' moons

- **Which:** Jupiter's four Galilean moons and Saturn's eight major ones (`astro/PlanetMoons`). Not drawn: Mars', Uranus' and Neptune's (magnitude 11 to 14, within an arcminute of their planet; Meeus has no theory for them).
- **Theory:** Meeus ch. 44 (the low-accuracy method) for Jupiter's and ch. 46 for Saturn's. The tests match Meeus' worked examples 44.a (to 0.01 Jupiter radius) and 46.a (to 0.003 Saturn radius, with Saturn's place for that instant taken from JPL Horizons). The chapter 46 port follows the layout of Sonia Keys' `meeus` Go library (MIT licence), which was used to check it.
- **Saturn's theory works in B1950 ecliptic coordinates.** Saturn's direction of date goes to J2000, then to B1950 (IAU 1976 precession with T = −0.5), then to the B1950 ecliptic.
- **Placement:** Meeus gives X along the planet's equator (positive west) and Y toward its pole, in planet radii. They are laid out in the sky plane at the planet using its projected pole.
  - Jupiter's pole is the IAU WGCCRE one; a test checks its position angle against Meeus 43.a (P = 24.80°).
  - Saturn's pole is built from Meeus' own equator (i = 28.0817°, Ω = 168.8112°, B1950), so "north" agrees with the theory.
- A moon behind its planet's disc (an ellipse, flattened at the poles) is neither drawn nor tappable, and its card says "behind the planet". Jupiter's low-accuracy method gives no Z, so "behind" there is cos u < 0 (u = 0 at inferior conjunction).
- **Magnitude:** V(1,0) + 5 log₁₀(rΔ), using JPL's V(1,0), with no phase effect.
- **Drawing:** kind 3 in the body pass, after the planets and before the Moon. A moon fades in between 5 and 9 dp from its planet's centre (`aSeparation`), so none shows at 1× (Callisto is at most 2 dp out). In 6° binoculars Jupiter's outer moons sit just beside its glare.
- **A planet's halo narrows as its disc grows,** as fast as the disc grows (down to 0.3 of its width). The whole glow keeps its size until then, so zooming in never makes it shrink, and Io two radii out isn't buried at 1°.
- **Labels:** brightest moon first, placed below, above, right or left of the dot, wherever it covers no planet glow, dot or name already drawn. A moon with no room goes unnamed but can still be tapped.
- **Taps:** a moon wins over the body, star or deep-sky object the fingertip rules picked only if it is strictly nearer, since its planet's glow is within a fingertip of any tap on it.
- **Search:** moons are listed under "Solar system" but not suggested. Guidance shows "Pinch to zoom in" under the marker until the moon has faded in. A moon's rise and set are its planet's (they agree to well under a minute).

## Orientation

- TYPE_ROTATION_VECTOR alone made the sky jump while the phone was swung around. During exactly that motion the magnetometer recalibrates, and its heading was measured wandering up to ~50° and settling 35° off. So where the phone has a gyroscope, `HeadingFusion` publishes TYPE_GAME_ROTATION_VECTOR turned about the vertical by a yaw offset to the rotation vector. That offset follows a 2 s low-pass that wraps at 180°. The result: motion comes from the gyroscope with no lag, north comes from the magnetometer, and heading jumps become glides. Game drift (~0.02°/s measured) is well within what the filter tracks.
- Each rotation-vector reading is paired with the game orientation slerped to its own timestamp; nearest-reading pairing wobbled by ~1° while panning. The first pairing snaps the offset, and until then the plain rotation vector is published.
- With no gyroscope, the rotation vector is used alone and still jumps. The fusion is reset on every `start()`, because the game frame's heading may restart. The compass-accuracy warning comes from the rotation vector only.
- The fusion stops jumps but cannot fix a wrong north: if the magnetometer settles wrong (near metal), north is wrong.

## Faintest stars

- The setting is a limiting magnitude L from 1.0 to 7.0 in steps of 0.1. The default, 7.0, means all stars, since the catalogue stops at 6.5. A limit, rather than a brightness scale, is what light pollution actually does: faint stars vanish first. The label also shows a rough Bortle class (Bortle 2001).
- Stars fade with a smoothstep over 1 magnitude centred on L, so dragging never pops stars in or out. "Visible" means exactly `vmag <= L`, which is what taps and the label use. `StarShaders` repeats the `StarVisibility` expression, and a test checks that the two agree. Fully faded stars are moved outside the clip volume.
- The slider affects stars only. The Sun, Moon and planets are what people orient by, lines and names are navigation aids, and the integrated magnitudes of deep-sky objects don't compare with a star limit. Moving the slider deselects a star it hides.
- The slider sits behind the Display button because a Material slider jumps to wherever its track is tapped, so a permanent one stole taps meant for stars near the bottom of the screen.
- Once the light-pollution map knows the observer's sky, the card shows its Bortle class and a Match button that sets the slider to it (see Light pollution). The slider never moves on its own: the map is a model, and the user's eyes are the better judge.

## Light pollution

- **Data:** EOG VIIRS Nighttime Lights v2.2, the annual masked average (Earth Observation Group, Colorado School of Mines; CC BY 4.0; a free sign-in to download, ~325 MB). It is upward radiance, so `tools/light_pollution/gen_light_pollution.py` models the skyglow. Rejected: the Falchi 2016 atlas (CC BY-NC, 2014 data, 2.9 GB), Lorenz's atlas (no licence), lightpollutionmap.info (needs a key), and any online lookup (no INTERNET permission).
- **Model:** zenith brightness = natural sky + K·Σ radiance·area·(d² + h²)^(−α/2) over sources within 200 km: Walker's law at a distance, flattened near the source. α, h and K are fitted with a robust loss to the 44 sites of Kyba et al. 2015 (CC BY 4.0, clear-sky medians 2011–12); the fit gives α ≈ 2.0, h ≈ 0.6 km, and the grid matches the sites to ~0.6 mag rms. The convolution is an FFT per half-degree latitude band, each band with its own kernel, wrapping in longitude.
- **Natural sky fixed at 22.0 mag/arcsec²** (Falchi 2016). Fitted, it drifts to ~21.3, which would make every dark site Bortle 3 at best.
- **Known errors:** sites are 2011–12 measurements against 2025 lights, so suburbs come out ~0.3 mag brighter than measured (roughly the growth VIIRS saw since). A time-matched fit is not possible: the v2.2 series starts in 2022, and the older v2/v2.1 years are processed differently. VIIRS misses the blue of white LEDs, a meter next to a lamp reads brighter than its 5 km cell, mountain observatories above the haze read darker, and snow, cloud and the Moon are ignored.
- **Asset:** `light_pollution.bin`, SQM on 0.05° cells from 75° N to 65° S, 0.04 mag steps in one byte, each row stored as its difference from the row above and deflated (1.9 MB; the generator caps it at 6 MB). An ASCII header, which the app parses, gives the grid. The app inflates down to the observer's row on each lookup (tens of ms) rather than holding the 20 MB grid, and looks up again only after moving ~1 km. Beyond the satellite coverage there is no estimate, and the line is hidden.
- **Bortle class:** from Wikipedia's SQM column of the Bortle table, mapped to a limiting magnitude at the same place within the class (linear between class boundaries), so the map's class and the slider's label always agree. The table lumps classes 8 and 9 below 18.0, so there the curve follows Schaefer's (1990) SQM–NELM relation, joined at 18.0; class 9 starts near 17.4.
- **Shown** in the Tonight panel under the darkness times and in the Display card under the slider, with the EOG credit that CC BY asks for.

## Horizon and compass

- The geometry is a static VBO in **true ENU**, drawn with the star MVP minus `H(LST, lat)` (`SkyViewMath.horizonModelViewProjection`). It is then exact whatever the time and never needs re-uploading. A test pins that it agrees with the star MVP for all four rotations. The overlay uses `lastHorizonMvp`.
- The horizon sits at geometric altitude 0° (no refraction), to match "above/below the horizon" on the cards. It is drawn in teal and requests 2 px; many GPUs only give 1 px, so the colour has to carry it.
- The grid is restrained on purpose: altitude circles at 30° and 60°, verticals every 45° up to 80°, and a small zenith cross instead of eight lines meeting in a knot. Nothing is drawn below the horizon. No equator, ecliptic or equatorial grid.
- Compass letters are raised by an altitude *of sky* rather than a fixed number of pixels, so they stay above the line when the phone is rolled. The altitude is recomputed each frame for a 13 dp gap (`CompassLabels.anchorAltitudeDeg`), so the letters don't float off at 1° or sink into the line at 90°. A point behind the viewer (clip w ≤ 0) is dropped, never mirrored onto the screen. A test checks true north with a declination built independently of `magneticToTrueEnu`.
- Circles are sampled every 0.5°, so the grid still looks round at the deepest zoom.

## Zoom

- Pinch zooms. The enhanced view narrows its vertical field of view from 90° to 1° (`SkyZoom`); 1° is where Jupiter's moons clear the planet and the Moon fills half the screen. The camera view instead sets CameraX's zoom ratio, and the sky follows the ratio the camera *reports* (`ZoomState`), not the one asked for, so it moves with the image. Each view keeps its own zoom. A chip at the bottom shows the zoom and resets to 1×; zoom isn't persisted.
- Zoom is a magnification, `tan(30°) / tan(fov / 2)`: the ratio of focal lengths. That's how a pinch scales what's under the fingers, and how Camera2 defines `CONTROL_ZOOM_RATIO` (whichever physical lens is in use), so `CameraFov.zoomed` is exact by definition and both views read alike.
- Everything sized by the view (bodies, deep-sky symbols, compass raise, taps) takes its focal length from the frame's own MVP (`SkyViewMath.focalLengthPx`), never from `fovYDeg`, which the main thread may already have changed mid-pinch.
- Hand shake grows on screen with the magnification, so in the enhanced view the orientation filter smooths more: `0.1 s × m^0.4`, capped at 0.3 s. The camera view keeps 0.05 s at any zoom, because the image isn't smoothed and the sky must stay on it.
- The pinch detector consumes two-finger moves, so the tap detector cancels instead of taking a pinch for a tap. One finger is left alone.
- The constellation art fades out between 25° and 10°: at 10–20 px per degree it is a blur beyond that.
- At 1° the catalogue (V ≤ 6.5) leaves most fields empty, and the planets' Keplerian positions (up to ~7′ for Jupiter, ~10′ for Saturn) can visibly misplace them against a nearby star. The magnetometer's few degrees of heading error matter more, though; the guidance arrow finds the target.

## Camera view

- Layers, bottom to top:
  1. the CameraX `PreviewView` (`PERFORMANCE`, so a `SurfaceView`, `FILL_CENTER`)
  2. the `GLSurfaceView` (RGBA8888, `TRANSLUCENT`, `setZOrderMediaOverlay(true)`)
  3. the Compose overlay
- **Blending:** the compositor expects premultiplied colour. rgb > 0 with alpha 0 only works by accident, and anything that un-premultiplies the layer (Night Light, colour conversion) drops it. So in camera mode shaders output `alpha = max(r, g, b)` and everything is blended "over" (`OverlayBlend`). As a result, drawn things *replace* camera pixels in proportion to their alpha. The Moon's whole drawn disc (alpha ~0.93) hides whatever is behind it, unlike in the enhanced view; this was accepted.
- **Field of view:** `CameraFov.forView` works it out from the Camera2 characteristics and the preview's `resolutionInfo`. It allows for the HAL's aspect-ratio crop of the sensor, the crop rect and `FILL_CENTER`. The app requests a 4:3 stream (the whole sensor) and falls back to `TYPICAL_PHONE`. It is a pinhole model, so distortion, OIS and digital zoom are ignored (up to 1–2° at the edge). It uses the first listed focal length, which by convention is the main lens.
- The orientation filter's time constant is 0.05 s in camera mode (0.1 s otherwise, more when zoomed). It trades the sky trailing the image while panning against it leading the image and showing jitter.
- **Camera busy:** `CameraStateTracker` shows "Waiting for the camera…" until the camera is `OPEN`. Trap: ignore everything delivered synchronously inside `observeForever` (the previous session's stale `CLOSED` plus error), and arm the tracker with `attached()` afterwards. Don't wait for a clean state instead, because `postValue` can coalesce a clean state and an error.
- **Failures** drop back to the enhanced view with a `CameraProblem` message that clears after 8 s (`cameraProblemSeq`):
  - Permission denied.
  - Permission blocked. `CameraPermissionPolicy` uses a persisted flag because after a process restart the rationale is false both before and after an instant refusal.
  - Permission lost on resume.
  - The bind fails or the camera state reports `CRITICAL`. Those don't throw from `bindToLifecycle`, so the state is observed.
  - The `PreviewView` falls back to a `TextureView` (`UNSUPPORTED`). A `TextureView` would be wiped by the hole the GL surface punches.

  Drawing the camera image inside GL as an external texture would remove the last of these, and let night mode turn the image red.
- The camera permission is requested the first time the switch goes on; the manifest's camera entries are `required="false"`.

## Night mode

- One mapping everywhere (`render/NightMode`): red = Rec. 601 luma, green and blue zero, alpha kept. Taking the brightest channel was rejected: it lifted the faint blue-grey constellation lines nearly to star brightness.
- GL: every fragment shader ends with `GLSL_APPLY`, a `mix` on a uniform, so toggling never recompiles. Since luma ≤ max channel ≤ alpha, premultiplied colour stays valid.
- Compose: a single `Modifier.nightMode` (`saveLayer` plus a `ColorMatrix`) covers all the UI above the surfaces. The `SurfaceView`s must stay outside it, because inside a layer their punched hole clears only the layer. Popups and dialogs are separate windows, so they apply the filter themselves from `LocalNightMode`.
- The camera image can't be recoloured from above (the compositor has one alpha, not one per channel). Instead, the GL surface is cleared to black at alpha 0.75 (`CAMERA_IMAGE_COVER`), so the image shows at a quarter of its brightness.
- Not covered: status- and navigation-bar icons, the keyboard, text-selection handles, system dialogs.

## Object details

- All cards share `ObjectCard`. `detailsExpanded` is shared and kept across taps. The card root swallows taps so they don't fall through to the sky.
- **Star distance:** the BSC (1991) parallaxes predate Hipparcos, with errors of 0.005–0.01″, and 388 of them are dynamical. So ≥ 0.05″ shows "about N light years", 0.02–0.05″ shows "roughly … (uncertain)", and anything else shows "not known reliably". Dynamical parallaxes are always shown as rough.
- **Star constellation:** the BSC's abbreviation (stars with a Bayer or Flamsteed designation only), else the stick figure that contains the star. About 63% of naked-eye stars get none, because there is no boundary table.
- Body sizes: the Moon's uses its geocentric distance (up to 1.7% off); Saturn's rings are excluded.
- Position: `SkyPlacement` at the last body update (so it refreshes every 10 s). Geometric altitude; RA/Dec are labelled "of date", not J2000.
- **Rise and set** (`RiseSet`):
  - The method: a 10-minute scan over 26 h (the Moon can go 25 h between risings) with bisection, plus a turning-point search so a grazing rise and set less than a step apart are not lost.
  - The body ephemeris is re-evaluated at each step, so the Moon's own motion counts.
  - Horizon altitudes: −34′ for stars and planets, −50′ for the Sun, and −34′ minus the semi-diameter for the topocentric Moon.
  - Tested against Meeus 15.a and USNO times fetched on 2026-10-08.
  - Runs on `Dispatchers.Default`, cancellable, and drops a result for an object that is no longer selected.
  - No terrain or observer height, so it can differ from an almanac by a minute or two.

## Search and navigation

- The index (`SkySearchIndex`, built once with the catalogue) covers:
  - the bodies
  - constellation *labels* (so "Serpens Caput" and "Serpens Cauda")
  - the naked-eye stars the sky draws, by name, Bayer, Flamsteed or HR number
  - deep-sky objects by M, NGC, IC, C, Mel or Cr number, common name or alias

  `normalize` lower-cases, strips accents and spells Greek letters out. Ranking is by match tier, then bodies > constellations > stars > deep-sky (so "alpha pers" finds the star before the cluster), then brightness. It is a linear scan per keystroke on the main thread, which is fast enough. Body names are localised when the index is built, so a language change mid-process isn't picked up.
- **Guidance** (`SkyNavigation.guide`) takes the arrow's direction from clip x, y, not from the projected point. A target behind the viewer (w < 0) would otherwise be mirrored through the centre. This is tested for all four rotations. The usable rectangle leaves out the system bars, the toggle row, the measured card and bottom panel, and a 28 dp margin.
- Choosing a result selects the object and guides to it. Guidance ends only with Stop.

## Tonight

- **Panel:** its own button and card (see Top bar), in sections (Planets, Meteor showers, Deep sky) under the Moon. Each row says where to look in words (`SkyHeight`: low under 25°, halfway up, high, overhead above 80°) and a compass point, not in degrees. A row guides to its object as a search result does; a meteor shower guides to its radiant (`SearchTarget.RadiantTarget`), selecting nothing. Worked out on `Dispatchers.Default` when the panel opens and every minute while it is open and the app is resumed.
- **When it describes** (`Tonight.plan`): now if it is already astronomically dark, else the start of darkness, else (no darkness, summer at high latitude) the Sun's lowest point. A list opened in the afternoon then shows the coming night, not the daytime sky; the header says which moment. Darkness is `RiseSet.find` on the Sun's centre at −18°, geometric; tested against USNO's twilight table.
- **Order:** the Moon (always: its phase decides what else shows), planets ≥ 5° up and brighter than 6 (so Uranus but not Neptune), active showers by ZHR, then up to six deep-sky objects brighter than 6 and ≥ 30° up. Bodies first because anyone can find them, deep-sky last because they want a dark sky and often binoculars. Ranked within each group by magnitude, which favours big low-surface-brightness objects (M31, the Pleiades): good beginner targets anyway.
- **Meteor showers** (`meteor_showers.txt`): the nine annual showers with ZHR ≥ 10, from Wikipedia's list (which follows the IMO working list), fetched 2026-10-12; facts, not copied from a database. The date is the observer's local mean date. The ZHR is the peak rate under ideal skies, so the row says "up to".
- **Radiant drift:** the table gives the radiant at the peak; away from it the radiant's ecliptic longitude moves with the Sun's mean motion and its latitude is kept, because the Earth meets the stream from a direction roughly fixed relative to the Sun. IMO's drift tables were unreachable when this was built. The approximation overstates retrograde showers' drift (the Orionids move ~0.7°/day, not 1°), so their radiant is a few degrees off at the ends of the period.

## Deep-sky objects

- **Data** (`deep_sky.txt`, 176 objects): all 110 Messier objects, by modern identifications (M102 = NGC 5866, M91 = NGC 4548, M48 = NGC 2548), and 66 others, mostly Caldwell.
- **Provenance:**
  - The Messier rows were transcribed from published tables and checked against Wikipedia's list.
  - The others were hand-tabulated; about 45 of the 66 positions were checked against Wikipedia infoboxes, and ~20 were not.
  - Positions and magnitudes are facts, and nothing was copied from a database.
  - **OpenNGC (CC BY-SA 4.0) was deliberately not used**; extending the data from it needs a licence decision first.
  - M8 and M24 carry the usual Messier-table magnitudes (6.0 and 4.6), not Wikipedia's 4.6 and 2.5, so the huge M24 star cloud isn't drawn as strong as a bright cluster.

  It is a viewing aid, not a research catalogue.
- Precessed from J2000 to date at load, with no proper motion.
- **Drawn as quads** in the tangent plane, not point sprites: the maximum point size is far too small for M31 or the LMC, and sprites vanish at the screen edge. Minimum radius is 7 dp. An object with no position angle is drawn round rather than given an invented direction. Each type has its own symbol. Strength comes from *surface brightness* × 0.7, so symbols never rival a bright star. Because the passes use different attribute layouts, the renderer disables attribute arrays 0–6 each frame.
- They have their own switch rather than following the slider. Switching them off deselects one.
- Labels: named objects brighter than magnitude 5 or larger than 2°, plus Messier objects brighter than 6 (47 in all).
- **Taps:** the hit area is the drawn ellipse or a 24 dp fingertip, whichever is larger. Inside a big object, its distance is the tap's radius in the ellipse × a fingertip, so tapping the middle of the LMC selects it. In `SkyHitTest.choose`, a body wins a tie with a star, and a deep-sky object wins only if it is strictly nearer than every star and body (they are drawn over it).

## Milky Way

- **Procedural:** Gaussians in galactic l and b (the disc, the bulge and the star clouds) times dark lanes (the Great Rift, Ophiuchus, the Coalsack). It is qualitative, right to a few degrees. No texture is used: none suitably licensed was at hand, and one would be large. The galactic frame is built from the three IAU numbers.
- Mesh: 1.5° × 29 rows from −40° to +40° (7,000 vertices) with premultiplied colour, peaking at 0.24. The shader adds ±½ LSB of noise, because a gradient at 0.05 bands.
- Over the camera it is drawn at × 0.5 (alpha ≤ 0.12), so ~88% of the image survives (tested).
- It is drawn whatever the Sun's altitude, including below the horizon.

## Constellations

- **Selecting:** tap a name (the measured text box + 8 dp) or a line (within 16 dp), or search for it. The line reach is smaller than the 24 dp fingertip so there is still empty sky to tap to dismiss. Serpens is one constellation with both halves lit.
- **Tap resolution** (`TapResolver`):
  - "On" an object means within its sprite + 4 dp (star), its disc + 4 dp (body), inside its ellipse (deep-sky) or inside its name box (constellation).
  - Then, in order: (1) on two or more → the "What's here?" menu (bodies, up to 3 stars, deep-sky, constellation; at most 6 rows); (2) on a name → the constellation; (3) `SkyHitTest.choose`; (4) the nearest line.
  - The slack is small on purpose: at 24 dp a faint star sits under about half of all taps on a name.
- **Highlight:** the selected figure's segments are redrawn from the same VBO (`ConstellationSpan` ranges → `highlightedConstellation`), in pale blue at 2 px, and the overlay rings each of its stars.
- **Switch:** turning Constellations off hides the lines and names and stops tap selection. **A selected constellation stays lit** anyway, because a search result you can't see is worse than the exception. Unlike the deep-sky switch, this one doesn't deselect.
- **Card:**
  - The chart is drawn from catalogue positions: stereographic about the figure's mean direction, north up and east left (a test checks that Betelgeuse lands upper left of Rigel), with neighbouring stars brighter than 5.5.
  - The card gives the figure's centre position, but no rise or set, which mean nothing for a figure 100° long.
  - "Brightest stars" skips stars tagged as another constellation's (Alpheratz).
- **Lore** (`constellation_lore.txt`, 88 entries):
  - 50 ancient (Ptolemy's 48, with Argo counted as three) carry a myth.
  - 38 modern carry the history of the name and say "there is no myth here"; a test enforces this, except for Coma Berenices, Crux, Scutum and Columba.
  - It was written for Skyglass from general knowledge of the classical sources and not checked line by line: a careful popular account, not scholarship. English only.

## Constellation art

- **Licence:**
  - Source: Stellarium's `modern` sky culture, drawn by Johan Meuris, copied unmodified from commit `daace2add6a1bf886e8ee1934f51e9c69f818d18`.
  - The illustrations are under the **Free Art License 1.3**; the anchor data is **CC BY-SA 4.0**, so `constellation_art.txt` is too.
  - `constellation_art/LICENSE.txt` ships in the APK, and the Display card carries a one-line credit.
  - Any change to the art (recolouring, cropping) makes a modified work under the FAL and must say so.
- 85 images cover the 88 constellations: the Argo covers Carina, Puppis and Vela, and Ophiuchus holds Serpens. A test checks that each constellation is covered exactly once.
- **Placement** works as in Stellarium. Each image has three anchor pixels on Hipparcos stars, and the image plane is mapped affinely onto the plane through those stars' unit vectors, then normalised onto the sphere. `gen_art.py` looks the anchors up in Hipparcos (VizieR I/239) at J2000; they are precessed to date at load. Tests: anchors match the BSC, no image is mirrored (seen from inside the sphere), and no mesh triangle folds.
- **Drawing:**
  - The art is grey, so only the green channel is kept, as `GL_R8` with mipmaps (15 MB of GPU memory against 60 MB for RGBA).
  - The renderer uploads 8 images a frame, so loading never stalls a frame.
  - Gain is 0.30, and the outer 4% of each image fades out, because some art runs to the image edge.
  - Over the camera it is drawn at × 0.6. It loads after the catalogue.
- The selected constellation's art is drawn at 0.65 whatever the switch says. The art is not tappable.

## Object photos

- **Coverage:** 163 of the 176 deep-sky objects, the Sun, the Moon, the planets, and a gallery of the planets' major moons on their cards (pictures only, not sky objects). There are none for stars or constellations. The 13 skipped objects are `skip` lines in `sources.src`; most are big naked-eye clusters no archive photographs whole.
- **Licences:** Solar System photos are NASA public domain (each checked to carry a NASA credit, not a third party's). Deep-sky photos are from ESA/Hubble, ESO and NOIRLab under CC BY 4.0. Their credit shows under the full-size photo and as the card's "Photo" row, and `LICENSE.txt` says they were scaled, re-encoded and sometimes cropped.
- **Choice:** the whole object in natural colour, as a telescope shows it, over a famous close-up (M16 is the wide view, not the Pillars). No charts, annotated images, or infrared and X-ray images.
- **Format:** at most 768 px, WebP quality 80; 191 files, 15.5 MB. The 16 MB budget is enforced by the generator and `ObjectImagesTest`. Thumbnails are decoded subsampled, bitmaps are kept in a 24 MB `LruCache`, and `ContentScale.Fit` keeps Saturn's rings.

## Object facts

- **Coverage:** the bodies, every deep-sky object and 81 notable stars. Not constellations, which already have their lore.
- `Key|Name|About|Fact`. A test checks the name against the keyed object, which catches a mistyped key, and caps each field at 220 characters.
- Star entries leave distance out, because it would contradict the card's 1991 parallax row.
- Written from general knowledge and not checked line by line. Distances are rounded common values (±10% or more). The claims most worth checking:
  - Aristotle and M41
  - M93 as the last object Messier found himself
  - the supernova counts in M61, M83 and NGC 6946
  - the nickname "Tweedledum and Tweedledee" for NGC 6633 and IC 4756

  English only.

## Viewing signs

- **What:** each details card shows the four instruments (eye, 10×50 binoculars, a 90 mm and a 200 mm telescope). They are lit from the least one that shows the object upward, the one that shows more in blue, with a sentence about it in the details ("A small telescope shows its rings"). The Sun gets a solar-filter warning instead. Tonight rows show the least instrument's glyph. Glyphs are drawn on a `Canvas`, as in the top bar.
- **The sky they are for** (`ViewingGuide.limitingMagnitude`): the faintest-stars slider if the user has moved it off "all", since it is their own judgement. Otherwise the light-pollution map's limit, else 6.5. Altitude, haze, the Moon and filters are ignored.
- **Reach:** a star shows in an instrument if it is no fainter than NELM + 5 log₁₀(D / 7 mm) − 1. The −1 covers glass, real eyes and hand-held binoculars, giving +3.3, +4.5 and +6.3.
- **Diffuse objects** must be 0.5 mag brighter than a star to be seen, and lose 0.5 mag per mag/arcmin² of surface brightness beyond 13 (`DeepSkyAppearance.surfaceBrightness`), so M33 and M101 need a darker sky than their magnitudes suggest. Calibrated against Bortle's class descriptions (M33 in classes 2 and 4, the bright globulars in 3, M31 and M44 in 7–8).
- **Planets' moons** count 1 mag fainter for their planet's glare and never get the eye sign. The Moon is always naked-eye; meteor showers are eye-only.
- **Beyond reach:** an object past the 200 mm telescope's reach gets a dimmed "beyond these instruments" sign rather than a lit large telescope.
- **Closer looks** are per body or per deep-sky type, shown only when they need more than the least instrument.

## Not built

- **A better planet theory (VSOP87)** for deep zoom, and **more labels when zoomed in** (every deep-sky object, named stars).
- **Planet detail at zoom:** Saturn's rings (Meeus ch. 45 gives their tilt and position angle), surface texture on the planets and the Moon.
- **Moons:** Mars', Uranus' and Neptune's moons in the sky; eclipses in the planet's shadow and shadow transits; Jupiter's high-accuracy theory (Meeus' E5 method).
- **IAU constellation boundaries** (Roman 1987, CDS VI/42). They would give every star a constellation and let a tap on empty sky select one.
- **Other objects:** asteroids, comets and satellites.
- **Daylight and twilight sky:** everything is drawn whatever the Sun's altitude.
- **Light pollution:** elevation, a map view, and moonlight in the estimate.
- **Equator, ecliptic and equatorial grid.**
- **Camera image as a GL external texture:** it would fix the `TextureView` fallback and a red camera image in night mode.

- **Search:** on-target confirmation, haptics, recent and favourite searches.
- **Tonight:** planets that rise later in the night, moonlight's effect on the deep-sky list, minor showers (ZHR < 10, the Taurids' fireballs), per-shower drift rates, and a date picker for another night.
- **An About / credits screen.**
- **Viewing signs:** splitting double stars (the BSC has no separations), altitude and extinction, nebula filters (the Rosette and the Horsehead want one), and signs in search results.
- **Constellation card:** boundaries in the chart, a larger chart, pronunciation, best season, and the deep-sky objects it contains.
- **Deep-sky and Milky Way:** deep-sky distances, hiding deep-sky objects below the horizon, and a Milky Way brightness setting.
