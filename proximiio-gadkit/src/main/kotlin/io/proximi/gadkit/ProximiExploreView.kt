package io.proximi.gadkit

import android.content.Context
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import io.proximi.gadkit.designsystem.Palette
import io.proximi.gadkit.explore.ExploreMuseumView
import io.proximi.sdk.Proximiio

/**
 * The Explore Museum map as a UI component on the host app's SDK, as iOS `ProximiExploreView`
 * (Nagarro, 2026-10-27 and 2026-10-28): "Level N" and the floor changer at the top, the map, the
 * legend button and the Spaces, Highlights and Amenities tabs with their lists and cards. No title, no
 * Back and no background: the host app draws them. `ProximiMap.open(Screen.MUSEUM_MAP)` shows the
 * same map with the library's title and Back.
 *
 * The visitor need not be at the museum: the map draws no position and no route. The view does not
 * start, stop or configure the SDK and reports no event.
 *
 * @param sdk the host app's SDK, started. The view reads the venue data the SDK holds and follows its
 *   `venueDataChanges()`.
 * @param settings the zoom range and the map's loading timeout. The view reads them when it opens.
 */
@Composable
public fun ProximiExploreView(sdk: Proximiio, modifier: Modifier = Modifier, settings: GadSettings = GadSettings.STANDARD) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val model: HostedExploreModel = viewModel(key = HostedExploreModel.KEY)
    LaunchedEffect(model) { model.start(context.applicationContext, sdk, settings) }
    DisposableEffect(model) {
        // A recreated activity shows the same map again.
        onDispose { if (activity?.isChangingConfigurations != true) model.stop() }
    }
    Box(modifier) {
        val venue = model.venue
        if (venue != null) {
            ExploreMuseumView(venue)
        } else {
            CircularProgressIndicator(Modifier.align(Alignment.Center), color = Palette.ink)
        }
    }
}

/**
 * The venue of a [ProximiExploreView] on the host app's SDK. A view model, so it survives a recreated
 * activity. Main thread only.
 */
internal class HostedExploreModel : ViewModel() {
    var venue by mutableStateOf<VenueService?>(null)
        private set

    private var isStarting = false
    private var isStopped = false

    /** Loads the venue. After [stop], uses [settings] and follows the venue data changes again if the SDK stopped in between. */
    suspend fun start(context: Context, sdk: Proximiio, settings: GadSettings) {
        venue?.let { venue ->
            if (isStopped) {
                isStopped = false
                // A new view reads its settings when it opens.
                venue.settings = settings
                venue.data.start()
            }
            return
        }
        if (isStarting) return
        isStarting = true
        venue = VenueService.hosted(context, sdk, settings)
        FieldLog.log("hosted explore map: ready")
    }

    /**
     * The view left the screen. The venue data stays in memory for the next view on the same SDK and
     * follows `venueDataChanges()` (iOS 1.23.0).
     */
    fun stop() {
        isStopped = true
    }

    companion object {
        const val KEY = "io.proximi.gadkit.hostedExplore"
    }
}
