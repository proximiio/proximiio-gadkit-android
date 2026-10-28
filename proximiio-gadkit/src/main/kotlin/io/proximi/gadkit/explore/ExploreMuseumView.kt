package io.proximi.gadkit.explore

import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.runtime.mutableIntStateOf
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.geometry.LatLng
import io.proximi.map.kit.MapContentInsets
import androidx.compose.ui.draw.rotate
import androidx.compose.animation.animateContentSize
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.key
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.material3.ripple
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import io.proximi.gadkit.map.galleryDestinations
import io.proximi.gadkit.map.AmenityListing
import io.proximi.gadkit.map.AmenityKind
import io.proximi.map.live.AmenityIcon
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.proximi.gadkit.Coordinate
import io.proximi.gadkit.R
import io.proximi.gadkit.VenueService
import io.proximi.gadkit.designsystem.GadTextStyle
import io.proximi.gadkit.designsystem.GlassStrength
import io.proximi.gadkit.designsystem.Palette
import io.proximi.gadkit.designsystem.ProvideGlassBackdrop
import io.proximi.gadkit.designsystem.glassBackdrop
import io.proximi.gadkit.designsystem.glassShadow
import io.proximi.gadkit.designsystem.glassSurface
import io.proximi.gadkit.designsystem.rememberGlassBackdrop
import io.proximi.gadkit.map.FloorWithoutPositioningGuide
import io.proximi.gadkit.map.Gallery
import io.proximi.gadkit.map.GalleryFill
import io.proximi.gadkit.map.GlassSquareButton
import io.proximi.gadkit.map.GuidanceText
import io.proximi.gadkit.map.JourneyMapLayers
import io.proximi.gadkit.map.MapIcon
import io.proximi.gadkit.map.MapLanguage
import io.proximi.gadkit.map.MapLoadingSpinner
import io.proximi.gadkit.map.MapStyle
import io.proximi.gadkit.map.PlaceFraming
import io.proximi.gadkit.map.VenueLevel
import io.proximi.gadkit.map.gadIcon
import io.proximi.gadkit.map.levelChangers
import io.proximi.gadkit.map.places
import io.proximi.gadkit.map.softEdges
import io.proximi.gadkit.map.venueGeofences
import io.proximi.map.compose.ProximiioMap
import io.proximi.map.compose.rememberProximiioMapSession
import io.proximi.map.core.FloorKey
import io.proximi.sdk.amenities
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.withContext

/** The lists of the Explore Museum map. */
internal enum class ExploreTab(val title: Int) {
    SPACES(R.string.spaces),
    HIGHLIGHTS(R.string.highlights),
    AMENITIES(R.string.amenities),
}

/** What the panel over the lower part of the map shows. */
internal sealed interface ExplorePanel {
    data class TabList(val tab: ExploreTab) : ExplorePanel
    data object Legend : ExplorePanel
}

/**
 * The Explore Museum map (Figma "Explore Museum", node 12129:102006; PM screens 2026-10-23), as
 * iOS `ExploreMuseumView`. "Level N" and the floor changer are at the top. The legend button and
 * the Spaces, Highlights and Amenities tabs are at the bottom. A tab opens its list over the map
 * ([ExploreCatalog]); the legend button opens the map legend (PM, Monday "Explore Museum: Map legend
 * missing", 2026-10-23). A row, or a tap on a gallery or a POI, shows its floor and its card. The
 * galleries of the shown floor are filled with the level colour; while a gallery's card is open, the
 * other galleries are lighter.
 *
 * The visitor is not at the museum here: the map has no position dot, no route and a camera that
 * does not follow ([MapStyle.exploreOptions]). The design's About the Building and Collections tabs
 * have no content in the app (DAILY-CALL N14).
 */
