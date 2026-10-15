package io.proximi.gadkit.wristband

/**
 * The sheet a visit shows when positions stop or the wristband session ends, as the
 * `isShowingTrackingAlert` binding of iOS `VisitView` (Figma "Wristband", nodes 9330:84838 and
 * 9330:84866; iOS `TrackingAlertSheet` and `WristbandEndedSheet`).
 */
internal enum class TrackingAlert {
    /** "We've lost your place in the room". */
    LOST_PLACE,

    /** "Your wristband has stopped responding". */
    STOPPED_RESPONDING,

    /**
     * "Your wristband is no longer connected", while the session is ended. An ended session
     * delivers no positions, so the tracking alert would never clear.
     */
    WRISTBAND_ENDED,
    ;

    internal companion object {
        /**
         * The alert to show, or `null`. Pure; covered by tests.
         *
         * @param isWristbandEnded the binding state is `Ended`.
         * @param dismissedHealth the health the visitor dismissed; that alert stays hidden until
         *   the health changes. The caller clears it when tracking resumes.
         * @param isBlocked another sheet or the pairing flow is on screen.
         */
        internal fun shown(
            health: TrackingHealth,
            isWristbandEnded: Boolean,
            dismissedHealth: TrackingHealth?,
            isBlocked: Boolean = false,
        ): TrackingAlert? {
            if (isBlocked) return null
            if (isWristbandEnded) return WRISTBAND_ENDED
            if (health == dismissedHealth) return null
            return when (health) {
                TrackingHealth.LOST_PLACE -> LOST_PLACE
                TrackingHealth.STOPPED_RESPONDING -> STOPPED_RESPONDING
                TrackingHealth.WAITING, TrackingHealth.TRACKING -> null
            }
        }
    }
}
