package io.proximi.gadkit.map

import kotlin.math.roundToInt
import org.maplibre.android.style.layers.SymbolLayer
import io.proximi.gadkit.designsystem.GadIcon
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import android.graphics.Paint
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Bitmap
import android.content.Context
import android.graphics.RectF
import android.text.TextUtils
import android.text.TextPaint
import androidx.core.content.res.ResourcesCompat
import io.proximi.gadkit.R
import io.proximi.gadkit.Coordinate
import io.proximi.gadkit.map.Gallery
import io.proximi.gadkit.map.GalleryFill
import io.proximi.gadkit.map.JourneyMapContent
import io.proximi.map.kit.MapCanvasLayerAnchor
import io.proximi.map.kit.MapCanvasToken
import io.proximi.map.kit.ProximiioMapCanvas
import io.proximi.map.kit.ProximiioMapLayer
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon

/**
 * Journey content drawn by the app on top of the SDK's map (page 3, section `Colors`), as iOS
 * `JourneyMapLayers`. The stop markers are the map library's (`MapStyle.journeyStops`).
 *
 * - the destination gallery filled with the level colour; visited galleries with its tint.
 * - a connector from the visitor to the start of the route ahead: `#3F69FF`, 6 dp, round cap, under
 *   the route. `VisitViewModel` draws none while `SHOWS_CONNECTOR` is false.
 * - the lift or stairs of the route's next level change on the shown floor: a 30 dp red marker with
 *   a white ring and the design's glyph, above the route ([updateLevelChanger]; PM 2026-10-15,
 *   "Level changer highlighting").
 * - every other lift, staircase and escalator that serves the shown floor, with its amenity marker
 *   ([updateLevelChangers], [LevelChangerPlace]; Monday "All level changers visible", 2026-10-15).
 * - the amenity picked in the menu: the red marker with its white icon ([updateHighlight],
 *   [PlaceFraming]).
 * - the gallery a leg leads to first: a red pill with the gallery's title at its POI; a visited
 *   gallery: its amenity icon in a white circle with a red ring and its title under it
 *   ([updateGalleryMarkers]; PM, Monday "Gallery numbers in gallery navigation" and "Visited gallery
 *   visualization", 2026-10-28).
 * - the visited and the destination galleries over the venue's artworks, furniture and inner walls
 *   (`proximiio-inner_wall`), under labels, POI icons, the route and the stop markers.
 *
 * The layers are added after each style load and show the selected floor only. The gallery fills
 * go over the venue's floor and room fills and under its walls, labels and POI icons
 * (`MapCanvasLayerAnchor.AboveVenueFills`, map 6.0.0-beta.18), as iOS uses `.aboveVenueFills`.
 *
 * Main thread only. [attach] it from `ProximiioMap(configure = …)`, then call [update] whenever the
 * journey, the galleries or the selected floor change. `VisitViewModel` uses it with
 * `JourneyMapContent.fills`; the museum map uses it for the level changers only.
 */
internal class JourneyMapLayers {
    private var canvas: ProximiioMapCanvas? = null
    private var styleToken: MapCanvasToken? = null
    private var fills: List<GalleryFill> = emptyList()
    private var level: Double = 0.0
    private var connector: List<Coordinate> = emptyList()
    private var levelChanger: LevelChanger? = null
    private var levelChangers: List<LevelChangerPlace> = emptyList()
    private var highlight: Highlight? = null
    private var galleryMarkers: List<GalleryMarker> = emptyList()

    /** The amenity icons of the venue data, by amenity id, for the gallery markers. */
    private var galleryIcons: Map<String, Bitmap> = emptyMap()

    /** Counts the changes of [galleryIcons]: a changed icon gets a new image name. */
    private var iconGeneration = 0

    /** The level changer of the route's next level change: its anchor, the floor it leaves and its type. */
    data class LevelChanger(val coordinate: Coordinate, val level: Double, val featureType: String) {
        /** The style image of the marker. Stairs and escalators share the stairs glyph. */
        val imageName: String get() = "$LEVEL_CHANGER_IMAGE-$featureType"

