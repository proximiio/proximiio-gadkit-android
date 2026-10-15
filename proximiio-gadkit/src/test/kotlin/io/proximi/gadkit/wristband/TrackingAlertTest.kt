package io.proximi.gadkit.wristband

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The visit's alert choice, as iOS `VisitView.isShowingTrackingAlert`. */
class TrackingAlertTest {
    @Test
    fun nothingWhileWaitingOrTracking() {
        assertNull(TrackingAlert.shown(TrackingHealth.WAITING, isWristbandEnded = false, dismissedHealth = null))
        assertNull(TrackingAlert.shown(TrackingHealth.TRACKING, isWristbandEnded = false, dismissedHealth = null))
    }

    @Test
    fun healthSelectsTheAlert() {
        assertEquals(TrackingAlert.LOST_PLACE, TrackingAlert.shown(TrackingHealth.LOST_PLACE, false, null))
        assertEquals(TrackingAlert.STOPPED_RESPONDING, TrackingAlert.shown(TrackingHealth.STOPPED_RESPONDING, false, null))
    }

    @Test
    fun dismissedAlertStaysHiddenUntilTheHealthChanges() {
        assertNull(TrackingAlert.shown(TrackingHealth.LOST_PLACE, false, dismissedHealth = TrackingHealth.LOST_PLACE))
        assertEquals(
            TrackingAlert.STOPPED_RESPONDING,
            TrackingAlert.shown(TrackingHealth.STOPPED_RESPONDING, false, dismissedHealth = TrackingHealth.LOST_PLACE),
        )
    }

    @Test
    fun endedSessionWinsOverHealthAndDismissal() {
        assertEquals(TrackingAlert.WRISTBAND_ENDED, TrackingAlert.shown(TrackingHealth.STOPPED_RESPONDING, true, TrackingHealth.STOPPED_RESPONDING))
        assertEquals(TrackingAlert.WRISTBAND_ENDED, TrackingAlert.shown(TrackingHealth.WAITING, true, null))
    }

    @Test
    fun blockedByAnotherSheetOrThePairingFlow() {
        assertNull(TrackingAlert.shown(TrackingHealth.STOPPED_RESPONDING, true, null, isBlocked = true))
        assertNull(TrackingAlert.shown(TrackingHealth.LOST_PLACE, false, null, isBlocked = true))
    }
}
