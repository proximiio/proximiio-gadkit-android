package io.proximi.gadkit.visit

import io.proximi.gadkit.map.MapHandleArea
import io.proximi.gadkit.designsystem.GlassStrength
import io.proximi.gadkit.designsystem.glassSurface
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import io.proximi.gadkit.map.verticalFades
import androidx.compose.runtime.remember
import io.proximi.gadkit.map.FloorChangeView
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import android.Manifest
import android.app.Application
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.proximi.gadkit.DebugLaunch
import io.proximi.gadkit.ProximiDestination
import io.proximi.gadkit.hasPrompt
import io.proximi.gadkit.map.AccessibleRoutes
import io.proximi.gadkit.map.AccessibleRoutesButton
import io.proximi.gadkit.map.LocalRouteAccessibility
import io.proximi.gadkit.map.MapStyle
import io.proximi.gadkit.map.RouteAccessibility
import io.proximi.gadkit.ui.AskNotificationPermission
import io.proximi.gadkit.IncomingJourney
import io.proximi.gadkit.ProximiMapView
import io.proximi.gadkit.VenueService
import kotlinx.coroutines.CoroutineScope
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.proximi.gadkit.R
import io.proximi.gadkit.map.GuidanceText
import io.proximi.gadkit.visit.VisitCardState
import io.proximi.gadkit.visit.VisitCardText
import io.proximi.gadkit.visit.VisitPlan
import io.proximi.gadkit.designsystem.GadIcon
import io.proximi.gadkit.designsystem.GadTextStyle
import io.proximi.gadkit.designsystem.OutlineBadge
import io.proximi.gadkit.designsystem.Palette
import io.proximi.gadkit.designsystem.ProvideGlassBackdrop
import io.proximi.gadkit.designsystem.glassBackdrop
import io.proximi.gadkit.designsystem.rememberGlassBackdrop
import io.proximi.gadkit.map.AmenityChipList
import io.proximi.gadkit.map.BottomGlassBar
import io.proximi.gadkit.map.GlassSquareButton
import io.proximi.gadkit.map.PlaceInfoCard
import io.proximi.gadkit.map.placeSubtitle
import io.proximi.gadkit.map.FloorChangeOverlay
import io.proximi.gadkit.map.FloorWithoutPositioningGuide
import io.proximi.gadkit.map.FollowsVisitorFloor
import io.proximi.gadkit.map.MapGlassFrame
import io.proximi.gadkit.map.MapLoadingSpinner
import io.proximi.gadkit.map.softEdges
import io.proximi.gadkit.map.MapIcon
import io.proximi.gadkit.map.MapListToggle
import io.proximi.gadkit.map.RoundGlassButton
import io.proximi.gadkit.map.StopInfoCard
import io.proximi.gadkit.map.mapText
import io.proximi.gadkit.map.remainingText
import io.proximi.gadkit.map.toCore
import io.proximi.map.compose.ProximiioMap
import io.proximi.map.live.JourneyNavigator
import kotlin.math.roundToInt

/**
 * The journey screen ("MAP Functionality Approach 02", node 10889:108085), as iOS `VisitView`:
 * the map in a glass card over the map background, Map / List toggle, search with the amenity
 * shortcuts (a detour), compass and floor buttons, the stop card, and the bottom bar with Back
 * and the Map / List button. The bar's pause button belongs to Nagarro's audio guide and is not
 * shown.
 *
 * The screen saves [journey] in [VisitStore] on every change. It keeps its state while the
 * activity is recreated, and ends the visit's work (notification, tracking monitor, navigator) when
 * it leaves the composition for good. It shows no wristband alerts: the host app handles the
 * wristband states, and the screen reports lost and returning positions as
 * [VisitEvent.TrackingChanged] (PM 2026-10-15).
 *
 * [options] turn the bottom bar, the background, the prompts, the journey logic and the journey
 * notification on. `ProximiMap` uses all of them; `ProximiMapView` none by default. A journey
 * without stops shows the map alone.
 *
 * @param onClose called after the visitor confirmed leaving, or after the journey is complete.
 *   `null`: no Back and no "Leave the tour here"; Journey Complete's close button returns to the map.
 * @param kind [JourneyKind.PERSONALISED] names the gallery of the next stop first, lists the stops
 *   under gallery names and lets the visitor skip an artwork. [JourneyKind.CURATED] names the
 *   artwork, lists no galleries and has no skip.
 * @param mode Map or List when the screen opens.
 * @param incoming the host app's journey after a change (`ProximiMapView`).
 * @param persist stores the journey after each change; `null` after Journey Complete.
 * @param onVisitEvent receives a [VisitEvent] for each visitor action and each journey change, on
 *   the main thread.
 * @param onNavigator gets the navigator before the journey starts (`ProximiMapView`'s `onNavigator`).
 */
