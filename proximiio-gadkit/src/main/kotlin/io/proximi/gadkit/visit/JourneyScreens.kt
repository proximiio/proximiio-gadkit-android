package io.proximi.gadkit.visit

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.proximi.gadkit.R
import io.proximi.gadkit.visit.VisitMeta
import io.proximi.gadkit.designsystem.AuroraBox
import io.proximi.gadkit.designsystem.AuroraVariant
import io.proximi.gadkit.designsystem.GadTextStyle
import io.proximi.gadkit.designsystem.GuggenheimSansDisplay
import io.proximi.gadkit.designsystem.Palette
import io.proximi.gadkit.designsystem.PrimaryButton
import io.proximi.gadkit.designsystem.TextActionButton
import io.proximi.gadkit.map.BottomGlassBar
import io.proximi.gadkit.map.GlassSquareButton
import io.proximi.gadkit.map.MapIcon
import io.proximi.gadkit.map.MuseumMapView
import io.proximi.gadkit.map.RoundGlassButton
import io.proximi.gadkit.ui.BackButton
import io.proximi.gadkit.ui.OverlaySheet
import io.proximi.gadkit.ui.asHeading
import io.proximi.gadkit.VenueService
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch

// Journey lifecycle screens from the Figma file, as iOS `JourneyScreens.swift`:
// Journey ready (02.10A, node 6902:55705), exit confirmation (03.10, node 6902:45589), Journey
// Complete (PM review 2026-10-12), and the skip, route change, off-route and EC04d prompts of
// `VisitView` and `LocationChoiceView`.
// Manage journey (02.11) is `hub/HubComponents.ManageJourneySheet`.

/**
 * "YOUR PERSONALISED JOURNEY · Light & Perception": title, summary, "Start the Journey" at the
 * museum, else "Go to Museum" with "It will be ready when you arrive at GAD", and "Back to Hub".
 */
