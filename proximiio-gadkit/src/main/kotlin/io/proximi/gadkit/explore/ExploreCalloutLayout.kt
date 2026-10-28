package io.proximi.gadkit.explore

import io.proximi.gadkit.Coordinate

/**
 * Where the card of a place picked on the Explore Museum map puts the place, as iOS
 * `ExploreCalloutLayout` (PM, Monday "Explore Museum view: gallery POI view", 2026-10-26). In the
 * design's 402 × 874 frame the place is at (124.5, 657) and the card's bottom 55 above it, 32 from the
 * sides. Sizes are in dp.
 */
internal object ExploreCalloutLayout {
    /** Padding of a framed box, in dp. */
    data class Insets(val top: Float, val left: Float, val bottom: Float, val right: Float)

    /** Where the place goes on a map of [width] × [height]. Pure; covered by tests. */
    fun target(width: Float, height: Float): Pair<Float, Float> = width * 124.5f / 402f to height * 657f / 874f

    /**
     * The item's framed points mirrored around its place, so the frame's centre is the place and the
     * fit puts the place on the target. Pure; covered by tests.
     */
    fun framedPoints(item: ExploreItem): List<Coordinate> {
        val place = item.coordinate
        return item.framedPoints.flatMap { point ->
            listOf(point, Coordinate(2 * place.latitude - point.latitude, 2 * place.longitude - point.longitude))
        }
    }

    /**
     * The padding that frames the place in a box around [target], clear of the level title and the tab
     * bar. Pure; covered by tests.
     */
    fun framingInsets(width: Float, height: Float): Insets {
        if (width <= 100 || height <= 400) return Insets(top = 130f, left = 30f, bottom = 120f, right = 30f)
        val (x, y) = target(width, height)
        val halfWidth = x - 16
        val halfHeight = maxOf(40f, minOf(y - 140, height - y - 110))
        return Insets(top = y - halfHeight, left = x - halfWidth, bottom = height - y - halfHeight, right = width - x - halfWidth)
    }
}
