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
import kotlin.math.sqrt

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
     * The centre of each group of points and its distance in metres from the median centre of all
     * groups. Pure; covered by tests.
     */
    fun distancesFromTheMedianCentre(groups: List<List<Coordinate>>): List<Double> {
        if (groups.isEmpty()) return emptyList()
        val centres = groups.map { points ->
            val count = max(points.size, 1)
            Coordinate(points.sumOf { it.latitude } / count, points.sumOf { it.longitude } / count)
        }
        fun median(values: List<Double>) = values.sorted()[values.size / 2]
        val latitude = median(centres.map { it.latitude })
        val longitude = median(centres.map { it.longitude })
        return centres.map { centre ->
            val north = (centre.latitude - latitude) * METRES_PER_DEGREE
            val east = (centre.longitude - longitude) * METRES_PER_DEGREE * cos(latitude * PI / 180)
            sqrt(north * north + east * east)
        }
    }

    /**
     * [groups] without those whose centre is more than [maximumMeters] from the median centre of all
     * of them. `null` keeps every group. A frame of the museum leaves out a space that lies far from
     * the building (GAD PM, on site, 2026-11-19). Pure; covered by tests.
     */
    fun nearTheMedianCentre(groups: List<List<Coordinate>>, maximumMeters: Double?): List<List<Coordinate>> {
        if (maximumMeters == null) return groups
        return groups.zip(distancesFromTheMedianCentre(groups)).filter { it.second <= maximumMeters }.map { it.first }
    }

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
