package io.proximi.gadkit.map

import kotlin.math.roundToInt
import io.proximi.sdk.core.model.ProximiioAmenity
import io.proximi.map.live.ProximiioMapSession
import io.proximi.map.live.AmenityIcon
import androidx.core.content.ContextCompat
import android.graphics.Bitmap
import android.util.DisplayMetrics
import android.graphics.RectF
import android.graphics.Paint
import android.graphics.Color
import android.graphics.Canvas
import android.content.Context
import io.proximi.map.core.JourneyStopMarkerStyle
import io.proximi.map.core.MapColor
import io.proximi.map.core.PositionAccuracyStyle
import io.proximi.map.core.PositionFreshnessRules
import io.proximi.map.core.PositionHeadingStyle
import io.proximi.gadkit.GadSettings
import io.proximi.map.core.PositionPrivacyStyle
import io.proximi.map.core.PositionSmoothing
import io.proximi.map.core.PositionStyle
import io.proximi.map.core.PositionTrailStyle
import io.proximi.map.core.RouteLineStyle
import io.proximi.map.core.RouteMarkerStyle
import io.proximi.map.kit.MapCanvasChrome
import io.proximi.map.live.MapOptions

/**
 * Map colours of the design (page "3. App Design - In-Gallery Map (Proximi)", section `Colors`,
 * node 10930:60509), as iOS `MapStyle`:
 *
 * - current location `#3F69FF`
 * - path ahead: gradient `#3F69FF` → `#ED3731`, 6 dp, round cap
 * - path traveled: `#3F69FF` at 30 %: the part of the computed leg behind the visitor. The leg
 *   keeps its shape until the arrival (PM review 2026-10-12).
 * - markers `#ED3731` with a `#EEEDED` stroke
 *
 * The gradient runs per floor: on the floor after a lift, it starts at the lift.
 */
internal object MapStyle {
    /**
     * Draws amenities with the design's icons ([amenityImage]); with [galleryIcons] the icons of the
     * Explore Museum map ([exploreAmenityImage]). Set it before the session loads the venue.
     */
    fun useAmenityIcons(session: ProximiioMapSession, context: Context, galleryIcons: Boolean = false) {
        val appContext = context.applicationContext
        session.amenityImageDecoder = if (galleryIcons) {
            { exploreAmenityImage(it, appContext) }
        } else {
            { amenityImage(it, appContext) }
        }
    }

    /**
     * The map icon of an amenity, one dp per pixel of the venue data's icon, as the portal and
     * MapTap draw it (MapLibre GL JS registers an image at pixel ratio 1). The style's `icon-size`
     * then gives the same size here and in MapTap: 120 px at 0.2 is 24 dp (PM, 2026-10-27: the
     * gallery icons were 8 pt at 0.2 on iOS, with the icon at the screen's density).
     *
     * A kind the design draws ([AmenityKind]) gets its marker (PM 2026-10-14, "Amenity icons larger")
     * at the size of the data's icon it replaces. Other amenities, such as artworks and galleries, keep
     * their icon.
     */
    fun amenityImage(amenity: ProximiioAmenity, context: Context): Bitmap? {
        val icon = dataIcon(amenity)
        val marker = AmenityKind.kind(amenity.id, amenity.title)?.gadIcon?.marker
        if (marker != null) {
            val drawable = ContextCompat.getDrawable(context, marker)
            if (drawable != null) {
                val (width, height) = styleIconSize(icon?.width, icon?.height)
                return render(context, width, height) { canvas, density ->
                    drawable.setBounds(0, 0, (width * density).roundToInt(), (height * density).roundToInt())
                    drawable.draw(canvas)
                }
            }
        }
        return icon
    }

