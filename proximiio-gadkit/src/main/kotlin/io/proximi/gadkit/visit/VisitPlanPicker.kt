package io.proximi.gadkit.visit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.proximi.gadkit.R
import io.proximi.gadkit.map.GuidanceText
import io.proximi.gadkit.map.VenuePlace
import io.proximi.gadkit.designsystem.GadTextStyle
import io.proximi.gadkit.designsystem.Palette
import io.proximi.gadkit.ui.GroupedBackground
import io.proximi.gadkit.ui.ListSeparator
import io.proximi.gadkit.ui.SecondaryLabel
import io.proximi.gadkit.ui.SystemBody
import io.proximi.gadkit.ui.SystemSearchField
import io.proximi.gadkit.ui.SystemTitle
import io.proximi.gadkit.ui.listRowShape

/**
 * Picks the stops of a visit by hand, as iOS `VisitPlanPicker`: a search field and the places;
 * the tap order is the visit order. Stands in for the personalised journey from Nagarro's guide
 * until that integration exists. On iOS a sheet with a navigation bar ("Cancel", "Plan your
 * visit", "Start"); here a full-screen cover with the same bar. Drawn as the iOS 26 searchable
 * inset grouped list ([SystemSearchField], white section with 26 dp corners).
 */
@Composable
public fun VisitPlanPicker(places: List<VenuePlace>, onStart: (List<String>) -> Unit, onCancel: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var picked by rememberSaveable { mutableStateOf(listOf<String>()) }
    val matching = remember(query, places) { VenuePlace.matching(query, places) }
    Column(Modifier.fillMaxSize().background(GroupedBackground).safeDrawingPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            BarButton(stringResource(R.string.cancel), enabled = true, onClick = onCancel)
            Text(
                stringResource(R.string.plan_your_visit),
                Modifier.weight(1f),
                style = SystemTitle,
                color = Palette.ink,
                textAlign = TextAlign.Center,
            )
            BarButton(stringResource(R.string.start), enabled = picked.isNotEmpty(), bold = true) { onStart(picked) }
        }
        SystemSearchField(query, { query = it }, stringResource(R.string.search), Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 24.dp)) {
            itemsIndexed(matching, key = { _, place -> place.id }) { position, place ->
                val index = picked.indexOf(place.id)
                val shape = listRowShape(position, matching.size)
                Column(Modifier.fillMaxWidth().background(Palette.white, shape).clip(shape)) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.Checkbox) { picked = if (index >= 0) picked - place.id else picked + place.id }
                            .semantics { selected = index >= 0 }
                            .heightIn(min = 52.dp)
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(place.title, style = GadTextStyle.BodyMedium.textStyle, color = Palette.ink)
                            Text(GuidanceText.floorLabel(place.level), style = GadTextStyle.BodyExtraSmall.textStyle, color = SecondaryLabel)
                        }
                        if (index >= 0) {
                            Box(Modifier.size(28.dp).background(Palette.ink, CircleShape), contentAlignment = Alignment.Center) {
                                Text("${index + 1}", style = GadTextStyle.BodySmallBold.textStyle, color = Palette.white)
                            }
                        }
                    }
                    if (position != matching.lastIndex) HorizontalDivider(Modifier.padding(horizontal = 16.dp), thickness = 1.dp, color = ListSeparator)
                }
            }
        }
    }
}

@Composable
private fun BarButton(text: String, enabled: Boolean, bold: Boolean = false, onClick: () -> Unit) {
    Text(
        text,
        Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(12.dp),
        style = if (bold) SystemBody.copy(fontWeight = FontWeight.SemiBold) else SystemBody,
        color = Palette.ink.copy(alpha = if (enabled) 1f else Palette.DISABLED_OPACITY),
    )
}
