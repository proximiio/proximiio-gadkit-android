package io.proximi.gadkit.wristband

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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.proximi.gadkit.FieldLog
import io.proximi.gadkit.R
import io.proximi.gadkit.map.VenuePlace
import io.proximi.gadkit.wristband.TrackingAlert
import io.proximi.gadkit.wristband.TrackingHealth
import io.proximi.gadkit.wristband.BindingState
import io.proximi.gadkit.wristband.WristbandCopy
import io.proximi.gadkit.designsystem.GadTextStyle
import io.proximi.gadkit.designsystem.Palette
import io.proximi.gadkit.designsystem.PrimaryButton
import io.proximi.gadkit.designsystem.TextActionButton
import io.proximi.gadkit.ui.asHeading
import io.proximi.gadkit.ui.asString
import java.time.temporal.ChronoUnit

/**
 * The visit's tracking alerts, as the `isShowingTrackingAlert` sheet of iOS `VisitView`: the
 * tracking alert for [monitor]'s health, or [WristbandEndedSheet] while [wristband]'s session is
 * ended. Place it last in the visit screen's root `Box`. Neither sheet can be swiped away; a
 * dismissed alert stays hidden until the health changes, and returns once tracking resumes.
 *
 * @param isBlocked another sheet or the pairing flow is on screen.
 * @param onSearchAgain re-attach the binding's provider (`VenueService.attachBinding()`).
 * @param onRouteToDesk add the Welcome Desk as a detour. Offered only when [places] has one.
 * @param onConnect open the pairing flow; after a bind, re-attach the provider.
 */
@Composable
internal fun TrackingAlerts(
    monitor: TrackingMonitor,
    wristband: WristbandSession,
    places: List<VenuePlace>,
    activeStopNumber: Int?,
    isBlocked: Boolean,
    onSearchAgain: () -> Unit,
    onRouteToDesk: () -> Unit,
    onConnect: () -> Unit,
    onBackToHub: () -> Unit,
) {
    val health by monitor.health.collectAsStateWithLifecycle()
    val binding by wristband.bindingState.collectAsStateWithLifecycle()
    var dismissedHealth by rememberSaveable { mutableStateOf<TrackingHealth?>(null) }
    LaunchedEffect(health) { if (health == TrackingHealth.TRACKING) dismissedHealth = null }

    val ended = binding as? BindingState.Ended
    val alert = TrackingAlert.shown(health, isWristbandEnded = ended != null, dismissedHealth = dismissedHealth, isBlocked = isBlocked)
    val dismiss = { dismissedHealth = health }
    // The sheet keeps its content while it slides out.
    val lastAlert = remember { mutableStateOf<TrackingAlert?>(null) }
    if (alert != null) lastAlert.value = alert
    LaunchedEffect(alert) {
        if (!FieldLog.isEnabled || alert == null) return@LaunchedEffect
        val lastFix = monitor.lastFix.value?.time?.truncatedTo(ChronoUnit.SECONDS)?.toString() ?: "-"
        when (alert) {
            TrackingAlert.WRISTBAND_ENDED ->
                FieldLog.log("sheet: wristband ended (${WristbandCopy.logLine(binding)}), last fix $lastFix")
            TrackingAlert.LOST_PLACE, TrackingAlert.STOPPED_RESPONDING -> FieldLog.log("sheet: tracking $health, last fix $lastFix")
        }
    }

    AlertOverlay(visible = alert != null) {
        when (alert ?: lastAlert.value) {
            TrackingAlert.WRISTBAND_ENDED ->
                WristbandEndedSheet(ended?.let { WristbandCopy.notice(it.reason).asString() }.orEmpty(), onConnect, onBackToHub)
            TrackingAlert.LOST_PLACE, TrackingAlert.STOPPED_RESPONDING -> TrackingAlertSheet(
                health = health,
                lastSeen = monitor.lastSeen(places),
                activeStopNumber = activeStopNumber,
                canRouteToDesk = VenuePlace.welcomeDesk(places) != null,
                onSearchAgain = { dismiss(); onSearchAgain() },
                onRouteToDesk = { dismiss(); onRouteToDesk() },
                onDismiss = dismiss,
            )
            null -> Unit
        }
    }
}

