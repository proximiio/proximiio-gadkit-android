package io.proximi.gadkit

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.proximi.gadkit.explore.MuseumMapScreen
import io.proximi.gadkit.map.LocalRouteAccessibility
import io.proximi.gadkit.ui.AskNotificationPermission
import io.proximi.gadkit.visit.JourneyReadyScreen
import io.proximi.gadkit.visit.ManageJourneySheet
import io.proximi.gadkit.visit.VisitPlanPicker
import io.proximi.gadkit.visit.VisitScreen
import io.proximi.gadkit.visit.VisitStore
import io.proximi.gadkit.wristband.LocationDialogHost

/**
 * Presents the screens and sheets of [map] over [content], as the iOS modifier `.proximiMap(_:)`.
 * Compose it once, at the root of the host app's UI, with the host app's screens as [content]. The
 * museum map, Journey ready, the journey and the stop picker cover [content]; Manage journey is a
 * bottom sheet. One presentation at a time: the next one shows after the previous one has gone.
 *
 * It also asks for the permissions the library needs, as iOS `ProximiMap.start()` does: location
 * when it is first composed in the process, the location for a wristband take-over, and the
 * notification permission once a wristband is connected ([GadSettings.postsGeofenceNotifications]).
 * In debuggable builds it passes the activity's debug launch extras to [ProximiMap.start].
 *
 * In an app with Fragments, compose it in a `ComposeView` that fills the activity's window above the
 * fragment container, without [content]. It draws nothing and passes touches through while no
 * screen of the library is shown.
 */
@Composable
public fun ProximiMapHost(map: ProximiMap, modifier: Modifier = Modifier, content: @Composable () -> Unit = {}) {
    val activity = LocalActivity.current
    LaunchedEffect(map) { map.launchOptionsRead(DebugLaunch.read(activity)) }
    val language = LocalConfiguration.current.locales[0]?.toLanguageTag()
    LaunchedEffect(map, language) { map.setLanguage(language) }
    AskLocation(map)
    // Location may have been allowed in Settings meanwhile: Android has no change callback.
    LifecycleResumeEffect(map) {
        map.locationAuthorizationChanged(answered = false)
        onPauseOrDispose {}
    }
    val wristband by map.wristband.collectAsState()
    AskNotificationPermission(isNeeded = map.configuration.settings.postsGeofenceNotifications && wristband == WristbandStatus.Connected)
    LocationDialogHost(map.session.locationDialog)

    val presentation by map.presenter.presentation.collectAsState()
    val cover = presentation?.takeIf { it.isFullScreen }
    Box(modifier) {
        // Under a cover the host app's screens leave the accessibility tree, as under an iOS full-screen cover.
        Box(Modifier.fillMaxSize().then(if (cover != null) Modifier.clearAndSetSemantics {} else Modifier)) { content() }
        AnimatedContent(
            targetState = cover,
            modifier = Modifier.fillMaxSize(),
            transitionSpec = {
                val transform = if (targetState != null) {
                    slideInVertically(tween(TRANSITION_MILLIS)) { it } togetherWith fadeOut(tween(TRANSITION_MILLIS))
                } else {
                    fadeIn(tween(TRANSITION_MILLIS)) togetherWith slideOutVertically(tween(TRANSITION_MILLIS)) { it }
                }
                // Both states fill the host; no size animation.
                transform using null
            },
            contentKey = { it?.key },
            label = "proximiMap",
        ) { shown ->
            if (shown != null) {
                Cover(map, shown)
                // The cover has gone once its exit animation ends; a recreated activity shows it again.
                DisposableEffect(shown.key) {
                    onDispose { if (activity?.isChangingConfigurations != true) map.presenter.dismissed(shown.key) }
                }
            }
        }
        (presentation as? Presentation.ManageJourney)?.let { sheet ->
            key(sheet) {
                ManageJourneySheet(
                    title = sheet.meta.title,
                    summary = sheet.meta.summary,
                    onEdit = map::editJourney,
                    onCraftNew = map::craftNewJourney,
                    onDelete = map::journeyDeleted,
                    onClose = map.presenter::hide,
                )
                DisposableEffect(sheet.key) {
                    onDispose { if (activity?.isChangingConfigurations != true) map.presenter.dismissed(sheet.key) }
                }
            }
        }
    }
}

