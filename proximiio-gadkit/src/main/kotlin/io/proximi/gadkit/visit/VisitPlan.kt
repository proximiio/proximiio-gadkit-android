package io.proximi.gadkit.visit

import io.proximi.gadkit.map.Gallery
import io.proximi.gadkit.map.VenuePlace
import io.proximi.gadkit.roundedInt

/**
 * The ordered stops of a visit, and the pure rules the visit screens use.
 *
 * The stops of a personalised journey come from Nagarro's guide as an ordered list of
 * Proximi.io POI ids. Debug builds can also pick stops by hand.
 */
public object VisitPlan {
    /**
     * A journey from POI ids in visit order. Ids that match no place are dropped. Each stop's group
     * is the gallery of [galleries] that contains it.
     */
    public fun journey(poiIds: List<String>, places: List<VenuePlace>, galleries: List<Gallery> = emptyList()): Journey {
        val byId = LinkedHashMap<String, VenuePlace>()
        for (place in places) byId.putIfAbsent(place.id, place)
        return Journey(poiIds.mapNotNull { byId[it] }.map { stop(it, galleries) })
    }

    internal fun stop(place: VenuePlace, galleries: List<Gallery> = emptyList()): JourneyStop = JourneyStop(
        id = place.id,
        title = place.title,
        coordinate = place.coordinate,
        level = place.level,
        poiId = place.id,
        group = Gallery.containing(place.coordinate, place.level, galleries)?.id,
    )

    /** [journey] with stop [id] set to [state]. The running navigator edits stops itself; this builds journeys in tests. */
    internal fun setting(state: StopState, id: String, journey: Journey): Journey {
        val index = journey.stops.indexOfFirst { it.id == id }
        if (index < 0) return journey
        return Journey(journey.stops.toMutableList().apply { this[index] = this[index].copy(state = state) })
    }

    internal data class GalleryGroup(val id: String, val gallery: String?, val stops: List<JourneyStop>)

    /**
     * Consecutive stops in the same gallery form one group. A gallery visited twice yields two
     * groups, so the list keeps the visit order.
     */
    internal fun galleryGroups(stops: List<JourneyStop>, galleryOf: (JourneyStop) -> String?): List<GalleryGroup> {
        val groups = mutableListOf<GalleryGroup>()
        for (stop in stops) {
            val gallery = galleryOf(stop)
            val last = groups.lastOrNull()
            if (last != null && last.gallery == gallery) {
                groups[groups.lastIndex] = last.copy(stops = last.stops + stop)
            } else {
                groups += GalleryGroup("${groups.size}-${gallery ?: ""}", gallery, listOf(stop))
            }
        }
        return groups
    }

    /** True when [stop] is the last artwork still to visit (Figma 04.01 "approaching end of tour"). */
    internal fun isLastOpenStop(stop: JourneyStop, journey: Journey): Boolean =
        artworks(journey).filter { it.isOpen }.map { it.id } == listOf(stop.id)

    /** Artworks still to visit: pending, active or reached. A finished journey has none. */
    public fun remainingStopCount(journey: Journey): Int = artworks(journey).count { it.isOpen }

    /**
     * The journey's artworks: stops that are not detours. An amenity added as a detour has no
     * number and is not in the list (PM review 2026-10-12).
     */
    internal fun artworks(journey: Journey): List<JourneyStop> = journey.stops.filter { it.kind != StopKind.DETOUR }

    /**
     * Display number of each artwork that is not skipped, 1-based, in visit order. Skipped stops
     * and detours get no number, so the list renumbers after a skip, as the design shows.
     */
    internal fun numbers(journey: Journey): Map<String, Int> {
        val result = LinkedHashMap<String, Int>()
        var next = 1
        for (stop in artworks(journey)) {
            if (stop.state == StopState.SKIPPED) continue
            result[stop.id] = next++
        }
        return result
    }

