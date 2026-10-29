package io.proximi.gadkit.map

import kotlin.math.roundToInt
import com.airbnb.lottie.compose.rememberLottieComposition
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieAnimation
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.proximi.gadkit.Coordinate
import io.proximi.gadkit.FieldLog
import io.proximi.gadkit.GadSettings
import io.proximi.gadkit.R
import io.proximi.gadkit.designsystem.GadIcon
import io.proximi.gadkit.designsystem.GadTextStyle
import io.proximi.gadkit.designsystem.Palette
import io.proximi.map.core.FloorKey
import io.proximi.map.core.MapCoordinate
import io.proximi.map.core.MapGeometry
import io.proximi.map.kit.MapContentInsets
import io.proximi.map.live.ProximiioMapSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Guidance to a floor without positioning, as iOS `FloorWithoutPositioningGuide` (PM 2026-10-15,
 * Monday "POIs on floors with no positioning"). In stage 1 the GAD level 0 has no positioning; the
 * café and the prayer room are there.
 *
 * 1. The route leads to the lift or stairs as usual.
 * 2. Within [GadSettings.levelChangerReachedMeters] of the lift or stairs of a ride that ends on a
 *    floor in [GadSettings.levelsWithoutPositioning], the screen shows the lift or stairs view for
 *    [GadSettings.levelChangeViewDuration] ([FloorChangeView]; a tap ends it).
 * 3. The map shows that floor without the route: the destination is highlighted (PM, Monday "POIs
 *    on floors with no positioning", 2026-10-26). It does not follow the visitor there.
 * 4. "Resume your Journey" there starts the way back ([returnFromFloor]): the map stays on that floor
 *    without a route and highlights the lift or stairs to the next stop's floor, nearest the
 *    destination the visitor went to. The first new fix ends it.
 *
 * The phase on that floor ends when the destination changes (another stop), on "Centre on me" or the
 * floor button, or after [GadSettings.floorWithoutPositioningMaximum]. The same destination then gets
 * no second view.
 *
 * [FloorChangeView] shows the lift or stairs icon until RPR's animation exists (Monday
 * "Elevator/Staircase view"). Main thread only; [scope] runs the timers and calls [onEnter] and
 * [onLeave].
 */