/** A full-screen presentation. It takes every touch, so the host app's screens below get none. */
@Composable
private fun Cover(map: ProximiMap, presentation: Presentation) {
    val venue = map.venue ?: return
    val accessibility by map.routeAccessibility.collectAsState()
    Box(Modifier.fillMaxSize().pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }) {
        CompositionLocalProvider(LocalRouteAccessibility provides accessibility) {
            // The journey screen asks before it closes; its own handler comes later and wins.
            BackHandler(onBack = map.presenter::hide)
            when (presentation) {
                Presentation.MuseumMap -> MuseumMapScreen(venue, onBack = map.presenter::hide)
                is Presentation.JourneyReady -> {
                    val isAtMuseum by map.isAtMuseum.collectAsState()
                    JourneyReadyScreen(
                        meta = presentation.meta,
                        isAtMuseum = isAtMuseum,
                        // Away from the museum, "Go to Museum" closes: the host app shows the journey
                        // until the visitor arrives.
                        onStart = { if (isAtMuseum) map.visitStarted(presentation) else map.presenter.hide() },
                        onBack = map.presenter::hide,
                    )
                }
                is Presentation.Visit -> {
                    val context = LocalContext.current
                    VisitScreen(
                        venue,
                        presentation.journey,
                        onClose = map.presenter::hide,
                        kind = presentation.kind,
                        persist = VisitStore.of(context)::persist,
                        onVisitEvent = map::handle,
                    )
                }
                Presentation.PlanJourney -> {
                    val places by map.places.collectAsState()
                    VisitPlanPicker(places, onStart = map::planned, onCancel = map.presenter::hide)
                }
                Presentation.HostedMap -> HostedMapCheck(map, venue.sdk)
                is Presentation.ManageJourney -> Unit
            }
        }
    }
}

/**
 * `gad.hostedMap`: [ProximiMapView] on the app's SDK with the default options, as Nagarro's app uses
 * it. This view is the host app: it skips a stop the visitor swiped, and "Visited" marks the first
 * pending stop visited. The line at the top counts the stop states of its journey, for UI tests.
 */
@Composable
private fun HostedMapCheck(map: ProximiMap, sdk: io.proximi.sdk.Proximiio) {
    val journey by map.hostedJourney.collectAsState()
    val accessibility by map.routeAccessibility.collectAsState()
    fun set(id: String, state: ProximiJourney.State) {
        map.hostedJourney.value = journey.copy(stops = journey.stops.map { if (it.id == id) it.copy(state = state) else it })
    }
    Box(Modifier.fillMaxSize()) {
        ProximiMapView(
            sdk = sdk,
            journey = journey,
            onJourneyChange = { map.hostedJourney.value = it },
            routeAccessibility = accessibility,
            onEvent = { event ->
                FieldLog.log("hosted map → host app: $event")
                if (event is ProximiMapEvent.SkipRequested) set(event.stop.id, ProximiJourney.State.SKIPPED)
            },
        )
        Row(
            Modifier.align(Alignment.TopCenter).statusBarsPadding().background(Color.White.copy(alpha = 0.8f)),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(hostedStates(journey), style = MaterialTheme.typography.labelSmall, modifier = Modifier.testTag("hostedJourneyStates"))
            journey.stops.firstOrNull { it.state == ProximiJourney.State.PENDING }?.let { first ->
                TextButton(onClick = { set(first.id, ProximiJourney.State.VISITED) }) { Text("Visited", style = MaterialTheme.typography.labelSmall) }
            }
            TextButton(onClick = map.presenter::hide) { Text("Close map", style = MaterialTheme.typography.labelSmall) }
        }
    }
}

/** For example "pending 3 · visited 0 · skipped 1". */
internal fun hostedStates(journey: ProximiJourney): String {
    fun count(state: ProximiJourney.State) = journey.stops.count { it.state == state }
    return "pending ${count(ProximiJourney.State.PENDING)} · visited ${count(ProximiJourney.State.VISITED)} · skipped ${count(ProximiJourney.State.SKIPPED)}"
}

/**
 * Asks for location once per process, as iOS `ProximiMap.start()` asks for "While Using the App".
 * The answer reaches the SDK and the arrival check. A recreated activity does not ask again; the
 * answer reaches the launcher of the new activity.
 */
@Composable
private fun AskLocation(map: ProximiMap) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        map.locationAuthorizationChanged(answered = true)
    }
    LaunchedEffect(map) {
        val allowed = LOCATION_PERMISSIONS.any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
        when {
            allowed -> map.locationAuthorizationChanged(answered = true)
            map.hasAskedLocation -> Unit
            else -> {
                map.hasAskedLocation = true
                launcher.launch(LOCATION_PERMISSIONS)
            }
        }
    }
}

private val LOCATION_PERMISSIONS = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

private const val TRANSITION_MILLIS = 350
