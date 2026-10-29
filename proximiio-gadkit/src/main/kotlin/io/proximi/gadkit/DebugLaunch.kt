package io.proximi.gadkit

import android.app.Activity
import android.content.pm.ApplicationInfo
import io.proximi.gadkit.map.RouteAccessibility
import io.proximi.gadkit.map.VenuePlace
import io.proximi.gadkit.visit.JourneyKind
import io.proximi.gadkit.wristband.WristbandSession
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
 * - `gad.pairWristband <code>`: binds the wristband after the start (iOS `-pairWristband`).
 *   Ignored with `gad.journey`.
 * - `gad.listJourneys`: logs the places and the organisation's journeys (tag `GAD`).
 * - `gad.openMap`: opens the museum map after the start (iOS `-openMap`).
 * - `gad.routeTo`: routes to the first place matching the text once there is a position.
 * - `gad.mapGalleryFills`: fills every gallery on the museum map, to check [JourneyMapLayers].
 * - `gad.visit`: stores a journey through the first place matching each comma-separated title and
 *   opens it (iOS `-visit`).
 * - `gad.visitList`: opens the journey screen in List mode (iOS `-visitList`).
 * - `gad.hostedMap`: opens the `gad.visit` journey in [io.proximi.gadkit.ProximiMapView] on the
 *   app's SDK with the default options (iOS `-hostedMap`). Without `gad.visit` the view shows the map
 *   alone. With `gad.openMap`: the museum map in [io.proximi.gadkit.ProximiExploreView].
 * - `gad.hostedDestination <title>`: with `gad.hostedMap`, the debug host routes to the place with
 *   that title, else the first place whose title contains it (`destination`; iOS `-hostedDestination`).
 * - `gad.hostedPrepare`: with `gad.hostedMap`, `ProximiMapView.prepare` runs before the view opens
 *   (iOS `-hostedPrepare`).
 * - `gad.visitAutoAdvance`: a reached detour ends 5 m after the visitor leaves it, without
 *   "Resume your Journey" (iOS `-visitAutoAdvance`). It applies to every visit of the process.
 * - `gad.routeAvoid`: `stairs,escalators,revolvingDoors,inaccessiblePaths` sets
 *   `ProximiMap.routeAccessibility` (iOS `-routeAvoid`).
 * - `gad.visitKind`: `personalised` (default) or `curated`, the kind of the `gad.visit` journey (iOS
 *   `-visitKind`).
 * - `gad.hubPage`: [Options.hubPage] for the Proximi GAD app, as iOS `-hubPage`. The library does not
 *   act on it.
 *
 * `gad.journeyPlayback` is accepted for `gad.journey`, as iOS names it `-journeyPlayback`.
 * `ProximiMap.start` runs the playback, the bind, `gad.openMap` and `gad.visit` with the extras of
 * the activity that composes `ProximiMapHost`.
 *
 * A host build that is not debuggable reads nothing: [read] returns `null`.
 */
@ProximiGadAppApi
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
        /** `gad.routeAvoid`: `ProximiMap.start` sets `ProximiMap.routeAccessibility` to it. */
        public val routeAccessibility: RouteAccessibility = RouteAccessibility.NONE,
        /** `gad.visitKind`: the kind of the `gad.visit` journey. */
        public val visitKind: JourneyKind = JourneyKind.PERSONALISED,
        /** `gad.hubPage`: `welcome` or `craft`. The library does not act on it; the Proximi GAD app does. */
        public val hubPage: String? = null,
        /** `gad.openMap`: the museum map opens after the start. */
        public val openMap: Boolean = false,
        /** `gad.pairWristband`: the wristband code to bind after the start, without `gad.journey`. */
        public val pairWristband: String? = null,
        /** `gad.hostedMap`: `ProximiMap` opens the visit in `ProximiMapView` instead of its journey screen. */
        public val hostedMap: Boolean = false,
        /**
         * `gad.hostedDestination`: with `gad.hostedMap`, the debug host sets `destination` to the first
         * place whose title matches, once the venue is loaded (iOS `-hostedDestination`).
         */
        public val hostedDestination: String? = null,
        /** `gad.hostedPrepare`: with `gad.hostedMap`, `ProximiMapView.prepare` before the view opens. */
        public val hostedPrepare: Boolean = false,
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
            visitKind = JourneyKind.of(value("gad.visitKind")),
            hubPage = value("gad.hubPage"),
            openMap = value("gad.openMap").toBoolean(),
            pairWristband = value("gad.pairWristband")?.trim()?.takeIf { it.isNotEmpty() },
            hostedMap = value("gad.hostedMap").toBoolean(),
            hostedDestination = value("gad.hostedDestination")?.trim()?.takeIf { it.isNotEmpty() },
            hostedPrepare = value("gad.hostedPrepare").toBoolean(),
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

    /**
     * Lists the journeys and starts the playback the options ask for, once per process. Without a
     * playback, binds [Options.pairWristband] through [wristband].
     */
    internal suspend fun run(options: Options, venue: VenueService, places: List<VenuePlace>, wristband: WristbandSession? = null) {
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
        val id = options.journeyId
        if (id != null) {
            try {
                venue.playJourney(id, options.journeySpeed, options.journeyLoops)
                log("journey playback started: $id")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("journey playback failed: ${e.message}")
            }
            return
        }
        val code = options.pairWristband ?: return
        val session = wristband ?: return
        log("binding wristband $code")
        try {
            session.bind(code)
            log("wristband $code: bound")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("wristband $code: ${e.message}")
        }
    }

    /** Writes to [FieldLog]. In debug builds: `adb exec-out run-as io.proximi.gad cat files/fieldlog/gad-debug.log`. */
    public fun log(message: String) {
        FieldLog.log(message)
    }
}