internal class FloorWithoutPositioningGuide(
    private val settings: GadSettings,
    private val scope: CoroutineScope,
) {
    /** A lift or stairs ride: to a floor without positioning, or any ride of the route ([reachedRide]). */
    data class Change(
        /** The floor the ride ends on. */
        val level: Int,
        /** The floor the ride starts on. */
        val from: Int,
        /** `elevator`, `staircase` or `escalator`. */
        val featureType: String,
        /** The lift or stairs. */
        val coordinate: Coordinate,
    )

    sealed interface Phase {
        data object Idle : Phase

        /** The lift or stairs view. */
        data class Changing(val change: Change) : Phase

        /** The map shows the floor without positioning. */
        data class OnFloor(val change: Change) : Phase

        /** The way back from the floor without positioning, until a new fix. */
        data class Returning(val change: Change) : Phase
    }

    private val mutablePhase = MutableStateFlow<Phase>(Phase.Idle)
    val phase: StateFlow<Phase> = mutablePhase.asStateFlow()

    /** Called when the map should show the floor of [Change]. */
    var onEnter: ((Change) -> Unit)? = null

    /** Called when the map should follow the visitor again. */
    var onLeave: ((Change) -> Unit)? = null

    private var destinationId: String? = null

    /** The destination whose phase ended; it gets no second view. */
    private var finishedDestinationId: String? = null
    private var timer: Job? = null

    /** When the way back started: a fix after it ends the phase. */
    private var returningSince: Long? = null

    /** `true` from the lift or stairs view until the map follows the visitor again. */
    val isActive: Boolean get() = phase.value != Phase.Idle

    /** Feeds a fix. [markers]: the route's level changes; [destinationId]: the leg's destination. */
    fun update(coordinate: Coordinate?, level: Double?, markers: List<LevelChangeMarker>, destinationId: String?) {
        if (destinationId != this.destinationId) {
            // On the way back the destination is the next stop.
            if (phase.value is Phase.Returning) {
                this.destinationId = destinationId
                return
            }
            leave()
            this.destinationId = destinationId
            finishedDestinationId = null
        }
        if (phase.value != Phase.Idle || destinationId == null || destinationId == finishedDestinationId || coordinate == null) return
        val change = reachedChange(coordinate, level, markers, settings) ?: return
        FieldLog.log("floor without positioning: at the ${change.featureType} to L${change.level}")
        mutablePhase.value = Phase.Changing(change)
        val duration = settings.levelChangeViewDuration
        timer = scope.launch {
            delay(duration)
            showFloor()
        }
    }

    /** Ends the lift or stairs view: the map shows the floor without positioning. */
    fun showFloor() {
        val change = (phase.value as? Phase.Changing)?.change ?: return
        timer?.cancel()
        mutablePhase.value = Phase.OnFloor(change)
        FieldLog.log("floor without positioning: showing L${change.level}")
        onEnter?.invoke(change)
        val maximum = settings.floorWithoutPositioningMaximum
        timer = scope.launch {
            delay(maximum)
            leave()
        }
    }

    /** "Resume your Journey" on the floor without positioning: the way back starts. */
    fun returnFromFloor() {
        val change = (phase.value as? Phase.OnFloor)?.change ?: return
        mutablePhase.value = Phase.Returning(change)
        returningSince = System.currentTimeMillis()
        FieldLog.log("floor without positioning: way back from L${change.level}")
    }

    /** A fix after the way back started ends it: the map follows the visitor again. */
    fun positionReceived(timestampMillis: Long) {
        val since = returningSince ?: return
        if (phase.value !is Phase.Returning || timestampMillis <= since) return
        FieldLog.log("floor without positioning: a fix again")
        leave()
    }

    /** The map follows the visitor again. The destination gets no second view. */
    fun leave() {
        timer?.cancel()
        timer = null
        returningSince = null
        val previous = phase.value
        if (previous == Phase.Idle) return
        mutablePhase.value = Phase.Idle
        finishedDestinationId = destinationId
        val change = when (previous) {
            is Phase.OnFloor -> previous.change
            is Phase.Returning -> previous.change
            else -> null
        }
        if (change != null) {
            FieldLog.log("floor without positioning: following the visitor again")
            onLeave?.invoke(change)
        }
    }

    internal companion object {
        /**
         * The route's next level change from the visitor's floor when its ride ends on a floor
         * without positioning and the visitor is within [GadSettings.levelChangerReachedMeters] of
         * it. Pure; covered by tests.
         */
        fun reachedChange(coordinate: Coordinate, level: Double?, markers: List<LevelChangeMarker>, settings: GadSettings): Change? =
            reachedRide(coordinate, level, markers, settings)?.takeIf { it.level in settings.levelsWithoutPositioning }

        /**
         * The route's next level change from the visitor's floor when the visitor is within
         * [GadSettings.levelChangerReachedMeters] of its lift or stairs, to any floor. Pure; covered by
         * tests.
         */
        fun reachedRide(coordinate: Coordinate, level: Double?, markers: List<LevelChangeMarker>, settings: GadSettings): Change? {
            val pending = markers.filter { !it.isCompleted }
            if (level == null) return null
            val marker = pending.firstOrNull { VenueLevel.same(it.fromLevel, level) } ?: return null
            val end = GuidanceText.rideEnd(marker.featureType, marker.coordinate, marker.fromLevel, marker.toLevel, pending)
            if (coordinate.distanceTo(marker.coordinate) > settings.levelChangerReachedMeters) return null
            return Change(end, level.roundToInt(), marker.featureType, marker.coordinate)
        }

        /**
         * Whether the map stops showing the floor [change] ends on after the lift or stairs view: a
         * fix on another floor than the ride's start, or on that floor more than [meters] from the
         * lift or stairs. A fix without a floor changes nothing. Pure; covered by tests.
         */
        fun releasesRideFloor(change: Change, coordinate: Coordinate, level: Double?, meters: Double): Boolean {
            if (level == null) return false
            if (!VenueLevel.same(level, change.from.toDouble())) return true
            return coordinate.distanceTo(change.coordinate) > meters
        }

        /**
         * The lift or stairs on [level] nearest [origin] that also serves [target], for the way back.
         * Pure; covered by tests.
         */
        fun exitChanger(origin: Coordinate, level: Int, target: Int, changers: List<LevelChangerPlace>): LevelChangerPlace? =
            changers
                .filter { it.serves(level.toDouble()) && it.serves(target.toDouble()) }
                .minByOrNull { origin.distanceTo(it.coordinate) }

        /**
         * Shows the floor of [change] and frames the route on it inside [insets], the map area under
         * the screen's controls and card.
         */
        fun show(change: Change, session: ProximiioMapSession, insets: MapContentInsets) {
            val level = change.level.toDouble()
            session.select(FloorKey(level))
            val points = session.routeRenderer?.geometry?.legs.orEmpty()
                .filter { VenueLevel.same(it.floor.level, level) }
                .flatMap { it.points }
            val bounds = MapGeometry.boundingBox(points + MapCoordinate(change.coordinate.latitude, change.coordinate.longitude)) ?: return
            session.camera.fitBounds(bounds, insets, animated = true)
        }

        /** [MapContentInsets] from dp. */
        fun insets(density: Float, top: Int, left: Int, bottom: Int, right: Int): MapContentInsets =
            MapContentInsets(topPx = top * density.toDouble(), leftPx = left * density.toDouble(), bottomPx = bottom * density.toDouble(), rightPx = right * density.toDouble())
    }
}

