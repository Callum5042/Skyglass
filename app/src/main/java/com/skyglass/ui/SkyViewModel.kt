package com.skyglass.ui

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.Manifest
import android.content.pm.PackageManager
import android.hardware.SensorManager
import android.location.Location
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.skyglass.R
import com.skyglass.astro.AstroTime
import com.skyglass.astro.BodyPosition
import com.skyglass.astro.PlanetMoon
import com.skyglass.astro.PlanetMoonPosition
import com.skyglass.astro.PlanetMoons
import com.skyglass.astro.RiseSet
import com.skyglass.astro.RiseSetTimes
import com.skyglass.astro.SkyBody
import com.skyglass.astro.SolarSystem
import com.skyglass.catalog.BodyVertices
import com.skyglass.catalog.Constellation
import com.skyglass.catalog.ConstellationArtMesh
import com.skyglass.catalog.ConstellationDescriber
import com.skyglass.catalog.ConstellationDescription
import com.skyglass.catalog.ConstellationGeometry
import com.skyglass.catalog.ConstellationLabel
import com.skyglass.catalog.ConstellationLore
import com.skyglass.catalog.ConstellationSky
import com.skyglass.catalog.DeepSkyLabels
import com.skyglass.catalog.DeepSkyObject
import com.skyglass.catalog.DeepSkyVertices
import com.skyglass.catalog.MeteorShower
import com.skyglass.catalog.MilkyWayMesh
import com.skyglass.catalog.NavigationTarget
import com.skyglass.catalog.ObjectFactIndex
import com.skyglass.catalog.ObjectImageIndex
import com.skyglass.catalog.SearchKind
import com.skyglass.catalog.SearchResult
import com.skyglass.catalog.SearchTarget
import com.skyglass.catalog.SkySearchIndex
import com.skyglass.catalog.Star
import com.skyglass.catalog.StarCatalog
import com.skyglass.catalog.StarDescription
import com.skyglass.catalog.StarNamer
import com.skyglass.catalog.StarPositions
import com.skyglass.catalog.StarVisibility
import com.skyglass.catalog.StarVertices
import com.skyglass.catalog.Tonight
import com.skyglass.math.Vector3
import com.skyglass.render.ConstellationHitTest
import com.skyglass.render.Observer
import com.skyglass.render.SkyHitTest
import com.skyglass.render.SkyRenderInputs
import com.skyglass.render.SkyViewMath
import com.skyglass.render.SkyZoom
import com.skyglass.render.TapOutcome
import com.skyglass.render.TapPick
import com.skyglass.render.TapResolver
import com.skyglass.sensors.LocationSource
import com.skyglass.sensors.MagneticDeclination
import com.skyglass.sensors.OrientationSensor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What is behind the stars: the plain black sky, or the live rear-camera image. */
enum class ViewMode { ENHANCED, CAMERA }

/** Why the camera view is not showing although the user asked for it; shown in the status overlay. */
enum class CameraProblem {
    PERMISSION_DENIED,

    /** Denied with "don't ask again" (or twice): the system dialog will not appear, only app settings can fix it. */
    PERMISSION_BLOCKED,

    /**
     * The camera will not open: disabled by policy, a fatal error, do-not-disturb mode, or a stream
     * configuration it cannot do. (A camera in use by another app is *not* this: CameraX keeps
     * retrying, so the view stays up with [SkyUiState.cameraWaiting] set.)
     */
    UNAVAILABLE,

    /** The preview would be drawn above the GL surface and hide the sky; see `CameraPreviewLayer`. */
    UNSUPPORTED,
}

/** The star the user tapped, with where it is in the sky so the overlay can ring it. */
data class SelectedStar(val description: StarDescription, val direction: Vector3)

/** The deep-sky object the user tapped, with where it is in the sky so the overlay can ring it. */
data class SelectedDeepSky(val obj: DeepSkyObject, val direction: Vector3)

/** A deep-sky object that gets a name beside it (see [DeepSkyLabels]), with where to put it. */
data class DeepSkyLabel(val text: String, val obj: DeepSkyObject, val direction: Vector3)

/**
 * The menu offered when a tap lands on several things at once (see [TapResolver]): where the finger
 * was, in pixels of the sky view, and what is there, as search titles them.
 */
data class TapChoices(val x: Float, val y: Float, val results: List<SearchResult>)

/** What one tap or search result selects; everything else is deselected. */
private data class Selection(
    val star: SelectedStar? = null,
    val body: SkyBody? = null,
    val moon: PlanetMoon? = null,
    val deepSky: SelectedDeepSky? = null,
    val constellation: ConstellationDescription? = null,
    /** Where a star, deep-sky object or constellation is, for guidance; null for a body, which moves. */
    val fixedDirection: Vector3? = null,
)

