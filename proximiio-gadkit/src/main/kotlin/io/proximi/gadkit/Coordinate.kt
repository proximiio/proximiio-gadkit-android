package io.proximi.gadkit

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

/** A WGS84 position in degrees. Mirrors `ProximiioCoordinate` on iOS. */
public data class Coordinate(val latitude: Double, val longitude: Double) {
    /**
     * Great-circle distance in metres (haversine, mean Earth radius). iOS uses
     * `CLLocation.distance(from:)`; the two agree to well under 1 % at museum scale.
     */
    public fun distanceTo(other: Coordinate): Double {
        val dLat = Math.toRadians(other.latitude - latitude)
        val dLon = Math.toRadians(other.longitude - longitude)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(latitude)) * cos(Math.toRadians(other.latitude)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_METERS * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    /** Equirectangular squared distance in degrees², for ranking only (iOS `TrackingMonitor.squaredDistance`). */
    public fun squaredDegrees(other: Coordinate): Double {
        val dLat = latitude - other.latitude
        val dLon = (longitude - other.longitude) * cos(latitude * Math.PI / 180)
        return dLat * dLat + dLon * dLon
    }

    private companion object {
        const val EARTH_RADIUS_METERS = 6_371_008.8
    }
}

/** Swift `Int(x.rounded())`: half away from zero. Kotlin's `round` is half to even. */
public fun Double.roundedInt(): Int = (sign * floor(abs(this) + 0.5)).toInt()

/** True when both levels round to the same floor. */
public fun sameFloor(a: Double, b: Double): Boolean = a.roundedInt() == b.roundedInt()
