package io.proximi.gadkit.explore

import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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

/**
 * The Explore Museum map (Figma "Explore Museum", node 12129:102006; PM screens 2026-10-23), as
 * iOS `ExploreMuseumView`. "Level N" and the floor changer are at the top. The list button and the
 * Spaces, Highlights and Amenities tabs are at the bottom. A tab opens its list over the map
 * ([ExploreCatalog]). A row, or a tap on a gallery or a POI, shows its floor and its card. The
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
    val session = rememberProximiioMapSession(venue.sdk, MapStyle.exploreOptions(language))
    remember(session) { MapStyle.useAmenityIcons(session, context) }
    SideEffect { if (session.options.language != language) session.options = session.options.copy(language = language) }

    val layers = remember { JourneyMapLayers() }
    DisposableEffect(layers) { onDispose { layers.detach() } }

    var catalog by remember { mutableStateOf(ExploreCatalog()) }
    var level by remember { mutableStateOf(0.0) }
    // The open list, or `null` for the map alone.
    var tab by remember { mutableStateOf<ExploreTab?>(null) }
    // The list the list button opens.
    var lastTab by remember { mutableStateOf(ExploreTab.SPACES) }
    // The place whose card is open.
    var selected by remember { mutableStateOf<ExploreItem?>(null) }
    var hasFramed by remember { mutableStateOf(false) }

    // The map area under the level title, the card and the tab bar.
    val insets = FloorWithoutPositioningGuide.insets(resources.displayMetrics.density, top = 130, left = 30, bottom = 220, right = 30)

    /**
     * The lists, at the start and after a change of the venue data or the language. The map is
     * changed on the main thread: in UI tests Compose resumes an effect on the thread of the call it
     * waited for.
     */
    suspend fun load(): Unit = withContext(Dispatchers.Main.immediate) {
        val places = venue.places(language)
        val categories = try {
            venue.sdk.amenities().mapNotNull { amenity -> amenity.title?.let { amenity.id to it } }.toMap()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyMap()
        }
        catalog = ExploreCatalog.of(places, Gallery.destinations(venue.venueGeofences(), places), categories)
        val levelChangers = venue.levelChangers()
        layers.updateLevelChangers(levelChangers)
        // The artworks are not drawn: the galleries and the Highlights list show them.
        session.hiddenFeatureIds = levelChangers.map { it.id }.toSet() + places.filter { it.isArtwork }.map { it.id }
        selected = selected?.let { current -> catalog.all.firstOrNull { it.id == current.id } }
    }

    fun show(newLevel: Double) {
        session.select(FloorKey(newLevel))
        level = newLevel
        if (selected?.let { VenueLevel.same(it.level, newLevel) } == false) selected = null
    }

    /** Closes the list, shows [item]'s floor, frames it and opens its card. */
    fun show(item: ExploreItem) {
        tab = null
        show(item.level)
        selected = item
        val bounds = PlaceFraming.bounds(item.framedPoints, settings.amenityFramingMinimumMeters) ?: return
        session.camera.fitBounds(bounds, insets, animated = true)
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
        val item = selected
        val amenity = (item?.kind as? ExploreItem.Kind.Amenity)?.kind
        layers.updateHighlight(if (item != null && amenity != null) JourneyMapLayers.Highlight(item.coordinate, item.level, amenity.gadIcon) else null)
    }

    val currentCatalog by rememberUpdatedState(catalog)
    val currentLevel by rememberUpdatedState(level)
    DisposableEffect(session) {
        // A tap on a POI of the lists or inside a gallery opens its card; elsewhere it closes the card.
        session.onFeatureTap = { identifiers, coordinate ->
            val all = currentCatalog.all
            tab = null
            selected = identifiers.firstNotNullOfOrNull { id -> all.firstOrNull { it.id == id } }
                ?: currentCatalog.space(Coordinate(coordinate.latitude, coordinate.longitude), currentLevel)
        }
        onDispose { session.onFeatureTap = null }
    }

    BackHandler(enabled = tab != null) { tab = null }

    val backdrop = rememberGlassBackdrop()
    Box(modifier.fillMaxSize()) {
        ProximiioMap(
            session = session,
            modifier = Modifier.fillMaxSize().glassBackdrop(backdrop).softEdges(cornerRadius = 40.dp),
            configure = { canvas -> layers.attach(canvas) },
        )
        ProvideGlassBackdrop(backdrop) {
            Header(level, catalog.levels, onPick = ::show)
            AnimatedVisibility(
                visible = tab != null,
                enter = slideInVertically(tween(250)) { it },
                exit = slideOutVertically(tween(250)) { it },
            ) {
                // Keeps the closing list on screen while it slides out.
                val shown = remember { mutableStateOf(tab ?: lastTab) }
                tab?.let { shown.value = it }
                ListPanel(shown.value, catalog, onPick = ::show, onClose = { tab = null })
            }
            Column(
                Modifier.fillMaxSize().navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Bottom),
            ) {
                val card = selected
                AnimatedVisibility(visible = tab == null && card != null, enter = fadeIn(tween(200)), exit = fadeOut(tween(200))) {
                    val shown = remember { mutableStateOf(card) }
                    card?.let { shown.value = it }
                    shown.value?.let { ExploreCard(it, onClose = { selected = null }) }
                }
                TabBar(
                    tab = tab,
                    onList = {
                        if (tab == null) {
                            selected = null
                            tab = lastTab
                        } else {
                            tab = null
                        }
                    },
                    onTab = { item ->
                        if (tab == item) {
                            tab = null
                        } else {
                            selected = null
                            tab = item
                            lastTab = item
                        }
                    },
                )
            }
        }
    }
}

