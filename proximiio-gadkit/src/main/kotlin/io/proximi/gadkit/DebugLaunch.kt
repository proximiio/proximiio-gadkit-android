package io.proximi.gadkit

import android.app.Activity
import android.content.pm.ApplicationInfo
import io.proximi.gadkit.map.RouteAccessibility
import io.proximi.gadkit.map.VenuePlace
import io.proximi.sdk.journeys
import kotlinx.coroutines.CancellationException

/**
 * Debuggable host builds only, as iOS `DebugLaunch` in the Debug configuration: launch options
 * for the map and the journey screen. Read from the launch intent's extras, for example
 *
 * ```
 * adb shell am start -n io.proximi.gad/.MainActivity --ez gad.listJourneys true
 * adb shell am start -n io.proximi.gad/.MainActivity --es gad.journey <id> --es gad.journeySpeed 2 --ez gad.journeyLoops true
 * adb shell am start -n io.proximi.gad/.MainActivity --es gad.routeTo "Café"
 * adb shell am start -n io.proximi.gad/.MainActivity --es gad.journey <id> --es gad.visit "Gallery 1,Café" --ez gad.visitAutoAdvance true
 * ```
 *
 * - `gad.journey`: plays the journey with this id in place of the wristband's positions
 *   (`VenueService.playJourney`), at `gad.journeySpeed` (default 1), looping with `gad.journeyLoops`.
 * - `gad.listJourneys`: logs the places and the organisation's journeys (tag `GAD`).
 * - `gad.routeTo`: routes to the first place matching the text once there is a position.
 * - `gad.mapGalleryFills`: fills every gallery on the museum map, to check [JourneyMapLayers].
 * - `gad.visit`: starts a visit through the first place matching each comma-separated title
 *   (iOS `-visit`). It is stored once the navigator activates the first stop, as on iOS.
 * - `gad.visitList`: opens the journey screen in List mode (iOS `-visitList`).
 * - `gad.visitAutoAdvance`: a reached detour ends 5 m after the visitor leaves it, without
 *   "Resume your Journey" (iOS `-visitAutoAdvance`). It applies to every visit of the process.
 * - `gad.routeAvoid`: `stairs,escalators,revolvingDoors,inaccessiblePaths` sets
 *   [Options.routeAccessibility] (iOS `-routeAvoid`). The host app passes it to
 *   `LocalRouteAccessibility`.
 *
 * `gad.journeyPlayback` is accepted for `gad.journey`, as iOS names it `-journeyPlayback`. The
 * playback starts with the Hub, so a visit gets positions without opening the museum map.
 *
 * A host build that is not debuggable reads nothing: [read] returns `null`.
 */
public object DebugLaunch {
    public data class Options(
        public val journeyId: String?,
        public val journeySpeed: Double,
        public val journeyLoops: Boolean,
        public val listJourneys: Boolean,
        public val routeTo: String?,
        public val galleryFills: Boolean,
        public val visitStops: List<String> = emptyList(),
        public val visitList: Boolean = false,
        public val visitAutoAdvance: Boolean = false,
        /** `gad.routeAvoid`: the accessibility needs the host app passes to `LocalRouteAccessibility`. */
        public val routeAccessibility: RouteAccessibility = RouteAccessibility.NONE,
    )

    /** Playback starts once per process; the map screen can open many times. */
    private var hasRun = false

    /** The options of [activity]'s launch intent, or `null` in a host build that is not debuggable. */
    public fun read(activity: Activity?): Options? {
        if (activity == null || (activity.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0) return null
        val extras = activity.intent?.extras ?: return null

        @Suppress("DEPRECATION")
        fun value(key: String): String? = extras.get(key)?.toString()
        return Options(
            journeyId = value("gad.journey") ?: value("gad.journeyPlayback"),
            journeySpeed = value("gad.journeySpeed")?.toDoubleOrNull() ?: 1.0,
            journeyLoops = value("gad.journeyLoops").toBoolean(),
            listJourneys = value("gad.listJourneys").toBoolean(),
            routeTo = value("gad.routeTo"),
            galleryFills = value("gad.mapGalleryFills").toBoolean(),
            visitStops = value("gad.visit")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty(),
            visitList = value("gad.visitList").toBoolean(),
            visitAutoAdvance = value("gad.visitAutoAdvance").toBoolean(),
            routeAccessibility = routeAvoid(value("gad.routeAvoid")),
        )
    }

    /**
     * The needs of `gad.routeAvoid`: comma-separated `stairs`, `escalators`, `revolvingDoors`,
     * `inaccessiblePaths`. Unknown words are ignored. Pure; covered by tests.
     */
    internal fun routeAvoid(text: String?): RouteAccessibility {
        val words = text?.split(',')?.map { it.trim() }?.toSet().orEmpty()
        return RouteAccessibility(
            avoidsStairs = "stairs" in words,
            avoidsEscalators = "escalators" in words,
            avoidsRevolvingDoors = "revolvingDoors" in words,
            avoidsInaccessiblePaths = "inaccessiblePaths" in words,
        )
    }

    /** Lists the journeys and starts the playback the options ask for, once per process. */
    public suspend fun run(options: Options, venue: VenueService, places: List<VenuePlace>) {
        if (!venue.isDebugBuild || hasRun) return
        hasRun = true
        if (options.listJourneys) {
            log("places: " + places.joinToString { "${it.title} [L${it.level.toInt()}]" })
            try {
                venue.sdk.journeys().forEach { journey ->
                    val status = journey.validationFailure()?.let { "not playable: $it" } ?: "playable"
                    log("journey ${journey.id} · ${journey.displayName} · ${journey.waypoints.size} waypoints · $status")
                    // The place nearest each waypoint, to pick visit stops along a playback (`gad.visit`).
                    val near = journey.waypoints.mapNotNull { point ->
                        val latitude = point.latitude ?: return@mapNotNull null
                        val longitude = point.longitude ?: return@mapNotNull null
                        val here = Coordinate(latitude, longitude)
                        VenuePlace.nearest(here, point.level, places)?.let { "${it.title} L${point.level?.toInt()} ${here.distanceTo(it.coordinate).toInt()} m" }
                    }
                    log("journey ${journey.displayName} passes: " + near.joinToString(" | "))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("journeys failed: ${e.message}")
            }
        }
        val id = options.journeyId ?: return
        try {
            venue.playJourney(id, options.journeySpeed, options.journeyLoops)
            log("journey playback started: $id")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("journey playback failed: ${e.message}")
        }
    }

    /** Writes to [FieldLog]. In debug builds: `adb exec-out run-as io.proximi.gad cat files/fieldlog/gad-debug.log`. */
    public fun log(message: String) {
        FieldLog.log(message)
    }
}