    /**
     * Back on the journey screen asks first (03.10) while the visit is under way: a stop is done
     * or skipped, and the journey is not finished. Otherwise Back closes at once (iOS `requestClose`).
     */
    internal fun asksBeforeLeaving(journey: Journey, isFinished: Boolean): Boolean =
        !isFinished && journey.stops.any { it.state == StopState.DONE || it.state == StopState.SKIPPED }

    /** "You've visited %1$d of %2$d stops" (03.10): done stops, and the stops not skipped. */
    internal data class Progress(val visited: Int, val total: Int)

    internal fun progress(journey: Journey): Progress {
        val stops = journey.stops.filter { it.state != StopState.SKIPPED }
        return Progress(stops.count { it.state == StopState.DONE }, stops.size)
    }
}

/** What the visit card shows (Figma "MAP Functionality Approach 02" annotations). */
internal sealed interface VisitCardState {
    /** The navigator has not activated a stop yet. */
    data object Starting : VisitCardState

    /** "Next stop: Gallery 2", with the next instruction. */
    data class Walking(val stop: String) : VisitCardState

    /** "Next stop is on Level 2 / Take the lift", while the visitor is on another floor. */
    data class NextStopOnOtherLevel(val stop: String, val level: Int) : VisitCardState

    /**
     * "Current artwork: Untitled (Mirrored Room)", until the journey moves on. Hidden while
     * [SHOWS_ARRIVAL] is false.
     */
    data class AtStop(val stop: String) : VisitCardState

    /** Every stop is done or skipped. */
    data object Finished : VisitCardState

    companion object {
        /**
         * False: on arrival the card keeps the stop until the journey moves on 2 s later, with no
         * "Current artwork" state (PM review 2026-10-12: the card is not needed).
         */
        internal const val SHOWS_ARRIVAL: Boolean = false

        internal fun make(
            activeStop: JourneyStop?,
            hasArrived: Boolean,
            isFinished: Boolean,
            visitorLevel: Double?,
            showsArrival: Boolean = SHOWS_ARRIVAL,
        ): VisitCardState {
            val stop = activeStop ?: return if (isFinished) Finished else Starting
            if (hasArrived && showsArrival) return AtStop(stop.title)
            val stopLevel = stop.level
            if (stopLevel != null && visitorLevel != null && stopLevel.roundedInt() != visitorLevel.roundedInt()) {
                return NextStopOnOtherLevel(stop.title, stopLevel.roundedInt())
            }
            return Walking(stop.title)
        }
    }
}

/**
 * Title and summary of a visit, shown on Journey ready (02.10A) and the Hub card.
 * iOS `VisitMeta.standard(stopCount:)` ("Your journey", "%d stops") is localized text; the app builds it.
 * [kind] is [JourneyKind.PERSONALISED] for a stored visit from before the kind existed.
 */
public data class VisitMeta(
    public val title: String,
    public val summary: String? = null,
    public val kind: JourneyKind = JourneyKind.PERSONALISED,
)

/**
 * A journey the visitor built with the guide, or a tour by the museum's curators, as iOS
 * `JourneyKind` (Figma "Personalised" and "Guided").
 */
public enum class JourneyKind(public val rawValue: String) {
    PERSONALISED("personalised"),
    CURATED("curated"),
    ;

    /** Whether the stop card names the gallery of the next stop first. Pure; covered by tests. */
    public val guidesToGalleryFirst: Boolean get() = this == PERSONALISED

    /** Whether the list shows gallery names. Pure; covered by tests. */
    public val listsGalleries: Boolean get() = this == PERSONALISED

    /** Whether the visitor can skip an artwork. Pure; covered by tests. */
    public val allowsSkip: Boolean get() = this == PERSONALISED

    /**
     * Whether the stops keep their order: no ordering from the visitor at the start, after a
     * detour or after an artwork visited out of order. Pure; covered by tests.
     */
    public val keepsOrder: Boolean get() = this == CURATED

    public companion object {
        /** The kind of [rawValue]; [PERSONALISED] for `null` or an unknown value. */
        public fun of(rawValue: String?): JourneyKind = entries.firstOrNull { it.rawValue == rawValue } ?: PERSONALISED
    }
}
