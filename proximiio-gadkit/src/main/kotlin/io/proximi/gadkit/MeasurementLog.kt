package io.proximi.gadkit

import io.proximi.gadkit.map.AmenityShortcut
import io.proximi.gadkit.map.VenuePlace
import io.proximi.map.live.ProximiioMapSession
import io.proximi.sdk.positioning.engine.CustomPositionSample
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Field log lines for the measurements of the contract KPIs (Metis T-1950, 2026-11-27), as iOS
 * `MeasurementLog` (1.74.0). They change no behaviour and have no setting.
 *
 *     search category <title> (<screen>): <n> places · nearest <title> L<level> · <ms> ms
 *     search text <n> chars (<screen>): <n> of <n> places · <ms> ms
 *     explore list <tab>: <n> items · shown in <ms> ms
 *     destination <title>: <outcome> in <ms> ms
 *     drawn position <map>: <lat>,<lng> L<level> · at <epoch ms> · fix <lat>,<lng> ts <epoch ms> · relay <lat>,<lng> ts <epoch ms>
 *     relay positions: <n> in <s> s
 *
 * Times of a search and of a filter count from the call to the result on the device. Coordinates
 * have 7 decimals, about 1 cm.
 */
internal object MeasurementLog {
    // Search and filter

    /** The whole milliseconds from [startNanos] (`System.nanoTime`) to now. */
    fun millisecondsSince(startNanos: Long): Long = ((System.nanoTime() - startNanos) / 1e6).roundToLong()

    /**
     * The nearest place of [shortcut], as [AmenityShortcut.nearest], with a `search category` line.
     */
    fun nearest(shortcut: AmenityShortcut, places: List<VenuePlace>, from: Coordinate?, level: Double?, screen: String): VenuePlace? {
        val start = System.nanoTime()
        val matching = places.filter(shortcut::matches)
        val nearest = AmenityShortcut.nearestOf(matching, from, level)
        FieldLog.log(categoryLine(title(shortcut), screen, matching.size, nearest, millisecondsSince(start)))
        return nearest
    }

    /**
     * The name of [shortcut] in the line: the title of an amenity of the venue data, or the name of
     * a kind of the design without its language ("toilet", "cafe"). Pure; covered by tests.
     */
    fun title(shortcut: AmenityShortcut): String = when (shortcut) {
        is AmenityShortcut.Amenity -> shortcut.title
        is AmenityShortcut.Kind -> shortcut.kind.titleKey.removePrefix("amenity_")
    }

    /** Pure; covered by tests. */
    fun categoryLine(title: String, screen: String, results: Int, nearest: VenuePlace?, milliseconds: Long): String {
        val place = nearest?.let { "${it.title} L${it.level.roundToInt()}" } ?: "none"
        return "search category $title ($screen): $results places · nearest $place · $milliseconds ms"
    }

    /** The last text search that got a line, by screen. A screen can run the same search again when it is drawn again. */
    private val loggedSearches = HashMap<String, String>()

    /**
     * The places with [query] in their title, as [VenuePlace.matching], with a `search text` line
     * for each new query that is not empty. The line holds the length of the query, not its text.
     */
    fun places(query: String, places: List<VenuePlace>, screen: String): List<VenuePlace> {
        val start = System.nanoTime()
        val result = VenuePlace.matching(query, places)
        val needle = query.trim()
        val isNew = synchronized(loggedSearches) { loggedSearches.put(screen, needle) != needle }
        if (isNew && needle.isNotEmpty()) {
            FieldLog.log(textLine(needle.length, screen, result.size, places.size, millisecondsSince(start)))
        }
        return result
    }

    /** Pure; covered by tests. */
    fun textLine(characters: Int, screen: String, results: Int, places: Int, milliseconds: Long): String =
        "search text $characters chars ($screen): $results of $places places · $milliseconds ms"

    /** Pure; covered by tests. */
    fun listLine(tab: String, items: Int, milliseconds: Long): String = "explore list $tab: $items items · shown in $milliseconds ms"

    /** Pure; covered by tests. */
    fun destinationLine(title: String, outcome: String, milliseconds: Long): String = "destination $title: $outcome in $milliseconds ms"

    // Drawn position

    /**
     * The last position of the relay-api the binding's provider gave, before the SDK processes it.
     * `BindingFieldLog` and `VenueService` set it for every sample while the field log is on.
     */
    @Volatile
    var latestRelayPosition: CustomPositionSample? = null

    /** What one `drawn position` line holds. */
    data class DrawnSample(
        /** The centre of the position dot in the last frame the map drew. */
        val drawnLatitude: Double,
        val drawnLongitude: Double,
        /** The position of the SDK the dot moves toward, with its floor and its time (epoch ms). */
        val fixLatitude: Double? = null,
        val fixLongitude: Double? = null,
        val level: Double? = null,
        val fixTimeMillis: Long? = null,
        /** The last position of the relay-api, with the time of the positioning engine (`ts`, epoch ms). */
        val relayLatitude: Double? = null,
        val relayLongitude: Double? = null,
        val relayTimeMillis: Long? = null,
    )

    /** The sample of [session] now. `null` while the map draws no position dot. Main thread. */
    fun drawnSample(session: ProximiioMapSession): DrawnSample? {
        val frame = session.renderer?.frame ?: return null
        val drawn = frame.drawn ?: return null
        val fix = frame.position
        val relay = latestRelayPosition
        return DrawnSample(
            drawnLatitude = drawn.coordinate.latitude,
            drawnLongitude = drawn.coordinate.longitude,
            fixLatitude = fix?.coordinate?.latitude,
            fixLongitude = fix?.coordinate?.longitude,
            level = fix?.floor?.level,
            fixTimeMillis = fix?.timestampMillis,
            relayLatitude = relay?.coordinate?.latitude,
            relayLongitude = relay?.coordinate?.longitude,
            relayTimeMillis = relay?.timestamp,
        )
    }

    /** Pure; covered by tests. */
    fun drawnLine(sample: DrawnSample, map: String, nowMillis: Long): String {
        val level = sample.level?.roundToInt()?.toString() ?: "-"
        val fix = part(sample.fixLatitude, sample.fixLongitude, sample.fixTimeMillis)
        val relay = part(sample.relayLatitude, sample.relayLongitude, sample.relayTimeMillis)
        return "drawn position $map: ${text(sample.drawnLatitude, sample.drawnLongitude)} L$level · at $nowMillis · fix $fix · relay $relay"
    }

    private fun part(latitude: Double?, longitude: Double?, timeMillis: Long?): String =
        if (latitude == null || longitude == null) "-" else "${text(latitude, longitude)} ts ${timeMillis ?: "-"}"

    private fun text(latitude: Double, longitude: Double): String = String.format(Locale.ROOT, "%.7f,%.7f", latitude, longitude)

    // Position rate

    /**
     * Counts the positions of the relay-api and gives a `relay positions` line for every
     * [INTERVAL_SECONDS]. The line is written with the first position after the interval, so a gap
     * in the positions shows as a longer time.
     */
    class RateLog {
        private var start: Double? = null
        private var count = 0

        /** Counts a position at [now] (seconds of a monotonic clock). Pure; covered by tests. */
        fun position(now: Double): String? {
            val started = start
            if (started == null) {
                start = now
                count = 0
                return null
            }
            count += 1
            if (now - started < INTERVAL_SECONDS) return null
            val line = String.format(Locale.ROOT, "relay positions: %d in %.1f s", count, now - started)
            start = now
            count = 0
            return line
        }

        companion object {
            const val INTERVAL_SECONDS: Double = 60.0
        }
    }
}