        internal companion object {
            /** The style image of a level changer that is not on the route: its amenity marker. */
            fun standardImageName(featureType: String): String = "$LEVEL_CHANGER_IMAGE-standard-$featureType"
        }
    }

    /** The amenity picked in the menu. */
    data class Highlight(val coordinate: Coordinate, val level: Double, val icon: GadIcon) {
        val imageName: String get() = imageName(icon)

        internal companion object {
            fun imageName(icon: GadIcon): String = "gad-highlight-${icon.name}"
        }
    }

    /**
     * A gallery's marker at its POI: the gallery a leg leads to first (gallery first), or a visited
     * gallery. [name] is the POI's title, such as "G303 Language"; [amenityId] the POI's amenity,
     * whose icon a visited gallery's marker shows.
     */
    data class GalleryMarker(val coordinate: Coordinate, val level: Double, val name: String, val amenityId: String?, val isDestination: Boolean) {
        fun imageName(iconGeneration: Int): String =
            "gad-gallery-${if (isDestination) "destination" else "visited"}-$iconGeneration-${amenityId ?: "-"}-$name"
    }

    /** Installs the layers on every style [canvas] loads, the loaded one included. */
    fun attach(canvas: ProximiioMapCanvas) {
        detach()
        this.canvas = canvas
        styleToken = canvas.onStyleLoaded { _, style -> install(canvas, style) }
    }

    /** Stops installing the layers. The layers on the loaded style stay until it reloads. */
    fun detach() {
        styleToken?.cancel()
        styleToken = null
        canvas = null
    }

    /** Draws [fills] on [level], the shown floor. Does nothing when nothing changed. */
    fun update(fills: List<GalleryFill>, level: Double) {
        if (fills == this.fills && level == this.level) return
        val levelChanged = level != this.level
        this.fills = fills
        this.level = level
        val style = canvas?.style ?: return
        apply(style)
        if (levelChanged) applyLevelChanger(style)
    }

    /** The venue's lifts, staircases and escalators. Each shows on the floors it serves. */
    fun updateLevelChangers(changers: List<LevelChangerPlace>) {
        if (changers == levelChangers) return
        levelChangers = changers
        canvas?.style?.let(::applyLevelChanger)
    }

    /** The line from the visitor to the start of the route ahead. `null` removes it. */
    fun updateConnector(from: Coordinate?, to: Coordinate?) {
        val points = if (from != null && to != null) listOf(from, to) else emptyList()
        if (points == connector) return
        connector = points
        canvas?.style?.let(::applyConnector)
    }

    /** Marks [changer] in red on its floor; `null` removes the marker. Does nothing when nothing changed. */
    fun updateLevelChanger(changer: LevelChanger?) {
        if (changer == levelChanger) return
        levelChanger = changer
        canvas?.style?.let(::applyLevelChanger)
    }

    /** The amenity picked in the menu, or `null` for none. */
    fun updateHighlight(highlight: Highlight?) {
        if (highlight == this.highlight) return
        this.highlight = highlight
        canvas?.style?.let(::applyLevelChanger)
    }

    /** The galleries' markers: the gallery a leg leads to first and the visited galleries. */
    fun updateGalleryMarkers(markers: List<GalleryMarker>) {
        if (markers == galleryMarkers) return
        galleryMarkers = markers
        canvas?.style?.let(::applyLevelChanger)
    }

    /** The amenity icons of the venue data, by amenity id, at the start and after a change. */
    fun updateGalleryIcons(icons: Map<String, Bitmap>) {
        galleryIcons = icons
        iconGeneration += 1
        canvas?.style?.let(::applyLevelChanger)
    }

    // Style