    /**
     * The map icon of an amenity on the Explore Museum map. A gallery's icon from the venue data on a
     * circle of white at 30 % with a white border, the icon at 22/30 of the circle (PM, Monday "Explore
     * museum view: default map view", 2026-10-26). The circle has the data icon's size in dp, as
     * [amenityImage]. A kind the design draws gets its marker.
     */
    fun exploreAmenityImage(amenity: ProximiioAmenity, context: Context): Bitmap? {
        if (AmenityKind.kind(amenity.id, amenity.title) != null) return amenityImage(amenity, context)
        val icon = dataIcon(amenity) ?: return null
        val side = maxOf(icon.width, icon.height)
        return render(context, side, side) { canvas, density ->
            val unit = side * density / 30f
            val bounds = side * density
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(77, 255, 255, 255) }
            canvas.drawCircle(bounds / 2, bounds / 2, bounds / 2 - unit / 2, fill)
            val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = unit
            }
            canvas.drawCircle(bounds / 2, bounds / 2, bounds / 2 - unit / 2, stroke)
            canvas.drawBitmap(icon, null, RectF(4 * unit, 4 * unit, 26 * unit, 26 * unit), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        }
    }

    /** The venue data's raster icon of [amenity] at one dp per pixel (pixel ratio 1), or `null`. */
    private fun dataIcon(amenity: ProximiioAmenity): Bitmap? =
        amenity.icon?.let(AmenityIcon::rasterImage)?.also { it.density = DisplayMetrics.DENSITY_DEFAULT }

    /**
     * The size in dp of a map icon made from the data's icon of [width] × [height] pixels: its size
     * in pixels, 120 × 120 without one (the venue data's icons on 2026-10-27). Pure; covered by tests.
     */
    fun styleIconSize(width: Int?, height: Int?): Pair<Int, Int> =
        if (width == null || height == null) 120 to 120 else width to height

    /** A bitmap of [widthDp] × [heightDp] at the screen's density; [draw] gets the canvas in pixels and the density. */
    private fun render(context: Context, widthDp: Int, heightDp: Int, draw: (Canvas, Float) -> Unit): Bitmap {
        val metrics = context.resources.displayMetrics
        val bitmap = Bitmap.createBitmap((widthDp * metrics.density).roundToInt().coerceAtLeast(1), (heightDp * metrics.density).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        bitmap.density = metrics.densityDpi
        draw(Canvas(bitmap), metrics.density)
        return bitmap
    }

    val location: MapColor = MapColor.hex(0x3F69FF)
    val marker: MapColor = MapColor.hex(0xED3731)
    val markerStroke: MapColor = MapColor.hex(0xEEEDED)

    /**
     * Map options shared by the map screens. The journey screen passes `routeMarkers = false` and
     * uses the map library's stop markers ([journeyStops]). No screen sets [walkedTrail].
     *
     * With [walkedTrail], the route behind the visitor is not drawn. On arrival the SDK marks the
     * whole leg as traveled, and the computed leg differs from the path the visitor walked; the
     * trail shows the walked path instead.
     *
     * [language] is the language of the POI labels and floor names (`ar` or `en`).
     *
     * The map follows the visitor and turns heading-up with the phone's compass, as iOS
     * `.followWithHeading` with `heading = .compass`. The compass reads the rotation-vector sensor
     * and needs no permission; before its first reading the map uses the walking direction.
     *
     * "Centre on me" and the first position zoom to `GadSettings.followZoom` (PM review 2026-10-12:
     * the library default is too far out). The map stays top-down: tilting is off.
     *
     * The dot is 30 dp across, the size of a journey stop marker, with a white arrow inside it
     * turned to the heading (PM review 2026-10-12). It is drawn on each fix unless
     * `GadSettings.smoothsPosition` is on.
     *
     * `fallbackStyleUrl` is `null`. With the default, a failed style load makes the map fetch
     * demotiles.maplibre.org, which a production build must not contact.
     */
    fun options(language: String, routeMarkers: Boolean = true, walkedTrail: Boolean = false, settings: GadSettings = GadSettings.STANDARD): MapOptions {
        val venue = PositionStyle.VENUE
        val position = venue.copy(
            smoothing = if (settings.smoothsPosition) PositionSmoothing.ADAPTIVE else PositionSmoothing.NONE,
            dot = venue.dot.copy(color = location, radiusDp = 15.0),
            // The library sizes the arrow at about 1.1 × the dot radius.
            heading = PositionHeadingStyle.Arrow(lengthDp = 16.5),
            trail = if (walkedTrail) {
                PositionTrailStyle(isVisible = true, color = location, widthDp = 6.0, opacity = 0.3)
            } else {
                PositionTrailStyle()
            },
            // The dot disappears when the position does (PM, Monday "Privacy zone to have the same
            // position marker as position disappearing", 2026-10-26). `hidingPosition` hides it in a
            // privacy zone.
            freshness = venue.freshness.let {
                PositionFreshnessRules.of(
                    staleAfter = it.staleAfter,
                    driftMetersPerSecond = it.driftMetersPerSecond,
                    maxAccuracyMeters = it.maxAccuracyMeters,
                    greyingSeconds = it.greyingSeconds,
                    lostAfter = it.lostAfter,
                    staleOpacity = 0.0,
                    lostOpacity = 0.0,
                )
            },
        )
        return MapOptions(
            camera = MapOptions.Camera.FOLLOW_WITH_HEADING,
            heading = MapOptions.Heading.COMPASS,
            // The app draws its own level control (MapChrome).
            floorSelector = MapOptions.FloorSelector.NONE,
            trail = if (walkedTrail) MapOptions.Trail.LastMinutes(15.0) else MapOptions.Trail.Off,
            route = MapOptions.Route.AUTOMATIC,
            routeMarkers = if (routeMarkers) {
                RouteMarkerStyle(color = marker, strokeColor = markerStroke, completedColor = MapColor.hex(0xC3D3FF))
            } else {
                RouteMarkerStyle.OFF
            },
            position = position,
            fallbackStyleUrl = null,
            // The MapLibre attribution is hidden until the app has a place for licence notices
            // (DAILY-CALL R16), as on iOS.
            chrome = MapCanvasChrome(showsAttribution = false, allowsTilting = false),
            routeLineStyle = if (walkedTrail) routeAhead else route,
            language = language,
            recentreZoom = settings.followZoom,
            // The camera keeps to the range in every frame the map sets too (map 6.0.0-beta.28).
            minimumZoom = settings.minimumZoom,
            maximumZoom = settings.maximumZoom,
        )
    }

    /**
     * [position] with nothing drawn while a privacy zone holds the dot. The map library draws a held
     * dot at full opacity; its stroke, shadow and ring are made clear.
     */
    fun hidingPosition(position: PositionStyle): PositionStyle {
        val clear = MapColor.hex(0xFFFFFF, alpha = 0.0)
        return position.copy(
            dot = position.dot.copy(strokeColor = clear, shadowOpacity = 0.0),
            privacy = PositionPrivacyStyle(color = clear, showsAccuracy = false),
        )
    }

    /**
     * The Explore Museum map: no position dot and a camera that never follows. The visitor is not
     * at the museum there (user, 2026-10-23). The dot a privacy zone holds is clear too.
     */
    fun exploreOptions(language: String, settings: GadSettings = GadSettings.STANDARD): MapOptions {
        val options = options(language, routeMarkers = false, settings = settings)
        val clear = MapColor.hex(0xFFFFFF, alpha = 0.0)
        val position = options.position
        return options.copy(
            camera = MapOptions.Camera.FREE,
            position = position.copy(
                dot = position.dot.copy(color = clear, staleColor = clear, strokeColor = clear, shadowOpacity = 0.0),
                accuracy = PositionAccuracyStyle.None,
                heading = PositionHeadingStyle.None,
                privacy = PositionPrivacyStyle(color = clear, showsAccuracy = false),
            ),
        )
    }

    const val RECENTRE_ZOOM: Double = 21.0

    /**
     * Journey stop markers, drawn by the map library (page 3 `Markers`, node 10930:60881): next stop
     * red with a white 2 dp ring and white number; visited and walked-past stops white with a red
     * 2 dp ring; pending white with a `#EEEDED` 1 dp ring. 30 dp, number 14 in GuggenheimSansDisplay
     * Medium. A detour is red with the amenity's icon and no number. Skipped stops have no marker.
     * The markers, the detour included, are drawn over the visitor's dot (RPR, Monday "Stop markers.",
     * 2026-10-28; map 6.0.0-beta.31).
     */
    val journeyStops: JourneyStopMarkerStyle = run {
        val ink = MapColor.hex(0x2B2B2B)
        val white = MapColor.hex(0xFFFFFF)
        val visited = JourneyStopMarkerStyle.Appearance(fillColor = white, strokeColor = marker, strokeWidthDp = 2.0, labelColor = ink)
        JourneyStopMarkerStyle(
            done = visited,
            active = JourneyStopMarkerStyle.Appearance(fillColor = marker, strokeColor = white, strokeWidthDp = 2.0, labelColor = white),
            pending = JourneyStopMarkerStyle.Appearance(fillColor = white, strokeColor = markerStroke, strokeWidthDp = 1.0, labelColor = ink),
            passed = visited,
            detour = JourneyStopMarkerStyle.Appearance(fillColor = marker, strokeColor = white, strokeWidthDp = 2.0, labelColor = white),
            diameterDp = 30.0,
            activeDiameterDp = 30.0,
            detourDiameterDp = 30.0,
            labelFontSize = 14.0,
            labelWeight = JourneyStopMarkerStyle.LabelWeight.MEDIUM,
            labelFontName = "guggenheim_sans_display_medium",
            drawsAbovePosition = true,
        )
    }

    /** Route line. It replaces the organisation style's route line layers on any style. */
    val route: RouteLineStyle = RouteLineStyle(
        remaining = RouteLineStyle.Paint.Gradient(from = location, to = marker),
        remainingOpacity = 1.0,
        completedColor = location,
        completedOpacity = 0.3,
        widthDp = 6.0,
        cap = RouteLineStyle.Cap.ROUND,
    )

    /** Route line without the traveled part, for a map that draws the walked trail. */
    val routeAhead: RouteLineStyle = route.copy(completedOpacity = 0.0)

    /** No route line: the map of a floor without positioning (`FloorWithoutPositioningGuide`). */
    val hiddenRoute: RouteLineStyle = route.copy(remainingOpacity = 0.0, completedOpacity = 0.0)

    /**
     * [journeyStops] with the artworks' markers invisible; the detour keeps its marker. A stop marker
     * style of `null` would take the detour's marker off too.
     */
    val journeyStopsHidden: JourneyStopMarkerStyle = journeyStops.copy(
        done = journeyStops.done.copy(opacity = 0.0),
        active = journeyStops.active.copy(opacity = 0.0),
        pending = journeyStops.pending.copy(opacity = 0.0),
        passed = journeyStops.passed.copy(opacity = 0.0),
        skipped = journeyStops.skipped.copy(opacity = 0.0),
    )
}