@Composable
internal fun ExploreMuseumView(venue: VenueService, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val language = MapLanguage.forAppLanguage(LocalConfiguration.current.locales[0]?.language)
    val settings = remember { venue.settings }
    val session = rememberProximiioMapSession(venue.sdk, MapStyle.exploreOptions(language, settings))
    remember(session) { MapStyle.useAmenityIcons(session, context, galleryIcons = true) }
    SideEffect { if (session.options.language != language) session.options = session.options.copy(language = language) }

    val layers = remember { JourneyMapLayers() }
    DisposableEffect(layers) { onDispose { layers.detach() } }

    var catalog by remember { mutableStateOf(ExploreCatalog()) }
    var level by remember { mutableStateOf(0.0) }
    // The open list or the legend, or `null` for the map alone.
    var panel by remember { mutableStateOf<ExplorePanel?>(null) }
    // The amenities' icons from the venue data, by amenity id.
    var amenityIcons by remember { mutableStateOf<Map<String, ImageBitmap>>(emptyMap()) }
    // The place whose card is open.
    var selected by remember { mutableStateOf<ExploreItem?>(null) }
    var hasFramed by remember { mutableStateOf(false) }
    // The features the map does not draw: artworks, level changers (drawn by `layers`) and the
    // amenities of [AmenityKind].
    var hiddenFeatureIds by remember { mutableStateOf(emptySet<String>()) }
    // The size of the map, in px.
    var mapSize by remember { mutableStateOf(IntSize.Zero) }
    // The MapLibre map, to place the card's line and dot on the picked place.
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    // Counts the camera moves: the line and the dot follow the place when the visitor moves the map.
    var cameraMoves by remember { mutableIntStateOf(0) }
    val density = resources.displayMetrics.density

    // The map area under the level title and above the tab bar.
    val insets = FloorWithoutPositioningGuide.insets(density, top = 130, left = 30, bottom = 120, right = 30)

    /**
     * The lists, at the start and after a change of the venue data or the language. The map is
     * changed on the main thread: in UI tests Compose resumes an effect on the thread of the call it
     * waited for.
     */
    suspend fun load(): Unit = withContext(Dispatchers.Main.immediate) {
        val places = venue.places(language)
        val amenities = try {
            venue.sdk.amenities().distinctBy { it.id }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyList()
        }
        amenityIcons = withContext(Dispatchers.Default) {
            amenities.mapNotNull { amenity -> amenity.icon?.let(AmenityIcon::rasterImage)?.let { amenity.id to it.asImageBitmap() } }.toMap()
        }
        val categories = amenities.mapNotNull { amenity -> amenity.title?.let { amenity.id to it } }.toMap()
        val levelChangers = venue.levelChangers()
        val changerTypes = levelChangers.map { it.featureType }.toSet()
        val changerTitles = mapOf("elevator" to R.string.elevator, "staircase" to R.string.stairs)
            .filterKeys { it in changerTypes }
            .mapValues { (_, title) -> resources.getString(title) }
        catalog = ExploreCatalog.of(places, venue.galleryDestinations(places), categories, changerTitles, AmenityListing.facilities(amenities))
        layers.updateLevelChangers(levelChangers)
        // The artworks are not drawn: the galleries and the Highlights list show them. Nor are the
        // amenities: the Amenities list and the card's dot show them (PM, Monday "Explore museum view:
        // default map view", 2026-10-26).
        val amenityPlaces = places.filter { place -> !place.isArtwork && AmenityKind.entries.any { it.matches(place) } }
        hiddenFeatureIds = levelChangers.map { it.id }.toSet() + places.filter { it.isArtwork }.map { it.id } + amenityPlaces.map { it.id }
        selected = selected?.let { current -> catalog.all.firstOrNull { it.id == current.id } }
    }

    fun show(newLevel: Double) {
        session.select(FloorKey(newLevel))
        level = newLevel
        if (selected?.let { VenueLevel.same(it.level, newLevel) } == false) selected = null
    }

    /**
     * Closes the panel, shows [item]'s floor and opens its card. The place is framed around the card's
     * target point ([ExploreCalloutLayout]).
     */
    fun show(item: ExploreItem) {
        panel = null
        show(item.level)
        selected = item
        val bounds = PlaceFraming.bounds(ExploreCalloutLayout.framedPoints(item), settings.amenityFramingMinimumMeters) ?: return
        val framing = ExploreCalloutLayout.framingInsets(mapSize.width / density, mapSize.height / density)
        session.camera.fitBounds(
            bounds,
            MapContentInsets(topPx = framing.top * density.toDouble(), leftPx = framing.left * density.toDouble(), bottomPx = framing.bottom * density.toDouble(), rightPx = framing.right * density.toDouble()),
            animated = true,
        )
    }

    LaunchedEffect(session, language) {
        load()
        if (hasFramed) return@LaunchedEffect
        hasFramed = true
        withContext(Dispatchers.Main.immediate) {
            // The lowest floor with a gallery, framed on the whole museum.
            show(catalog.spaces.minOfOrNull { it.level } ?: catalog.levels.firstOrNull() ?: 0.0)
            PlaceFraming.bounds(catalog.spaces.flatMap { it.framedPoints }, minimumMeters = 60.0)?.let { bounds ->
                session.camera.fitBounds(bounds, insets, animated = false)
            }
        }
    }
    LaunchedEffect(session, language) { venue.data.version.drop(1).collect { load() } }

    // A floor picked on the map itself.
    LaunchedEffect(session) {
        session.selectedFloor.collect { floor ->
            if (VenueLevel.same(floor.level, level)) return@collect
            level = floor.level
        }
    }

    // The galleries of the shown floor and the marker of a selected amenity.
    LaunchedEffect(catalog, level, selected) {
        val focus = selected?.takeIf { it.kind == ExploreItem.Kind.Space }?.id
        val fills = catalog.spaces.mapNotNull { item -> item.gallery?.let { GalleryFill(it, isDestination = focus == null || focus == item.id) } }
        layers.update(fills, level)
    }
    // The selected place's own POI is not drawn: the card's dot marks it (PM, Monday "Explore Museum
    // view: gallery POI view", 2026-10-27).
    LaunchedEffect(hiddenFeatureIds, selected) { session.hiddenFeatureIds = hiddenFeatureIds + listOfNotNull(selected?.id) }
    DisposableEffect(map) {
        val loaded = map
        val listener = MapLibreMap.OnCameraMoveListener { cameraMoves += 1 }
        loaded?.addOnCameraMoveListener(listener)
        onDispose { loaded?.removeOnCameraMoveListener(listener) }
    }

    val currentShow by rememberUpdatedState<(ExploreItem) -> Unit> { item -> show(item) }
    val currentCatalog by rememberUpdatedState(catalog)
    val currentLevel by rememberUpdatedState(level)
    DisposableEffect(session) {
        // A tap on a POI of the lists or inside a gallery opens its card; elsewhere it closes the card.
        session.onFeatureTap = { identifiers, coordinate ->
            val all = currentCatalog.all
            val item = identifiers.firstNotNullOfOrNull { id -> all.firstOrNull { it.id == id } }
                ?: currentCatalog.space(Coordinate(coordinate.latitude, coordinate.longitude), currentLevel)
            if (item != null) {
                currentShow(item)
            } else {
                panel = null
                selected = null
            }
        }
        onDispose { session.onFeatureTap = null }
    }

    /** Opens [target], or closes it when it is open. */
    fun toggle(target: ExplorePanel) {
        if (panel == target) {
            panel = null
        } else {
            selected = null
            panel = target
        }
    }

    BackHandler(enabled = panel != null) { panel = null }

    val backdrop = rememberGlassBackdrop()
    Box(modifier.fillMaxSize().onSizeChanged { mapSize = it }) {
        ProximiioMap(
            session = session,
            modifier = Modifier.fillMaxSize().glassBackdrop(backdrop).softEdges(cornerRadius = 40.dp),
            configure = { canvas ->
                layers.attach(canvas)
                canvas.onStyleLoaded { loaded, _ -> map = loaded }
            },
        )
        ProvideGlassBackdrop(backdrop) {
            MapLoadingSpinner(session, settings.mapLoadingTimeout)
            val card = selected
            AnimatedVisibility(visible = panel == null && card != null, enter = fadeIn(tween(200)), exit = fadeOut(tween(200))) {
                val shown = remember { mutableStateOf(card) }
                card?.let { shown.value = it }
                shown.value?.let { item ->
                    // Read on every camera move: the line and the dot follow the place.
                    val point = remember(cameraMoves, item, map, mapSize) {
                        map?.projection?.toScreenLocation(LatLng(item.coordinate.latitude, item.coordinate.longitude))?.let { Offset(it.x, it.y) }
                    }
                    ExploreCallout(item, amenityIcons, mapSize, point, onClose = { selected = null })
                }
            }
            Header(level, catalog.levels, onPick = ::show)
            AnimatedVisibility(
                visible = panel != null,
                enter = slideInVertically(tween(250)) { it },
                exit = slideOutVertically(tween(250)) { it },
            ) {
                // Keeps the closing panel on screen while it slides out.
                val shown = remember { mutableStateOf(panel ?: ExplorePanel.Legend) }
                panel?.let { shown.value = it }
                Panel(shown.value, catalog, amenityIcons, onPick = ::show, onClose = { panel = null })
            }
            Column(
                Modifier.fillMaxSize().navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Bottom),
            ) {
                TabBar(panel, onToggle = ::toggle)
            }
        }
    }
}