    private fun install(canvas: ProximiioMapCanvas, style: Style) {
        for (type in LevelChangerPlace.FEATURE_TYPES) {
            val name = LevelChanger(Coordinate(0.0, 0.0), 0.0, type).imageName
            if (style.getImage(name) == null) levelChangerImage(canvas.context, type)?.let { style.addImage(name, it) }
            val standard = LevelChanger.standardImageName(type)
            if (style.getImage(standard) == null) standardLevelChangerImage(canvas.context, type)?.let { style.addImage(standard, it) }
        }
        for (icon in AmenityKind.entries.map { it.gadIcon }.toSet()) {
            val name = Highlight.imageName(icon)
            if (style.getImage(name) == null) redMarker(canvas.context, icon)?.let { style.addImage(name, it) }
        }
        if (style.getSource(LEVEL_CHANGER_SOURCE) == null) {
            style.addSource(GeoJsonSource(LEVEL_CHANGER_SOURCE))
            val layer = SymbolLayer(LEVEL_CHANGER_LAYER, LEVEL_CHANGER_SOURCE).withProperties(
                PropertyFactory.iconImage(Expression.get(IMAGE_PROPERTY)),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
                // The highlighted level changer over the others.
                PropertyFactory.symbolSortKey(Expression.get(SORT_PROPERTY)),
            )
            canvas.addLayer(layer, MapCanvasLayerAnchor.Above(ProximiioMapLayer.ROUTE))
        }
        if (style.getSource(GALLERY_SOURCE) == null) {
            style.addSource(GeoJsonSource(GALLERY_SOURCE))
            // The visited and the destination galleries over the venue's artworks, furniture and inner
            // walls (PM, Monday "Navigation to a gallery", 2026-10-27, and "Visited gallery
            // visualization", 2026-10-28), under labels, POI icons, the route and the stop markers. On
            // a style without inner walls the visited galleries are over the floor plan.
            for ((id, isDestination) in listOf(VISITED_LAYER to false, DESTINATION_LAYER to true)) {
                val layer = FillLayer(id, GALLERY_SOURCE).withFilter(
                    Expression.eq(Expression.get(DESTINATION_PROPERTY), Expression.literal(isDestination)),
                )
                val anchor = when {
                    isDestination -> MapCanvasLayerAnchor.Above(VISITED_LAYER)
                    style.getLayer(INNER_WALL_LAYER) != null -> MapCanvasLayerAnchor.Above(INNER_WALL_LAYER)
                    else -> MapCanvasLayerAnchor.AboveVenueFills
                }
                canvas.addLayer(layer, anchor)
            }
        }
        if (style.getSource(CONNECTOR_SOURCE) == null) {
            style.addSource(GeoJsonSource(CONNECTOR_SOURCE))
            val layer = LineLayer(CONNECTOR_LAYER, CONNECTOR_SOURCE).withProperties(
                PropertyFactory.lineColor(CONNECTOR_COLOR),
                PropertyFactory.lineWidth(CONNECTOR_WIDTH_DP),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            )
            canvas.addLayer(layer, MapCanvasLayerAnchor.Below(ProximiioMapLayer.ROUTE))
        }
        apply(style)
        applyConnector(style)
        applyLevelChanger(style)
    }

