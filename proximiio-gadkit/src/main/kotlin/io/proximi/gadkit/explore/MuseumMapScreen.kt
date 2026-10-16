package io.proximi.gadkit.explore

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.proximi.gadkit.R
import io.proximi.gadkit.designsystem.AuroraBox
import io.proximi.gadkit.designsystem.GadTextStyle
import io.proximi.gadkit.designsystem.Palette
import io.proximi.gadkit.map.MuseumMapView
import io.proximi.gadkit.VenueService
import io.proximi.gadkit.ui.BackButton
import io.proximi.gadkit.ui.asHeading

/**
 * The museum map as a full screen, as iOS `MuseumMapScreen`: header with back button and title,
 * then the museum map with floors, amenities, the visitor's position, a route to a tapped place
 * and the gallery legend.
 */
@Composable
internal fun MuseumMapScreen(venue: VenueService, onBack: () -> Unit, modifier: Modifier = Modifier) {
    AuroraBox(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.museum_map), Modifier.asHeading(), style = GadTextStyle.LargeBodyBold.textStyle, color = Palette.ink)
                BackButton(onBack, Modifier.align(Alignment.CenterStart))
            }
            Box(Modifier.fillMaxWidth().weight(1f)) {
                MuseumMapView(venue, showsLegend = true)
            }
        }
    }
}