/** "Level N" and the floor changer. */
@Composable
private fun Header(level: Double, levels: List<Double>, onPick: (Double) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp), verticalAlignment = Alignment.Top) {
        // Regular 24 as in the Figma frame 12129:102006 (PM, Monday "Font size and style", 2026-10-26).
        Text(
            stringResource(R.string.level_n, level.roundToInt()),
            Modifier.padding(top = 8.dp).testTag("exploreLevel"),
            style = GadTextStyle.H1Regular.textStyle,
            color = Palette.ink,
        )
        Spacer(Modifier.weight(1f))
        // Shown once the museum's levels are known, so its menu is never empty.
        if (levels.isNotEmpty()) FloorChanger(level, levels, onPick)
    }
}

/**
 * The floor changer of the PM screens: the level in a tile of the level colour, a chevron under it,
 * in a frosted frame. Open, it shows a glass button for each of the museum's levels, highest first,
 * the shown level in bold, and a chevron that closes it (PM, Monday "Explore museum: Level selector
 * dropdown", 2026-10-28).
 */
@Composable
private fun FloorChanger(level: Double, levels: List<Double>, onPick: (Double) -> Unit) {
    var isShowingLevels by remember { mutableStateOf(false) }
    val label = GuidanceText.floorLabel(level)
    val color = Palette.level(level.roundToInt())
    // From the bottom leading corner (100 %) to the top trailing corner (45 %).
    val tile = Brush.linearGradient(listOf(color, color.copy(alpha = 0.45f)), start = Offset(0f, Float.POSITIVE_INFINITY), end = Offset(Float.POSITIVE_INFINITY, 0f))
    val description = stringResource(R.string.floor_x, label)
    val close = stringResource(R.string.close)
    Column(
        Modifier
            .glassSurface(RoundedCornerShape(14.dp), GlassStrength.Frosted)
            .animateContentSize(tween(200))
            .padding(start = 6.dp, end = 6.dp, top = 6.dp, bottom = 4.dp)
            .testTag("exploreFloorChanger"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(if (isShowingLevels) 8.dp else 4.dp),
    ) {
        if (isShowingLevels) {
            levels.asReversed().forEach { candidate ->
                val isShown = VenueLevel.same(candidate, level)
                GlassSquareButton(
                    onClick = {
                        isShowingLevels = false
                        onPick(candidate)
                    },
                    contentDescription = stringResource(R.string.level_n, candidate.roundToInt()),
                    isSelected = isShown,
                ) {
                    Text(
                        GuidanceText.floorLabel(candidate),
                        style = (if (isShown) GadTextStyle.BodyBold else GadTextStyle.BodyRegular).textStyle,
                        color = Palette.ink,
                    )
                }
            }
            Box(
                Modifier
                    .size(width = 50.dp, height = 18.dp)
                    .clickable(remember { MutableInteractionSource() }, indication = ripple(), role = Role.Button) { isShowingLevels = false }
                    .semantics { contentDescription = close },
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(R.drawable.ic_chevron_down), contentDescription = null, Modifier.size(11.dp).rotate(180f), tint = Palette.ink)
            }
        } else {
            Column(
                Modifier
                    .clickable(remember { MutableInteractionSource() }, indication = ripple(), role = Role.Button) { isShowingLevels = true }
                    .semantics { contentDescription = description },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(Modifier.size(46.dp).background(tile, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                    Text(label, style = GadTextStyle.BodyBold.textStyle, color = Palette.ink)
                }
                Icon(painterResource(R.drawable.ic_chevron_down), contentDescription = null, Modifier.size(11.dp), tint = Palette.ink)
            }
        }
    }
}

/** The legend button and the Spaces, Highlights and Amenities tabs. */
@Composable
private fun TabBar(panel: ExplorePanel?, onToggle: (ExplorePanel) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        GlassSquareButton(
            onClick = { onToggle(ExplorePanel.Legend) },
            contentDescription = stringResource(R.string.map_legend),
            isSelected = panel == ExplorePanel.Legend,
        ) {
            MapIcon(R.drawable.ic_list, size = 20)
        }
        Row(
            Modifier
                .weight(1f)
                .height(50.dp)
                .glassSurface(RoundedCornerShape(12.dp), GlassStrength.Frosted)
                .padding(5.dp),
        ) {
            ExploreTab.entries.forEach { item ->
                val isSelected = panel == ExplorePanel.TabList(item)
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .then(if (isSelected) Modifier.border(1.dp, Palette.ink, RoundedCornerShape(10.dp)) else Modifier)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(remember { MutableInteractionSource() }, indication = ripple(), role = Role.Tab) { onToggle(ExplorePanel.TabList(item)) }
                        .semantics { selected = isSelected },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(item.title), style = GadTextStyle.BodySmallRegular.textStyle, color = Palette.ink)
                }
            }
        }
    }
}

