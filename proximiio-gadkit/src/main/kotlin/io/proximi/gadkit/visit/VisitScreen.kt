package io.proximi.gadkit.visit

import android.Manifest
import android.app.Application
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.proximi.gadkit.DebugLaunch
import io.proximi.gadkit.map.AccessibleRoutes
import io.proximi.gadkit.map.AccessibleRoutesButton
import io.proximi.gadkit.map.LocalRouteAccessibility
import io.proximi.gadkit.map.RouteAccessibility
import io.proximi.gadkit.ui.AskNotificationPermission
import io.proximi.gadkit.VenueService
import io.proximi.gadkit.wristband.WristbandSession
import kotlinx.coroutines.CoroutineScope
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material3.CircularProgressIndicator
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
import io.proximi.gadkit.map.AmenityKind
import io.proximi.gadkit.map.GuidanceText
import io.proximi.gadkit.wristband.TrackingHealth
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
import io.proximi.gadkit.map.LevelChangeAnimation
import io.proximi.gadkit.map.MapGlassFrame
import io.proximi.gadkit.map.MapIcon
import io.proximi.gadkit.map.MapListToggle
import io.proximi.gadkit.map.RoundGlassButton
import io.proximi.gadkit.map.StopInfoCard
import io.proximi.gadkit.map.mapText
import io.proximi.gadkit.map.remainingText
import io.proximi.gadkit.map.toCore
import io.proximi.gadkit.wristband.TrackingAlerts
import io.proximi.gadkit.wristband.WristbandPairingScreen
import io.proximi.map.compose.ProximiioMap
import kotlin.math.roundToInt

/**
 * The journey screen ("MAP Functionality Approach 02", node 10889:108085), as iOS `VisitView`:
 * the map in a glass card over the map background, Map / List toggle, search with the amenity
 * shortcuts (a detour), compass and floor buttons, the stop card, and the bottom bar with Back
 * and the Map / List button. The bar's pause button belongs to Nagarro's audio guide and is not
 * shown.
 *
 * The screen saves [journey] in [VisitStore] on every change. It keeps its state while the
 * activity is recreated, and ends the visit's work (notification, tracking alerts, navigator) when
 * it leaves the composition for good.
 *
 * @param onClose called after the visitor confirmed leaving, or after the journey is complete.
 * @param kind [JourneyKind.PERSONALISED] names the gallery of the next stop first, lists the stops
 *   under gallery names and lets the visitor skip an artwork. [JourneyKind.CURATED] names the
 *   artwork, lists no galleries and has no skip.
 * @param onVisitEvent receives a [VisitEvent] for each visitor action and each journey change, on
 *   the main thread.
 * @param wristbandConnectionScreen the host app's screen for "Connect a wristband" after the
 *   wristband session ended, in place of [io.proximi.gadkit.wristband.WristbandPairingScreen]. It
 *   gets a function that closes it. When it closes with an active session, the screen reopens the
 *   wristband's position stream; without one, the "wristband no longer connected" sheet shows again.
 */
@Composable
public fun VisitScreen(
    venue: VenueService,
    wristband: WristbandSession,
    journey: Journey,
    onClose: () -> Unit,
    kind: JourneyKind = JourneyKind.PERSONALISED,
    onVisitEvent: (VisitEvent) -> Unit = {},
    wristbandConnectionScreen: (@Composable (close: () -> Unit) -> Unit)? = null,
) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val holder: VisitHolder = viewModel(key = VisitHolder.KEY)
    val isAccessibleRoutesOn by AccessibleRoutes.isOn(context).collectAsStateWithLifecycle()
    val accessibility = RouteAccessibility.effective(LocalRouteAccessibility.current, isAccessibleRoutesOn)
    SideEffect {
        holder.onClose = onClose
        holder.onVisitEvent = onVisitEvent
    }
    val model = holder.visit(journey) { scope ->
        VisitViewModel(
            context.applicationContext as Application,
            venue,
            wristband,
            journey,
            scope,
            DebugLaunch.read(activity),
            accessibility,
            kind,
            onClose = { holder.onClose() },
            onEvent = { holder.onVisitEvent(it) },
        )
    }
    DisposableEffect(model) {
        // A recreated activity (a language change) shows the same visit again.
        onDispose { if (activity?.isChangingConfigurations != true) holder.close(model) }
    }
    LaunchedEffect(model, accessibility) { model.setAccessibility(accessibility) }
    VisitContent(model, wristbandConnectionScreen)
}

/**
 * Holds the visit on screen in the activity's view model store, so it survives a recreated
 * activity, as the iOS `@State` of `VisitView` survives a redrawn view.
 */
internal class VisitHolder : ViewModel() {
    private var current: VisitViewModel? = null
    private var currentJourney: Journey? = null

