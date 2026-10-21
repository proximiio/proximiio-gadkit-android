package io.proximi.gadkit.map

import io.proximi.gadkit.Coordinate
import io.proximi.map.core.FloorKey
import io.proximi.map.core.MapBoundingBox
import io.proximi.map.core.MapCoordinate
import io.proximi.map.kit.MapContentInsets
import io.proximi.map.live.ProximiioMapSession
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max

/**
 * The map after an amenity is picked in the menu (PM, Monday "When selecting an amenity from the
 * menu, zoom to location", 2026-10-15), as iOS `PlaceFraming`. On the visitor's floor the map shows
 * the visitor and the amenity. On another floor, or without a position, it shows the amenity's floor
 * around the amenity. The camera eases to the new area; it does not fly. The amenity's marker is
 * [JourneyMapLayers.Highlight].
 */
internal object PlaceFraming {
    private const val METRES_PER_DEGREE = 111_320.0

    /**
     * The area that holds [points], grown about its centre to at least [minimumMeters] wide and high.
     * Pure; covered by tests.
     */
    fun bounds(points: List<Coordinate>, minimumMeters: Double): MapBoundingBox? {
        if (points.isEmpty()) return null
        val south = points.minOf { it.latitude }
        val north = points.maxOf { it.latitude }
        val west = points.minOf { it.longitude }
        val east = points.maxOf { it.longitude }
        val latitude = (south + north) / 2
        val longitude = (west + east) / 2
        val halfHeight = max((north - south) / 2, minimumMeters / 2 / METRES_PER_DEGREE)
        val halfWidth = max((east - west) / 2, minimumMeters / 2 / (METRES_PER_DEGREE * cos(latitude * PI / 180)))
        return MapBoundingBox(
            southWest = MapCoordinate(latitude - halfHeight, longitude - halfWidth),
            northEast = MapCoordinate(latitude + halfHeight, longitude + halfWidth),
        )
    }

    /** Shows [place] on [session] inside [insets], the map area under the screen's controls and card. */
    fun show(place: VenuePlace, session: ProximiioMapSession, insets: MapContentInsets, minimumMeters: Double) {
        val points = mutableListOf(place.coordinate)
        val here = session.position.value
        val level = here?.floor?.level
        if (here != null && level != null && VenueLevel.same(level, place.level)) {
            session.followMyFloor()
            points += Coordinate(here.coordinate.latitude, here.coordinate.longitude)
        } else {
            session.select(FloorKey(place.level))
        }
        val bounds = bounds(points, minimumMeters) ?: return
        session.camera.fitBounds(bounds, insets, animated = true)
    }
}