/** A list or the legend over the lower part of the map. Dragging its top edge down closes it. */
@Composable
private fun Panel(panel: ExplorePanel, catalog: ExploreCatalog, icons: Map<String, ImageBitmap>, onPick: (ExploreItem) -> Unit, onClose: () -> Unit) {
    val closeDistance = with(LocalDensity.current) { 60.dp.toPx() }
    // The tab bar: the navigation bar, 16 dp, the bar's 50 dp and 6 dp.
    val tabBarHeight = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 72.dp
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(maxHeight * if (panel == ExplorePanel.Legend) 0.62f else 0.72f)
                .glassSurface(RoundedCornerShape(topStart = 40.dp, topEnd = 40.dp), GlassStrength.Frosted)
                .testTag("explorePanel"),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        var dragged = 0f
                        detectVerticalDragGestures(
                            onDragStart = { dragged = 0f },
                            onDragEnd = { if (dragged > closeDistance) onClose() },
                        ) { _, amount -> dragged += amount }
                    }
                    .padding(start = 24.dp, end = 24.dp, top = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(Modifier.width(44.dp).height(5.dp).background(Palette.ink.copy(alpha = 0.25f), CircleShape))
                Row(Modifier.fillMaxWidth()) {
                    val title = when (panel) {
                        is ExplorePanel.TabList -> stringResource(panel.tab.title)
                        ExplorePanel.Legend -> stringResource(R.string.map_legend)
                    }
                    val style = if (panel == ExplorePanel.Legend) GadTextStyle.BodySmallSemiBold else GadTextStyle.CardTitle
                    Text(title, Modifier.alignByBaseline(), style = style.textStyle, color = Palette.ink)
                    Spacer(Modifier.weight(1f))
                    if (panel is ExplorePanel.TabList) {
                        Text(summary(panel.tab, catalog), Modifier.alignByBaseline(), style = GadTextStyle.BodySmallRegular.textStyle, color = Palette.ink)
                    }
                }
            }
            // Each panel scrolls on its own: a list opened after another starts at its top.
            key(panel) {
                val content = Modifier.fillMaxWidth().weight(1f).fadesOut(above = tabBarHeight)
                // The last rows scroll up clear of the fade (RPR, Monday "Explore museum: list fade", 2026-10-28).
                val bottom = tabBarHeight + LIST_FADE
                when (panel) {
                    is ExplorePanel.TabList -> TabList(items(panel.tab, catalog), icons, onPick, content, bottom)
                    ExplorePanel.Legend -> Legend(catalog, icons, content, bottom)
                }
            }
        }
    }
}