    private fun applyLevelChanger(style: Style) {
        val source = style.getSource(LEVEL_CHANGER_SOURCE) as? GeoJsonSource ?: return
        val highlighted = levelChanger?.takeIf { VenueLevel.same(it.level, level) }
        val features = levelChangers
            .filter { it.serves(level) && it.coordinate != highlighted?.coordinate }
            .map { point(it.coordinate, LevelChanger.standardImageName(it.featureType), sort = 0) }
            .toMutableList()
        if (highlighted != null) features += point(highlighted.coordinate, highlighted.imageName, sort = 1)
        highlight?.takeIf { VenueLevel.same(it.level, level) }?.let { features += point(it.coordinate, it.imageName, sort = 2) }
        val context = canvas?.context
        for (marker in galleryMarkers) {
            if (!VenueLevel.same(marker.level, level)) continue
            val name = marker.imageName(iconGeneration)
            if (style.getImage(name) == null && context != null) {
                style.addImage(name, galleryMarkerImage(context, marker.name, marker.amenityId?.let { galleryIcons[it] }, marker.isDestination))
            }
            features += point(marker.coordinate, name, sort = if (marker.isDestination) 3 else 0)
        }
        source.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    private fun point(coordinate: Coordinate, image: String, sort: Int): Feature =
        Feature.fromGeometry(Point.fromLngLat(coordinate.longitude, coordinate.latitude)).apply {
            addStringProperty(IMAGE_PROPERTY, image)
            addNumberProperty(SORT_PROPERTY, sort)
        }

    /** The design's icon of a level changer type. */
    private fun icon(featureType: String): GadIcon = if (featureType == "elevator") GadIcon.Elevator else GadIcon.Stairs

    /** The 30 dp amenity marker of a level changer that is not on the route ([MapStyle.amenityImage]). */
    private fun standardLevelChangerImage(context: Context, featureType: String): Bitmap? {
        val marker = icon(featureType).marker ?: return null
        val size = (MARKER_DP * context.resources.displayMetrics.density).roundToInt()
        return ContextCompat.getDrawable(context, marker)?.toBitmap(size, size)
    }

    /** The red marker of the route's next level change. */
    private fun levelChangerImage(context: Context, featureType: String): Bitmap? = redMarker(context, icon(featureType))

    /** 30 dp: a red circle with a white 2 dp ring and the white glyph of [icon] in its middle 18 dp. */
    private fun redMarker(context: Context, icon: GadIcon): Bitmap? {
        val glyph = icon.glyph
        val drawable = ContextCompat.getDrawable(context, glyph)?.mutate() ?: return null
        val density = context.resources.displayMetrics.density
        val size = (MARKER_DP * density).roundToInt()
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val radius = size / 2f - density
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = MARKER_COLOR
        canvas.drawCircle(size / 2f, size / 2f, radius, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2 * density
        paint.color = Color.WHITE
        canvas.drawCircle(size / 2f, size / 2f, radius - density, paint)
        val inset = (6 * density).roundToInt()
        drawable.setBounds(inset, inset, size - inset, size - inset)
        drawable.setTint(Color.WHITE)
        drawable.draw(canvas)
        return bitmap
    }

    private fun applyConnector(style: Style) {
        val source = style.getSource(CONNECTOR_SOURCE) as? GeoJsonSource ?: return
        val line = if (connector.size == 2) {
            listOf(Feature.fromGeometry(LineString.fromLngLats(connector.map { Point.fromLngLat(it.longitude, it.latitude) })))
        } else {
            emptyList()
        }
        source.setGeoJson(FeatureCollection.fromFeatures(line))
    }

    private fun apply(style: Style) {
        val colors = Gallery.colors(level)
        (style.getLayer(DESTINATION_LAYER) as? FillLayer)?.setProperties(PropertyFactory.fillColor(colors.destination.toInt()))
        (style.getLayer(VISITED_LAYER) as? FillLayer)?.setProperties(PropertyFactory.fillColor(colors.visited.toInt()))
        (style.getSource(GALLERY_SOURCE) as? GeoJsonSource)?.setGeoJson(
            FeatureCollection.fromFeatures(
                JourneyMapContent.fillsOn(level, fills).map { fill ->
                    val ring = fill.gallery.ring.map { Point.fromLngLat(it.longitude, it.latitude) }
                    val closed = if (ring.first() == ring.last()) ring else ring + ring.first()
                    Feature.fromGeometry(Polygon.fromLngLats(listOf(closed))).apply {
                        addBooleanProperty(DESTINATION_PROPERTY, fill.isDestination)
                    }
                },
            ),
        )
    }

    internal companion object {
        /** [place] with the icon of its amenity kind; `null` for a place of no [AmenityKind]. */
        fun highlight(place: VenuePlace?): Highlight? {
            place ?: return null
            val kind = AmenityKind.entries.firstOrNull { it.matches(place) } ?: return null
            return Highlight(place.coordinate, place.level, kind.gadIcon)
        }

        /**
         * A gallery's marker (PM, Monday "Gallery numbers in gallery navigation" and "Visited gallery
         * visualization", 2026-10-28). The destination is a red pill with a white border and the whole
         * title in white, no icon, its middle on the POI. A visited gallery is its [icon] in the white
         * 30 dp circle with the red border of a visited artwork, with its title under it; the circle is
         * in the middle of the image, so it sits on the POI. Without [icon], the gallery glyph.
         */
        fun galleryMarkerImage(context: Context, name: String, icon: Bitmap?, isDestination: Boolean): Bitmap {
            if (isDestination) return galleryPill(context, name)
            val density = context.resources.displayMetrics.density
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = INK_COLOR
                textSize = 12 * density
                textAlign = Paint.Align.CENTER
                typeface = ResourcesCompat.getFont(context, R.font.guggenheim_sans_display_semibold)
            }
            val labelWidth = minOf(paint.measureText(name), 120 * density) + 4 * density
            val labelHeight = paint.fontSpacing
            val circle = MARKER_DP * density
            val gap = 2 * density
            val width = maxOf(circle, labelWidth).roundToInt()
            val height = (circle + 2 * (gap + labelHeight)).roundToInt()
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val centerX = width / 2f
            val circleTop = gap + labelHeight
            val radius = circle / 2 - density
            val disk = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
            canvas.drawCircle(centerX, circleTop + circle / 2, radius, disk)
            val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = MARKER_COLOR
                style = Paint.Style.STROKE
                strokeWidth = 2 * density
            }
            canvas.drawCircle(centerX, circleTop + circle / 2, radius, ring)
            val glyphRect = RectF(centerX - circle / 2 + 7 * density, circleTop + 7 * density, centerX + circle / 2 - 7 * density, circleTop + circle - 7 * density)
            val glyph = icon ?: ContextCompat.getDrawable(context, R.drawable.ic_gallery)?.toBitmap(glyphRect.width().roundToInt(), glyphRect.height().roundToInt())
            glyph?.let { canvas.drawBitmap(it, null, glyphRect, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)) }
            val text = TextUtils.ellipsize(name, TextPaint(paint), 120 * density, TextUtils.TruncateAt.END).toString()
            val baseline = circleTop + circle + gap - paint.ascent()
            // The white halo of the venue style's labels, then the text.
            val halo = Paint(paint).apply {
                color = Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = 3 * density
            }
            canvas.drawText(text, centerX, baseline, halo)
            canvas.drawText(text, centerX, baseline, paint)
            return bitmap
        }

