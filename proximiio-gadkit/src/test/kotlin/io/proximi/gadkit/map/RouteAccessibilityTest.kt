package io.proximi.gadkit.map

import io.proximi.gadkit.DebugLaunch
import io.proximi.sdk.wayfinding.RouteOptions.Avoidance
import io.proximi.sdk.wayfinding.RouteOptions.Profile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Port of ProximiioGADKit `RouteAccessibilityTests`. */
class RouteAccessibilityTest {
    @Test
    fun noNeedAvoidsNothing() {
        val options = RouteAccessibility.NONE.routeOptions
        assertEquals(Profile.PEDESTRIAN, options.profile)
        assertTrue(options.effectiveAvoidances.isEmpty())
    }

    @Test
    fun eachNeedIsOneAvoidance() {
        assertEquals(setOf(Avoidance.STAIRCASES), RouteAccessibility(avoidsStairs = true).routeOptions.effectiveAvoidances)
        assertEquals(setOf(Avoidance.ESCALATORS), RouteAccessibility(avoidsEscalators = true).routeOptions.effectiveAvoidances)
        assertEquals(setOf(Avoidance.REVOLVING_DOORS), RouteAccessibility(avoidsRevolvingDoors = true).routeOptions.effectiveAvoidances)
        assertEquals(setOf(Avoidance.NARROW_PATHS), RouteAccessibility(avoidsInaccessiblePaths = true).routeOptions.effectiveAvoidances)
    }

    @Test
    fun allNeedsTogether() {
        val all = RouteAccessibility(avoidsStairs = true, avoidsEscalators = true, avoidsRevolvingDoors = true, avoidsInaccessiblePaths = true)
        assertEquals(
            setOf(Avoidance.STAIRCASES, Avoidance.ESCALATORS, Avoidance.REVOLVING_DOORS, Avoidance.NARROW_PATHS),
            all.routeOptions.effectiveAvoidances,
        )
    }

    @Test
    fun launchArgumentSetsTheNeeds() {
        assertEquals(RouteAccessibility(avoidsStairs = true, avoidsRevolvingDoors = true), DebugLaunch.routeAvoid("stairs, revolvingDoors"))
        assertEquals(RouteAccessibility.NONE, DebugLaunch.routeAvoid(null))
    }

    @Test
    fun theAccessibleRoutesButtonAvoidsAllFour() {
        val host = RouteAccessibility(avoidsStairs = true)
        assertEquals(host, RouteAccessibility.effective(host, accessibleRoutes = false))
        assertEquals(
            setOf(Avoidance.STAIRCASES, Avoidance.ESCALATORS, Avoidance.REVOLVING_DOORS, Avoidance.NARROW_PATHS),
            RouteAccessibility.effective(host, accessibleRoutes = true).routeOptions.effectiveAvoidances,
        )
    }
}