private fun items(tab: ExploreTab, catalog: ExploreCatalog): List<ExploreItem> = when (tab) {
    ExploreTab.SPACES -> catalog.spaces
    ExploreTab.HIGHLIGHTS -> catalog.highlights
    ExploreTab.AMENITIES -> catalog.amenities
}

/** "8 spaces · 4 levels", "6 works", "12 amenities · 5 levels". */
@Composable
private fun summary(tab: ExploreTab, catalog: ExploreCatalog): String {
    val items = items(tab, catalog)
    val count = items.size
    val levelCount = items.map { it.level.roundToInt() }.distinct().size
    val levels = pluralStringResource(R.plurals.n_levels, levelCount, levelCount)
    return when (tab) {
        ExploreTab.SPACES -> pluralStringResource(R.plurals.n_spaces, count, count) + " · " + levels
        ExploreTab.HIGHLIGHTS -> pluralStringResource(R.plurals.n_works, count, count)
        ExploreTab.AMENITIES -> pluralStringResource(R.plurals.n_amenities, count, count) + " · " + levels
    }
}

/**
 * Fades the content out toward [above] dp from its bottom and leaves that part clear: the rows do
 * not show through the glass of the tab bar.
 */
private fun Modifier.fadesOut(above: Dp, fade: Dp = LIST_FADE): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            if (size.height <= 0f) return@drawWithContent
            val end = ((size.height - above.toPx()) / size.height).coerceIn(0f, 1f)
            val start = ((size.height - above.toPx() - fade.toPx()) / size.height).coerceIn(0f, end)
            drawRect(
                Brush.verticalGradient(0f to Color.Black, start to Color.Black, end to Color.Transparent, 1f to Color.Transparent),
                blendMode = BlendMode.DstIn,
            )
        }

