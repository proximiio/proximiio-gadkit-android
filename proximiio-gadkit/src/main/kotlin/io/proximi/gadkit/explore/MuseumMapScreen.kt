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
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.proximi.gadkit.R
import io.proximi.gadkit.designsystem.AuroraBox
import io.proximi.gadkit.designsystem.GadTextStyle
import io.proximi.gadkit.designsystem.Palette
import io.proximi.gadkit.VenueService
import io.proximi.gadkit.ui.BackButton
import io.proximi.gadkit.ui.asHeading

/**
 * The Explore Museum map as a full screen, as iOS `MuseumMapScreen`: the back button and "Explore
 * Museum", then [ExploreMuseumView]. Opened with `ProximiMap.open(Screen.MUSEUM_MAP)`, in the Proximi
 * GAD app from the Hub's journey card away from the museum.
 */
@Composable
internal fun MuseumMapScreen(venue: VenueService, onBack: () -> Unit, modifier: Modifier = Modifier) {
    AuroraBox(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                // "Explore" bold and "Museum" regular.
                val title = buildAnnotatedString {
                    withStyle(GadTextStyle.LargeBodyBold.textStyle.toSpanStyle()) { append(stringResource(R.string.explore)) }
                    append(" ")
                    withStyle(GadTextStyle.LargeBodyRegular.textStyle.toSpanStyle()) { append(stringResource(R.string.museum)) }
                }
                Text(title, Modifier.asHeading(), style = GadTextStyle.LargeBodyBold.textStyle, color = Palette.ink)
                BackButton(onBack, Modifier.align(Alignment.CenterStart))
            }
            Box(Modifier.fillMaxWidth().weight(1f)) {
                ExploreMuseumView(venue)
            }
        }
    }
}