@Composable
internal fun VisitScreen(
    venue: VenueService,
    journey: Journey,
    onClose: (() -> Unit)?,
    kind: JourneyKind = JourneyKind.PERSONALISED,
    options: Set<ProximiMapView.Option> = ProximiMapView.Option.ALL,
    mode: VisitMode = VisitMode.MAP,
    incoming: IncomingJourney? = null,
    persist: (Journey?) -> Unit,
    onVisitEvent: (VisitEvent) -> Unit = {},
    onNavigator: ((JourneyNavigator) -> Unit)? = null,
    destination: ProximiDestination? = null,
    onDestinationChange: (ProximiDestination?) -> Unit = {},
    isPaused: Boolean = false,
    onPausedChange: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val holder: VisitHolder = viewModel(key = VisitHolder.KEY)
    val isAccessibleRoutesOn by AccessibleRoutes.isOn(context).collectAsStateWithLifecycle()
    val accessibility = RouteAccessibility.effective(LocalRouteAccessibility.current, isAccessibleRoutesOn)
    SideEffect {
        holder.onClose = onClose
        holder.onVisitEvent = onVisitEvent
        holder.persist = persist
        holder.onDestinationChange = onDestinationChange
        holder.onPausedChange = onPausedChange
    }
    val model = holder.visit(journey) { scope ->
        VisitViewModel(
            context.applicationContext as Application,
            venue,
            journey,
            scope,
            DebugLaunch.read(activity),
            accessibility,
            kind,
            options,
            mode,
            persist = { holder.persist(it) },
            onClose = onClose?.let { { holder.onClose?.invoke() } },
            onEvent = { holder.onVisitEvent(it) },
            onNavigator = onNavigator,
            destination = destination,
            onDestinationChange = { holder.onDestinationChange(it) },
            isPausedAtStart = isPaused,
            onPausedChange = { holder.onPausedChange(it) },
        )
    }
    DisposableEffect(model) {
        // A recreated activity (a language change) shows the same visit again.
        onDispose { if (activity?.isChangingConfigurations != true) holder.close(model) }
    }
    LaunchedEffect(model, accessibility) { model.setAccessibility(accessibility) }
    LaunchedEffect(model, incoming) { incoming?.let(model::apply) }
    LaunchedEffect(model, destination) { model.destinationChanged(destination) }
    LaunchedEffect(model, isPaused) { model.pausedChanged(isPaused) }
    VisitContent(model, hasBack = onClose != null)
}

/**
 * Holds the visit on screen in the activity's view model store, so it survives a recreated
 * activity, as the iOS `@State` of `VisitView` survives a redrawn view.
 */
internal class VisitHolder : ViewModel() {
    private var current: VisitViewModel? = null
    private var currentJourney: Journey? = null

    /** The host app's latest `onClose`. */
    var onClose: (() -> Unit)? = null

    /** The latest `persist`. */
    var persist: (Journey?) -> Unit = {}

    /** The host app's latest `onVisitEvent`. */
    var onVisitEvent: (VisitEvent) -> Unit = {}

    /** The host app's latest `onDestinationChange` (`ProximiMapView`). */
    var onDestinationChange: (ProximiDestination?) -> Unit = {}

    /** The host app's latest `onPausedChange` (`ProximiMapView`). */
    var onPausedChange: (Boolean) -> Unit = {}

    /** The visit of [journey]; a different journey ends the visit before it. */
    fun visit(journey: Journey, create: (CoroutineScope) -> VisitViewModel): VisitViewModel {
        current?.takeIf { currentJourney === journey }?.let { return it }
        current?.close()
        return create(viewModelScope).also {
            current = it
            currentJourney = journey
        }
    }

    fun close(model: VisitViewModel) {
        if (current !== model) return
        model.close()
        current = null
        currentJourney = null
    }

    override fun onCleared() {
        current?.close()
    }

    companion object {
        const val KEY = "io.proximi.gadkit.visit"
    }
}

