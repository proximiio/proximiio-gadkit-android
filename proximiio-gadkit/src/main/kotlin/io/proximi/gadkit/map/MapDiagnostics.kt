package io.proximi.gadkit.map

import io.proximi.gadkit.FieldLog
import io.proximi.map.live.ProximiioMapSession
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

/**
 * Field log only, as iOS `MapDiagnostics`. The state of a map session, written when it changes: the
 * venue features on the map, the features the last push left out, the last error, where the style
 * came from, the shown floor, the zoom and the style's fonts the glyph server does not have. For the
 * floor plan that is missing on the journey map at a high zoom (PM, Monday "Floorplan disappears
 * zometimes", 2026-10-27): a MapLibre source takes the venue's collection whole or not at all. Also the
 * time from a map's appearance to the map showing the venue and to its first fully drawn frame
 * (`ProximiioMapSession.venueDrawing`, map 6.0.0-beta.31).
 */
internal object MapDiagnostics {
    /** One state of a map session. Pure; covered by tests. */
    data class Snapshot(
        val features: Int,
        val dropped: Int,
        val error: String?,
        val style: String,
        val level: Double,
        /** The zoom to 0.5. */
        val zoom: Double?,
        val isCameraReady: Boolean,
        /** The style's font stacks the glyph server answered "not found" for, sorted. */
        val missingFonts: List<String> = emptyList(),
        val name: String = "",
    ) {
        val line: String
            get() {
                val zoomText = zoom?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "-"
                return "map state $name: features $features · dropped $dropped · style $style · L${level.roundToInt()} · zoom $zoomText · camera ready $isCameraReady" +
                    (if (missingFonts.isEmpty()) "" else " · missing fonts ${missingFonts.joinToString()}") +
                    (error?.let { " · error $it" } ?: "")
            }

        companion object {
            fun rounded(zoom: Double?): Double? = zoom?.let { Math.round(it * 2) / 2.0 }
        }
    }

    /** The time from a map's appearance to each step of the map showing the venue. Pure; covered by tests. */
    data class Timing(
        val name: String = "",
        /** Seconds from `ProximiMap.open` to the map's appearance, when a screen was opened. */
        val openSeconds: Double? = null,
        /** Each style the map took, in order. */
        val styles: List<Style> = emptyList(),
        val features: Int? = null,
        val featuresSeconds: Double? = null,
        val cameraSeconds: Double? = null,
        /** The first frame drawn with the venue's features, tiles, icons and labels (`VenueDrawing.DRAWN`). */
        val drawnSeconds: Double? = null,
    ) {
        data class Style(val source: String, val isVenue: Boolean, val seconds: Double)

        /** The venue's first style, its features and the camera, whichever came last. */
        val shownSeconds: Double?
            get() {
                val style = styles.firstOrNull { it.isVenue } ?: return null
                return max(style.seconds, max(featuresSeconds ?: return null, cameraSeconds ?: return null))
            }

        /** [closedSeconds]: when the map went before it drew the venue. */
        fun line(closedSeconds: Double? = null): String {
            val parts = styles.map { "style ${it.source} ${text(it.seconds)}" }.toMutableList()
            if (features != null && featuresSeconds != null) parts += "features $features ${text(featuresSeconds)}"
            cameraSeconds?.let { parts += "camera ready ${text(it)}" }
            shownSeconds?.let { parts += "shown ${text(it)}" }
            if (drawnSeconds != null) {
                parts += "drawn ${text(drawnSeconds)}" + (openSeconds?.let { ", ${text(drawnSeconds + it)} after open()" } ?: "")
            } else if (closedSeconds != null) {
                parts += "closed ${text(closedSeconds)} before drawn"
            }
            return "map timing $name: " + parts.joinToString(" · ")
        }

        companion object {
            fun text(seconds: Double): String = String.format(Locale.ROOT, "%.2f s", seconds)
        }
    }

