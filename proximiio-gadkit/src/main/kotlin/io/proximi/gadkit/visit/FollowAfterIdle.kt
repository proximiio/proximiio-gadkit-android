package io.proximi.gadkit.visit

import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * The journey map follows the visitor again when a gesture left the camera free and the camera has
 * not moved for `GadSettings.followsAgainAfterSeconds` (owner, 2026-11-27), as iOS `FollowAfterIdle`
 * (1.75.0). The camera is read at an interval: a pan, a zoom or a rotation changes it, and the time
 * counts from the last change.
 */
internal class FollowAfterIdle {
    /** The camera at one reading, rounded so that a still camera compares equal. */
    data class Camera(val latitude: Long, val longitude: Long, val zoom: Int, val direction: Int) {
        companion object {
            /** About 1 cm, 0.01 of a zoom level and 0.5°. */
            fun of(latitude: Double, longitude: Double, zoom: Double, direction: Double): Camera =
                Camera((latitude * 1e7).roundToLong(), (longitude * 1e7).roundToLong(), (zoom * 100).roundToInt(), (direction * 2).roundToInt())
        }
    }

    private var camera: Camera? = null

    /** When the free camera last moved, or was first read free. */
    private var stillSince: Double? = null

    /**
     * One reading at [now] (seconds of a monotonic clock). `true` when the map takes the follow
     * again: the camera is free, it has been still for [seconds], and [isAllowed]. [seconds] of 0 or
     * less never follows. While the camera follows, or [isAllowed] is `false`, the time starts again,
     * so the follow comes [seconds] after the condition ends. Pure; covered by tests.
     */
    fun reading(camera: Camera?, isFree: Boolean, isAllowed: Boolean, seconds: Double, now: Double): Boolean {
        if (seconds <= 0 || !isFree || !isAllowed || camera == null) {
            this.camera = null
            stillSince = null
            return false
        }
        val since = stillSince
        if (camera != this.camera || since == null) {
            this.camera = camera
            stillSince = now
            return false
        }
        if (now - since < seconds) return false
        this.camera = null
        stillSince = null
        return true
    }

    companion object {
        /** Milliseconds between two readings of the camera. */
        const val INTERVAL_MILLIS: Long = 500
    }
}
