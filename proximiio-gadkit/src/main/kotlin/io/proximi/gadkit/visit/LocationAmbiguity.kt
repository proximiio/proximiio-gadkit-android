package io.proximi.gadkit.visit

import io.proximi.gadkit.Coordinate
import io.proximi.gadkit.map.VenuePlace
import io.proximi.gadkit.map.VenueLevel

/**
 * Returns the prompt that asks the visitor to confirm their location (Figma "Other Use Cases",
 * Edge 04 — Ambiguous location: four prompts, selected by position accuracy):
 *
 * - EC04a (two or more journey stops nearby) is not used: artworks are far enough apart that
 *   the question is not needed (PM review 2026-10-12)
 * - EC04b: the visitor is between galleries and no gallery geofence is entered
 * - EC04c: manual confirmation, opened by the visitor ("Pick my stop manually", "Not here"); UI only
 * - EC04d: the position is uncertain near the active stop: a soft prompt
 *
 * Checked in the order b, d. Pure; covered by tests.
 */
internal sealed interface LocationAmbiguity {
    /** EC04b: galleries the visitor could be in, nearest first. */
    data class BetweenGalleries(val galleries: List<VenuePlace>) : LocationAmbiguity

    /** EC04d: the visitor is probably at this stop, but the position is uncertain. */
    data class LowConfidence(val stop: JourneyStop) : LocationAmbiguity

    /** The kind, for suppressing a prompt the visitor has just answered. */
    val kind: String
        get() = when (this) {
            is BetweenGalleries -> "galleries"
            is LowConfidence -> "low"
        }

    data class Input(
        val coordinate: Coordinate,
        val level: Double?,
        val horizontalAccuracy: Double,
        val activeStop: JourneyStop?,
        /** Gallery places of the venue (title contains "Gallery"). */
        val galleries: List<VenuePlace>,
        /** The gallery geofence the visitor is in, if any. */
        val currentGallery: String?,
    )

    companion object {
        /** Positions less accurate than this are uncertain (metres). */
        internal const val UNCERTAIN_ACCURACY: Double = 8.0

        /** Galleries whose centre lies within this distance count as candidates (metres). */
        internal const val NEARBY_GALLERY_RADIUS: Double = 20.0

        internal fun evaluate(input: Input): LocationAmbiguity? {
            val here = input.coordinate

            fun sameLevel(level: Double?): Boolean {
                val visitor = input.level
                if (level == null || visitor == null) return true
                return VenueLevel.same(level, visitor)
            }

            if (input.currentGallery == null) {
                val nearbyGalleries = input.galleries
                    .filter { sameLevel(it.level) }
                    .map { it to here.distanceTo(it.coordinate) }
                    .filter { it.second <= NEARBY_GALLERY_RADIUS }
                    .sortedBy { it.second }
                    .map { it.first }
                if (nearbyGalleries.size >= 2) return BetweenGalleries(nearbyGalleries)
            }

            val active = input.activeStop
            if (input.horizontalAccuracy > UNCERTAIN_ACCURACY && active != null &&
                sameLevel(active.level) &&
                here.distanceTo(active.coordinate) <= input.horizontalAccuracy
            ) {
                return LowConfidence(active)
            }
            return null
        }

        /** Gallery places: the venue's places whose title names a gallery. */
        internal fun galleries(places: List<VenuePlace>): List<VenuePlace> =
            places.filter { it.title.contains("gallery", ignoreCase = true) }
    }
}
