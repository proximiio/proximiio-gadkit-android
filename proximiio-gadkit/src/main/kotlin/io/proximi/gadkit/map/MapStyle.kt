package io.proximi.gadkit.map

import kotlin.math.roundToInt
import io.proximi.sdk.core.model.ProximiioAmenity
import io.proximi.map.live.ProximiioMapSession
import io.proximi.map.live.AmenityIcon
import androidx.core.graphics.drawable.toBitmap
import androidx.core.content.ContextCompat
import android.graphics.Bitmap
import android.content.Context
import io.proximi.map.core.JourneyStopMarkerStyle
import io.proximi.map.core.MapColor
import io.proximi.map.core.PositionAccuracyStyle
import io.proximi.map.core.PositionHeadingStyle
import io.proximi.gadkit.GadSettings
import io.proximi.map.core.PositionPrivacyStyle
import io.proximi.map.core.PositionSmoothing
import io.proximi.map.core.PositionStyle
import io.proximi.map.core.PositionTrailStyle
import io.proximi.map.core.RouteLineStyle
import io.proximi.map.core.RouteMarkerStyle
import io.proximi.map.kit.MapCanvasChrome
import io.proximi.map.kit.ProximiioMapCanvas
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
    /** The size of the design's amenity markers, the stop markers and the position arrow. */
    private const val AMENITY_MARKER_DP = 30

    /** Draws amenities with the design's icons ([amenityImage]). Set it before the session loads the venue. */
    fun useAmenityIcons(session: ProximiioMapSession, context: Context) {
        val appContext = context.applicationContext
        session.amenityImageDecoder = { amenityImage(it, appContext) }
    }

    /**
     * The map icon of an amenity. A kind the design draws ([AmenityKind]) gets its 30 dp marker,
     * the size of the stop markers and the position arrow (PM 2026-10-14, "Amenity icons larger"):
     * the data's icons are 30 px, which the map library draws at about 10 dp. Other amenities, such
     * as artworks and gallery labels, keep their icon.
     */
    fun amenityImage(amenity: ProximiioAmenity, context: Context): Bitmap? {
        val marker = AmenityKind.kind(amenity.id, amenity.title)?.gadIcon?.marker
        if (marker != null) {
            val size = (AMENITY_MARKER_DP * context.resources.displayMetrics.density).roundToInt()
            ContextCompat.getDrawable(context, marker)?.let { return it.toBitmap(size, size) }
        }
        return amenity.icon?.let(AmenityIcon::rasterImage)
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
        )
    }

    /**
     * Applies `GadSettings.minimumZoom` and `maximumZoom` to the map of [canvas] once MapLibre hands
     * it over. The map library has no zoom limit of its own; MapLibre keeps the camera inside these
     * levels.
     */
    fun applyZoomLimits(settings: GadSettings, canvas: ProximiioMapCanvas) {
        if (settings.minimumZoom == null && settings.maximumZoom == null) return
        canvas.onStyleLoaded { map, _ ->
            settings.minimumZoom?.let(map::setMinZoomPreference)
            settings.maximumZoom?.let(map::setMaxZoomPreference)
        }
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
}
