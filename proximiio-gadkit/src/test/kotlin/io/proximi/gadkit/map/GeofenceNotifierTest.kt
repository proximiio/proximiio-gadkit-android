package io.proximi.gadkit.map

import io.proximi.gadkit.UiText
import io.proximi.sdk.core.model.JsonValue
import io.proximi.sdk.core.model.ProximiioGeofence
import io.proximi.sdk.core.model.ProximiioPrivacyZone
import io.proximi.sdk.geofencing.GeofenceEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Port of ProximiioGADKit `GeofenceNotifierTests`. */
class GeofenceNotifierTest {
    private fun geofence(id: String, name: String?, type: String) =
        ProximiioGeofence(id = id, name = name, metadata = JsonValue.Object(mapOf("geofence_type" to JsonValue.String(type))))

    @Test
    fun theTitleIsTheGeofenceName() {
        val notice = assertNotNull(GeofenceNotifier.notice(GeofenceEvent.Entered(geofence("g1", " Gallery 101 ", "gallery"))))
        assertEquals("Gallery 101", notice.title)
        assertEquals("geofence-entered-g1", notice.tag)
        assertEquals(UiText.Resource("geofence_entered"), notice.body)
        assertEquals("geofence", notice.group)
    }

    @Test
    fun anExitPostsItsOwnNotification() {
        val notice = assertNotNull(GeofenceNotifier.notice(GeofenceEvent.Exited(geofence("g1", "Gallery 101", "gallery"), dwellTime = 30.0)))
        assertEquals("Gallery 101", notice.title)
        assertEquals("geofence-exited-g1", notice.tag)
        assertEquals(UiText.Resource("geofence_exited"), notice.body)
    }

    @Test
    fun everyGeofenceTypePostsOne() {
        assertNotNull(GeofenceNotifier.notice(GeofenceEvent.Entered(geofence("a1", "Artwork", "artwork"))))
        assertNotNull(GeofenceNotifier.notice(GeofenceEvent.Entered(geofence("o1", "Shop", "other"))))
    }

    @Test
    fun aGeofenceWithoutANamePostsNothing() {
        assertNull(GeofenceNotifier.notice(GeofenceEvent.Entered(geofence("x", null, "gallery"))))
        assertNull(GeofenceNotifier.notice(GeofenceEvent.Exited(geofence("y", "  ", "gallery"), dwellTime = 0.0)))
    }

    @Test
    fun privacyZonesPostNothing() {
        val zone = ProximiioPrivacyZone(id = "z", name = "Office")
        assertNull(GeofenceNotifier.notice(GeofenceEvent.PrivacyZoneEntered(zone)))
        assertNull(GeofenceNotifier.notice(GeofenceEvent.PrivacyZoneExited(zone)))
    }
}