data class SkyUiState(
    /** Null while the catalogue is loading. */
    val starCount: Int? = null,
    val observer: Observer? = null,
    val hasRotationSensor: Boolean = true,
    val compassAccuracy: Int = SensorManager.SENSOR_STATUS_UNRELIABLE,
    val locationEnabled: Boolean = true,
    val constellationLabels: List<ConstellationLabel> = emptyList(),
    val selectedStar: SelectedStar? = null,
    /** Sun, Moon and planets as of the last update; empty until the observer's location is known. */
    val bodies: List<BodyPosition> = emptyList(),
    /** The tapped body; its current position is looked up in [bodies] so the ring follows it. */
    val selectedBody: SkyBody? = null,
    /** Jupiter's and Saturn's major moons as of the last update, hidden ones included; empty until the location is known. */
    val moons: List<PlanetMoonPosition> = emptyList(),
    /** The tapped (or searched-for) planet's moon; its position is looked up in [moons]. */
    val selectedMoon: PlanetMoon? = null,
    val viewMode: ViewMode = ViewMode.ENHANCED,
    /** Set when the camera view could not be shown; cleared once it is. */
    val cameraProblem: CameraProblem? = null,
    /** Bumped each time a problem is reported, so a timeout started for one message cannot clear a newer one. */
    val cameraProblemSeq: Int = 0,
    /** In the camera view, CameraX is waiting to open a camera that is busy (e.g. in use by another app). */
    val cameraWaiting: Boolean = false,
    /** The "faintest stars" slider: stars fainter than this fade out (see `StarVisibility`). */
    val limitingMagnitude: Double = StarVisibility.DEFAULT_LIMIT,
    /**
     * How far the current view is zoomed: the enhanced view's magnification against the default field
     * of view (see `SkyZoom`), or the camera's zoom ratio in the camera view. 1 when not zoomed.
     */
    val zoom: Double = 1.0,
    /** The zoom ratio the camera view asks the camera for; kept while the enhanced view is showing. */
    val cameraZoomRatio: Double = 1.0,
    /**
     * Julian date (UTC) of the last body update, so at most ten seconds old: the
     * moment the details card works out the tapped object's altitude and azimuth for. Null until the
     * observer's location is known.
     */
    val julianDate: Double? = null,
    /** Rise and set of the tapped star or body; null until calculated (in the background) and when nothing is tapped. */
    val riseSet: RiseSetTimes? = null,
    /** What the user searched for and is being guided to, until they stop; see [SkyViewModel.navigateTo]. */
    val navigation: NavigationTarget? = null,
    /** The horizon line and the compass letters on it (see `HorizonGuides`); on by default. */
    val showHorizon: Boolean = true,
    /** The altitude/azimuth reference grid; on by default. */
    val showGrid: Boolean = true,
    /** The deep-sky objects, their names and tapping them; on by default. */
    val showDeepSky: Boolean = true,
    /** The Milky Way band; on by default. */
    val showMilkyWay: Boolean = true,
    /** The tapped (or searched-for) deep-sky object. */
    val selectedDeepSky: SelectedDeepSky? = null,
    /**
     * The tapped (or searched-for) constellation, which is also lit up in the sky. Selected and shown
     * even when [showConstellations] is off.
     */
    val selectedConstellation: ConstellationDescription? = null,
    /** The constellation lines and names; on by default. */
    val showConstellations: Boolean = true,
    /** The constellation illustrations; on by default. The selected constellation's is drawn either way. */
    val showConstellationArt: Boolean = true,
    /** Everything in red, for dark-adapted eyes (see `NightMode`); off by default. */
    val nightMode: Boolean = false,
    /** The deep-sky objects that are named on screen; empty until the catalogue has loaded. */
    val deepSkyLabels: List<DeepSkyLabel> = emptyList(),
    /** The "what's here?" menu after a tap on several things at once; null when it is not open. */
    val tapChoices: TapChoices? = null,
    /** The photos shown on the details cards; empty until loaded. */
    val objectImages: ObjectImageIndex = ObjectImageIndex.EMPTY,
    /** The "about" and "did you know?" words on the details cards; empty until loaded. */
    val objectFacts: ObjectFactIndex = ObjectFactIndex.EMPTY,
    /** What is worth seeing tonight, for the search panel; null until the location is known and it is first asked for. */
    val tonight: Tonight.Plan? = null,
)

private class LoadedSky(
    val stars: List<Star>,
    val vertices: FloatArray,
    val constellations: ConstellationSky,
    val namer: StarNamer,
    val searchIndex: SkySearchIndex,
    val deepSky: List<DeepSkyObject>,
    val deepSkyDirections: List<Vector3>,
    val deepSkyVertices: FloatArray,
    val milkyWay: MilkyWayMesh,
    val figures: List<Constellation>,
    val starPositions: List<Vector3>,
    val constellationLore: Map<String, ConstellationLore>,
)

class SkyViewModel(application: Application) : AndroidViewModel(application) {

    val orientationSensor = OrientationSensor(application)
    val renderInputs = SkyRenderInputs()

    // Stars in the same order as the vertex buffer, and what the tap overlay needs to describe them.
    private var stars: List<Star> = emptyList()
    private var starNamer: StarNamer? = null
    private var starIndexByHr: Map<Int, Int> = emptyMap()
    private var searchIndex: SkySearchIndex? = null
    private var deepSky: List<DeepSkyObject> = emptyList()
    private var deepSkyDirections: List<Vector3> = emptyList()
    private var deepSkyIndexById: Map<String, Int> = emptyMap()
    private var constellations: List<Constellation> = emptyList()
    private var constellationSky: ConstellationSky? = null
    private var starPositions: List<Vector3> = emptyList()
    private var constellationLore: Map<String, ConstellationLore> = emptyMap()
    private var constellationArt: ConstellationArtMesh? = null
    private val constellationDescriptions = HashMap<String, ConstellationDescription>()
    private var meteorShowers: List<MeteorShower> = emptyList()

    private val locationSource = LocationSource(application)
    private var locationJob: Job? = null
    private var bodyJob: Job? = null
    private var riseSetJob: Job? = null
    private var tonightJob: Job? = null

