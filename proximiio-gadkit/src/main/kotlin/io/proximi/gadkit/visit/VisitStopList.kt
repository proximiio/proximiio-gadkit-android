package io.proximi.gadkit.visit

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.proximi.gadkit.R
import io.proximi.gadkit.map.Gallery
import io.proximi.gadkit.visit.JourneyStop
import io.proximi.gadkit.visit.StopState
import io.proximi.gadkit.visit.VisitPlan
import io.proximi.gadkit.designsystem.Emphasis
import io.proximi.gadkit.designsystem.GadTextStyle
import io.proximi.gadkit.designsystem.GlassStrength
import io.proximi.gadkit.designsystem.Palette
import io.proximi.gadkit.designsystem.SmallButton
import io.proximi.gadkit.designsystem.StopNumberBadge
import io.proximi.gadkit.designsystem.glassSurface
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * The journey as a list (Figma "Personalized Journey list"), as iOS `VisitStopList`. Stops are
 * numbered in visit order and grouped under the gallery they lie in; visited stops are dimmed;
 * swiping towards the start edge offers Skip; a skipped stop is hidden and the list renumbers.
 * "Undo skip" restores the last skipped stop.
 */
@Composable
internal fun VisitStopList(model: VisitViewModel, modifier: Modifier = Modifier) {
    val journey = model.progress.journey
    val lastSkipped by model.controller.lastSkippedStopId.collectAsStateWithLifecycle()
    val numbers = VisitPlan.numbers(journey)
    val galleries = model.galleries
    val groups = VisitPlan.galleryGroups(VisitPlan.artworks(journey).filter { it.state != StopState.SKIPPED }) { stop ->
        Gallery.containing(stop.coordinate, stop.level, galleries)?.name
    }
    // The row whose skip action is revealed. One row at a time.
    var revealedStopId by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier,
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 90.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        groups.forEach { group ->
            group.gallery?.let { name ->
                item(key = "gallery-${group.id}") {
                    Text(
                        name,
                        Modifier.fillMaxWidth().padding(top = 10.dp).semantics { heading() },
                        style = GadTextStyle.LargeBodyBold.textStyle,
                        color = Palette.ink,
                    )
                }
            }
            items(group.stops, key = { "${group.id}-${it.id}" }) { stop ->
                SwipeToSkipRow(
                    isRevealed = revealedStopId == stop.id,
                    onRevealChange = { revealedStopId = if (it) stop.id else null },
                    isEnabled = stop.state == StopState.PENDING || stop.state == StopState.ACTIVE,
                    onSkip = {
                        revealedStopId = null
                        model.requestSkip(stop)
                    },
                ) {
                    StopRow(stop, numbers[stop.id] ?: 0, isRevealed = revealedStopId == stop.id)
                }
            }
        }
        if (lastSkipped != null) {
            item(key = "undo") { SmallButton(stringResource(R.string.undo_skip), model::undoSkip, Modifier.padding(top = 6.dp)) }
        }
        item(key = "hint") {
            Text(
                stringResource(R.string.swipe_left_to_skip_an_artwork),
                Modifier.padding(top = 10.dp),
                style = GadTextStyle.BodyExtraSmall.textStyle,
                color = Palette.ink,
            )
        }
    }
}

