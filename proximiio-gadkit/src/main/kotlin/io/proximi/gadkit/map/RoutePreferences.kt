package io.proximi.gadkit.map

import android.content.Context
import androidx.core.content.edit
import io.proximi.sdk.wayfinding.RouteOptions

/**
 * The visitor's route preference, as iOS `RoutePreferences`. A step-free route uses the SDK's
 * wheelchair profile: no stairs, no escalators, no narrow paths. The museum map and the journey
 * read it when they compute a route. No screen sets it since My Profile is removed (PM review
 * 2026-10-12), so routes are Standard unless a stored choice remains.
 */
internal object RoutePreferences {
    private const val FILE = "routes"
    private const val KEY = "stepFreeRoutes"

    fun isStepFree(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun setStepFree(context: Context, isStepFree: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit { putBoolean(KEY, isStepFree) }
    }

    /** The SDK route options for the preference. */
    fun options(context: Context): RouteOptions = options(isStepFree(context))

    fun options(isStepFree: Boolean): RouteOptions =
        RouteOptions(profile = if (isStepFree) RouteOptions.Profile.WHEELCHAIR else RouteOptions.Profile.PEDESTRIAN)
}
