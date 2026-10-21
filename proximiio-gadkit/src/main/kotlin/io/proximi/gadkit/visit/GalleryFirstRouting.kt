package io.proximi.gadkit.visit

import io.proximi.gadkit.Coordinate
import io.proximi.gadkit.FieldLog
import io.proximi.gadkit.map.GalleryDestination
import io.proximi.gadkit.map.VenueLevel
import io.proximi.gadkit.map.VenuePlace
import io.proximi.sdk.wayfinding.ComputedRoute
import io.proximi.map.core.FloorKey
import io.proximi.map.core.MapCoordinate
import io.proximi.map.live.JourneyRoutePoint
import io.proximi.map.live.JourneyRouting
import io.proximi.sdk.wayfinding.RouteOptions
import io.proximi.sdk.wayfinding.RouteResult
import kotlin.math.roundToLong

/**
 * Gallery first (PM, Monday "Navigation to a gallery", 2026-10-15; personalised journeys only), as
 * iOS `GalleryFirstRouting`: a leg to an artwork in a gallery that the visitor is not in ends at the
 * gallery's POI ([GalleryDestination]). When the visitor enters that gallery, [VisitController] marks
 * the artwork reached and routes the leg again, to the artwork. A leg that starts in the artwork's
 * gallery ends at the artwork.
 *
 * A leg ends at the artwork also when the gallery has no route from the visitor. The artwork then
 * keeps that ending for the rest of the visit. An artwork far from its gallery
 * ([io.proximi.gadkit.GadSettings.galleryFirstMarginMeters]) has no gallery (field test 2026-10-19:
 * test copies of artworks named galleries in another building).
 *
 * The navigator's legs come from this router (`JourneyRouting`). The walking times that order the
 * journey come from the SDK's router unchanged. Thread-safe: the navigator routes off the main thread.
 */
internal class GalleryFirstRouting(private val inner: JourneyRouting) : JourneyRouting {
    data class State(
        val isEnabled: Boolean = false,
        /** The gallery of each artwork, by the artwork's coordinate. */
        val galleries: Map<Key, GalleryDestination> = emptyMap(),
        /** Artworks whose gallery the visitor entered on the way to them. */
        val reached: Set<Key> = emptySet(),
        /** Artworks whose gallery had no route. */
        val withoutRoute: Set<Key> = emptySet(),
    ) {
        /** The gallery a leg to the artwork at [key] leads to first, or `null` when it ends at the artwork. */
        fun gallery(key: Key): GalleryDestination? =
            if (isEnabled && key !in reached && key !in withoutRoute) galleries[key] else null
    }

    /** An artwork's coordinate to about 1 cm. A journey stop has its POI's coordinate. */
    data class Key(val latitude: Long, val longitude: Long) {
        constructor(coordinate: MapCoordinate) : this((coordinate.latitude * 10_000_000).roundToLong(), (coordinate.longitude * 10_000_000).roundToLong())
    }

    @Volatile
    private var state = State()
    private val lock = Any()

    /** Turns gallery first on or off and sets the gallery of each artwork. Keeps the reached artworks. */
    fun update(isEnabled: Boolean, galleries: Map<Key, GalleryDestination>) {
        synchronized(lock) { state = state.copy(isEnabled = isEnabled, galleries = galleries) }
    }

    /** The gallery that a leg to [artwork] leads to first while the visitor is outside it, or `null`. */
    fun gallery(artwork: MapCoordinate): GalleryDestination? = state.gallery(Key(artwork))

    /** The visitor entered the gallery of [artwork]: legs to it end at the artwork from now on. */
    fun markReached(artwork: MapCoordinate) {
        synchronized(lock) { state = state.copy(reached = state.reached + Key(artwork)) }
    }

    override suspend fun route(origin: MapCoordinate, originFloor: FloorKey, destination: MapCoordinate, destinationFloor: FloorKey): ComputedRoute? =
        inner.route(origin, originFloor, destination, destinationFloor)

    override suspend fun route(
        origin: MapCoordinate,
        originFloor: FloorKey,
        destination: MapCoordinate,
        destinationFloor: FloorKey,
        options: RouteOptions,
    ): RouteResult {
        val gallery = gallery(Coordinate(origin.latitude, origin.longitude), originFloor.level, destination, state)
        if (gallery != null) {
            val result = inner.route(
                origin,
                originFloor,
                MapCoordinate(gallery.entry.latitude, gallery.entry.longitude),
                FloorKey(gallery.gallery.level),
                options,
            )
            if (result !is RouteResult.NoRoute) return result
            synchronized(lock) { state = state.copy(withoutRoute = state.withoutRoute + Key(destination)) }
            FieldLog.log("gallery first: no route to ${gallery.gallery.name} (${result.reason}) · route ends at the artwork")
        }
        return inner.route(origin, originFloor, destination, destinationFloor, options)
    }

    override suspend fun durations(origins: List<JourneyRoutePoint>, destinations: List<JourneyRoutePoint>): List<List<Double?>> =
        inner.durations(origins, destinations)

    override suspend fun durations(origins: List<JourneyRoutePoint>, destinations: List<JourneyRoutePoint>, options: RouteOptions): List<List<Double?>> =
        inner.durations(origins, destinations, options)

    companion object {
        /** The gallery where a leg from [origin] to [destination] ends instead of the artwork, or `null`. Pure; covered by tests. */
        fun gallery(origin: Coordinate, originLevel: Double, destination: MapCoordinate, state: State): GalleryDestination? {
            val gallery = state.gallery(Key(destination)) ?: return null
            return if (gallery.gallery.contains(origin, originLevel)) null else gallery
        }

        /**
         * `true` when [artwork] lies in the outline of its gallery on the same floor, or at most
         * [marginMeters] outside it. Pure; covered by tests.
         */
        fun isInGallery(artwork: VenuePlace, destination: GalleryDestination, marginMeters: Double): Boolean =
            VenueLevel.same(artwork.level, destination.gallery.level) && destination.gallery.distanceTo(artwork.coordinate) <= marginMeters
    }
}
