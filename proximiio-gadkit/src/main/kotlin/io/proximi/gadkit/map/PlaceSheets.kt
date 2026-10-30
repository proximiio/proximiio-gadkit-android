package io.proximi.gadkit.map

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import io.proximi.gadkit.R
import io.proximi.gadkit.map.GuidanceText
import io.proximi.gadkit.map.VenuePlace
import io.proximi.gadkit.designsystem.GadTextStyle
import io.proximi.gadkit.designsystem.Palette
import io.proximi.gadkit.designsystem.PrimaryButton
import io.proximi.gadkit.ui.GroupedBackground
import io.proximi.gadkit.ui.ListSeparator
import io.proximi.gadkit.ui.SecondaryLabel
import io.proximi.gadkit.ui.SystemSearchField
import io.proximi.gadkit.ui.SystemTitle
import io.proximi.gadkit.ui.asHeading
import io.proximi.gadkit.ui.listRowShape
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL
import kotlin.math.roundToInt

/**
 * Place search on the map (Figma "MAP Functionality Approach 02", "Search"), as iOS
 * `PlaceSearchSheet`: a search field, the amenity shortcuts and the matching places with their floor.
 * The look follows the iOS 26 sheet with a searchable inset grouped list: grey background, the
 * system font in the title and the field, a white capsule field, and white sections with 26 dp
 * corners.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlaceSearchSheet(
    places: List<VenuePlace>,
    /** The amenity shortcuts, in order ([AmenityListing.searchShortcuts]). */
    shortcuts: List<AmenityShortcut>,
    onPick: (VenuePlace) -> Unit,
    onPickAmenity: (AmenityShortcut) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 40.dp, topEnd = 40.dp),
        containerColor = GroupedBackground,
        contentColor = Palette.ink,
        dragHandle = null,
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight().imePadding()) {
            Text(
                stringResource(R.string.search),
                Modifier.align(Alignment.CenterHorizontally).padding(top = 28.dp, bottom = 23.dp),
                style = SystemTitle,
            )
            SystemSearchField(query, { query = it }, stringResource(R.string.search_the_museum), Modifier.padding(horizontal = 16.dp))
            val amenities = shortcuts
            val matches = VenuePlace.matching(query, places)
            val showsAmenities = query.isEmpty() && amenities.isNotEmpty()
            LazyColumn(
                Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 50.dp, bottom = 24.dp),
            ) {
                if (showsAmenities) {
                    item(key = "amenities") {
                        LazyRow(
                            Modifier.fillMaxWidth().padding(bottom = 35.dp).background(Palette.white, listRowShape(0, 1)).clip(listRowShape(0, 1)),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(amenities, key = { it.id }) { shortcut ->
                                Row(
                                    Modifier
                                        .heightIn(min = 36.dp)
                                        .border(1.dp, Palette.inputStroke, RoundedCornerShape(50))
                                        .clip(RoundedCornerShape(50))
                                        .clickable { onPickAmenity(shortcut) }
                                        .padding(horizontal = 12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    AmenityShortcutIcon(shortcut)
                                    Text(shortcut.title(), style = GadTextStyle.BodySmallRegular.textStyle, maxLines = 1, softWrap = false)
                                }
                            }
                        }
                    }
                }
                itemsIndexed(matches, key = { _, place -> place.id }) { index, place ->
                    val shape = listRowShape(index, matches.size)
                    Column(Modifier.fillMaxWidth().background(Palette.white, shape).clip(shape).clickable { onPick(place) }) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 52.dp)
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(place.title, Modifier.weight(1f), style = GadTextStyle.BodyMedium.textStyle)
                            Text(
                                GuidanceText.floorLabel(place.level),
                                style = GadTextStyle.BodyExtraSmall.textStyle,
                                color = SecondaryLabel,
                            )
                        }
                        if (index != matches.lastIndex) HorizontalDivider(Modifier.padding(horizontal = 16.dp), thickness = 1.dp, color = ListSeparator)
                    }
                }
            }
        }
    }
}

/**
 * Place detail (Figma "Explore Museum" pin detail, node 6902:59556), as iOS `PlaceDetailSheet`:
 * image 16:9, title, level, description and "Guide Me".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlaceDetailSheet(place: VenuePlace, onGuide: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        // The iOS system sheet: 40 dp corners, no grabber.
        shape = RoundedCornerShape(topStart = 40.dp, topEnd = 40.dp),
        containerColor = Palette.white,
        contentColor = Palette.ink,
        dragHandle = null,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(30.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            place.imageUrl?.let { url ->
                val image by produceState<Bitmap?>(null, url) { value = loadImage(url) }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Palette.inputStroke)
                        .clearAndSetSemantics {},
                ) {
                    image?.let {
                        Image(it.asImageBitmap(), contentDescription = null, Modifier.fillMaxWidth().aspectRatio(16f / 9f), contentScale = ContentScale.Crop)
                    }
                }
                Box(Modifier.padding(bottom = 10.dp))
            }
            Text(place.title, Modifier.asHeading(), style = GadTextStyle.H1Bold.textStyle)
            Text(
                stringResource(R.string.level_n, place.level.roundToInt()).uppercase(),
                style = GadTextStyle.Caption.textStyle,
            )
            place.details?.let {
                Text(it, Modifier.padding(top = 10.dp), style = GadTextStyle.BodySmallRegular.textStyle)
            }
            PrimaryButton(stringResource(R.string.guide_me), onGuide, Modifier.padding(top = 30.dp))
        }
    }
}

/** The image at [url], or `null` when it cannot be read. */
private suspend fun loadImage(url: String): Bitmap? = withContext(Dispatchers.IO) {
    try {
        URL(url).openStream().use(BitmapFactory::decodeStream)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
}