/** The rows of a list, by level. */
@Composable
private fun TabList(items: List<ExploreItem>, icons: Map<String, ImageBitmap>, onPick: (ExploreItem) -> Unit, modifier: Modifier, bottom: Dp) {
    val groups = ExploreCatalog.byLevel(items)
    LazyColumn(
        modifier,
        // Clear of the tab bar and the fade above it.
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = bottom),
    ) {
        if (groups.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.nothing_here_yet),
                    Modifier.padding(top = 20.dp),
                    style = GadTextStyle.BodySmallRegular.textStyle,
                    color = Palette.ink,
                )
            }
        }
        groups.forEach { group ->
            item(key = "level-${group.level}") {
                Text(
                    stringResource(R.string.level_n, group.level.roundToInt()).uppercase(),
                    Modifier.padding(top = 20.dp, bottom = 4.dp),
                    style = GadTextStyle.Caption.textStyle.copy(letterSpacing = 1.5.sp),
                    color = Palette.ink,
                )
            }
            items(group.items, key = { it.id }) { item ->
                Column {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button) { onPick(item) }
                            .padding(vertical = 14.dp)
                            .testTag("exploreRow"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        if (item.kind != ExploreItem.Kind.Highlight) ExploreIcon(item, icons)
                        ExploreText(item, Modifier.weight(1f))
                        Text(GuidanceText.floorLabel(item.level), style = GadTextStyle.BodyBold.textStyle, color = Palette.ink)
                    }
                    HorizontalDivider(thickness = 1.dp, color = Palette.ink.copy(alpha = 0.12f))
                }
            }
        }
    }
}