/** [FloorChangeView] while [guide] is in the lift or stairs view, faded in and out. */
@Composable
internal fun FloorChangeOverlay(guide: FloorWithoutPositioningGuide) {
    val phase by guide.phase.collectAsStateWithLifecycle()
    val changing = (phase as? FloorWithoutPositioningGuide.Phase.Changing)?.change
    // The change stays while the view fades out.
    var shown by remember { mutableStateOf(changing) }
    if (changing != null) shown = changing
    AnimatedVisibility(
        visible = changing != null,
        enter = fadeIn(tween(FADE_MILLIS, easing = FastOutSlowInEasing)),
        exit = fadeOut(tween(FADE_MILLIS, easing = FastOutSlowInEasing)),
    ) {
        shown?.let { FloorChangeView(it, onTap = guide::showFloor) }
    }
}

/**
 * The lift or stairs view at a ride: RPR's animation, "Take the lift to level 0" and, on the way to a
 * floor without positioning ([showsNoPositionNote]), that the position is not shown there. A tap ends
 * it (PM, Monday "Elevator/Staircase animation", 2026-10-27 and 2026-10-28).
 */
@Composable
internal fun FloorChangeView(change: FloorWithoutPositioningGuide.Change, onTap: () -> Unit, showsNoPositionNote: Boolean = true) {
    val icon = if (change.featureType == "elevator") GadIcon.Elevator else GadIcon.Stairs
    val animation by rememberLottieComposition(LottieCompositionSpec.RawRes(animationResource(change.featureType, change.from, change.level)))
    Box(
        Modifier
            .fillMaxSize()
            .background(Palette.white.copy(alpha = 0.92f))
            .pointerInput(Unit) {
                // The view takes the touches meant for the map under it.
                awaitPointerEventScope {
                    while (true) awaitPointerEvent().changes.forEach { it.consume() }
                }
            }
            .pointerInput(onTap) { detectTapGestures { onTap() } }
            .semantics(mergeDescendants = true) {
                role = Role.Button
                onClick {
                    onTap()
                    true
                }
            }
            .testTag("floorChangeView"),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.padding(horizontal = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val composition = animation
            if (composition != null) {
                LottieAnimation(composition, iterations = LottieConstants.IterateForever, modifier = Modifier.size(140.dp))
            } else {
                Box(
                    Modifier.size(140.dp).background(Palette.white, CircleShape).border(1.5.dp, Palette.badgeStroke, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(painterResource(icon.glyph), contentDescription = null, modifier = Modifier.size(64.dp), tint = Palette.position)
                }
            }
            Text(
                GuidanceText.rideText(change.featureType, change.level).mapText(),
                style = GadTextStyle.H1Bold.textStyle,
                color = Palette.ink,
                textAlign = TextAlign.Center,
            )
            if (showsNoPositionNote) {
                Text(
                    stringResource(R.string.your_position_is_not_shown_on_this),
                    style = GadTextStyle.BodySmallRegular.textStyle,
                    color = Palette.ink,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

private const val FADE_MILLIS = 200

/** RPR's animation name of a ride: a lift for a lift, stairs for stairs and escalators. Pure; covered by tests. */
internal fun animationName(featureType: String, from: Int, to: Int): String =
    "gad_${if (featureType == "elevator") "lift" else "stairs"}_going${if (to > from) "up" else "down"}"

/** The raw resource of [animationName]. */
private fun animationResource(featureType: String, from: Int, to: Int): Int = when (animationName(featureType, from, to)) {
    "gad_lift_goingup" -> R.raw.gad_lift_goingup
    "gad_lift_goingdown" -> R.raw.gad_lift_goingdown
    "gad_stairs_goingup" -> R.raw.gad_stairs_goingup
    else -> R.raw.gad_stairs_goingdown
}