@Composable
internal fun JourneyReadyScreen(meta: VisitMeta, isAtMuseum: Boolean, onStart: () -> Unit, onBack: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Image(painterResource(R.drawable.background_hub), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Column(Modifier.fillMaxSize().navigationBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.weight(1f))
            Column(Modifier.width(322.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(15.dp)) {
                val caption = if (meta.kind == JourneyKind.CURATED) R.string.curated_2 else R.string.your_personalised_journey
                Text(stringResource(caption), style = GadTextStyle.Caption.textStyle, color = Palette.ink, textAlign = TextAlign.Center)
                Text(
                    meta.title,
                    Modifier.asHeading(),
                    style = GadTextStyle.H1Bold.textStyle.copy(fontFamily = GuggenheimSansDisplay, fontWeight = FontWeight.SemiBold, fontSize = 32.sp),
                    color = Palette.ink,
                    textAlign = TextAlign.Center,
                )
                meta.summary?.let {
                    Text(it, style = GadTextStyle.BodySmallRegular.textStyle, color = Palette.ink, textAlign = TextAlign.Center)
                }
            }
            Spacer(Modifier.weight(1f))
            Column(
                Modifier.width(322.dp).padding(bottom = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(28.dp),
            ) {
                if (isAtMuseum) {
                    PrimaryButton(stringResource(R.string.start_the_journey), onStart)
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        PrimaryButton(stringResource(R.string.go_to_museum), onStart)
                        Text(
                            stringResource(R.string.it_will_be_ready_when_you_arrive),
                            style = GadTextStyle.BodyExtraSmall.textStyle,
                            color = Palette.ink,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                TextActionButton(stringResource(R.string.back_to_hub), onBack)
            }
        }
    }
}

/** "Leave the tour here" (03.10): progress is saved; the visitor can carry on. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ExitConfirmationSheet(visited: Int, total: Int, onLeave: () -> Unit, onCarryOn: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    OverlaySheet(onDismiss = onCarryOn, sheetState = sheetState) {
        SheetTitle(stringResource(R.string.leave_the_tour_here))
        SheetBody(stringResource(R.string.you_ve_visited_n_of_n_stops, visited, total), Modifier.padding(bottom = 20.dp))
        // The visit closes once the sheet has gone (iOS 0.11.0, Monday "UI gets stuck").
        PrimaryButton(stringResource(R.string.leave_the_tour), onClick = { scope.launch { sheetState.hide() }.invokeOnCompletion { onLeave() } })
        TextActionButton(stringResource(R.string.carry_on), onCarryOn)
    }
}

/**
 * Journey Complete (PM review 2026-10-12, `design/monday-2026-10-12/journey-complete.png`), as
 * iOS `JourneyCompleteView`: the journey ends here. The close button leaves the visit; the map
 * button opens the museum map. The subtitle is the design's text.
 */
@Composable
internal fun JourneyCompleteScreen(venue: VenueService, onClose: () -> Unit) {
    var isShowingMap by rememberSaveable { mutableStateOf(false) }
    BackHandler(onBack = onClose)
    AuroraBox(Modifier.fillMaxSize(), variant = AuroraVariant.Mint) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 30.dp, end = 30.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.journey_complete_3), Modifier.weight(1f).asHeading(), style = GadTextStyle.BodyBold.textStyle, color = Palette.ink)
                GlassSquareButton(onClick = onClose, contentDescription = stringResource(R.string.close)) {
                    MapIcon(R.drawable.ic_map_close, size = 16)
                }
            }
            Column(
                Modifier.align(Alignment.CenterHorizontally).width(322.dp).padding(top = 60.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Text(
                    stringResource(R.string.you_ve_walked_your_route),
                    Modifier.fillMaxWidth(),
                    style = GadTextStyle.CardTitle.textStyle,
                    color = Palette.ink,
                    textAlign = TextAlign.Center,
                )
                Text(
                    stringResource(R.string.sculptural_works_exploring_negative_space_and_mass_2),
                    Modifier.fillMaxWidth(),
                    style = GadTextStyle.BodyMedium.textStyle,
                    color = Palette.ink,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.weight(1f))
            BottomGlassBar(
                Modifier.padding(bottom = 4.dp),
                leading = {
                    RoundGlassButton({ isShowingMap = true }, stringResource(R.string.museum_map_2)) { MapIcon(R.drawable.ic_map, size = 22) }
                },
                trailing = {},
            )
        }
    }
    if (isShowingMap) {
        BackHandler { isShowingMap = false }
        Box(Modifier.fillMaxSize().background(Palette.white)) {
            MuseumMapView(venue, Modifier.fillMaxSize().statusBarsPadding())
            BackButton({ isShowingMap = false }, Modifier.statusBarsPadding().padding(16.dp))
        }
    }
}

/** "Skip this artwork?" bottom sheet. */
@Composable
internal fun SkipConfirmation(stop: String, onSkip: () -> Unit, onKeep: () -> Unit) {
    OverlaySheet(onDismiss = onKeep) {
        SheetTitle(stringResource(R.string.skip_this_artwork))
        Text(stop, style = GadTextStyle.LargeBodyRegular.textStyle, color = Palette.ink, textAlign = TextAlign.Center)
        SheetBody(stringResource(R.string.it_will_be_removed_from_your_route), Modifier.padding(bottom = 20.dp))
        PrimaryButton(stringResource(R.string.skip_artwork), onSkip)
        TextActionButton(stringResource(R.string.keep_it), onKeep)
    }
}

/** Soft prompt when the position is uncertain near the active stop (EC04d, node 6902:38362). */
@Composable
internal fun LowConfidencePrompt(stop: String, onConfirm: () -> Unit, onNotHere: () -> Unit) {
    OverlaySheet(onDismiss = onConfirm) {
        Text(stringResource(R.string.probably_at), style = GadTextStyle.Caption.textStyle, color = Palette.ink)
        SheetTitle(stop)
        SheetBody(stringResource(R.string.your_position_is_uncertain_here), Modifier.padding(bottom = 20.dp))
        PrimaryButton(stringResource(R.string.that_s_right), onConfirm)
        TextActionButton(stringResource(R.string.not_here), onNotHere)
    }
}

@Composable
private fun SheetTitle(text: String) {
    Text(text, Modifier.asHeading(), style = GadTextStyle.H1Bold.textStyle, color = Palette.ink, textAlign = TextAlign.Center)
}

@Composable
private fun SheetBody(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = GadTextStyle.BodySmallRegular.textStyle, color = Palette.ink, textAlign = TextAlign.Center)
}
