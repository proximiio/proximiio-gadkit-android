package io.proximi.gadkit.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import io.proximi.gadkit.Coordinate
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The building's cones, as iOS `VenueCone` (1.22.2): the polygon features of type `cone`, nine on each
 * floor. The maps draw each cone as in the design (Figma "0 - DPT Map Kiosk", "Spaces - level 3",
 * layers `L3-architectural-cones`; PM, Monday "Cone visualization", 2026-10-29). A map style fills a
 * polygon with one colour only, so the library draws each cone as an image in the cone's coordinates
 * ([JourneyMapLayers]), and draws the outline with the venue style's `proximiio-cones` line.
 *
 * The image: the design's floor colour #FAFAFB under the cone, the gradient across the cone,
 * perpendicular to its long axis (#D4DEE6, #F8FAFC at 22 %, #E8EEF2 at 46 %, #CBD6DF at 78 %,
 * #AEBCC9, at 32 % opacity), and a white edge at 18 % opacity inside the outline. The design's 1 px is
 * about 0.21 m (its longest cone on level 3 is 610 px, 130 m in the venue data).
 */
internal data class VenueCone(val id: String, val level: Double, val ring: List<Coordinate>) {
    internal companion object {
        /** The polygon features whose type is `cone`. Pure; covered by tests. */
        fun all(features: List<VenueFeature>): List<VenueCone> = features.mapNotNull { feature ->
            if (feature.type != "cone") return@mapNotNull null
            val geometry = feature.geometry ?: return@mapNotNull null
            val level = feature.level ?: return@mapNotNull null
            val ring = Gallery.outerRing(geometry.type, geometry.coordinates) ?: return@mapNotNull null
            VenueCone(feature.id, level, ring)
        }
    }
}

/** A cone's gradient image and where it lies on the map: its top-left corner at ([north], [west]). */
internal class ConeShading(val bitmap: Bitmap, val north: Double, val south: Double, val west: Double, val east: Double) {
    /** A point of the image, in pixels from its top-left corner. */
    data class Point(val x: Double, val y: Double)

    /** The image's size and the cone's outline in it. */
    data class Frame(
        val width: Int,
        val height: Int,
        val points: List<Point>,
        /** Pixels per metre. */
        val scale: Double,
        val north: Double,
        val south: Double,
        val west: Double,
        val east: Double,
    )

    internal companion object {
        val STOP_COLORS = intArrayOf(0xFFD4DEE6.toInt(), 0xFFF8FAFC.toInt(), 0xFFE8EEF2.toInt(), 0xFFCBD6DF.toInt(), 0xFFAEBCC9.toInt())
        val STOP_POSITIONS = floatArrayOf(0f, 0.22f, 0.46f, 0.78f, 1f)

        /** The design's floor under the cones. */
        const val BASE_COLOR = 0xFFFAFAFB.toInt()
        const val OPACITY = 0.32f
        const val EDGE_OPACITY = 0.18f

        /** The visible width of the white edge inside the outline: half the design's 3.2 px line. */
        const val EDGE_METERS = 0.34

        /**
         * The outline of the design: #95A6B5 at 48 % opacity, a hairline. The kiosk's 1.28 px on its
         * 2160 px screen is about 0.2 m; the map draws it 0.3 dp wide at zoom 16, 0.6 dp at 19 and
         * 1.2 dp at 21 (the user's comparison of 2026-10-29, iOS 1.22.2).
         */
        const val OUTLINE_COLOR = 0xFF95A6B5.toInt()
        const val OUTLINE_OPACITY = 0.48f
        val OUTLINE_WIDTHS = listOf(16f to 0.3f, 19f to 0.6f, 21f to 1.2f)

        /**
         * The image of [cone] at [pixelsPerMeter], at most [maxPixels] on the longer side, in a local
         * projection of the cone's bounds. `null` for a cone without an area. Pure; covered by tests.
         */
        fun frame(cone: VenueCone, pixelsPerMeter: Double = 4.0, maxPixels: Double = 1024.0): Frame? {
            val latitudes = cone.ring.map { it.latitude }
            val longitudes = cone.ring.map { it.longitude }
            val north = latitudes.maxOrNull() ?: return null
            val south = latitudes.minOrNull() ?: return null
            val west = longitudes.minOrNull() ?: return null
            val east = longitudes.maxOrNull() ?: return null
            if (north <= south || east <= west) return null
            val metersPerDegreeLatitude = 110_574.0
            val metersPerDegreeLongitude = 111_320.0 * cos((north + south) / 2 * Math.PI / 180)
            val widthMeters = (east - west) * metersPerDegreeLongitude
            val heightMeters = (north - south) * metersPerDegreeLatitude
            val scale = min(pixelsPerMeter, maxPixels / max(widthMeters, heightMeters))
            val points = cone.ring.map { Point((it.longitude - west) * metersPerDegreeLongitude * scale, (north - it.latitude) * metersPerDegreeLatitude * scale) }
            return Frame(ceil(widthMeters * scale).toInt(), ceil(heightMeters * scale).toInt(), points, scale, north, south, west, east)
        }

        /**
         * The gradient's start and end: across the long axis of [points] (their principal axis), from
         * the outermost point on one side to the outermost on the other. Pure; covered by tests.
         */
        fun gradientAxis(points: List<Point>): Pair<Point, Point> {
            if (points.isEmpty()) return Point(0.0, 0.0) to Point(0.0, 0.0)
            val cx = points.sumOf { it.x } / points.size
            val cy = points.sumOf { it.y } / points.size
            var sxx = 0.0
            var syy = 0.0
            var sxy = 0.0
            for (point in points) {
                val dx = point.x - cx
                val dy = point.y - cy
                sxx += dx * dx
                syy += dy * dy
                sxy += dx * dy
            }
            val angle = 0.5 * atan2(2 * sxy, sxx - syy)
            val acrossX = -sin(angle)
            val acrossY = cos(angle)
            val projections = points.map { (it.x - cx) * acrossX + (it.y - cy) * acrossY }
            val low = projections.minOrNull() ?: 0.0
            val high = projections.maxOrNull() ?: 0.0
            return Point(cx + acrossX * low, cy + acrossY * low) to Point(cx + acrossX * high, cy + acrossY * high)
        }

        /** Draws [cone]; `null` for a cone without an area. */
        fun make(cone: VenueCone): ConeShading? {
            val frame = frame(cone) ?: return null
            if (frame.width <= 0 || frame.height <= 0) return null
            val bitmap = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val path = Path().apply {
                frame.points.forEachIndexed { index, point ->
                    if (index == 0) moveTo(point.x.toFloat(), point.y.toFloat()) else lineTo(point.x.toFloat(), point.y.toFloat())
                }
                close()
            }
            canvas.clipPath(path)
            canvas.drawColor(BASE_COLOR)
            val (start, end) = gradientAxis(frame.points)
            val gradient = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(start.x.toFloat(), start.y.toFloat(), end.x.toFloat(), end.y.toFloat(), STOP_COLORS, STOP_POSITIONS, Shader.TileMode.CLAMP)
                alpha = (OPACITY * 255).roundToInt()
            }
            canvas.drawPaint(gradient)
            // Clipped to the cone: half the line width shows inside the outline.
            val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                alpha = (EDGE_OPACITY * 255).roundToInt()
                style = Paint.Style.STROKE
                strokeWidth = max(2.0, 2 * EDGE_METERS * frame.scale).toFloat()
                strokeJoin = Paint.Join.ROUND
            }
            canvas.drawPath(path, edge)
            return ConeShading(bitmap, frame.north, frame.south, frame.west, frame.east)
        }
    }
}