@Composable
private fun VisitContent(model: VisitViewModel, hasBack: Boolean) {
    val language = LocalConfiguration.current.locales[0]?.language
    LaunchedEffect(language) { model.setLanguage(language) }
    // The journey notification stands in for the iOS Live Activity.
    if (ProximiMapView.Option.JOURNEY_NOTIFICATION in model.options && model.hasStops) AskNotificationPermission()
    // Without `onClose` the host app's Back handling stays.
    BackHandler(enabled = hasBack, onBack = model::requestClose)
    // The first position zooms in on the visitor, once the map shows (iOS `onChange(of: position == nil)`).
    val position by model.session.position.collectAsStateWithLifecycle()
    val isCameraReady by model.session.isCameraReady.collectAsStateWithLifecycle()
    val hasPosition = position != null
    LaunchedEffect(hasPosition, isCameraReady) { if (hasPosition && isCameraReady) model.showVisitorThenFollow(reason = "first position") }

    val backdrop = rememberGlassBackdrop()
    val mapBackdrop = rememberGlassBackdrop()
    Box(Modifier.fillMaxSize()) {
        if (ProximiMapView.Option.BACKGROUND in model.options) {
            Image(painterResource(R.drawable.background_map), null, Modifier.fillMaxSize().glassBackdrop(backdrop), contentScale = ContentScale.Crop)
        }
        ProvideGlassBackdrop(backdrop) {
            // As iOS: the card 8 dp under the status bar, the bar 14 dp into the navigation bar inset (at least 4 dp).
            val navigationBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            Column(
                Modifier.fillMaxSize().statusBarsPadding().padding(top = 8.dp, bottom = maxOf(navigationBottom - 14.dp, 4.dp)),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                MapGlassFrame(Modifier.weight(1f)) {
                    ProximiioMap(
                        session = model.session,
                        // The map fades out under the controls at the top (110 dp) and over the card at the
                        // bottom (170 dp; RPR, Monday "Map fade at top & bottom", 2026-10-28).
                        modifier = Modifier.fillMaxSize().glassBackdrop(mapBackdrop).softEdges(cornerRadius = 50.dp).verticalFades(top = 110.dp, bottom = 170.dp),
                        configure = { canvas ->
                            model.layers.attach(canvas)
                        },
                    )
                    // The top 44 dp do not pan the map; with the bottom bar and `onClose` a drag down there
                    // is Back (PM, Monday "More space for the pull down functionality", 2026-10-26).
                    MapHandleArea(onPullDown = if (ProximiMapView.Option.BOTTOM_BAR in model.options && hasBack) model::requestClose else null)
                    // The list covers the map with white: its rows have nothing to blur.
                    ProvideGlassBackdrop(null) { ListOverlay(model) }
                    // "Rerouting you…" fades the map (PM, Monday "Rerouting calculation view", 2026-10-26).
                    // The scope-free overload: inside the frame's Box the Column scope of the screen is also in reach.
                    androidx.compose.animation.AnimatedVisibility(model.showsRerouting, enter = fadeIn(tween(200)), exit = fadeOut(tween(200))) {
                        Box(Modifier.fillMaxSize().background(Palette.white.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) { ReroutingCard() }
                    }
                    ProvideGlassBackdrop(if (!model.showsList) mapBackdrop else null) { CardOverlay(model) }
                }
                if (ProximiMapView.Option.BOTTOM_BAR in model.options) {
                    BottomGlassBar(
                        leading = {
                            if (hasBack) {
                                RoundGlassButton(model::requestClose, stringResource(R.string.back)) {
                                    MapIcon(R.drawable.ic_chevron_backward, size = 18)
                                }
                            }
                        },
                        trailing = {
                            if (model.hasStops) {
                                val showsList = model.showsList
                                RoundGlassButton(
                                    onClick = { model.mode = if (showsList) VisitMode.MAP else VisitMode.LIST },
                                    contentDescription = stringResource(if (showsList) R.string.map else R.string.list),
                                ) { MapIcon(if (showsList) R.drawable.ic_map else R.drawable.ic_list, size = 22) }
                            }
                        },
                    )
                }
            }
        }
        // While the map draws the venue and, with stops, until the first leg is routed (PM, Monday
        // "Spinner on map loading center", 2026-10-26). The stop card shows "Your journey" meanwhile.
        ProvideGlassBackdrop(mapBackdrop) {
            MapLoadingSpinner(
                model.session,
                model.settings.mapLoadingTimeout,
                isBusy = model.hasStops && model.progress.activeStop == null && !model.progress.isFinished,
            )
        }
        val floorPhase by model.floorGuide.phase.collectAsStateWithLifecycle()
        FollowsVisitorFloor(model.session, model.venue, isPaused = floorPhase != FloorWithoutPositioningGuide.Phase.Idle)
        FloorChangeOverlay(model.floorGuide)
        RideOverlay(model)
        VisitSheets(model)
    }
}

/** "Rerouting you…" in the middle of the map (PM, Monday "Rerouting calculation view", 2026-10-26). */
@Composable
private fun ReroutingCard() {
    Column(
        Modifier
            .background(Palette.white, RoundedCornerShape(16.dp))
            .padding(horizontal = 36.dp, vertical = 28.dp)
            .semantics(mergeDescendants = true) {},
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        CircularProgressIndicator(color = Palette.ink)
        Text(stringResource(R.string.rerouting_you), style = GadTextStyle.BodyMedium.textStyle, color = Palette.ink)
    }
}

/** The lift or stairs view at a ride to a floor with positioning, faded in and out (iOS 1.20.0). */
@Composable
private fun RideOverlay(model: VisitViewModel) {
    val ride = model.ride
    // The ride stays while the view fades out.
    var shown by remember { mutableStateOf(ride) }
    if (ride != null) shown = ride
    androidx.compose.animation.AnimatedVisibility(ride != null, enter = fadeIn(tween(200)), exit = fadeOut(tween(200))) {
        shown?.let { FloorChangeView(it, onTap = model::dismissRide, showsNoPositionNote = false) }
    }
}

/** List mode: the stop list over the map, faded in. */
@Composable
private fun ListOverlay(model: VisitViewModel) {
    AnimatedVisibility(model.showsList, enter = fadeIn(), exit = fadeOut()) {
        VisitStopList(model, Modifier.fillMaxSize().background(Palette.white.copy(alpha = 0.92f)))
    }
}

/**
 * Controls inside the map card: the toggle and search at the top, compass and floor at the
 * trailing edge, the stop card at the bottom. In List mode the toggle stays left and the rest
 * is hidden (PM review 2026-10-12).
 */
@Composable
private fun CardOverlay(model: VisitViewModel) {
    // Without stops the map shows, whatever the mode is.
    val isMap = !model.showsList
    Column(Modifier.fillMaxSize().padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // The free height above the bottom buttons: a long amenity list scrolls within it (iOS 1.24.1).
        Row(Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.Top) {
            if (model.hasStops) MapListToggle(showsList = !isMap, onChange = { model.mode = if (it) VisitMode.LIST else VisitMode.MAP })
            Spacer(Modifier.weight(1f))
            if (isMap) {
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassSquareButton(
                        onClick = { model.isShowingAmenities = !model.isShowingAmenities },
                        contentDescription = stringResource(R.string.search),
                        isSelected = model.isShowingAmenities,
                    ) { MapIcon(R.drawable.icon_search) }
                    AnimatedVisibility(model.isShowingAmenities, enter = fadeIn(), exit = fadeOut()) {
                        AmenityChipList(
                            shortcuts = model.searchShortcuts,
                            selected = null,
                            onPick = model::detour,
                        )
                    }
                }
            }
        }
        if (isMap) {
            val selectedFloor by model.session.selectedFloor.collectAsStateWithLifecycle()
            Row(Modifier.fillMaxWidth()) {
                Spacer(Modifier.weight(1f))
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    GlassSquareButton(
                        onClick = model::centreOnMe,
                        contentDescription = stringResource(R.string.centre_on_me),
                    ) { MapIcon(GadIcon.Compass.glyph) }
                    val isAccessibleRoutesOn by AccessibleRoutes.isOn(LocalContext.current).collectAsStateWithLifecycle()
                    AccessibleRoutesButton(isAccessibleRoutesOn, model::setAccessibleRoutes)
                    // The shown floor, a label and no button (PM, Monday "Current level button", 2026-10-28).
                    val label = GuidanceText.floorLabel(selectedFloor.level)
                    val floorDescription = stringResource(R.string.floor_x, label)
                    Box(
                        Modifier
                            .size(50.dp)
                            .glassSurface(RoundedCornerShape(12.dp), GlassStrength.Frosted)
                            .clearAndSetSemantics { contentDescription = floorDescription }
                            .testTag("visitFloor"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(label, style = GadTextStyle.LargeBodyBold.textStyle, color = Palette.ink)
                    }
                }
            }
            val amenity = model.selectedAmenity
            val isOffRoute by model.controller.isOffRoute.collectAsStateWithLifecycle()
            val floorPhase by model.floorGuide.phase.collectAsStateWithLifecycle()
            // "Looks like you've left your route" on the stop card's place, unless another card is up
            // (PM, Monday "Personalized tour: off-route and re-route behaviour", 2026-10-26).
            val showsOffRouteCard = model.options.hasPrompt(ProximiMapView.Option.OFF_ROUTE_PROMPT) && isOffRoute && model.hasStops &&
                floorPhase == FloorWithoutPositioningGuide.Phase.Idle
            if (amenity != null) {
                val resources = LocalResources.current
                PlaceInfoCard(
                    title = amenity.title,
                    subtitle = placeSubtitle(amenity, resources),
                    primary = stringResource(if (model.hasStops) R.string.add_a_stop else R.string.go_here),
                    onPrimary = model::confirmAmenity,
                    onClose = { model.selectedAmenity = null },
                )
            } else if (model.routedAmenity != null) {
                // The route to an amenity on a map without a journey.
                val place = checkNotNull(model.routedAmenity)
                val resources = LocalResources.current
                PlaceInfoCard(
                    title = place.title,
                    subtitle = model.amenityRouteError?.let { stringResource(it) } ?: placeSubtitle(place, resources),
                    primary = stringResource(R.string.close),
                    onPrimary = model::endAmenityRoute,
                    onClose = null,
                )
            } else if (showsOffRouteCard) {
                PlaceInfoCard(
                    title = stringResource(R.string.looks_like_you_ve_left_your_route),
                    subtitle = model.nextStopName?.let { stringResource(R.string.your_next_stop_is_x, it) }
                        ?: stringResource(R.string.head_back_to_your_route),
                    primary = stringResource(R.string.dismiss),
                    onPrimary = model::dismissOffRoute,
                    onClose = null,
                )
            } else if (model.hasStops) {
                VisitCard(model)
            }
        }
    }
}