/**
 * Shown during a visit when positions stop (Figma "Wristband", nodes 9330:84838 and 9330:84866),
 * as iOS `TrackingAlertSheet`.
 *
 * @param lastSeen "Gallery 2, 9:38" ([TrackingMonitor.lastSeen]).
 * @param activeStopNumber for "Your journey is saved at Stop 3".
 */
@Composable
internal fun ColumnScope.TrackingAlertSheet(
    health: TrackingHealth,
    lastSeen: String?,
    activeStopNumber: Int?,
    canRouteToDesk: Boolean,
    onSearchAgain: () -> Unit,
    onRouteToDesk: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertMark()
    if (health == TrackingHealth.STOPPED_RESPONDING) {
        Title(stringResource(R.string.your_wristband_has_stopped_responding))
        Body(
            activeStopNumber?.let { stringResource(R.string.its_battery_may_have_run_out_your_2, it) }
                ?: stringResource(R.string.its_battery_may_have_run_out_your),
        )
        lastSeen?.let { OutlinedRow(stringResource(R.string.last_seen), it, Modifier.padding(vertical = 10.dp)) }
        PrimaryButton(stringResource(R.string.search_again), onSearchAgain, Modifier.padding(top = 10.dp))
        if (canRouteToDesk) TextActionButton(stringResource(R.string.swap_it_at_the_welcome_desk), onRouteToDesk)
    } else {
        Title(stringResource(R.string.we_ve_lost_your_place_in_the))
        Body(stringResource(R.string.walk_a_few_steps_toward_the_centre))
        // No "Pick my stop manually" (PM 2026-10-15: the view should not exist).
        PrimaryButton(stringResource(R.string.dismiss), onDismiss, Modifier.padding(top = 20.dp))
    }
}

/**
 * Shown during a visit when the wristband session ended, for example after another phone took the
 * wristband over, as iOS `WristbandEndedSheet`. No Figma frame exists for it yet (iOS DAILY-CALL R19).
 *
 * @param notice `WristbandCopy.notice(reason)`.
 */
@Composable
internal fun ColumnScope.WristbandEndedSheet(notice: String, onConnect: () -> Unit, onBackToHub: () -> Unit) {
    AlertMark()
    Title(stringResource(R.string.your_wristband_is_no_longer_connected))
    Body(notice)
    PrimaryButton(stringResource(R.string.connect_a_wristband), onConnect, Modifier.padding(top = 20.dp))
    TextActionButton(stringResource(R.string.back_to_hub), onBackToHub)
}

/**
 * A white sheet with 50 dp top corners over a dim scrim, as iOS `overlaySheetStyle()` with
 * `interactiveDismissDisabled()`: no swipe, no scrim tap, and Back does nothing.
 */
@Composable
private fun AlertOverlay(visible: Boolean, content: @Composable ColumnScope.() -> Unit) {
    if (visible) BackHandler {}
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(visible, enter = fadeIn(tween(250)), exit = fadeOut(tween(250))) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.3f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {}),
            )
        }
        AnimatedVisibility(visible, enter = slideInVertically(tween(300)) { it }, exit = slideOutVertically(tween(300)) { it }) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Palette.white, RoundedCornerShape(topStart = 50.dp, topEnd = 50.dp))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
                    .navigationBarsPadding()
                    .padding(horizontal = 30.dp, vertical = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
                content = content,
            )
        }
    }
}

/** 44 dp exclamation mark in a white circle with a blurred red ring. */
@Composable
private fun AlertMark() {
    Box(Modifier.padding(bottom = 10.dp).size(44.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(44.dp).blur(3.dp, BlurredEdgeTreatment.Unbounded).border(6.dp, Palette.red.copy(alpha = 0.4f), CircleShape))
        Box(Modifier.size(38.dp).background(Palette.white, CircleShape), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_wristband_exclamation), null, Modifier.size(20.dp), tint = Palette.ink)
        }
    }
}

@Composable
private fun Title(text: String) {
    Text(text, Modifier.asHeading(), style = GadTextStyle.H1Bold.textStyle, color = Palette.ink, textAlign = TextAlign.Center)
}

@Composable
private fun Body(text: String) {
    Text(text, style = GadTextStyle.BodySmallRegular.textStyle, color = Palette.ink, textAlign = TextAlign.Center)
}
