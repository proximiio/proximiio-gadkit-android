package io.proximi.gadkit

import io.proximi.gadkit.visit.JourneyKind
import io.proximi.gadkit.visit.VisitViewModel
import io.proximi.gadkit.map.MapLoading
import io.proximi.gadkit.map.MapStyle
import io.proximi.gadkit.visit.StopNearbyDetector
import io.proximi.map.core.PositionSmoothing
import io.proximi.gadkit.wristband.TrackingHealth
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Port of ProximiioGADKit `GADSettingsTests`. */
class GadSettingsTest {
    @Test
    fun theConfigurationStartsWithTheDefaults() {
        val configuration = GadConfiguration("https://api.example.com", "token", "https://relay.example.com", "relay")
        assertEquals(GadSettings.STANDARD, configuration.settings)
        assertEquals(110.seconds, GadSettings.STANDARD.trackingLostAfter)
        assertEquals(5.0, GadSettings.STANDARD.stopNearbyMeters)
        assertEquals(setOf(0), GadSettings.STANDARD.levelsWithoutPositioning)
        assertEquals(5.seconds, GadSettings.STANDARD.levelChangeViewDuration)
        assertEquals(20.0, GadSettings.STANDARD.rideFloorHoldMeters)
        // Zoom 18 for 2 s, then the follow (PM, Monday "Zoom level at start + navigation", 2026-10-29).
        assertEquals(18.0, GadSettings.STANDARD.startZoom)
        assertEquals(2.seconds, GadSettings.STANDARD.startZoomDuration)
        assertEquals(5.0, GadSettings.STANDARD.levelChangerReachedMeters)
        assertEquals(1800.seconds, GadSettings.STANDARD.floorWithoutPositioningMaximum)
        assertTrue(GadSettings.STANDARD.postsGeofenceNotifications)
        assertTrue(GadSettings.STANDARD.notifiesGeofenceEvents)
        assertFalse(GadSettings.STANDARD.smoothsPosition)
        assertEquals(null, GadSettings.STANDARD.minimumZoom)
        assertEquals(null, GadSettings.STANDARD.maximumZoom)
    }

    @Test
    fun theMapDrawsTheDotOnEachFixUnlessItSmooths() {
        assertEquals(PositionSmoothing.NONE, MapStyle.options("en").position.smoothing)
        assertEquals(PositionSmoothing.ADAPTIVE, MapStyle.options("en", settings = GadSettings(smoothsPosition = true)).position.smoothing)
        assertEquals(PositionSmoothing.NONE, MapStyle.exploreOptions("en").position.smoothing)
        assertEquals(30.0, MapStyle.options("en", settings = GadSettings(followZoom = 30.0)).recentreZoom)
    }

    /** Port of `DesignSystemTests.theMapSpinnerStaysUntilTheVenueIsDrawn`. */
    @Test
    fun theMapSpinnerStaysUntilTheVenueIsDrawn() {
        assertTrue(MapLoading.isShown(featureCount = 0, lastError = null, hasTimedOut = false, isBusy = false))
        assertFalse(MapLoading.isShown(featureCount = 120, lastError = null, hasTimedOut = false, isBusy = false))
        assertFalse(MapLoading.isShown(featureCount = 0, lastError = "style", hasTimedOut = false, isBusy = false))
        assertFalse(MapLoading.isShown(featureCount = 0, lastError = null, hasTimedOut = true, isBusy = false))
        assertTrue(MapLoading.isShown(featureCount = 120, lastError = null, hasTimedOut = true, isBusy = true))
        assertEquals(20.seconds, GadSettings.STANDARD.mapLoadingTimeout)
    }

    @Test
    fun theRelayApiCanBeAUrl() {
        val configuration = GadConfiguration("https://api.example.com", "token", "https://relay.example.com", "relay")
        assertEquals("relay.example.com", configuration.relayEndpoint.baseUrl.host)
        assertFailsWith<IllegalArgumentException> { GadConfiguration("https://api.example.com", "token", "file:///tmp", "relay") }
    }

    @Test
    fun trackingAlertsFollowTheSettings() {
        val settings = GadSettings(trackingLostAfter = 30.seconds, trackingStoppedAfter = 60.seconds)
        val since = Instant.ofEpochSecond(0)
        fun health(afterSeconds: Long) = TrackingHealth.evaluate(true, since, since.plusSeconds(afterSeconds), settings)
        assertEquals(TrackingHealth.TRACKING, health(29))
        assertEquals(TrackingHealth.LOST_PLACE, health(30))
        assertEquals(TrackingHealth.STOPPED_RESPONDING, health(60))
        assertEquals(TrackingHealth.TRACKING, TrackingHealth.evaluate(true, since, since.plusSeconds(60)))
    }

    @Test
    fun stopNearbyFollowsTheSettings() {
        val stop = Coordinate(24.5, 54.4)
        val eightMetresNorth = Coordinate(24.5 + 8.0 / 111_320, 54.4)
        assertFalse(StopNearbyDetector.isNearby(eightMetresNorth, 1.0, stop, 1.0))
        assertTrue(StopNearbyDetector.isNearby(eightMetresNorth, 1.0, stop, 1.0, nearbyMeters = 10.0))
        assertTrue(StopNearbyDetector.isNearby(eightMetresNorth, 1.0, stop, 1.0, routeMeters = 9.0, nearbyMeters = 10.0))
    }

    /** RPR, Monday "Need to determine correct zoom levels on-site", 2026-10-28. */
    @Test
    fun theFollowZoomIs18Point8And20InAGalleryOfAPersonalisedJourney() {
        val settings = GadSettings.STANDARD
        assertEquals(18.8, VisitViewModel.followZoom(inGallery = false, kind = JourneyKind.PERSONALISED, settings = settings))
        assertEquals(20.0, VisitViewModel.followZoom(inGallery = true, kind = JourneyKind.PERSONALISED, settings = settings))
        assertEquals(18.8, VisitViewModel.followZoom(inGallery = true, kind = JourneyKind.CURATED, settings = settings))
        val withoutGalleryZoom = settings.copy(galleryFollowZoom = null)
        assertEquals(18.8, VisitViewModel.followZoom(inGallery = true, kind = JourneyKind.PERSONALISED, settings = withoutGalleryZoom))
    }

    /** The heading-up map turns in 1.5 s and ignores changes under 5° (Nagarro, 2026-10-29; map 6.0.0-beta.32). */
    @Test
    fun theMapTurnsSlowlyAndIgnoresSmallHeadingChanges() {
        val standard = MapStyle.options("en", settings = GadSettings.STANDARD)
        assertEquals(1.5, standard.bearingSettlingTime)
        assertEquals(5.0, standard.bearingDeadband)
        val changed = MapStyle.options("en", settings = GadSettings.STANDARD.copy(bearingSettlingTime = 2.seconds, bearingDeadband = 0.0))
        assertEquals(2.0, changed.bearingSettlingTime)
        assertEquals(0.0, changed.bearingDeadband)
    }

    /** Overlapping stop markers show as one with a count badge, but not while they are hidden (PM, Monday "Clustering", 2026-10-29). */
    @Test
    fun overlappingStopMarkersAreGrouped() {
        assertTrue(GadSettings.STANDARD.groupsStopMarkers)
        assertTrue(MapStyle.journeyStops.groupsOverlapping)
        assertFalse(MapStyle.journeyStopsHidden.groupsOverlapping)
    }
}