/**
 * The card at the bottom of the map (Figma "Stop Info"), as the `VisitCard` of iOS `VisitView`.
 * An amenity detour shows the place, "Amenity · Level 1" and "Resume your Journey", without a number.
 */
@Composable
private fun VisitCard(model: VisitViewModel) {
    val resources = LocalResources.current
    val isPaused by model.controller.isPaused.collectAsStateWithLifecycle()
    if (isPaused) {
        // The host app paused the journey (Nagarro, 2026-10-28).
        PlaceInfoCard(
            title = stringResource(R.string.journey_paused),
            subtitle = model.nextStopName?.let { stringResource(R.string.your_next_stop_is_x, it) }.orEmpty(),
            primary = stringResource(R.string.resume_your_journey),
            onPrimary = model::resumeFromPause,
            onClose = null,
        )
        return
    }
    val detour = model.controller.navigator.detourStop
    if (model.progress.isDetour && detour != null) {
        val place = model.places.firstOrNull { it.id == detour.id }
        PlaceInfoCard(
            title = detour.title,
            subtitle = place?.let { placeSubtitle(it, resources) }
                ?: detour.floor?.let { resources.getString(R.string.level_n, it.level.roundToInt()) }.orEmpty(),
            primary = stringResource(R.string.resume_your_journey),
            onPrimary = model::resumeFromCard,
            onClose = null,
        )
        return
    }
    val progress = model.progress
    val state = model.cardState
    val guidance = model.guidance
    // The map shows a floor without positioning: the card names the stop, with no instruction from
    // fixes of another floor.
    val floorPhase by model.floorGuide.phase.collectAsStateWithLifecycle()
    val isOnFloorWithoutPositioning = floorPhase != FloorWithoutPositioningGuide.Phase.Idle
    val isLast = progress.activeStop?.let { VisitPlan.isLastOpenStop(it, progress.journey) } ?: false
    val showsGuidance = VisitCardText.showsGuidance(state)
    val galleryAhead = model.galleryAhead?.name
    // The route's next level change, from the drawn route (not a flow: read when the card recomposes).
    val markers = model.levelChangeMarkers
    val levelChangeTitle = (state as? VisitCardState.NextStopOnOtherLevel)?.let {
        GuidanceText.levelChangeTitle(markers, model.visitorLevel, it.level)
    }
    val title = VisitCardText.title(state, galleryAhead, levelChangeTitle)
    // The way back from a floor without positioning: "NEXT STOP IS LEVEL 1", "Head to Level 1 by lift or stairs".
    val returningToLevel = model.returningToLevel
    val instruction = if (returningToLevel != null) {
        stringResource(R.string.head_to_level_n_by_lift_or, returningToLevel)
    } else if (isOnFloorWithoutPositioning) {
        stringResource(R.string.your_position_is_not_shown_on_this)
    } else if (model.hasNoAccessibleRoute) {
        // The navigator keeps a stop that no route with the visitor's accessibility needs
        // reaches, with no route line. In a personalised journey the visitor can skip it.
        stringResource(R.string.no_route_to_this_stop_with_your)
    } else {
        VisitCardText.instruction(state, guidance?.let { GuidanceText.instruction(it.toCore(), markers) }, title)?.mapText()
    }
    val remaining = guidance
        ?.takeIf { showsGuidance && !isOnFloorWithoutPositioning && !model.hasNoAccessibleRoute && !it.isOffRoute && !it.hasArrived }
        ?.let { remainingText(it.remainingMeters, resources) }
    val number = model.activeStopNumber
    StopInfoCard(
        caption = if (returningToLevel != null) {
            stringResource(R.string.next_stop_is_level_n, returningToLevel)
        } else if (isOnFloorWithoutPositioning) {
            stringResource(R.string.next_stop)
        } else {
            VisitCardText.caption(state, isLast, model.cardVisitorGallery?.name, galleryAhead).mapText()
        },
        title = progress.activeStop?.takeIf { isOnFloorWithoutPositioning }?.let { galleryAhead ?: it.title } ?: title.mapText(),
        instruction = instruction,
        remaining = remaining,
        icon = {
            // The lift or stairs of the next level change while the next stop is on another floor (PM,
            // Monday "Stairs&elevator icon disappeared from instructions", 2026-10-27).
            val levelChange = (state as? VisitCardState.NextStopOnOtherLevel)?.let {
                GuidanceText.levelChange(markers, model.visitorLevel)
            }
            // While the leg ends at a gallery: the icon of the gallery POI's amenity (PM, Monday 2026-10-28).
            val galleryIcon = model.galleryAheadDestination
                ?.let { ahead -> model.places.firstOrNull { it.id == ahead.placeId }?.amenityId }
                ?.let { model.amenityIcons[it] }
            when {
                levelChange != null -> MapIcon(if (levelChange.featureType == "elevator") GadIcon.Elevator.glyph else GadIcon.Stairs.glyph, size = 20)
                state == VisitCardState.Starting -> MapIcon(R.drawable.ic_map_straight, size = 16)
                state == VisitCardState.Finished -> MapIcon(R.drawable.ic_checkmark, size = 16)
                number != null && VisitCardText.showsStopNumber(state, galleryAhead) -> Text("$number", style = GadTextStyle.BodyMedium.textStyle, color = Palette.ink)
                galleryAhead != null && galleryIcon != null -> Image(galleryIcon, null, Modifier.size(20.dp))
                galleryAhead != null -> MapIcon(R.drawable.ic_gallery, size = 18)
                else -> MapIcon(R.drawable.ic_map_straight, size = 16)
            }
        },
        trailing = {
            if (state is VisitCardState.NextStopOnOtherLevel) {
                val description = stringResource(R.string.level_n, state.level)
                OutlineBadge("L${state.level}", Modifier.semantics { contentDescription = description })
            }
        },
        badges = { model.namedArtwork?.let { ArtworkBadges(it) } },
        // On the way to a stop the card keeps the instruction line, also while the route is routed again.
        keepsInstructionLine = state is VisitCardState.Walking || state is VisitCardState.NextStopOnOtherLevel,
    )
}

/** The sheets and full-screen covers of the journey screen, in the order iOS presents them. */
@Composable
private fun VisitSheets(model: VisitViewModel) {
    if (model.isConfirmingExit) {
        val progress = VisitPlan.progress(model.progress.journey)
        ExitConfirmationSheet(
            visited = progress.visited,
            total = progress.total,
            onLeave = model::leave,
            onCarryOn = model::carryOn,
        )
    }
    model.lowConfidenceStop?.let { stop ->
        LowConfidencePrompt(stop.title, onConfirm = model::confirmLowConfidence, onNotHere = model::rejectLowConfidence)
    }
    model.stopToSkip?.let { stop ->
        SkipConfirmation(stop.title, onSkip = model::confirmSkip, onKeep = model::keepStop)
    }
    // The off-route warning is a card on the map (CardOverlay). No tracking or wristband alerts: the
    // host app handles the wristband states (PM 2026-10-15).
    if (model.isShowingWrapUp) JourneyCompleteScreen(model.venue, onClose = model::finishVisit)
}