/** "Level N" and the floor changer. */
@Composable
private fun Header(level: Double, levels: List<Double>, onPick: (Double) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp), verticalAlignment = Alignment.Top) {
        Text(
            stringResource(R.string.level_n, level.roundToInt()),
            Modifier.padding(top = 8.dp).testTag("exploreLevel"),
            style = GadTextStyle.CardTitle.textStyle,
            color = Palette.ink,
        )
        Spacer(Modifier.weight(1f))
        FloorChanger(level, levels, onPick)
    }
}

/**
 * The floor changer of the PM screens: the level in a tile of the level colour, a chevron under it,
 * in a frosted frame. Lists the museum's levels, highest first.
 */
@Composable
private fun FloorChanger(level: Double, levels: List<Double>, onPick: (Double) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val label = GuidanceText.floorLabel(level)
    val color = Palette.level(level.roundToInt())
    // From the bottom leading corner (100 %) to the top trailing corner (45 %).
    val tile = Brush.linearGradient(listOf(color, color.copy(alpha = 0.45f)), start = Offset(0f, Float.POSITIVE_INFINITY), end = Offset(Float.POSITIVE_INFINITY, 0f))
    val description = stringResource(R.string.floor_x, label)
    Box {
        Column(
            Modifier
                .glassSurface(RoundedCornerShape(14.dp), GlassStrength.Frosted)
                .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button) { expanded = true }
                .semantics { contentDescription = description }
                .padding(start = 6.dp, end = 6.dp, top = 6.dp, bottom = 4.dp)
                .testTag("exploreFloorChanger"),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box(Modifier.size(46.dp).background(tile, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                Text(label, style = GadTextStyle.BodyBold.textStyle, color = Palette.ink)
            }
            Icon(painterResource(R.drawable.ic_chevron_down), contentDescription = null, Modifier.size(11.dp), tint = Palette.ink)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, containerColor = Palette.white) {
            levels.asReversed().forEach { candidate ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.level_n, candidate.roundToInt()), style = GadTextStyle.BodyMedium.textStyle) },
                    onClick = {
                        expanded = false
                        onPick(candidate)
                    },
                )
            }
        }
    }
}

