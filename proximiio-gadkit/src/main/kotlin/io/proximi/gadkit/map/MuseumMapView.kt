package io.proximi.gadkit.map

import io.proximi.gadkit.DebugLaunch
import android.content.res.Resources
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.proximi.gadkit.R
import io.proximi.gadkit.Coordinate
import io.proximi.gadkit.map.AmenityKind
import io.proximi.gadkit.map.Gallery
import io.proximi.gadkit.map.GalleryFill
import io.proximi.gadkit.map.GuidanceSymbol
import io.proximi.gadkit.map.GuidanceText
import io.proximi.gadkit.map.MapLanguage
import io.proximi.gadkit.map.VenuePlace
import io.proximi.gadkit.designsystem.GadIcon
import io.proximi.gadkit.designsystem.GadTextStyle
import io.proximi.gadkit.designsystem.Palette
import io.proximi.gadkit.designsystem.ProvideGlassBackdrop
import io.proximi.gadkit.designsystem.SmallButton
import io.proximi.gadkit.designsystem.glassBackdrop
import io.proximi.gadkit.designsystem.rememberGlassBackdrop
import io.proximi.gadkit.VenueService
import io.proximi.gadkit.map.galleries
import io.proximi.gadkit.map.places
import io.proximi.map.compose.ProximiioMap
import io.proximi.map.compose.rememberProximiioMapSession
import io.proximi.map.core.RouteFollowRules
import io.proximi.map.live.ProximiioMapSession
import io.proximi.sdk.computeRoute
import io.proximi.sdk.core.model.ProximiioCoordinate
import io.proximi.sdk.wayfinding.NoRouteReason
import io.proximi.sdk.wayfinding.WayfindingRoutingError
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The museum map, as iOS `MuseumMapView`: venue style, floors, amenities, the visitor's position
 * and one route. A tap on a place shows [PlaceInfoCard] (or [PlaceDetailSheet] for a place with a
 * photo or a description); "Go here" computes a route from the current position and shows
 * [StopInfoCard]. Layout and controls follow the Figma map frames ("MAP Functionality Approach 02"
 * and "Explore Museum").
 *
 * [initialDestination] is routed to after the first position, e.g. a place picked in "Before You
 * Go". [showsLegend] shows the gallery category legend of the Explore Museum map.
 */
