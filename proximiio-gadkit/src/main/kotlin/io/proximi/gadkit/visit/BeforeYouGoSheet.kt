package io.proximi.gadkit.visit

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.proximi.gadkit.R
import io.proximi.gadkit.map.GuidanceText
import io.proximi.gadkit.map.VenuePlace
import io.proximi.gadkit.visit.BeforeYouGo
import io.proximi.gadkit.designsystem.AuroraBox
import io.proximi.gadkit.designsystem.GadTextStyle
import io.proximi.gadkit.designsystem.Palette
import io.proximi.gadkit.designsystem.glassSurface
import io.proximi.gadkit.ui.asHeading

/**
 * End of the journey (Figma "Stage 03", 04.03 "End of journey — shop/café/book tour"), as iOS
 * `BeforeYouGoSheet`: the places to visit on the way out. A place opens the museum map with a
 * route to it. "Watch your Visit Recap" belongs to Nagarro's guide and is not shown.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BeforeYouGoSheet(places: List<VenuePlace>, onPick: (VenuePlace) -> Unit, onDismiss: () -> Unit) {
    val suggestions = remember(places) { BeforeYouGo.suggestions(places) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
        shape = RoundedCornerShape(topStart = 50.dp, topEnd = 50.dp),
        containerColor = Color.Transparent,
        contentColor = Palette.ink,
        dragHandle = null,
        // The aurora runs under the navigation bar; the content pads itself.
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
    ) {
        AuroraBox {
            Column(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(30.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(stringResource(R.string.before_you_go), style = GadTextStyle.Caption.textStyle, color = Palette.ink)
                Text(stringResource(R.string.take_your_time_leaving_here_s_what), Modifier.asHeading(), style = GadTextStyle.H1Bold.textStyle, color = Palette.ink)
                if (suggestions.isEmpty()) {
                    Text(stringResource(R.string.thank_you_for_visiting_guggenheim_abu_dhabi), style = GadTextStyle.BodySmallRegular.textStyle, color = Palette.ink)
                }
                suggestions.forEach { place ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .glassSurface(RoundedCornerShape(12.dp))
                            .clickable(role = Role.Button) { onPick(place) }
                            .padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(place.title, style = GadTextStyle.BodyBold.textStyle, color = Palette.ink)
                            Text(
                                place.details ?: GuidanceText.floorLabel(place.level),
                                style = GadTextStyle.BodyExtraSmall.textStyle,
                                color = Palette.ink.copy(alpha = 0.6f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Icon(painterResource(R.drawable.ic_chevron_forward), null, Modifier.size(12.dp), tint = Palette.ink)
                    }
                }
            }
        }
    }
}
