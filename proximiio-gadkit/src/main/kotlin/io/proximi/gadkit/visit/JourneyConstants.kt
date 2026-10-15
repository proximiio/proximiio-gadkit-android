package io.proximi.gadkit.visit

/**
 * Navigation rules of a visit (iOS `VisitController.detourGuidanceRules` and `JourneyRules.visit`).
 * The app passes them to the SDK's journey navigator.
 */
internal object JourneyConstants {
    /**
     * The arrival radius is 2.5 × the fix accuracy, at least 5 m and at most 10 m. The SDK
     * default (at least 3 m, 2 s dwell) missed visitors who walked past a stop with relay fixes.
     */
    internal const val ARRIVAL_ACCURACY_MULTIPLIER: Double = 2.5
    internal const val ARRIVAL_MIN_METERS: Double = 5.0
    internal const val ARRIVAL_MAX_METERS: Double = 10.0

    /** Seconds within the arrival radius before a detour counts as reached. Artworks are reached by their geofence. */
    internal const val ARRIVAL_DWELL_SECONDS: Double = 5.0

    /**
     * Time between the arrival at an artwork and the route to the next one (PM review 2026-10-12:
     * no "Continue" button). A detour waits for "Resume your Journey".
     */
    internal const val AUTO_ADVANCE_DELAY_MILLIS: Long = 2_000

    /** With the debug flag `visitAutoAdvance`, a reached detour ends this far from it. */
    internal const val AUTO_ADVANCE_ON_DEPARTURE_METERS: Double = 5.0

    internal fun arrivalRadiusMeters(horizontalAccuracy: Double): Double =
        (horizontalAccuracy * ARRIVAL_ACCURACY_MULTIPLIER).coerceIn(ARRIVAL_MIN_METERS, ARRIVAL_MAX_METERS)
}