/**
 * The map legend of the PM's screen: the facilities and the galleries the museum's POIs use, with
 * their map icons.
 */
@Composable
private fun Legend(catalog: ExploreCatalog, icons: Map<String, ImageBitmap>, modifier: Modifier, bottom: Dp) {
    Row(
        modifier
            .verticalScroll(rememberScrollState())
            // Clear of the tab bar and the fade above it.
            .padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = bottom)
            .testTag("exploreLegend"),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalAlignment = Alignment.Top,
    ) {
        LegendColumn(stringResource(R.string.facilities), catalog.legendFacilities, icons, Modifier.weight(1f))
        LegendColumn(stringResource(R.string.galleries), catalog.legendGalleries, icons, Modifier.weight(1f))
    }
}

@Composable
private fun LegendColumn(title: String, entries: List<ExploreLegendEntry>, icons: Map<String, ImageBitmap>, modifier: Modifier) {
    Column(modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(title, style = GadTextStyle.BodySmallRegular.textStyle, color = Palette.ink)
        entries.forEach { entry ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LegendGlyph(entry, icons)
                Text(entry.title, style = GadTextStyle.BodySmallBold.textStyle, color = Palette.ink)
            }
        }
    }
}

/**
 * The legend's glyph of an amenity, 30 dp: the design's glyph of its kind, else its icon in the
 * venue data, else a symbol for a gallery category.
 */
@Composable
private fun LegendGlyph(entry: ExploreLegendEntry, icons: Map<String, ImageBitmap>) {
    val kind = AmenityKind.kind(entry.amenityId, entry.title)
    val icon = icons[entry.amenityId]
    Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) {
        when {
            kind != null -> MapIcon(kind.gadIcon.glyph, size = 22)
            icon != null -> Image(icon, contentDescription = null, Modifier.fillMaxSize(), filterQuality = FilterQuality.High)
            else -> MapIcon(symbolIcon(ExploreItem.symbol(entry.title)), size = 24)
        }
    }
}

/**
 * The card of a place picked on the map (PM, Monday "Explore Museum view: gallery POI view",
 * 2026-10-26): over the map above the place, a line down to the place, and the place as a black dot
 * in a translucent ring with its title under it. The map frames the place at
 * [ExploreCalloutLayout.target]; the line and the dot follow the place ([point], in px) when the
 * visitor moves the map.
 */
@Composable
private fun ExploreCallout(item: ExploreItem, icons: Map<String, ImageBitmap>, mapSize: IntSize, point: Offset?, onClose: () -> Unit) {
    val density = LocalDensity.current
    val (targetX, targetY) = ExploreCalloutLayout.target(mapSize.width.toFloat(), mapSize.height.toFloat())
    val place = point ?: Offset(targetX, targetY)
    val cardBottom = targetY - with(density) { 55.dp.toPx() }
    val ink = Palette.ink
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize().clearAndSetSemantics {}) {
            if (place.y > cardBottom) {
                drawLine(ink, Offset(place.x, cardBottom), place, strokeWidth = 1.dp.toPx())
            }
            // The black dot in a ring at 30 % with a white border.
            drawCircle(ink.copy(alpha = 0.3f), radius = 10.5.dp.toPx(), center = place)
            drawCircle(Color.White.copy(alpha = 0.7f), radius = 11.dp.toPx(), center = place, style = Stroke(width = 1.dp.toPx()))
            drawCircle(ink, radius = 4.135.dp.toPx(), center = place)
        }
        Text(
            item.title,
            Modifier
                .offset { IntOffset((place.x - 70.dp.toPx()).roundToInt(), (place.y + 18.dp.toPx()).roundToInt()) }
                .width(140.dp)
                .clearAndSetSemantics {},
            style = GadTextStyle.BodySmallBold.textStyle,
            color = ink,
            textAlign = TextAlign.Center,
        )
        Box(
            Modifier.fillMaxWidth().height(with(density) { cardBottom.coerceAtLeast(0f).toDp() }).padding(horizontal = 32.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            ExploreCard(item, icons, onClose)
        }
    }
}

