package io.proximi.gadkit.map

import io.proximi.sdk.wayfinding.RouteOptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Port of guggenheim-ios `RoutePreferencesTests`. Storage is `SharedPreferences`; the mapping is pure. */
class RoutePreferencesTest {
    @Test
    fun stepFreeUsesTheWheelchairProfile() {
        assertEquals(RouteOptions.Profile.PEDESTRIAN, RoutePreferences.options(isStepFree = false).profile)
        val stepFree = RoutePreferences.options(isStepFree = true)
        assertEquals(RouteOptions.Profile.WHEELCHAIR, stepFree.profile)
        assertTrue(RouteOptions.Avoidance.STAIRCASES in stepFree.profile.avoidances)
    }
}