@Composable
internal fun MuseumMapView(
    venue: VenueService,
    modifier: Modifier = Modifier,
    initialDestination: VenuePlace? = null,
    showsLegend: Boolean = false,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val language = MapLanguage.forAppLanguage(LocalConfiguration.current.locales[0]?.language)
    // The host app's times and distances, read once when the screen opens.
    val settings = remember { venue.settings }
    val session = rememberProximiioMapSession(venue.sdk, MapStyle.options(language, followZoom = settings.followZoom))
    remember(session) { MapStyle.useAmenityIcons(session, context) }
    // The labels and floor names follow a language change without a style reload.
    SideEffect { if (session.options.language != language) session.options = session.options.copy(language = language) }

    val position by session.position.collectAsStateWithLifecycle()
    val guidance by session.guidance.collectAsStateWithLifecycle()
    val floors by session.floors.collectAsStateWithLifecycle()
    val selectedFloor by session.selectedFloor.collectAsStateWithLifecycle()

    var places by remember { mutableStateOf(emptyList<VenuePlace>()) }
    var selectedPlace by remember { mutableStateOf<VenuePlace?>(null) }
    var destination by remember { mutableStateOf<VenuePlace?>(null) }
    var routeError by remember { mutableStateOf<Int?>(null) }
    var isSearching by remember { mutableStateOf(false) }
    var isShowingAmenities by remember { mutableStateOf(false) }
    var selectedAmenity by remember { mutableStateOf<AmenityKind?>(null) }
    var detailPlace by remember { mutableStateOf<VenuePlace?>(null) }

    val isAccessibleRoutesOn by AccessibleRoutes.isOn(context).collectAsStateWithLifecycle()
    val accessibility = RouteAccessibility.effective(LocalRouteAccessibility.current, isAccessibleRoutesOn)
    val currentAccessibility by rememberUpdatedState(accessibility)

    val activity = LocalActivity.current
    val debug = remember { DebugLaunch.read(activity) }
    val layers = remember { if (debug?.galleryFills == true) JourneyMapLayers() else null }

    /** Computes a route from the current position to [place] with the SDK's on-device router. */
    suspend fun route(place: VenuePlace) {
        destination = place
        val here = session.position.value
        if (here == null) {
            routeError = R.string.waiting_for_your_position
            session.clearRoute()
            return
        }
        val fromLevel = here.floor?.level ?: 0.0
        try {
            val route = withContext(Dispatchers.Default) {
                venue.sdk.computeRoute(
                    from = ProximiioCoordinate(here.coordinate.latitude, here.coordinate.longitude),
                    fromLevel = fromLevel,
                    to = ProximiioCoordinate(place.coordinate.latitude, place.coordinate.longitude),
                    toLevel = place.level,
                    options = currentAccessibility.routeOptions,
                )
            }
            session.setRoute(route)
            routeError = null
            if (debug != null) {
                val via = route.levelChanges.joinToString { "${it.featureType} L${it.fromLevel.roundToInt()}→L${it.toLevel.roundToInt()}" }
                val avoiding = currentAccessibility.routeOptions.effectiveAvoidances.map { it.name }.sorted().joinToString()
                DebugLaunch.log("route to ${place.title}: ${route.distanceMeters.roundToInt()} m via [$via] avoiding [$avoiding]")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLaunch.log("route to ${place.title} failed from level $fromLevel: $e")
            session.clearRoute()
            // A route that exists only through what the accessibility needs avoid.
            val isAccessibilityRefusal = (e as? WayfindingRoutingError)?.reason is NoRouteReason.NoAccessibleRoute
            routeError = if (isAccessibilityRefusal) R.string.no_route_from_here_with_your_accessibility else R.string.no_route_from_here
        }
    }

    fun endRoute() {
        destination = null
        routeError = null
        session.clearRoute()
    }

    /** Routes to the nearest place of [kind], preferring the visitor's floor. */
    fun routeToNearest(kind: AmenityKind) {
        val here = session.position.value
        val from = here?.let { Coordinate(it.coordinate.latitude, it.coordinate.longitude) }
        val place = kind.nearest(places, from, here?.floor?.level) ?: return
        selectedPlace = null
        scope.launch { route(place) }
    }

    LaunchedEffect(session) { session.guidanceRules = RouteFollowRules.VENUE_WALK }

    // A change of the accessibility needs re-routes to the destination on screen.
    LaunchedEffect(accessibility) { destination?.let { route(it) } }

    val currentPlaces by rememberUpdatedState(places)
    DisposableEffect(session) {
        session.onFeatureTap = { identifiers, _ ->
            VenuePlace.placeUnder(identifiers, currentPlaces)?.let { place ->
                isShowingAmenities = false
                // A place with a photo or a description opens the detail sheet of the Explore
                // Museum design; other places show the compact card.
                if (place.imageUrl != null || place.details != null) detailPlace = place else selectedPlace = place
            }
        }
        onDispose { session.onFeatureTap = null }
    }

    LaunchedEffect(language) {
        places = venue.places(language)
        // The picked places take their titles in the new language.
        destination = destination?.let { picked -> places.firstOrNull { it.id == picked.id } ?: picked }
        selectedPlace = selectedPlace?.let { picked -> places.firstOrNull { it.id == picked.id } ?: picked }
        if (initialDestination != null && destination == null) {
            session.position.first { it != null }
            route(initialDestination)
        }
    }

    // The follow camera keeps the initial venue framing. The first position zooms in to
    // `MapOptions.recentreZoom` on the visitor, once the map shows: a position that arrives
    // before the canvas would leave the map north-up until "Centre on me".
    val hasPosition = position != null
    val isCameraReady by session.isCameraReady.collectAsStateWithLifecycle()
    LaunchedEffect(hasPosition, isCameraReady) { if (hasPosition && isCameraReady) session.recentre() }

    if (debug != null) {
        LaunchedEffect(Unit) {
            val loaded = venue.places(language)
            DebugLaunch.run(debug, venue, loaded)
            val query = debug.routeTo ?: return@LaunchedEffect
            val place = VenuePlace.matching(query, loaded).firstOrNull() ?: return@LaunchedEffect
            // Waits for the first position, then routes as the "Go here" button does.
            session.position.first { it != null }
            DebugLaunch.log("route to ${place.title} (level ${place.level.roundToInt()})")
            route(place)
        }
        if (layers != null) DebugGalleryFills(venue, session, layers, language)
        LaunchedEffect(session) {
            launch { session.lastError.collect { if (it != null) DebugLaunch.log("map error: $it") } }
            launch { session.hasFramedVenue.collect { DebugLaunch.log("map framed venue: $it") } }
            launch { session.featureCount.collect { DebugLaunch.log("map features: $it") } }
            launch { session.styleSource.collect { DebugLaunch.log("map style: $it") } }
        }
    }

    val backdrop = rememberGlassBackdrop()
    Box(modifier.fillMaxSize()) {
        ProximiioMap(
            session = session,
            modifier = Modifier.fillMaxSize().glassBackdrop(backdrop),
            configure = { canvas -> layers?.attach(canvas) },
        )
        ProvideGlassBackdrop(backdrop) {
            Column(
                Modifier
                    .fillMaxSize()
                    .navigationBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = if (destination == null && selectedPlace == null) 40.dp else 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(Modifier.fillMaxWidth()) {
                    Spacer(Modifier.weight(1f))
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        GlassSquareButton(
                            onClick = { isShowingAmenities = !isShowingAmenities },
                            contentDescription = stringResource(R.string.search),
                            isSelected = isShowingAmenities,
                        ) { MapIcon(io.proximi.gadkit.R.drawable.icon_search) }
                        AnimatedVisibility(isShowingAmenities, enter = fadeIn(), exit = fadeOut()) {
                            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                AmenityChipList(
                                    kinds = AmenityKind.SHORTCUTS.filter { kind -> places.any(kind::matches) },
                                    selected = selectedAmenity,
                                ) { kind ->
                                    selectedAmenity = kind
                                    isShowingAmenities = false
                                    routeToNearest(kind)
                                }
                                SmallButton(stringResource(R.string.search_the_museum), onClick = { isSearching = true })
                            }
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                    if (showsLegend && destination == null && selectedPlace == null) GalleryLegend()
                    Spacer(Modifier.weight(1f))
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        GlassSquareButton(
                            onClick = {
                                session.followMyFloor()
                                session.recentre()
                            },
                            contentDescription = stringResource(R.string.centre_on_me),
                            enabled = hasPosition,
                        ) { MapIcon(GadIcon.Compass.glyph) }
                        AccessibleRoutesButton(isAccessibleRoutesOn) { AccessibleRoutes.set(context, it) }
                        FloorButton(session, hasPosition, floors, selectedFloor.level)
                    }
                }
                val routed = destination
                val picked = selectedPlace
                if (routed != null) {
                    val coreGuidance = guidance?.toCore()
                    val instruction = routeError?.let { stringResource(it) } ?: coreGuidance?.let { GuidanceText.instruction(it).mapText() }
                    val remaining = guidance
                        ?.takeIf { routeError == null && !it.isOffRoute && !it.hasArrived }
                        ?.let { remainingText(it.remainingMeters, resources) }
                    StopInfoCard(
                        caption = stringResource(R.string.next_stop),
                        title = routed.title,
                        instruction = instruction,
                        remaining = remaining,
                        icon = {
                            val kind = AmenityKind.entries.firstOrNull { it.matches(routed) }
                            if (kind != null) {
                                MapIcon(kind.gadIcon.glyph, size = 22)
                            } else {
                                MapIcon((coreGuidance?.let(GuidanceText::symbol) ?: GuidanceSymbol.STRAIGHT).drawable, size = 16)
                            }
                        },
                        trailing = { CardCloseButton(stringResource(R.string.end_route), ::endRoute) },
                    )
                    if (guidance?.isOffRoute == true && guidance?.hasArrived == false) {
                        SmallButton(stringResource(R.string.new_route_from_here), onClick = { scope.launch { route(routed) } })
                    }
                } else if (picked != null) {
                    PlaceInfoCard(
                        title = picked.title,
                        subtitle = placeSubtitle(picked, resources),
                        primary = stringResource(R.string.go_here),
                        onPrimary = {
                            selectedPlace = null
                            scope.launch { route(picked) }
                        },
                        onClose = { selectedPlace = null },
                    )
                }
            }
        }
        LevelChangeAnimation(session, venue)
    }

    detailPlace?.let { place ->
        PlaceDetailSheet(
            place = place,
            onGuide = {
                detailPlace = null
                scope.launch { route(place) }
            },
            onDismiss = { detailPlace = null },
        )
    }
    if (isSearching) {
        PlaceSearchSheet(
            places = places,
            onPick = { place ->
                isSearching = false
                selectedPlace = place
            },
            onPickAmenity = { kind ->
                isSearching = false
                routeToNearest(kind)
            },
            onDismiss = { isSearching = false },
        )
    }
}