/** The card of a place on the map: the icon, the title and its lines, the level and a close button. */
@Composable
private fun ExploreCard(item: ExploreItem, icons: Map<String, ImageBitmap>, onClose: () -> Unit) {
    val close = stringResource(R.string.close)
    Box(
        Modifier
            .fillMaxWidth()
            .glassSurface(RoundedCornerShape(16.dp), GlassStrength.Frosted)
            // Taps on the card do not reach the map under it.
            .pointerInput(Unit) { detectTapGestures {} }
            .testTag("exploreCard"),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 36.dp, top = 16.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (item.kind != ExploreItem.Kind.Highlight) ExploreIcon(item, icons)
            ExploreText(item, Modifier.weight(1f), isCard = true)
            Text(GuidanceText.floorLabel(item.level), style = GadTextStyle.BodyBold.textStyle, color = Palette.ink)
        }
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .size(36.dp)
                .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onClose)
                .semantics { contentDescription = close },
            contentAlignment = Alignment.Center,
        ) {
            MapIcon(R.drawable.ic_map_close, size = 12)
        }
    }
}

/**
 * The title (italic for an artwork) and the lines under it. The card's text is larger than the list's:
 * Bold 18 and Regular 16 (Figma frame 12129:102006).
 */
@Composable
private fun ExploreText(item: ExploreItem, modifier: Modifier = Modifier, isCard: Boolean = false) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            item.title,
            style = (if (isCard) GadTextStyle.LargeBodyBold else GadTextStyle.BodyBold).textStyle,
            fontStyle = if (item.kind == ExploreItem.Kind.Highlight) FontStyle.Italic else FontStyle.Normal,
            color = Palette.ink,
        )
        val lineStyle = if (isCard) GadTextStyle.BodyRegular else GadTextStyle.BodySmallRegular
        item.lines.forEach { line -> Text(line, style = lineStyle.textStyle, color = Palette.ink) }
    }
}

/**
 * The icon in a white circle: the design's glyph of an amenity kind, else the icon of the POI's
 * amenity in the venue data (the icon the map draws), else a symbol for the gallery's category.
 */
@Composable
private fun ExploreIcon(item: ExploreItem, icons: Map<String, ImageBitmap>) {
    val icon = item.amenityId?.let { icons[it] }
    Box(
        Modifier
            .size(44.dp)
            .glassShadow(CircleShape, Color.Black.copy(alpha = 0.08f), offsetY = 2.dp, blur = 6.dp)
            .background(Palette.white, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        val kind = item.kind
        when {
            kind is ExploreItem.Kind.Amenity -> MapIcon(kind.kind.gadIcon.glyph, size = 22)
            // The data's icon is a 30 px marker, a white circle with the glyph: it fills the circle.
            icon != null -> Image(icon, contentDescription = null, Modifier.fillMaxSize(), filterQuality = FilterQuality.High)
            else -> MapIcon(symbolIcon(ExploreItem.symbol(item.category)), size = 18)
        }
    }
}

private fun symbolIcon(symbol: ExploreItem.Symbol): Int = when (symbol) {
    ExploreItem.Symbol.PALETTE -> R.drawable.ic_explore_palette
    ExploreItem.Symbol.GRID -> R.drawable.ic_explore_grid
    ExploreItem.Symbol.STAR -> R.drawable.ic_explore_star
    ExploreItem.Symbol.COLUMNS -> R.drawable.ic_explore_columns
}

/** The height over which the lists fade out above the tab bar (RPR, Monday "Explore museum: list fade", 2026-10-28; was 30). */
private val LIST_FADE = 90.dp