        /** The destination gallery's red pill: 30 dp high, the title in white. */
        private fun galleryPill(context: Context, name: String): Bitmap {
            val density = context.resources.displayMetrics.density
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 14 * density
                typeface = ResourcesCompat.getFont(context, R.font.guggenheim_sans_display_semibold)
            }
            val text = TextUtils.ellipsize(name, TextPaint(paint), 220 * density, TextUtils.TruncateAt.END).toString()
            val inset = 12 * density
            val height = (MARKER_DP * density).roundToInt()
            val width = (inset + paint.measureText(text) + inset + 2 * density).roundToInt()
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val pill = RectF(density, density, width - density, height - density)
            val corner = height / 2f - density
            canvas.drawRoundRect(pill, corner, corner, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = MARKER_COLOR })
            val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = 2 * density
            }
            canvas.drawRoundRect(pill, corner, corner, stroke)
            val baseline = height / 2f - (paint.descent() + paint.ascent()) / 2
            canvas.drawText(text, inset + density, baseline, paint)
            return bitmap
        }

        /** The organisation style's inner walls, above its artworks and furniture. */
        const val INNER_WALL_LAYER = "proximiio-inner_wall"
        const val GALLERY_SOURCE = "gad-journey-galleries"
        const val DESTINATION_LAYER = "gad-gallery-destination"
        const val VISITED_LAYER = "gad-gallery-visited"
        const val CONNECTOR_SOURCE = "gad-route-connector"
        const val CONNECTOR_LAYER = "gad-route-connector"
        const val DESTINATION_PROPERTY = "destination"
        const val CONNECTOR_COLOR = 0xFF3F69FF.toInt()
        const val CONNECTOR_WIDTH_DP = 6f
        const val LEVEL_CHANGER_SOURCE = "gad-level-changer"
        const val LEVEL_CHANGER_LAYER = "gad-level-changer"
        const val LEVEL_CHANGER_IMAGE = "gad-level-changer"
        const val IMAGE_PROPERTY = "image"
        const val SORT_PROPERTY = "sort"
        const val MARKER_DP = 30
        const val MARKER_COLOR = 0xFFED3731.toInt()
        const val INK_COLOR = 0xFF2B2B2B.toInt()
    }
}
