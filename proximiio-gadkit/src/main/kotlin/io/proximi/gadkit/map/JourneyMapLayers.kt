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
import org.maplibre.android.style.sources.ImageSource
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngQuad
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
 * - each gallery's title in a pill at its POI: red for the gallery a leg leads to, white with a red
 *   outline for a visited gallery, white with a black outline for the others ([updateGalleryMarkers];
 *   PM, Monday "Gallery numbers in gallery navigation", 2026-10-28).
 * - the visited and the destination galleries over the venue's artworks, furniture and inner walls
 *   (`proximiio-inner_wall`), under labels, POI icons, the route and the stop markers.
 * - the building's cones on the shown floor, each an image of the design's metal gradient
 *   ([VenueCone], [ConeShading]; PM, Monday "Cone visualization", 2026-10-29), over the floors and
 *   under the venue's galleries ([updateCones]); the venue style's cone outlines in the design's
 *   colour and width.
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
    private var cones: List<VenueCone> = emptyList()

    /** The cones' images, drawn when their floor first shows. */
    private var coneShadings: Map<String, ConeShading> = emptyMap()

    /** The image each cone source holds, so an unchanged cone keeps its source as it is. */
    private val coneSourceImages = mutableMapOf<String, Bitmap>()

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

    /** A gallery's title at its POI. [name] is the POI's title, such as "G303 Language". */
    data class GalleryMarker(val coordinate: Coordinate, val level: Double, val name: String, val style: Style) {
        enum class Style {
            /** The gallery a leg leads to first (gallery first). */
            DESTINATION,

            /** A gallery whose artworks of the journey are done. */
            VISITED,
            STANDARD,
        }

        val imageName: String get() = "gad-gallery-${style.name.lowercase()}-$name"
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
        if (levelChanged) {
            applyLevelChanger(style)
            applyCones(style)
        }
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

    /** The galleries' titles. */
    fun updateGalleryMarkers(markers: List<GalleryMarker>) {
        if (markers == galleryMarkers) return
        galleryMarkers = markers
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
        applyCones(style)
        styleConeOutlines(style)
    }

    /** The venue's cones, at the start and after a change of the venue data. */
    fun updateCones(cones: List<VenueCone>) {
        if (cones == this.cones) return
        val removed = this.cones.map { it.id }.toSet() - cones.map { it.id }.toSet()
        val unchanged = cones.filter { it in this.cones }.map { it.id }.toSet()
        coneShadings = coneShadings.filterKeys { it in unchanged }
        val style = canvas?.style
        for (id in removed) {
            style?.removeLayer(coneId(id))
            style?.removeSource(coneId(id))
            coneSourceImages.remove(coneId(id))
        }
        this.cones = cones
        style?.let(::applyCones)
    }

    /** The cones' outline in the design's colour and width, on each style. */
    private fun styleConeOutlines(style: Style) {
        val outline = style.getLayer(CONE_OUTLINE_LAYER) as? LineLayer ?: return
        outline.setProperties(
            PropertyFactory.lineColor(ConeShading.OUTLINE_COLOR),
            PropertyFactory.lineOpacity(ConeShading.OUTLINE_OPACITY),
            PropertyFactory.lineWidth(
                Expression.interpolate(
                    Expression.linear(),
                    Expression.zoom(),
                    *ConeShading.OUTLINE_WIDTHS.map { (zoom, width) -> Expression.stop(zoom, width) }.toTypedArray(),
                ),
            ),
        )
    }

    /**
     * Shows the cones of the shown floor and hides the others. A cone's image, source and layer are
     * made when its floor first shows; a changed cone gets a new image.
     */
    private fun applyCones(style: Style) {
        val canvas = canvas ?: return
        for (cone in cones) {
            val id = coneId(cone.id)
            val layer = style.getLayer(id)
            if (!VenueLevel.same(cone.level, level)) {
                layer?.setProperties(PropertyFactory.visibility(Property.NONE))
                continue
            }
            val shading = coneShadings[cone.id] ?: ConeShading.make(cone)?.also { coneShadings = coneShadings + (cone.id to it) } ?: continue
            val quad = LatLngQuad(
                LatLng(shading.north, shading.west),
                LatLng(shading.north, shading.east),
                LatLng(shading.south, shading.east),
                LatLng(shading.south, shading.west),
            )
            val source = style.getSource(id) as? ImageSource
            if (source == null) {
                style.addSource(ImageSource(id, quad, shading.bitmap))
            } else if (coneSourceImages[id] !== shading.bitmap) {
                source.setCoordinates(quad)
                source.setImage(shading.bitmap)
            }
            coneSourceImages[id] = shading.bitmap
            if (layer != null) {
                layer.setProperties(PropertyFactory.visibility(Property.VISIBLE))
            } else {
                val raster = RasterLayer(id, id).withProperties(PropertyFactory.rasterFadeDuration(0f))
                // Over the floors, under the venue's galleries and the gallery fills of [apply]: a cone
                // that is a gallery keeps the gallery's colour.
                val anchor = if (style.getLayer(VENUE_GALLERY_LAYER) != null) MapCanvasLayerAnchor.Below(VENUE_GALLERY_LAYER) else MapCanvasLayerAnchor.AboveVenueFills
                canvas.addLayer(raster, anchor)
            }
        }
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
            val name = marker.imageName
            if (style.getImage(name) == null && context != null) {
                style.addImage(name, galleryMarkerImage(context, marker.name, marker.style))
            }
            val sort = when (marker.style) {
                GalleryMarker.Style.DESTINATION -> 3
                GalleryMarker.Style.VISITED -> 1
                GalleryMarker.Style.STANDARD -> 0
            }
            features += point(marker.coordinate, name, sort)
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
         * A gallery's title in a 30 dp pill, its middle on the POI (PM, Monday "Gallery numbers in
         * gallery navigation", 2026-10-28): the destination red with a white border and white text, a
         * visited gallery white with a red outline, the others white with a black outline.
         */
        fun galleryMarkerImage(context: Context, name: String, style: GalleryMarker.Style): Bitmap {
            val density = context.resources.displayMetrics.density
            val (fill, outline, outlineDp, textColor) = when (style) {
                GalleryMarker.Style.DESTINATION -> PillColors(MARKER_COLOR, Color.WHITE, 2f, Color.WHITE)
                GalleryMarker.Style.VISITED -> PillColors(Color.WHITE, MARKER_COLOR, 2f, INK_COLOR)
                GalleryMarker.Style.STANDARD -> PillColors(Color.WHITE, INK_COLOR, 1.5f, INK_COLOR)
            }
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = textColor
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
            canvas.drawRoundRect(pill, corner, corner, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill })
            val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = outline
                this.style = Paint.Style.STROKE
                strokeWidth = outlineDp * density
            }
            canvas.drawRoundRect(pill, corner, corner, stroke)
            val baseline = height / 2f - (paint.descent() + paint.ascent()) / 2
            canvas.drawText(text, inset + density, baseline, paint)
            return bitmap
        }

        private data class PillColors(val fill: Int, val outline: Int, val outlineDp: Float, val text: Int)

        private fun coneId(id: String): String = "gad-cone-$id"

        /** The venue style's gallery fill; the cone images go under it. */
        const val VENUE_GALLERY_LAYER = "proximiio-gallery"

        /** The venue style's cone outlines. */
        const val CONE_OUTLINE_LAYER = "proximiio-cones"

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
