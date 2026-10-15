package io.proximi.gadkit.map

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import io.proximi.gadkit.Coordinate
import io.proximi.gadkit.FieldLog
import io.proximi.gadkit.VenueService
import io.proximi.gadkit.visit.LevelChangeDetector
import io.proximi.gadkit.visit.LiftArea
import io.proximi.gadkit.visit.LiftDetector
import io.proximi.map.core.RouteManoeuvre
import io.proximi.map.live.ProximiioMapSession

/**
 * Floor changes and lift rides of the visitor, as iOS `followsVisitorFloor(_:venue:)`. The journey
 * screen and the museum map move the map to the visitor's floor when the position changes floor
 * ([LevelChangeDetector]). The level change animation over the map was removed (PM 2026-10-15).
 *
 * A lift ride ([LiftDetector]): positions stay inside a lift outline for
 * [io.proximi.gadkit.GadSettings.liftRideDwell]. The field log records the ride and the route's
 * floor at that lift. Draws nothing.
 *
 * While [isPaused] the map keeps its floor (a floor without positioning, [FloorWithoutPositioningGuide]).
 */
@Composable
internal fun FollowsVisitorFloor(session: ProximiioMapSession, venue: VenueService, isPaused: Boolean = false) {
    val paused by rememberUpdatedState(isPaused)
    val floors = remember(session) { LevelChangeDetector() }
    val lift = remember(session) {
        val settings = venue.settings
        LiftDetector(settings.liftRideDwell.inWholeMilliseconds, settings.liftExitFixes)
    }
    var lifts by remember { mutableStateOf(emptyList<LiftArea>()) }
    // The lift the last fix was in.
    var currentLift by remember { mutableStateOf<LiftArea?>(null) }
    LaunchedEffect(venue) { lifts = venue.lifts() }
    LaunchedEffect(session) {
        session.position.collect { position ->
            if (position == null) return@collect
            val coordinate = Coordinate(position.coordinate.latitude, position.coordinate.longitude)
            val area = lifts.firstOrNull { it.contains(coordinate) }
            if (area != null) currentLift = area
            val event = lift.update(area != null, SystemClock.elapsedRealtime())
            val change = floors.update(position.floor?.level)
            if (change != null) {
                if (FieldLog.isEnabled) FieldLog.log("level change: L${change.from} → L${change.to}${if (paused) " (map keeps its floor)" else ""}")
                if (!paused) session.followMyFloor()
                return@collect
            }
            if (event == LiftDetector.Event.BOARDED && FieldLog.isEnabled) {
                val from = floors.level ?: return@collect
                // The route's next level change, when it leaves this floor by the lift the visitor is in.
                val levelChange = (session.guidance.value?.manoeuvre?.kind as? RouteManoeuvre.Kind.LevelChange)?.change
                val to = levelChange?.let {
                    currentLift?.rideTarget(it.featureType, it.fromLevel, it.toLevel, Coordinate(it.coordinate.latitude, it.coordinate.longitude), from)
                }
                FieldLog.log("lift boarded on L$from, target ${to?.let { "L$it" } ?: "unknown"}")
            }
        }
    }
}