    // Display settings the user chooses, kept apart from the camera state below. Safe to back up.
    private val displayPreferences = application.getSharedPreferences(DISPLAY_PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val savedLimitingMagnitude = StarVisibility.snapLimit(
        displayPreferences.getFloat(KEY_LIMITING_MAGNITUDE, StarVisibility.DEFAULT_LIMIT.toFloat()).toDouble(),
    )

    private val savedShowHorizon = displayPreferences.getBoolean(KEY_SHOW_HORIZON, true)
    private val savedShowGrid = displayPreferences.getBoolean(KEY_SHOW_GRID, true)
    private val savedShowDeepSky = displayPreferences.getBoolean(KEY_SHOW_DEEP_SKY, true)
    private val savedShowMilkyWay = displayPreferences.getBoolean(KEY_SHOW_MILKY_WAY, true)
    private val savedShowConstellations = displayPreferences.getBoolean(KEY_SHOW_CONSTELLATIONS, true)
    private val savedShowConstellationArt = displayPreferences.getBoolean(KEY_SHOW_CONSTELLATION_ART, true)
    private val savedNightMode = displayPreferences.getBoolean(KEY_NIGHT_MODE, false)

    private val _uiState = MutableStateFlow(
        SkyUiState(
            hasRotationSensor = orientationSensor.isAvailable,
            limitingMagnitude = savedLimitingMagnitude,
            showHorizon = savedShowHorizon,
            showGrid = savedShowGrid,
            showDeepSky = savedShowDeepSky,
            showMilkyWay = savedShowMilkyWay,
            showConstellations = savedShowConstellations,
            showConstellationArt = savedShowConstellationArt,
            nightMode = savedNightMode,
        ),
    )
    val uiState: StateFlow<SkyUiState> = _uiState.asStateFlow()

    init {
        renderInputs.limitingMagnitude = savedLimitingMagnitude
        renderInputs.showHorizon = savedShowHorizon
        renderInputs.showGrid = savedShowGrid
        renderInputs.showDeepSky = savedShowDeepSky
        renderInputs.showMilkyWay = savedShowMilkyWay
        renderInputs.showConstellations = savedShowConstellations
        renderInputs.showConstellationArt = savedShowConstellationArt
        renderInputs.nightMode = savedNightMode
        viewModelScope.launch {
            val sky = withContext(Dispatchers.IO) {
                val assets = application.assets
                val loadedStars = StarCatalog.loadNakedEyeStars(assets)
                val constellations = StarCatalog.loadConstellations(assets)
                val julianDate = AstroTime.julianDate(System.currentTimeMillis())
                val namer = StarNamer(StarCatalog.loadProperNames(assets), constellations)
                val constellationSky = ConstellationGeometry.build(constellations, loadedStars, julianDate)
                val loadedDeepSky = StarCatalog.loadDeepSky(assets)
                val deepSkyDirections = DeepSkyVertices.directions(loadedDeepSky, julianDate)
                val lore = StarCatalog.loadConstellationLore(assets)
                LoadedSky(
                    stars = loadedStars,
                    vertices = StarVertices.build(loadedStars, julianDate),
                    constellations = constellationSky,
                    namer = namer,
                    searchIndex = SkySearchIndex.build(
                        loadedStars,
                        namer,
                        constellationSky.labels.map { it.name },
                        SkyBody.entries.associateWith { application.getString(it.nameRes()) },
                        loadedDeepSky,
                        moonNames = PlanetMoon.entries.associateWith { application.getString(it.nameRes()) },
                        moonDetails = PlanetMoon.entries.associateWith {
                            application.getString(R.string.moon_of_planet, application.getString(it.planet.nameRes()))
                        },
                    ),
                    deepSky = loadedDeepSky,
                    deepSkyDirections = deepSkyDirections,
                    deepSkyVertices = DeepSkyVertices.build(loadedDeepSky, deepSkyDirections),
                    milkyWay = MilkyWayMesh.build(julianDate),
                    figures = constellations,
                    starPositions = StarPositions.ofDate(loadedStars, julianDate),
                    constellationLore = lore.associateBy { it.abbreviation },
                )
            }
            stars = sky.stars
            starNamer = sky.namer
            starIndexByHr = sky.stars.withIndex().associate { (i, star) -> star.hr to i }
            searchIndex = sky.searchIndex
            deepSky = sky.deepSky
            deepSkyDirections = sky.deepSkyDirections
            deepSkyIndexById = sky.deepSky.withIndex().associate { (i, obj) -> obj.id to i }
            constellations = sky.figures
            constellationSky = sky.constellations
            starPositions = sky.starPositions
            constellationLore = sky.constellationLore
            renderInputs.constellationLines = sky.constellations.lineVertices
            renderInputs.starVertices = sky.vertices
            renderInputs.deepSkyVertices = sky.deepSkyVertices
            renderInputs.milkyWayMesh = sky.milkyWay
            val deepSkyLabels = sky.deepSky.indices.mapNotNull { i ->
                DeepSkyLabels.text(sky.deepSky[i])?.let { DeepSkyLabel(it, sky.deepSky[i], sky.deepSkyDirections[i]) }
            }
            _uiState.update {
                it.copy(
                    starCount = sky.stars.size,
                    constellationLabels = sky.constellations.labels,
                    deepSkyLabels = deepSkyLabels,
                )
            }
        }
        // The illustrations separately, so decoding 85 images does not hold up the stars.
        viewModelScope.launch {
            val (mesh, images) = withContext(Dispatchers.IO) {
                val assets = application.assets
                val art = StarCatalog.loadConstellationArt(assets)
                val mesh = ConstellationArtMesh.build(art, AstroTime.julianDate(System.currentTimeMillis()))
                mesh to art.map { StarCatalog.loadArtImage(assets, it) }
            }
            constellationArt = mesh
            renderInputs.constellationArtImages = images
            renderInputs.constellationArtMesh = mesh
            // A constellation may have been selected before its picture was ready.
            publishHighlight(_uiState.value.selectedConstellation)
        }
        viewModelScope.launch {
            val images = withContext(Dispatchers.IO) { StarCatalog.loadObjectImageIndex(application.assets) }
            _uiState.update { it.copy(objectImages = images) }
        }
        viewModelScope.launch {
            val facts = withContext(Dispatchers.IO) { StarCatalog.loadObjectFactIndex(application.assets) }
            _uiState.update { it.copy(objectFacts = facts) }
        }
        viewModelScope.launch {
            meteorShowers = withContext(Dispatchers.IO) { StarCatalog.loadMeteorShowers(application.assets) }
        }
        viewModelScope.launch {
            orientationSensor.accuracy.collect { accuracy -> _uiState.update { it.copy(compassAccuracy = accuracy) } }
        }
    }

    /**
     * Selects what the tap means, clears the selection on empty sky, or, when the finger is on several
     * things at once, opens the "what's here?" menu ([SkyUiState.tapChoices]); the rules are
     * [TapResolver]'s. While that menu is open a tap anywhere else only closes it. Coordinates are in
     * pixels of the [widthPx] × [heightPx] sky view.
     *
     * @param labelSizesPx the drawn size of each constellation name, as [ConstellationHitTest.labelAt] takes it
     */
    fun onSkyTapped(x: Float, y: Float, widthPx: Int, heightPx: Int, density: Float, labelSizesPx: FloatArray) {
        if (_uiState.value.tapChoices != null) {
            dismissTapChoices()
            return
        }
        val mvp = renderInputs.lastMvp
        if (mvp == null) {
            applySelection(Selection())
            return
        }
        val tapX = x.toDouble()
        val tapY = y.toDouble()
        val bodies = _uiState.value.bodies
        val moons = _uiState.value.moons
        val vertices = renderInputs.starVertices
        val bodyHits = SkyHitTest.bodiesAt(mvp, bodies, widthPx, heightPx, tapX, tapY, density)
        val moonHits = SkyHitTest.moonsAt(mvp, moons, widthPx, heightPx, tapX, tapY, density)
        val starHits = if (vertices != null && starNamer != null) {
            SkyHitTest.starsAt(mvp, vertices, widthPx, heightPx, tapX, tapY, density, renderInputs.limitingMagnitude)
        } else {
            emptyList()
        }
        val deepSkyHits = if (renderInputs.showDeepSky && deepSky.isNotEmpty()) {
            val focalPx = SkyViewMath.focalLengthPx(mvp, heightPx)
            SkyHitTest.deepSkyAt(mvp, deepSky, deepSkyDirections, widthPx, heightPx, tapX, tapY, density, focalPx)
        } else {
            emptyList()
        }
        // Nothing of the constellations is drawn to tap while they are switched off.
        val sky = constellationSky?.takeIf { renderInputs.showConstellations }
        val labelHit = sky?.let { ConstellationHitTest.labelAt(mvp, it, labelSizesPx, widthPx, heightPx, tapX, tapY, density) }
        val lineHit = sky?.let { ConstellationHitTest.nearestLine(mvp, it, widthPx, heightPx, tapX, tapY, density) }

        when (val outcome = TapResolver.resolve(bodyHits, starHits, deepSkyHits, labelHit, lineHit, moonHits)) {
            TapOutcome.Nothing -> applySelection(Selection())
            is TapOutcome.Select -> applySelection(selectionFor(outcome.pick, bodies, moons) ?: Selection())
            is TapOutcome.Choose -> {
                val results = outcome.picks.mapNotNull { pick -> targetOf(pick, bodies, moons)?.let { searchIndex?.resultFor(it) } }
                when (results.size) {
                    0 -> applySelection(Selection())
                    1 -> applySelection(selectionFor(results.single().target) ?: Selection())
                    else -> {
                        applySelection(Selection())
                        _uiState.update { it.copy(tapChoices = TapChoices(x, y, results)) }
                    }
                }
            }
        }
    }

    /** Selects one of the things offered by the "what's here?" menu, and closes it. */
    fun chooseTapped(result: SearchResult) {
        _uiState.update { it.copy(tapChoices = null) }
        applySelection(selectionFor(result.target) ?: Selection())
    }

    /** Closes the "what's here?" menu without choosing; nothing is selected. */
    fun dismissTapChoices() {
        _uiState.update { it.copy(tapChoices = null) }
    }

    private fun targetOf(pick: TapPick, bodies: List<BodyPosition>, moons: List<PlanetMoonPosition>): SearchTarget? = when (pick) {
        is TapPick.Body -> SearchTarget.BodyTarget(bodies[pick.index].body)
        is TapPick.Moon -> SearchTarget.MoonTarget(moons[pick.index].moon)
        is TapPick.Star -> SearchTarget.StarTarget(stars[pick.index].hr)
        is TapPick.DeepSky -> SearchTarget.DeepSkyTarget(deepSky[pick.index].id)
        is TapPick.Constellation -> SearchTarget.ConstellationTarget(pick.name)
    }

    private fun selectionFor(pick: TapPick, bodies: List<BodyPosition>, moons: List<PlanetMoonPosition>): Selection? = when (pick) {
        // A figure spread over opposite sides of the sky has no label, so it is found by abbreviation, not by name.
        is TapPick.Constellation -> describeConstellation(pick.abbreviation)?.let { Selection(constellation = it, fixedDirection = it.direction) }
        else -> targetOf(pick, bodies, moons)?.let(::selectionFor)
    }

    /** What selecting [target] means, or null if it cannot be found (the catalogue is still loading). */
    private fun selectionFor(target: SearchTarget): Selection? = when (target) {
        is SearchTarget.StarTarget -> {
            val vertices = renderInputs.starVertices
            val namer = starNamer
            val index = starIndexByHr[target.hr]
            if (vertices == null || namer == null || index == null) {
                null
            } else {
                val direction = starDirection(vertices, index)
                Selection(star = SelectedStar(namer.describe(stars[index]), direction), fixedDirection = direction)
            }
        }
        is SearchTarget.BodyTarget -> Selection(body = target.body)
        is SearchTarget.MoonTarget -> Selection(moon = target.moon)
        is SearchTarget.DeepSkyTarget -> deepSkyIndexById[target.id]?.let { index ->
            Selection(deepSky = SelectedDeepSky(deepSky[index], deepSkyDirections[index]), fixedDirection = deepSkyDirections[index])
        }
        is SearchTarget.ConstellationTarget -> _uiState.value.constellationLabels.firstOrNull { it.name == target.name }?.let { label ->
            describeConstellation(label.abbreviation)?.let { Selection(constellation = it, fixedDirection = label.direction) }
        }
        // Nothing to select or describe, only a place to guide to.
        is SearchTarget.RadiantTarget -> _uiState.value.tonight?.showers?.firstOrNull { it.shower.code == target.code }
            ?.let { Selection(fixedDirection = it.direction) }
    }

    /** Makes [selection] the selection (an empty one clears it), lights a selected constellation and starts its rise and set. */
    private fun applySelection(selection: Selection, navigation: NavigationTarget? = _uiState.value.navigation) {
        publishHighlight(selection.constellation)
        _uiState.update {
            it.copy(
                selectedStar = selection.star,
                selectedBody = selection.body,
                selectedMoon = selection.moon,
                selectedDeepSky = selection.deepSky,
                selectedConstellation = selection.constellation,
                riseSet = null,
                navigation = navigation,
            )
        }
        refreshRiseSet()
    }

    /** The card content for a constellation, built on first use (a few thousand projections for its chart) and kept. */
    private fun describeConstellation(abbreviation: String): ConstellationDescription? {
        constellationDescriptions[abbreviation]?.let { return it }
        val namer = starNamer ?: return null
        val description = ConstellationDescriber.describe(
            abbreviation, constellations, stars, starPositions, constellationLore, namer,
        ) ?: return null
        constellationDescriptions[abbreviation] = description
        return description
    }

    /** Tells the renderer which lines and illustration to light: the selected constellation's, or none. */
    private fun publishHighlight(selected: ConstellationDescription?) {
        val ranges = selected?.let { constellationSky?.rangesOf(it.abbreviation) }
        renderInputs.highlightedConstellation = ranges?.takeIf { it.isNotEmpty() }
        renderInputs.highlightedArtImage = selected?.let { constellationArt?.imageOf(it.abbreviation) } ?: -1
    }

    private fun starDirection(vertices: FloatArray, index: Int): Vector3 {
        val base = index * StarVertices.FLOATS_PER_STAR + StarVertices.POSITION_OFFSET
        return Vector3(vertices[base].toDouble(), vertices[base + 1].toDouble(), vertices[base + 2].toDouble())
    }

    /** Results for the search box, best first; empty until the catalogue has loaded. See [SkySearchIndex]. */
    fun search(query: String): List<SearchResult> = searchIndex?.search(query).orEmpty()

    /**
     * Starts guiding the user to a search [result] and selects it, so the details card opens (and a
     * constellation is lit up in the sky). The guidance itself is drawn
     * by `SkyOverlay`: an arrow at the edge of the view while the target is off screen, a marker on it
     * once it is in view. It lasts until [stopNavigation], through taps and the camera switch.
     */
    fun navigateTo(result: SearchResult) {
        val selection = selectionFor(result.target) ?: return
        _uiState.update { it.copy(tapChoices = null) }
        applySelection(selection, navigation = NavigationTarget(result, selection.fixedDirection))
    }

    /**
     * Guides to something from the Tonight list, as choosing it in search would: a body or deep-sky
     * object by its search row, a meteor shower to its radiant.
     */
    fun navigateToTonight(target: SearchTarget) {
        val result = when (target) {
            is SearchTarget.RadiantTarget -> _uiState.value.tonight?.showers?.firstOrNull { it.shower.code == target.code }?.let {
                SearchResult(target, SearchKind.METEOR_SHOWER, getApplication<Application>().getString(R.string.tonight_radiant_title, it.shower.name))
            }
            else -> searchIndex?.resultFor(target)
        } ?: return
        navigateTo(result)
    }

    /**
     * Works out the Tonight list ([SkyUiState.tonight]) in the background, for now and the observer's
     * place: a few thousand ephemeris evaluations (the darkness scan and the rises and sets), tens of
     * milliseconds. No-op until the location is known; called while the list is showing.
     */
    fun refreshTonight() {
        val observer = renderInputs.observer ?: return
        tonightJob?.cancel()
        val nowJd = AstroTime.julianDate(System.currentTimeMillis())
        val objects = deepSky
        val directions = deepSkyDirections
        val showers = meteorShowers
        tonightJob = viewModelScope.launch {
            val plan = withContext(Dispatchers.Default) {
                Tonight.plan(nowJd, observer.latitudeDeg, observer.longitudeDeg, objects, directions, showers) { ensureActive() }
            }
            _uiState.update { it.copy(tonight = plan) }
        }
    }

    /** Ends the guidance. The object stays selected, so its card is not taken away. */
    fun stopNavigation() {
        _uiState.update { it.copy(navigation = null) }
    }

    /** Dismisses the details card (the same as tapping empty sky). */
    fun clearSelection() {
        applySelection(Selection())
    }

    /**
     * Works out when the tapped object next rises and sets, off the main thread: it scans the
     * altitude over the next day (see [RiseSet]), which for a body means a few hundred ephemeris
     * evaluations. A planet's moon rises and sets with its planet (to well under a minute), so it gets
     * the planet's times. Called on a new selection and on every body update, so the times stay "next".
     * A result for something no longer selected is dropped.
     */
    private fun refreshRiseSet() {
        riseSetJob?.cancel()
        riseSetJob = null
        val state = _uiState.value
        val star = state.selectedStar
        val moon = state.selectedMoon
        val body = state.selectedBody ?: moon?.planet
        val deepSkyObject = state.selectedDeepSky
        val observer = renderInputs.observer
        if ((star == null && body == null && deepSkyObject == null) || observer == null) return
        val startJd = AstroTime.julianDate(System.currentTimeMillis())
        riseSetJob = viewModelScope.launch {
            val times = withContext(Dispatchers.Default) {
                // Stars and deep-sky objects are both fixed points of the equatorial frame of date.
                val fixedDirection = star?.direction ?: deepSkyObject?.direction
                if (fixedDirection != null) {
                    RiseSet.forStar(fixedDirection, startJd, observer.latitudeDeg, observer.longitudeDeg) { ensureActive() }
                } else {
                    RiseSet.forBody(checkNotNull(body), startJd, observer.latitudeDeg, observer.longitudeDeg) { ensureActive() }
                }
            }
            _uiState.update {
                val stillSelected = it.selectedStar == star && it.selectedMoon == moon &&
                    (it.selectedBody ?: it.selectedMoon?.planet) == body && it.selectedDeepSky == deepSkyObject
                if (stillSelected) it.copy(riseSet = times) else it
            }
        }
    }

    /**
     * Sets how faint a star may be and still be drawn (the "faintest stars" slider). Applies at once
     * to the renderer and to tapping; call [saveLimitingMagnitude] when the drag ends to keep it.
     * A selected star that this dims out of sight is deselected.
     */
    fun setLimitingMagnitude(value: Double) {
        val limit = StarVisibility.snapLimit(value)
        renderInputs.limitingMagnitude = limit
        val selectedBefore = _uiState.value.selectedStar
        _uiState.update { state ->
            val selected = state.selectedStar
            val keep = StarVisibility.keepsSelection(selected?.description?.vmag, limit)
            if (keep) state.copy(limitingMagnitude = limit)
            else state.copy(limitingMagnitude = limit, selectedStar = null, riseSet = null)
        }
        if (selectedBefore != null && _uiState.value.selectedStar == null) refreshRiseSet() // cancels the calculation for the dropped star
    }

    /** Persists the current "faintest stars" setting across restarts. */
    fun saveLimitingMagnitude() {
        displayPreferences.edit().putFloat(KEY_LIMITING_MAGNITUDE, _uiState.value.limitingMagnitude.toFloat()).apply()
    }

    /** Shows or hides the horizon line and compass letters, and keeps the choice across restarts. */
    fun setShowHorizon(show: Boolean) {
        renderInputs.showHorizon = show
        _uiState.update { it.copy(showHorizon = show) }
        displayPreferences.edit().putBoolean(KEY_SHOW_HORIZON, show).apply()
    }

    /** Shows or hides the altitude/azimuth reference grid, and keeps the choice across restarts. */
    fun setShowGrid(show: Boolean) {
        renderInputs.showGrid = show
        _uiState.update { it.copy(showGrid = show) }
        displayPreferences.edit().putBoolean(KEY_SHOW_GRID, show).apply()
    }

    /** Shows or hides the deep-sky objects, and keeps the choice across restarts. Hiding them deselects the tapped one. */
    fun setShowDeepSky(show: Boolean) {
        renderInputs.showDeepSky = show
        val deselect = !show && _uiState.value.selectedDeepSky != null
        _uiState.update {
            if (show || it.selectedDeepSky == null) it.copy(showDeepSky = show)
            else it.copy(showDeepSky = false, selectedDeepSky = null, riseSet = null)
        }
        if (deselect) refreshRiseSet() // cancels the calculation for the dropped object
        displayPreferences.edit().putBoolean(KEY_SHOW_DEEP_SKY, show).apply()
    }

    /**
     * Shows or hides the constellation lines and names, and keeps the choice across restarts. A
     * constellation that is selected stays selected, lit and named either way (the user asked for that
     * one); it is dismissed like any other selection. While the lines are hidden, tapping the sky does
     * not select a constellation, since there is nothing drawn to tap.
     */
    fun setShowConstellations(show: Boolean) {
        renderInputs.showConstellations = show
        _uiState.update { it.copy(showConstellations = show) }
        displayPreferences.edit().putBoolean(KEY_SHOW_CONSTELLATIONS, show).apply()
    }

    /**
     * Shows or hides the constellation illustrations, and keeps the choice across restarts. As with
     * the lines, a selected constellation's illustration is drawn either way.
     */
    fun setShowConstellationArt(show: Boolean) {
        renderInputs.showConstellationArt = show
        _uiState.update { it.copy(showConstellationArt = show) }
        displayPreferences.edit().putBoolean(KEY_SHOW_CONSTELLATION_ART, show).apply()
    }

    /** Shows or hides the Milky Way band, and keeps the choice across restarts. */
    fun setShowMilkyWay(show: Boolean) {
        renderInputs.showMilkyWay = show
        _uiState.update { it.copy(showMilkyWay = show) }
        displayPreferences.edit().putBoolean(KEY_SHOW_MILKY_WAY, show).apply()
    }

    /** Turns night mode (everything in red) on or off, and keeps the choice across restarts. */
    fun setNightMode(on: Boolean) {
        renderInputs.nightMode = on
        _uiState.update { it.copy(nightMode = on) }
        displayPreferences.edit().putBoolean(KEY_NIGHT_MODE, on).apply()
    }

    fun hasLocationPermission(): Boolean = locationSource.hasPermission()

    fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(getApplication(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    /**
     * Switches between the enhanced sky and the camera view. Taps keep working in both: they are
     * resolved with the MVP of the last frame, which in camera mode uses the camera's field of
     * view (see [onCameraFieldOfView]). Each view keeps its own zoom: leaving the camera view
     * restores the enhanced view's field of view.
     */
    fun setViewMode(mode: ViewMode) {
        renderInputs.cameraMode = mode == ViewMode.CAMERA
        if (mode == ViewMode.ENHANCED) renderInputs.fovYDeg = enhancedFovYDeg
        _uiState.update {
            it.copy(
                viewMode = mode,
                cameraProblem = if (mode == ViewMode.CAMERA) null else it.cameraProblem,
                cameraWaiting = false,
                zoom = if (mode == ViewMode.CAMERA) it.cameraZoomRatio else SkyZoom.magnification(enhancedFovYDeg),
            )
        }
    }

    /** The enhanced view's field of view, as last pinched; the camera view's comes from the camera. */
    private var enhancedFovYDeg = SkyRenderInputs.DEFAULT_FOV_Y_DEG

    /** The zoom ratios the bound camera can do; nothing but 1× until it says (see [onCameraZoomRange]). */
    private var cameraZoomRange = 1.0..1.0

    /**
     * A pinch that spread the fingers by [scale] (above 1 zooms in). The enhanced view narrows its field
     * of view (see [SkyZoom]); the camera view changes the camera's zoom ratio, and the sky follows once
     * the camera reports it (see `CameraPreviewLayer`), so the two stay in step.
     */
    fun onPinch(scale: Float) {
        if (_uiState.value.viewMode == ViewMode.CAMERA) {
            setCameraZoomRatio(_uiState.value.cameraZoomRatio * scale)
        } else {
            setEnhancedFov(SkyZoom.pinched(enhancedFovYDeg, scale.toDouble()))
        }
    }

    /** Back to 1× in the view that is showing. */
    fun resetZoom() {
        if (_uiState.value.viewMode == ViewMode.CAMERA) setCameraZoomRatio(1.0) else setEnhancedFov(SkyRenderInputs.DEFAULT_FOV_Y_DEG)
    }

    /** The bound camera's range of zoom ratios; a ratio already chosen is brought inside it. */
    fun onCameraZoomRange(minRatio: Double, maxRatio: Double) {
        if (!(minRatio > 0.0) || maxRatio < minRatio) return
        cameraZoomRange = minRatio..maxRatio
        setCameraZoomRatio(_uiState.value.cameraZoomRatio)
    }

    private fun setEnhancedFov(fovYDeg: Double) {
        enhancedFovYDeg = fovYDeg
        if (_uiState.value.viewMode == ViewMode.ENHANCED) renderInputs.fovYDeg = fovYDeg
        _uiState.update { if (it.viewMode == ViewMode.ENHANCED) it.copy(zoom = SkyZoom.magnification(fovYDeg)) else it }
    }

    private fun setCameraZoomRatio(ratio: Double) {
        val clamped = if (ratio.isFinite()) ratio.coerceIn(cameraZoomRange) else 1.0.coerceIn(cameraZoomRange)
        _uiState.update {
            it.copy(cameraZoomRatio = clamped, zoom = if (it.viewMode == ViewMode.CAMERA) clamped else it.zoom)
        }
    }

    /** The camera view could not be shown: return to the enhanced sky and say why. */
    fun onCameraProblem(problem: CameraProblem) {
        setViewMode(ViewMode.ENHANCED)
        _uiState.update { it.copy(cameraProblem = problem, cameraProblemSeq = it.cameraProblemSeq + 1) }
    }

    /**
     * Removes the camera message. With [expectedSeq] (from [SkyUiState.cameraProblemSeq]) only if
     * it is still that message, so a timeout cannot wipe a newer one.
     */
    fun clearCameraProblem(expectedSeq: Int? = null) {
        _uiState.update {
            if (it.cameraProblem == null || (expectedSeq != null && it.cameraProblemSeq != expectedSeq)) it
            else it.copy(cameraProblem = null)
        }
    }

    /** The camera is busy and CameraX is retrying ([waiting]), or it has opened. */
    fun onCameraWaiting(waiting: Boolean) {
        _uiState.update { if (it.cameraWaiting == waiting) it else it.copy(cameraWaiting = waiting) }
    }

    private val preferences = application.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    /**
     * True once a camera permission request has been found to be blocked (the system will no
     * longer show the dialog), until the permission is granted. See [CameraPermissionPolicy].
     *
     * Kept in SharedPreferences, not memory: after a process restart `shouldShowRequestPermissionRationale`
     * is false both before and after an instantly refused request, so without this the user could
     * never be sent to the settings page again. Excluded from backup (it describes this device).
     */
    var cameraPermissionBlocked: Boolean
        get() = preferences.getBoolean(KEY_CAMERA_PERMISSION_BLOCKED, false)
        set(value) = preferences.edit().putBoolean(KEY_CAMERA_PERMISSION_BLOCKED, value).apply()

    /** The camera permission is held (granted just now, or found granted on resume): forget any complaint about it. */
    fun onCameraPermissionGranted() {
        cameraPermissionBlocked = false
        _uiState.update {
            if (it.cameraProblem == CameraProblem.PERMISSION_DENIED || it.cameraProblem == CameraProblem.PERMISSION_BLOCKED) {
                it.copy(cameraProblem = null)
            } else {
                it
            }
        }
    }

    /** The vertical field of view of the camera preview as laid out, at its zoom ratio, for the projection to match. */
    fun onCameraFieldOfView(fovYDeg: Double) {
        if (_uiState.value.viewMode == ViewMode.CAMERA) renderInputs.fovYDeg = fovYDeg
    }

    fun onResume() {
        orientationSensor.start()
        startLocationUpdates()
        startBodyUpdates()
    }

    fun onPause() {
        orientationSensor.stop()
        locationJob?.cancel()
        locationJob = null
        bodyJob?.cancel()
        bodyJob = null
    }

    /**
     * Recomputes the Sun, Moon and planets now and then every [BODY_UPDATE_INTERVAL_MS]. They are
     * stored in the equatorial frame, so the Earth's rotation does not move them; what changes is
     * their own motion (the Moon drifts about 0.0015° in 10 s, the planets far less) and the Moon's
     * parallax as the observer is carried round, together well under a pixel per update.
     */
    private fun startBodyUpdates() {
        if (bodyJob != null) return
        bodyJob = viewModelScope.launch {
            while (true) {
                updateBodies()
                delay(BODY_UPDATE_INTERVAL_MS)
            }
        }
    }

    /** No-op until a location is known: the Moon's parallax needs the observer. */
    private fun updateBodies() {
        val observer = renderInputs.observer ?: return
        val julianDate = AstroTime.julianDate(System.currentTimeMillis())
        val bodies = SolarSystem.positions(julianDate, observer.latitudeDeg, observer.longitudeDeg)
        val moons = PlanetMoons.positions(julianDate, bodies)
        renderInputs.bodyVertices = BodyVertices.build(bodies, moons)
        _uiState.update { it.copy(bodies = bodies, moons = moons, julianDate = julianDate) }
        refreshRiseSet()
    }

    @SuppressLint("MissingPermission") // guarded by hasPermission()
    fun startLocationUpdates() {
        _uiState.update { it.copy(locationEnabled = locationSource.isLocationEnabled()) }
        if (locationJob != null || !locationSource.hasPermission()) return
        locationJob = viewModelScope.launch {
            locationSource.updates().collect(::onLocation)
        }
    }

    private fun onLocation(location: Location) {
        val altitude = if (location.hasAltitude()) location.altitude else 0.0
        val declination = MagneticDeclination.degrees(
            location.latitude, location.longitude, altitude, System.currentTimeMillis(),
        )
        val observer = Observer(location.latitude, location.longitude, declination)
        renderInputs.observer = observer
        _uiState.update { it.copy(observer = observer, locationEnabled = true) }
        updateBodies() // the Moon's parallax depends on where we are
    }

    override fun onCleared() {
        orientationSensor.stop()
    }

    private companion object {
        const val BODY_UPDATE_INTERVAL_MS = 10_000L

        /** Also named in `res/xml/backup_rules.xml` and `data_extraction_rules.xml`. */
        const val PREFERENCES_NAME = "camera_permission_state"
        const val KEY_CAMERA_PERMISSION_BLOCKED = "blocked"

        /** User settings; unlike the camera file this one is deliberately left in backups. */
        const val DISPLAY_PREFERENCES_NAME = "display_settings"
        const val KEY_LIMITING_MAGNITUDE = "limiting_magnitude"
        const val KEY_SHOW_HORIZON = "show_horizon"
        const val KEY_SHOW_GRID = "show_grid"
        const val KEY_SHOW_DEEP_SKY = "show_deep_sky"
        const val KEY_SHOW_MILKY_WAY = "show_milky_way"
        const val KEY_SHOW_CONSTELLATIONS = "show_constellations"
        const val KEY_SHOW_CONSTELLATION_ART = "show_constellation_art"
        const val KEY_NIGHT_MODE = "night_mode"
    }
}