/** A stop as a frosted card (Figma journey list): number badge, title and floor. */
@Composable
private fun StopRow(stop: JourneyStop, number: Int, isRevealed: Boolean) {
    val isVisited = stop.state == StopState.DONE
    val isActive = stop.state == StopState.ACTIVE || stop.state == StopState.REACHED
    val visited = stringResource(R.string.visited)
    Row(
        Modifier
            .fillMaxWidth()
            .glassSurface(RoundedCornerShape(12.dp), GlassStrength.Frosted)
            .semantics(mergeDescendants = true) {
                selected = isActive
                if (isVisited) stateDescription = visited
            }
            .padding(horizontal = 16.dp, vertical = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StopNumberBadge(
            number = number,
            emphasis = when {
                isRevealed || isActive -> Emphasis.Filled
                isVisited -> Emphasis.Visited
                else -> Emphasis.Normal
            },
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(stop.title, style = GadTextStyle.BodyMedium.textStyle, color = Palette.ink)
            stop.level?.let {
                Text(stringResource(R.string.level_n, it.roundToInt()), style = GadTextStyle.BodySmallRegular.textStyle, color = Palette.ink)
            }
        }
    }
}

/**
 * Swipe towards the start edge to reveal the skip action (Figma journey list, node 11820:71364):
 * the row slides, its badge turns red, and a glass panel with the skip glyph appears at the end
 * edge. Left in left-to-right layouts, right in right-to-left ones. TalkBack offers "Skip" as an action.
 */
@Composable
private fun SwipeToSkipRow(
    isRevealed: Boolean,
    onRevealChange: (Boolean) -> Unit,
    isEnabled: Boolean,
    onSkip: () -> Unit,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val revealWidth = with(density) { REVEAL_WIDTH.toPx() }
    val scope = rememberCoroutineScope()
    // Logical offset: negative moves the row towards the start edge.
    val offset = remember { Animatable(0f) }
    // The revealed skip action closes when its stop is no longer pending or active, for example once reached.
    val showsAction = isRevealed && isEnabled
    LaunchedEffect(showsAction) {
        if (isRevealed && !isEnabled) onRevealChange(false)
        offset.animateTo(if (showsAction) -revealWidth else 0f, tween(250))
    }
    val skipLabel = stringResource(R.string.skip)

    Box(
        Modifier.fillMaxWidth().semantics {
            if (isEnabled) customActions = listOf(CustomAccessibilityAction(skipLabel) { onSkip(); true })
        },
        contentAlignment = Alignment.CenterEnd,
    ) {
        if (isEnabled) {
            val visibility = (-offset.value / revealWidth).coerceIn(0f, 1f)
            Box(
                Modifier
                    .matchParentSize()
                    .alpha(visibility),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Box(
                    Modifier
                        .width(REVEAL_WIDTH + 24.dp)
                        .fillMaxHeight()
                        .glassSurface(RoundedCornerShape(12.dp), GlassStrength.Frosted)
                        .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onSkip)
                        .padding(start = 24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    // The glyph fills half of its 24-unit viewport: 26 dp draws it about 13 dp high, as
                    // the SF Symbol forward.end.fill at 17 pt on iOS (PM item 13264978932).
                    Icon(painterResource(R.drawable.ic_skip), null, Modifier.size(26.dp), tint = Palette.ink)
                }
            }
        }
        Box(
            Modifier
                .offset { IntOffset(rubberBand(offset.value, revealWidth).roundToInt(), 0) }
                .then(
                    if (isEnabled) {
                        Modifier.draggable(
                            state = rememberDraggableState { delta -> scope.launch { offset.snapTo(offset.value + delta) } },
                            orientation = Orientation.Horizontal,
                            reverseDirection = isRtl,
                            onDragStopped = { velocity ->
                                val logicalVelocity = if (isRtl) -velocity else velocity
                                val projected = offset.value + logicalVelocity * 0.1f
                                val reveal = projected < -revealWidth / 2
                                if (reveal == isRevealed) offset.animateTo(if (reveal) -revealWidth else 0f, tween(250))
                                onRevealChange(reveal)
                            },
                        )
                    } else {
                        Modifier
                    },
                )
                .clickable(remember { MutableInteractionSource() }, indication = null, enabled = isRevealed) { onRevealChange(false) },
        ) { content() }
    }
}

/** Damped drag past the open position and past the closed position. */
private fun rubberBand(raw: Float, revealWidth: Float): Float = when {
    raw > 0 -> raw * 0.2f
    raw < -revealWidth -> -revealWidth + (raw + revealWidth) * 0.3f
    else -> raw
}

private val REVEAL_WIDTH = 72.dp
