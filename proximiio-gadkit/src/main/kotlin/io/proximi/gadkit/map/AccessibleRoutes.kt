package io.proximi.gadkit.map

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The state of the accessible routes button, one value for the museum map and the journey map
 * and for later launches, as the iOS `@AppStorage` of `RouteAccessibility.accessibleRoutesKey`.
 * A change shows on the other map at once.
 */
internal object AccessibleRoutes {
    private var state: MutableStateFlow<Boolean>? = null

    /** `true` while the button is on. The first call reads the preferences. */
    fun isOn(context: Context): StateFlow<Boolean> = flow(context).asStateFlow()

    fun set(context: Context, isOn: Boolean) {
        flow(context).value = isOn
        preferences(context).edit { putBoolean(RouteAccessibility.ACCESSIBLE_ROUTES_KEY, isOn) }
    }

    @Synchronized
    private fun flow(context: Context): MutableStateFlow<Boolean> = state ?: MutableStateFlow(
        preferences(context).getBoolean(RouteAccessibility.ACCESSIBLE_ROUTES_KEY, false),
    ).also { state = it }

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(RouteAccessibility.PREFERENCES_FILE, Context.MODE_PRIVATE)
}