/** The list button and the Spaces, Highlights and Amenities tabs. */
@Composable
private fun TabBar(tab: ExploreTab?, onList: () -> Unit, onTab: (ExploreTab) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        GlassSquareButton(onClick = onList, contentDescription = stringResource(R.string.list), isSelected = tab != null) {
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
                val isSelected = tab == item
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .then(if (isSelected) Modifier.border(1.5.dp, Palette.ink, RoundedCornerShape(10.dp)) else Modifier)
                        .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Tab) { onTab(item) }
                        .semantics { selected = isSelected },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(item.title), style = GadTextStyle.BodySmallRegular.textStyle, color = Palette.ink)
                }
            }
        }
    }
}

/** The list of [tab] over the lower part of the map. Dragging its top edge down closes it. */
@Composable
private fun ListPanel(tab: ExploreTab, catalog: ExploreCatalog, onPick: (ExploreItem) -> Unit, onClose: () -> Unit) {
    val items = when (tab) {
        ExploreTab.SPACES -> catalog.spaces
        ExploreTab.HIGHLIGHTS -> catalog.highlights
        ExploreTab.AMENITIES -> catalog.amenities
    }
    val count = items.size
    val levelCount = items.map { it.level.roundToInt() }.distinct().size
    val levels = pluralStringResource(R.plurals.n_levels, levelCount, levelCount)
    // "8 spaces · 4 levels", "6 works", "12 amenities · 5 levels".
    val summary = when (tab) {
        ExploreTab.SPACES -> pluralStringResource(R.plurals.n_spaces, count, count) + " · " + levels
        ExploreTab.HIGHLIGHTS -> pluralStringResource(R.plurals.n_works, count, count)
        ExploreTab.AMENITIES -> pluralStringResource(R.plurals.n_amenities, count, count) + " · " + levels
    }
    val closeDistance = with(LocalDensity.current) { 60.dp.toPx() }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(maxHeight * 0.72f)
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
                    Text(stringResource(tab.title), Modifier.alignByBaseline(), style = GadTextStyle.CardTitle.textStyle, color = Palette.ink)
                    Spacer(Modifier.weight(1f))
                    Text(summary, Modifier.alignByBaseline(), style = GadTextStyle.BodySmallRegular.textStyle, color = Palette.ink)
                }
            }
            val groups = ExploreCatalog.byLevel(items)
            LazyColumn(
                Modifier.fillMaxWidth().weight(1f),
                // Clear of the tab bar.
                contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 110.dp),
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
                                if (item.kind != ExploreItem.Kind.Highlight) ExploreIcon(item)
                                ExploreText(item, Modifier.weight(1f))
                                Text(GuidanceText.floorLabel(item.level), style = GadTextStyle.BodyBold.textStyle, color = Palette.ink)
                            }
                            HorizontalDivider(thickness = 1.dp, color = Palette.ink.copy(alpha = 0.12f))
                        }
                    }
                }
            }
        }
    }
}

/** The card of a place on the map: the icon, the title and its lines, the level and a close button. */
@Composable
private fun ExploreCard(item: ExploreItem, onClose: () -> Unit) {
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
            if (item.kind != ExploreItem.Kind.Highlight) ExploreIcon(item)
            ExploreText(item, Modifier.weight(1f))
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

/** The title (italic for an artwork) and the lines under it. */
@Composable
private fun ExploreText(item: ExploreItem, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            item.title,
            style = GadTextStyle.BodyBold.textStyle,
            fontStyle = if (item.kind == ExploreItem.Kind.Highlight) FontStyle.Italic else FontStyle.Normal,
            color = Palette.ink,
        )
        item.lines.forEach { line -> Text(line, style = GadTextStyle.BodySmallRegular.textStyle, color = Palette.ink) }
    }
}

/** The icon in a white circle: the amenity's icon, or a symbol for the gallery's category. */
@Composable
private fun ExploreIcon(item: ExploreItem) {
    Box(
        Modifier
            .size(44.dp)
            .glassShadow(CircleShape, Color.Black.copy(alpha = 0.08f), offsetY = 2.dp, blur = 6.dp)
            .background(Palette.white, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        when (val kind = item.kind) {
            is ExploreItem.Kind.Amenity -> MapIcon(kind.kind.gadIcon.glyph, size = 22)
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
