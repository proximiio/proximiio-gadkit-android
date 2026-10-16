package io.proximi.gadkit

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import io.proximi.gadkit.map.VenuePlace
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Detects that the visitor is at the museum, as iOS `ArrivalDetector` (Figma "Stage 02 – Museum
 * Arrival", 02A.01: "When the location has gathered that the user is at the museum, on opening the
 * app it should acknowledge it").
 *
 * Two sources: a wristband or playback position (the visitor is inside), or one location fix of the
 * phone within [RADIUS_METERS] of a place of the venue. The phone fix needs the location permission,
 * which `ProximiMapHost` asks for. No geofencing of the phone: it needs background location, which
 * the library does not request.
 */
internal class ArrivalDetector(context: Context) {
    private val context = context.applicationContext
    private val mutableIsAtMuseum = MutableStateFlow(false)
    private val mutableIsDenied = MutableStateFlow(false)
    private var places: List<Coordinate> = emptyList()

    /** Called once, when [isAtMuseum] becomes `true`. */
    var onArrival: (() -> Unit)? = null

    val isAtMuseum: StateFlow<Boolean> = mutableIsAtMuseum.asStateFlow()

    /**
     * `true` when the visitor answered the location dialog without allowing location, or turned it
     * off in Settings afterwards. Android cannot tell a refusal from a permission never asked for, so
     * it stays `false` until the dialog of `ProximiMapHost` has been answered.
     */
    val isDenied: StateFlow<Boolean> = mutableIsDenied.asStateFlow()

    private var hasAnswer = false
    private var wasAllowed = isAllowed()

    /** Requests one phone fix and compares it with the places. */
    fun check(places: List<VenuePlace>) {
        this.places = places.map { it.coordinate }
        if (this.places.isNotEmpty()) requestFix()
    }

    /** A position from the wristband or playback means the visitor is inside. */
    fun positionReceived() {
        arrive()
    }

    /**
     * Reads the permission again: after the location dialog ([answered] `true`) and when the host
     * screen resumes, for a change in Settings. Android has no change callback. Requests a fix when
     * location became allowed. Returns `true` on that change.
     */
    fun refreshAuthorization(answered: Boolean = false): Boolean {
        if (answered) hasAnswer = true
        val allowed = isAllowed()
        val becameAllowed = allowed && !wasAllowed
        wasAllowed = allowed
        mutableIsDenied.value = hasAnswer && !allowed
        if (becameAllowed && places.isNotEmpty()) requestFix()
        return becameAllowed
    }

    private fun arrive() {
        if (mutableIsAtMuseum.value) return
        mutableIsAtMuseum.value = true
        onArrival?.invoke()
    }

    private fun isAllowed(): Boolean =
        listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            .any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    /** One fix at about 100 m accuracy (iOS `kCLLocationAccuracyHundredMeters`). */
    @SuppressLint("MissingPermission")
    private fun requestFix() {
        if (!isAllowed()) return
        val manager = context.getSystemService(LocationManager::class.java) ?: return
        val provider = providers().firstOrNull { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) } ?: return
        runCatching {
            LocationManagerCompat.getCurrentLocation(
                manager,
                provider,
                null as android.os.CancellationSignal?,
                ContextCompat.getMainExecutor(context),
            ) { location ->
                if (location != null && isNear(Coordinate(location.latitude, location.longitude), places)) arrive()
            }
        }
    }

    /** Network and fused work with approximate location; GPS needs precise location. */
    private fun providers(): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
        add(LocationManager.NETWORK_PROVIDER)
        val precise = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (precise) add(LocationManager.GPS_PROVIDER)
    }

    companion object {
        /** Distance from a place of the venue that counts as "at the museum", in metres. */
        const val RADIUS_METERS = 400.0

        /**
         * `true` when [location] is within [radius] metres of one of [places]. The GAD organisation
         * has two venues 11 km apart, so the mean of all places is near neither. Pure; covered by tests.
         */
        fun isNear(location: Coordinate, places: List<Coordinate>, radius: Double = RADIUS_METERS): Boolean =
            places.any { location.distanceTo(it) <= radius }
    }
}