    /** The host app's latest `onClose`. */
    var onClose: () -> Unit = {}

    /** The host app's latest `onVisitEvent`. */
    var onVisitEvent: (VisitEvent) -> Unit = {}

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
private fun VisitContent(model: VisitViewModel, wristbandConnectionScreen: (@Composable (close: () -> Unit) -> Unit)?) {
    val language = LocalConfiguration.current.locales[0]?.language
    LaunchedEffect(language) { model.setLanguage(language) }
    // The journey notification stands in for the iOS Live Activity.
    AskNotificationPermission()
    BackHandler(onBack = model::requestClose)
    // The first position zooms in on the visitor, once the map shows (iOS `onChange(of: position == nil)`).
    val position by model.session.position.collectAsStateWithLifecycle()
    val isCameraReady by model.session.isCameraReady.collectAsStateWithLifecycle()
    val hasPosition = position != null
    LaunchedEffect(hasPosition, isCameraReady) { if (hasPosition && isCameraReady) model.recentre(reason = "first position") }

    val backdrop = rememberGlassBackdrop()
    val mapBackdrop = rememberGlassBackdrop()
    Box(Modifier.fillMaxSize()) {
        Image(painterResource(R.drawable.background_map), null, Modifier.fillMaxSize().glassBackdrop(backdrop), contentScale = ContentScale.Crop)
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
                        modifier = Modifier.fillMaxSize().glassBackdrop(mapBackdrop),
                        configure = { canvas -> model.layers.attach(canvas) },
                    )
                    // The list covers the map with white: its rows have nothing to blur.
                    ProvideGlassBackdrop(null) { ListOverlay(model) }
                    ProvideGlassBackdrop(if (model.mode == VisitMode.MAP) mapBackdrop else null) { CardOverlay(model) }
                }
                BottomGlassBar(
                    leading = {
                        RoundGlassButton(model::requestClose, stringResource(R.string.back)) {
                            MapIcon(R.drawable.ic_chevron_backward, size = 18)
                        }
                    },
                    trailing = {
                        val showsList = model.mode == VisitMode.LIST
                        RoundGlassButton(
                            onClick = { model.mode = if (showsList) VisitMode.MAP else VisitMode.LIST },
                            contentDescription = stringResource(if (showsList) R.string.map else R.string.list),
                        ) { MapIcon(if (showsList) R.drawable.ic_map else R.drawable.ic_list, size = 22) }
                    },
                )
            }
        }
        LevelChangeAnimation(model.session, model.venue)
        VisitSheets(model, wristbandConnectionScreen)
    }
}

