package io.proximi.gadkit.map

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
 *
 * The layers are added after each style load and show the selected floor only. The gallery fills
 * go over the venue's
 * floor and room fills and under its walls, labels and POI icons
 * (`MapCanvasLayerAnchor.AboveVenueFills`, map 6.0.0-beta.18), as iOS uses `.aboveVenueFills`.
 *
 * Main thread only. [attach] it from `ProximiioMap(configure = …)`, then call [update] whenever the
 * journey, the galleries or the selected floor change. `VisitViewModel` uses it with
 * `JourneyMapContent.fills`.
 */
internal class JourneyMapLayers {
    private var canvas: ProximiioMapCanvas? = null
    private var styleToken: MapCanvasToken? = null
    private var fills: List<GalleryFill> = emptyList()
    private var level: Double = 0.0
    private var connector: List<Coordinate> = emptyList()

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

    /** Draws [fills] on [level]. Does nothing when nothing changed. */
    fun update(fills: List<GalleryFill>, level: Double) {
        if (fills == this.fills && level == this.level) return
        this.fills = fills
        this.level = level
        canvas?.style?.let(::apply)
    }

    /** The line from the visitor to the start of the route ahead. `null` removes it. */
    fun updateConnector(from: Coordinate?, to: Coordinate?) {
        val points = if (from != null && to != null) listOf(from, to) else emptyList()
        if (points == connector) return
        connector = points
        canvas?.style?.let(::applyConnector)
    }

    // Style

    private fun install(canvas: ProximiioMapCanvas, style: Style) {
        if (style.getSource(GALLERY_SOURCE) == null) {
            style.addSource(GeoJsonSource(GALLERY_SOURCE))
            // Each layer added at AboveVenueFills goes directly over the venue fills, so the
            // destination layer goes above the visited one.
            for ((id, isDestination) in listOf(VISITED_LAYER to false, DESTINATION_LAYER to true)) {
                val layer = FillLayer(id, GALLERY_SOURCE).withFilter(
                    Expression.eq(Expression.get(DESTINATION_PROPERTY), Expression.literal(isDestination)),
                )
                canvas.addLayer(layer, if (isDestination) MapCanvasLayerAnchor.Above(VISITED_LAYER) else MapCanvasLayerAnchor.AboveVenueFills)
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

    private companion object {
        const val GALLERY_SOURCE = "gad-journey-galleries"
        const val DESTINATION_LAYER = "gad-gallery-destination"
        const val VISITED_LAYER = "gad-gallery-visited"
        const val CONNECTOR_SOURCE = "gad-route-connector"
        const val CONNECTOR_LAYER = "gad-route-connector"
        const val DESTINATION_PROPERTY = "destination"
        const val CONNECTOR_COLOR = 0xFF3F69FF.toInt()
        const val CONNECTOR_WIDTH_DP = 6f
    }
}