    /** When `ProximiMap.open` was last called (`System.nanoTime`). The next map timing counts from it too. */
    @Volatile
    var openedAtNanos: Long? = null

    fun seconds(sinceNanos: Long): Double = (System.nanoTime() - sinceNanos) / 1e9

    fun styleName(source: ProximiioMapSession.StyleSource): String = source.toString().lowercase(Locale.ROOT)

    /**
     * Writes the state of [session] to the field log when it changes, checked every second, while the
     * caller's coroutine runs, and the time the map takes to show the venue ([Timing]), counted from
     * the call. Does nothing when the field log is off.
     */
    suspend fun follow(session: ProximiioMapSession, name: String, scope: CoroutineScope) {
        if (!FieldLog.isEnabled) return
        val recorder = TimingRecorder(session, name, scope)
        try {
            var last: Snapshot? = null
            while (coroutineContext.isActive) {
                val snapshot = Snapshot(
                    features = session.featureCount.value,
                    dropped = session.droppedFeatureCount.value,
                    error = session.lastError.value,
                    style = styleName(session.styleSource.value),
                    level = session.selectedFloor.value.level,
                    zoom = Snapshot.rounded(session.camera.zoomLevel),
                    isCameraReady = session.isCameraReady.value,
                    missingFonts = session.unavailableFontStacks.value.sorted(),
                    name = name,
                )
                if (snapshot != last) {
                    FieldLog.log(snapshot.line)
                    last = snapshot
                }
                delay(1_000)
            }
        } finally {
            recorder.close()
        }
    }

    /**
     * Records [Timing] from the session's flows and writes it once: when the map has drawn the venue,
     * or at [close]. A style the map takes, or a drawing of the venue, after that gets a line of its
     * own: two drawings in one opening mean the style was loaded twice.
     */
    private class TimingRecorder(session: ProximiioMapSession, name: String, scope: CoroutineScope) {
        private val start = System.nanoTime()
        private var timing = Timing(name = name, openSeconds = openedAtNanos?.let(::seconds))
        private var isWritten = false
        private val jobs = mutableListOf<Job>()

        init {
            openedAtNanos = null
            jobs += scope.launch {
                session.styleSource.collect { source ->
                    if (source == ProximiioMapSession.StyleSource.Pending) return@collect
                    val style = Timing.Style(styleName(source), source.isVenue, seconds(start))
                    if (isWritten) {
                        FieldLog.log("map timing ${timing.name}: style ${style.source} ${Timing.text(style.seconds)} after drawn")
                    } else {
                        timing = timing.copy(styles = timing.styles + style)
                    }
                }
            }
            jobs += scope.launch {
                // Not `== DRAWN`: a collector that starts after MapLibre went idle sees `IDLE`.
                session.venueDrawing.collect { drawing ->
                    if (drawing == ProximiioMapSession.VenueDrawing.PENDING) return@collect
                    if (drawing != ProximiioMapSession.VenueDrawing.DRAWN && isWritten) return@collect
                    val seconds = seconds(start)
                    if (isWritten) {
                        FieldLog.log("map timing ${timing.name}: drawn again ${Timing.text(seconds)}")
                    } else {
                        timing = timing.copy(drawnSeconds = seconds)
                        write(null)
                    }
                }
            }
            jobs += scope.launch {
                val count = session.featureCount.first { it > 0 }
                timing = timing.copy(features = count, featuresSeconds = seconds(start))
            }
            jobs += scope.launch {
                session.isCameraReady.first { it }
                timing = timing.copy(cameraSeconds = seconds(start))
            }
        }

        /** Writes what was recorded, unless the map drew the venue before, and stops. */
        fun close() {
            write(seconds(start))
            jobs.forEach { it.cancel() }
        }

        private fun write(closedSeconds: Double?) {
            if (isWritten) return
            isWritten = true
            FieldLog.log(timing.line(closedSeconds))
        }
    }
}