/** Floor button ("L1", Bold 18). Lists the venue's floors; picking one pins the map to it. */
@Composable
private fun FloorButton(session: ProximiioMapSession, hasPosition: Boolean, floors: List<ProximiioMapSession.Floor>, level: Double) {
    var expanded by remember { mutableStateOf(false) }
    val label = GuidanceText.floorLabel(level)
    Box {
        GlassSquareButton(onClick = { expanded = true }, contentDescription = stringResource(R.string.floor_x, label)) {
            Text(label, style = GadTextStyle.LargeBodyBold.textStyle, color = Palette.ink)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, containerColor = Palette.white) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.follow_my_floor), style = GadTextStyle.BodyMedium.textStyle) },
                onClick = {
                    expanded = false
                    session.followMyFloor()
                },
                enabled = hasPosition,
            )
            floors.asReversed().forEach { floor ->
                DropdownMenuItem(
                    text = { Text(floor.name ?: GuidanceText.floorLabel(floor.key.level), style = GadTextStyle.BodyMedium.textStyle) },
                    onClick = {
                        expanded = false
                        session.select(floor.key)
                    },
                )
            }
        }
    }
}

/** "Amenity · Level 1" for an amenity, "Level 1" for other places (iOS `VenuePlace.cardSubtitle`). */
internal fun placeSubtitle(place: VenuePlace, resources: Resources): String {
    val level = resources.getString(R.string.level_n, place.level.roundToInt())
    return if (AmenityKind.entries.any { it.matches(place) }) resources.getString(R.string.amenity) + " · " + level else level
}

/**
 * Debug builds only (`gad.mapGalleryFills`): fills every gallery of the selected floor, the first
 * as the destination, to check [JourneyMapLayers] on the museum map. iOS draws none on this map.
 */
@Composable
private fun DebugGalleryFills(venue: VenueService, session: ProximiioMapSession, layers: JourneyMapLayers, language: String) {
    var galleries by remember { mutableStateOf(emptyList<Gallery>()) }
    val selectedFloor by session.selectedFloor.collectAsStateWithLifecycle()
    LaunchedEffect(language) {
        galleries = venue.galleries(language)
        DebugLaunch.log("galleries: " + galleries.joinToString { "${it.name} L${it.level.roundToInt()}" })
    }
    LaunchedEffect(galleries, selectedFloor) {
        val level = selectedFloor.level
        val onFloor = galleries.filter { VenueLevel.same(it.level, level) }
        val fills = onFloor.mapIndexed { index, gallery -> GalleryFill(gallery, isDestination = index == 0) }
        layers.update(fills, level)
    }
    DisposableEffect(layers) { onDispose { layers.detach() } }
}