/** List mode: the stop list over the map, faded in. */
@Composable
private fun ListOverlay(model: VisitViewModel) {
    AnimatedVisibility(model.mode == VisitMode.LIST, enter = fadeIn(), exit = fadeOut()) {
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
    val isMap = model.mode == VisitMode.MAP
    Column(Modifier.fillMaxSize().padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            MapListToggle(showsList = !isMap, onChange = { model.mode = if (it) VisitMode.LIST else VisitMode.MAP })
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
                            kinds = AmenityKind.SHORTCUTS.filter { kind -> model.places.any(kind::matches) },
                            selected = null,
                            onPick = model::detour,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        if (isMap) {
            val selectedFloor by model.session.selectedFloor.collectAsStateWithLifecycle()
            Row(Modifier.fillMaxWidth()) {
                Spacer(Modifier.weight(1f))
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    GlassSquareButton(
                        onClick = {
                            model.session.followMyFloor()
                            model.centreOnMe()
                        },
                        contentDescription = stringResource(R.string.centre_on_me),
                    ) { MapIcon(GadIcon.Compass.glyph) }
                    val isAccessibleRoutesOn by AccessibleRoutes.isOn(LocalContext.current).collectAsStateWithLifecycle()
                    AccessibleRoutesButton(isAccessibleRoutesOn, model::setAccessibleRoutes)
                    val label = GuidanceText.floorLabel(selectedFloor.level)
                    GlassSquareButton(onClick = model.session::followMyFloor, contentDescription = stringResource(R.string.floor_x, label)) {
                        Text(label, style = GadTextStyle.LargeBodyBold.textStyle, color = Palette.ink)
                    }
                }
            }
            val amenity = model.selectedAmenity
            if (amenity != null) {
                val resources = LocalResources.current
                PlaceInfoCard(
                    title = amenity.title,
                    subtitle = placeSubtitle(amenity, resources),
                    primary = stringResource(R.string.go_here),
                    onPrimary = model::confirmAmenity,
                    onClose = { model.selectedAmenity = null },
                )
            } else {
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
    val detour = model.controller.navigator.detourStop
    if (model.progress.isDetour && detour != null) {
        val place = model.places.firstOrNull { it.id == detour.id }
        PlaceInfoCard(
            title = detour.title,
            subtitle = place?.let { placeSubtitle(it, resources) }
                ?: detour.floor?.let { resources.getString(R.string.level_n, it.level.roundToInt()) }.orEmpty(),
            primary = stringResource(R.string.resume_your_journey),
            onPrimary = model::resumeJourney,
            onClose = null,
        )
        return
    }
    val progress = model.progress
    val state = model.cardState
    val guidance = model.guidance
    val isLast = progress.activeStop?.let { VisitPlan.isLastOpenStop(it, progress.journey) } ?: false
    val showsGuidance = VisitCardText.showsGuidance(state)
    val instruction = if (model.hasNoAccessibleRoute) {
        stringResource(R.string.no_route_to_this_stop_with_your)
    } else if (showsGuidance) {
        (guidance?.let { GuidanceText.instruction(it.toCore()) } ?: VisitCardText.instructionWithoutGuidance(state))?.mapText()
    } else {
        null
    }
    val remaining = guidance
        ?.takeIf { showsGuidance && !model.hasNoAccessibleRoute && !it.isOffRoute && !it.hasArrived }
        ?.let { remainingText(it.remainingMeters, resources) }
    val number = model.activeStopNumber
    val galleryAhead = model.galleryAhead?.name
    StopInfoCard(
        caption = VisitCardText.caption(state, isLast, model.cardVisitorGallery?.name, galleryAhead).mapText(),
        title = VisitCardText.title(state, galleryAhead).mapText(),
        instruction = instruction,
        remaining = remaining,
        icon = {
            when {
                state == VisitCardState.Starting -> CircularProgressIndicator(Modifier.size(18.dp), color = Palette.ink, strokeWidth = 2.dp)
                state == VisitCardState.Finished -> MapIcon(R.drawable.ic_checkmark, size = 16)
                number != null && VisitCardText.showsStopNumber(state, galleryAhead) -> Text("$number", style = GadTextStyle.BodyMedium.textStyle, color = Palette.ink)
                else -> MapIcon(R.drawable.ic_map_straight, size = 16)
            }
        },
        trailing = {
            if (state is VisitCardState.NextStopOnOtherLevel) {
                val description = stringResource(R.string.level_n, state.level)
                OutlineBadge("L${state.level}", Modifier.semantics { contentDescription = description })
            }
        },
    )
}

/** The sheets and full-screen covers of the journey screen, in the order iOS presents them. */
@Composable
private fun VisitSheets(model: VisitViewModel, wristbandConnectionScreen: (@Composable (close: () -> Unit) -> Unit)?) {
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
    model.amenityToConfirm?.let { place ->
        ChangeRouteConfirmation(place.title, model.nextStopName, onGo = model::goToAmenity, onClose = { model.amenityToConfirm = null })
    }
    // The off-route warning, unless another prompt is up. The tracking alert has priority over it.
    val isOffRoute by model.controller.isOffRoute.collectAsStateWithLifecycle()
    val health by model.monitor.health.collectAsStateWithLifecycle()
    val isTracking = health == TrackingHealth.TRACKING || health == TrackingHealth.WAITING
    if (isOffRoute && isTracking && model.stopToSkip == null && model.amenityToConfirm == null && model.locationChoice == null) {
        OffRouteWarning(onReroute = model::resumeJourney, onClose = model::dismissOffRoute)
    }
    TrackingAlerts(
        monitor = model.monitor,
        wristband = model.wristband,
        places = model.places,
        activeStopNumber = model.activeStopNumber,
        isBlocked = model.isPairing || model.stopToSkip != null || model.locationChoice != null || model.amenityToConfirm != null,
        onSearchAgain = model::searchAgain,
        onRouteToDesk = model::routeToDesk,
        onPickStop = model::openManualChoice,
        onConnect = model::connectWristband,
        onBackToHub = model::backToHub,
    )
    model.locationChoice?.let { choice ->
        val level = model.visitorLevel?.let { stringResource(R.string.level_n, it.roundToInt()) }
        LocationChoiceScreen(
            choice = choice,
            level = level,
            gallery = model.currentGallery,
            onPick = { model.pick(it, choice) },
            onClose = { model.closeChoice(choice) },
        )
    }
    model.pairing?.let { flow ->
        BackHandler(onBack = model::pairingClosed)
        if (wristbandConnectionScreen != null) {
            wristbandConnectionScreen(model::pairingClosed)
        } else {
            WristbandPairingScreen(flow, onFinish = model::pairingClosed)
        }
    }
    if (model.isShowingWrapUp) JourneyCompleteScreen(model.venue, onClose = model::finishVisit)
}

