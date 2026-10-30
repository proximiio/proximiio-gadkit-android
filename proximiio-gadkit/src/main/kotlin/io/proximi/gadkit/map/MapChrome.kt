package io.proximi.gadkit.map

import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import kotlin.math.abs
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Paint
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import kotlin.math.roundToInt
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.proximi.gadkit.R
import io.proximi.gadkit.UiText
import io.proximi.gadkit.map.AmenityKind
import io.proximi.gadkit.designsystem.GadTextStyle
import io.proximi.gadkit.designsystem.GlassStrength
import io.proximi.gadkit.designsystem.Palette
import io.proximi.gadkit.designsystem.glassSurface
import io.proximi.gadkit.designsystem.GlassButtonBox
import androidx.compose.material3.ripple
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.proximi.map.live.ProximiioMapSession
import kotlin.time.Duration
import kotlinx.coroutines.delay

// The map screen controls of the design ("MAP Functionality Approach 02", nodes 10889:108085 and
// 10889:110275), as iOS `MapChrome.swift`.

private val ControlShape = RoundedCornerShape(12.dp)

/** Square glass button, 50 × 50, with an icon or a short label ("L1"). */
@Composable
internal fun GlassSquareButton(
    onClick: () -> Unit,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    isSelected: Boolean = false,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    GlassButtonBox(
        onClick,
        modifier
            .size(50.dp)
            .semantics {
                if (contentDescription != null) this.contentDescription = contentDescription
                selected = isSelected
            },
        shape = ControlShape,
        enabled = enabled,
        isSelected = isSelected,
    ) { content() }
}

/**
 * The accessible routes button, 50 × 50 glass: the icon in ink, with a 1 dp ink outline while it is on
 * or pressed (RPR, Monday "Accessible routing", 2026-10-28: no blue fill).
 */
@Composable
internal fun AccessibleRoutesButton(isOn: Boolean, onToggle: (Boolean) -> Unit) {
    val label = stringResource(R.string.accessible_routes)
    val state = stringResource(if (isOn) R.string.on else R.string.off)
    val interaction = remember { MutableInteractionSource() }
    val isPressed by interaction.collectIsPressedAsState()
    Box(
        Modifier
            .size(50.dp)
            .glassSurface(ControlShape, GlassStrength.Frosted)
            .then(if (isOn || isPressed) Modifier.border(1.dp, Palette.ink, ControlShape) else Modifier)
            .toggleable(
                value = isOn,
                interactionSource = interaction,
                indication = ripple(),
                role = Role.Switch,
                onValueChange = onToggle,
            )
            .semantics {
                contentDescription = label
                stateDescription = state
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(R.drawable.ic_accessible), contentDescription = null, Modifier.size(22.dp), tint = Palette.ink)
    }
}

/** An icon of the design, 24 dp, in ink. */
@Composable
internal fun MapIcon(icon: Int, modifier: Modifier = Modifier, size: Int = 24) {
    Icon(painterResource(icon), contentDescription = null, modifier.size(size.dp), tint = Palette.ink)
}

/**
 * Amenity shortcuts as a trailing-aligned stack of small chips (Figma search state). A list taller
 * than the space it gets scrolls and fades out at the bottom (PM, Monday "Amenity search when list is
 * long", 2026-10-29; iOS 1.24.1).
 */
@Composable
internal fun AmenityChipList(shortcuts: List<AmenityShortcut>, selected: AmenityShortcut?, modifier: Modifier = Modifier, onPick: (AmenityShortcut) -> Unit) {
    val scroll = rememberScrollState()
    val scrolls = AmenityChipListLayout.scrolls(contentHeight = scroll.maxValue + scroll.viewportSize, visibleHeight = scroll.viewportSize)
    Box(modifier) {
        // A white haze behind the list, so the map behind it shows less (RPR, Monday "White blur
        // behind amenity list", 2026-10-28).
        Box(Modifier.matchParentSize().outset(12.dp).blur(18.dp, BlurredEdgeTreatment.Unbounded).background(Palette.white.copy(alpha = 0.8f)))
        Column(
            Modifier
                // Room inside the scroll's clip for the chips' shadows.
                .bleed(AmenityChipListLayout.SHADOW_ROOM)
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    if (scroll.maxValue > 0) {
                        val fade = AmenityChipListLayout.FADE_HEIGHT.toPx()
                        drawRect(
                            Brush.verticalGradient(listOf(Color.Black, Color.Transparent), startY = size.height - fade, endY = size.height),
                            topLeft = Offset(0f, size.height - fade),
                            blendMode = BlendMode.DstIn,
                        )
                    }
                }
                .verticalScroll(scroll)
                .padding(AmenityChipListLayout.SHADOW_ROOM)
                // The last chip scrolls out of the fade.
                .padding(bottom = if (scrolls) AmenityChipListLayout.FADE_HEIGHT else 0.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            shortcuts.forEach { shortcut ->
                // Frosted: on the clear glass the titles were hard to read over the map
                // (PM 2026-10-15, "Search menu transparency").
                AmenityChip(shortcut, selected = shortcut == selected, onClick = { onPick(shortcut) }, strength = GlassStrength.Frosted)
            }
        }
    }
}

/** The scrolling of [AmenityChipList]. */
internal object AmenityChipListLayout {
    /** The height of the fade at the bottom of a list that scrolls. */
    val FADE_HEIGHT = 44.dp

    /** The room around the chips inside the scroll's clip, so their shadows show. */
    val SHADOW_ROOM = 12.dp

    /** Whether the chips need more height than the list has. Pure; covered by tests. */
    fun scrolls(contentHeight: Int, visibleHeight: Int): Boolean = visibleHeight > 0 && contentHeight > visibleHeight
}

/** [this] laid out [amount] larger on every side, beyond its parent's bounds, and placed so its content stays put. */
private fun Modifier.bleed(amount: Dp): Modifier = layout { measurable, constraints ->
    val extra = amount.roundToPx()
    val placeable = measurable.measure(
        constraints.copy(
            maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth + 2 * extra else constraints.maxWidth,
            maxHeight = if (constraints.hasBoundedHeight) constraints.maxHeight + 2 * extra else constraints.maxHeight,
        ),
    )
    val width = (placeable.width - 2 * extra).coerceAtLeast(0)
    val height = (placeable.height - 2 * extra).coerceAtLeast(0)
    layout(width, height) { placeable.place(-extra, -extra) }
}

/** [this] grown by [amount] on every side, beyond its parent's bounds. */
private fun Modifier.outset(amount: Dp): Modifier = layout { measurable, constraints ->
    val extra = amount.roundToPx()
    val placeable = measurable.measure(
        Constraints.fixed(
            (constraints.maxWidth + 2 * extra).coerceAtLeast(0),
            (constraints.maxHeight + 2 * extra).coerceAtLeast(0),
        ),
    )
    layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(-extra, -extra) }
}

/** "Button Small" with the amenity icon before the title. */
@Composable
internal fun AmenityChip(
    shortcut: AmenityShortcut,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    strength: GlassStrength = GlassStrength.Light,
) {
    val interaction = remember { MutableInteractionSource() }
    val isPressed by interaction.collectIsPressedAsState()
    Row(
        modifier
            .defaultMinSize(minHeight = 32.dp)
            .glassSurface(ControlShape, strength)
            .then(if (selected || isPressed) Modifier.border(1.dp, Palette.ink, ControlShape) else Modifier)
            .clickable(interaction, indication = ripple(), role = Role.Button, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AmenityShortcutIcon(shortcut)
        // One line, as in the design; a long title widens the chip.
        Text(shortcut.title(), style = GadTextStyle.BodySmallRegular.textStyle, color = Palette.ink, maxLines = 1, softWrap = false)
    }
}

/**
 * Bottom card for the next stop (Figma "Stop Info"): icon in a white circle, caption, title, and
 * the instruction with distance and time on one line. The card spans the width of the map.
 */
@Composable
internal fun StopInfoCard(
    caption: String,
    title: String,
    instruction: String?,
    remaining: String?,
    icon: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {},
    /**
     * At the end of the caption line: the artwork's AR and audio badges (PM, Monday "Augmented
     * Reality and Audio symbols").
     */
    badges: @Composable RowScope.() -> Unit = {},
    /**
     * The instruction line keeps its height without an instruction, for example while the route is
     * routed again: the card and the buttons above it do not move (RPR, Monday "Accessible routing",
     * 2026-10-28).
     */
    keepsInstructionLine: Boolean = false,
) {
    Row(
        modifier
            .fillMaxWidth()
            .glassSurface(ControlShape, GlassStrength.Frosted)
            .padding(start = 20.dp, end = 10.dp, top = 20.dp, bottom = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(40.dp)
                .background(Palette.white, CircleShape)
                .border(1.5.dp, Palette.badgeStroke, CircleShape)
                .clearAndSetSemantics {},
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) { icon() }
        }
        Column(
            Modifier.weight(1f).semantics(mergeDescendants = true) {},
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(caption.uppercase(), Modifier.weight(1f), style = GadTextStyle.Caption.textStyle, color = Palette.ink)
                badges()
            }
            Text(title, style = GadTextStyle.LargeBodyBold.textStyle, color = Palette.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (instruction != null || remaining != null || keepsInstructionLine) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    // An empty line still takes the height of one line of text.
                    Text(
                        instruction ?: "",
                        Modifier.weight(1f),
                        style = GadTextStyle.BodyExtraSmall.textStyle.copy(fontWeight = FontWeight.Bold),
                        color = Palette.ink,
                    )
                    if (remaining != null) Text(remaining, style = GadTextStyle.BodyExtraSmall.textStyle, color = Palette.ink)
                }
            }
        }
        trailing()
    }
}

/**
 * Bottom card for a picked place (Figma amenity state): title, kind and floor, and two ghost
 * buttons. Without [onClose] the primary button fills the card.
 */
@Composable
internal fun PlaceInfoCard(
    title: String,
    subtitle: String,
    primary: String,
    onPrimary: () -> Unit,
    onClose: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .glassSurface(ControlShape, GlassStrength.Frosted)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, style = GadTextStyle.LargeBodyBold.textStyle, color = Palette.ink)
            Text(subtitle, style = GadTextStyle.BodySmallRegular.textStyle, color = Palette.ink)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CardGhostButton(primary, onPrimary, Modifier.weight(1f))
            if (onClose != null) CardGhostButton(stringResource(R.string.close), onClose, Modifier.weight(1f))
        }
    }
}

/** Glass button stretched to half the card, 50 dp high ("Go here", "Close"). */
@Composable
internal fun CardGhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    GlassButtonBox(onClick, modifier.defaultMinSize(minHeight = 50.dp), shape = ControlShape) {
        Text(text, style = GadTextStyle.BodyMedium.textStyle, color = Palette.ink)
    }
}

/** Close button of a card, 44 × 44. */
@Composable
internal fun CardCloseButton(contentDescription: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onClick)
            .semantics {
                this.contentDescription = contentDescription
                role = Role.Button
            },
        contentAlignment = Alignment.Center,
    ) {
        MapIcon(R.drawable.ic_map_close, size = 16)
    }
}

/** The visitor-facing name of an amenity kind. */
@Composable
internal fun AmenityKind.title(): String = UiText.Resource(titleKey).mapText()

/** The design's title of a kind, else the amenity's title from the data. */
@Composable
internal fun AmenityShortcut.title(): String = when (this) {
    is AmenityShortcut.Kind -> kind.title()
    is AmenityShortcut.Amenity -> title
}

/** The design's icon of a kind, else the amenity's icon from the data; nothing without one. 16 dp. */
@Composable
internal fun AmenityShortcutIcon(shortcut: AmenityShortcut) {
    when (shortcut) {
        is AmenityShortcut.Kind -> MapIcon(shortcut.kind.gadIcon.glyph, size = 16)
        is AmenityShortcut.Amenity -> shortcut.icon?.let { Image(it, contentDescription = null, Modifier.size(16.dp), contentScale = ContentScale.Fit) }
    }
}

// The journey screen layout (iOS `MapGlassFrame`, `MapListToggle`, `BottomGlassBar`,
// `RoundGlassButtonStyle`).

private val FrameShape = RoundedCornerShape(50.dp)

/**
 * The handle area at the top of [MapGlassFrame], 44 dp: the map does not pan there (PM, Monday "More
 * space for the pull down functionality", 2026-10-26). A drag down calls [onPullDown]; `null` takes the
 * touches and does nothing. Place it over the map and under the map's controls.
 */
@Composable
internal fun MapHandleArea(onPullDown: (() -> Unit)?) {
    val pullDistance = with(LocalDensity.current) { 60.dp.toPx() }
    val currentPullDown by rememberUpdatedState(onPullDown)
    Box(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .pointerInput(Unit) {
                var dragged = 0f
                detectVerticalDragGestures(
                    onDragStart = { dragged = 0f },
                    onDragEnd = { if (dragged > pullDistance) currentPullDown?.invoke() },
                ) { change, amount ->
                    change.consume()
                    dragged += amount
                }
            }
            // Taps and other gestures there do not reach the map either.
            .pointerInput(Unit) { detectTapGestures {} },
    )
}

/**
 * The map in a glass card with 50 dp corners, 10 dp from the screen edges, a white 1 dp edge and
 * a drag handle at the top. [content] is drawn clipped to the card: the map, then its overlays.
 */
@Composable
internal fun MapGlassFrame(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.padding(horizontal = 10.dp)) {
        Box(Modifier.fillMaxSize().clip(FrameShape), content = content)
        Box(Modifier.fillMaxSize().border(1.dp, Palette.white, FrameShape))
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = 8.dp)
                .size(width = 60.dp, height = 6.dp)
                .background(Color.Black.copy(alpha = 0.2f), CircleShape)
                .border(2.dp, Palette.white, CircleShape)
                .clearAndSetSemantics {},
        )
    }
}

/**
 * Fades the content in over [top] from its top and out over [bottom] to its bottom: the journey map
 * under the controls at the top and the card at the bottom (RPR, Monday "Map fade at top & bottom",
 * 2026-10-28). It changes the look only.
 */
internal fun Modifier.verticalFades(top: Dp, bottom: Dp): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            if (size.height <= 0f) return@drawWithContent
            val topEnd = (top.toPx() / size.height).coerceIn(0f, 1f)
            val bottomStart = ((size.height - bottom.toPx()) / size.height).coerceIn(topEnd, 1f)
            drawRect(
                Brush.verticalGradient(0f to Color.Transparent, topEnd to Color.Black, bottomStart to Color.Black, 1f to Color.Transparent),
                blendMode = BlendMode.DstIn,
            )
        }

/**
 * Fades the content to transparent over [width] at every edge, so the screen background shows
 * through (the map card of the Figma map frames; PM 2026-10-23), as iOS `softEdges`. The mask is a
 * rounded rectangle with [cornerRadius], inset by half of [width] and blurred by half of [width]. It
 * changes the look only: touches on the faded edge still reach the content.
 */
internal fun Modifier.softEdges(cornerRadius: Dp, width: Dp = 28.dp): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithCache {
            val mask = softEdgeMask(size, width.toPx(), cornerRadius.toPx())
            onDrawWithContent {
                drawContent()
                drawImage(mask, blendMode = BlendMode.DstIn)
            }
        }

/** Opaque inside, clear at the edges. A software canvas: hardware canvases blur no mask before API 28. */
private fun softEdgeMask(size: Size, width: Float, cornerRadius: Float): ImageBitmap {
    val bitmap = Bitmap.createBitmap(size.width.roundToInt().coerceAtLeast(1), size.height.roundToInt().coerceAtLeast(1), Bitmap.Config.ALPHA_8)
    val inset = width / 2
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { maskFilter = BlurMaskFilter(width / 2, BlurMaskFilter.Blur.NORMAL) }
    android.graphics.Canvas(bitmap).drawRoundRect(inset, inset, size.width - inset, size.height - inset, cornerRadius, cornerRadius, paint)
    return bitmap.asImageBitmap()
}

/**
 * The spinner in the middle of a map while the map library draws the venue (PM, Monday "Spinner on
 * map loading center", 2026-10-26), as iOS `MapLoadingSpinner`. [isBusy] keeps it while the screen
 * has more to do, such as routing the first leg of a journey. [timeout] is
 * `GadSettings.mapLoadingTimeout`. Touches pass through to the map.
 */
@Composable
internal fun MapLoadingSpinner(session: ProximiioMapSession, timeout: Duration, modifier: Modifier = Modifier, isBusy: Boolean = false) {
    val featureCount by session.featureCount.collectAsStateWithLifecycle()
    val lastError by session.lastError.collectAsStateWithLifecycle()
    var hasTimedOut by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(timeout)
        hasTimedOut = true
    }
    val isShown = MapLoading.isShown(featureCount, lastError, hasTimedOut, isBusy)
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AnimatedVisibility(isShown, enter = fadeIn(tween(200)), exit = fadeOut(tween(200))) {
            Box(Modifier.glassSurface(RoundedCornerShape(16.dp), GlassStrength.Frosted).padding(24.dp).testTag("mapLoadingSpinner")) {
                CircularProgressIndicator(Modifier.size(36.dp), color = Palette.ink, strokeWidth = 3.dp)
            }
        }
    }
}

/** When [MapLoadingSpinner] shows. */
internal object MapLoading {
    /**
     * Until the venue's features are on the map, the map reports an error or the timeout passes,
     * and while [isBusy]. Pure; covered by tests.
     */
    fun isShown(featureCount: Int, lastError: String?, hasTimedOut: Boolean, isBusy: Boolean): Boolean =
        isBusy || (featureCount == 0 && lastError == null && !hasTimedOut)
}

/**
 * "Map | List" toggle on glass, in place of the system's segmented button. 150 × 50 glass with 16 dp
 * corners; the selected part is white at 60 % with a 1.5 dp ink outline (Figma frame of the PM, Monday
 * "Native elements", 2026-10-28). A finger slides the selection to the other part, as on the system's
 * segmented control (Monday "Native elements", 2026-10-28 evening). A tap picks the part under the
 * finger; TalkBack and the UI tests see two tabs.
 */
@Composable
internal fun MapListToggle(showsList: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val partWidth = with(density) { MapListSelection.PART_WIDTH.toPx() }
    val threshold = with(density) { MapListSelection.SLIDE_THRESHOLD.toPx() }
    // The finger's travel toward List while it slides the selection, in px; null without a slide.
    var slide by remember { mutableStateOf<Float?>(null) }
    val selection by animateFloatAsState(
        MapListSelection.offset(showsList, slide ?: 0f, partWidth),
        if (slide != null) snap() else spring(stiffness = Spring.StiffnessMediumLow),
        label = "selection",
    )
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier
            .size(width = 150.dp, height = 50.dp)
            .glassSurface(RoundedCornerShape(16.dp), GlassStrength.Frosted)
            .padding(4.dp)
            .pointerInput(showsList, partWidth, isRtl) {
                val forward = if (isRtl) -1f else 1f
                var position = 0f
                detectHorizontalDragGestures(
                    onDragStart = { slide = 0f },
                    onDragEnd = {
                        val travel = slide ?: 0f
                        slide = null
                        onChange(MapListSelection.showsList(travel, position, showsList, partWidth, threshold))
                    },
                    onDragCancel = { slide = null },
                    onHorizontalDrag = { change, amount ->
                        change.consume()
                        slide = (slide ?: 0f) + forward * amount
                        // The finger's distance from the Map end.
                        position = if (isRtl) 2 * partWidth - change.position.x else change.position.x
                    },
                )
            },
    ) {
        // `offset` follows the layout direction: toward List on both.
        Box(
            Modifier
                .offset { IntOffset(selection.roundToInt(), 0) }
                .width(MapListSelection.PART_WIDTH)
                .fillMaxHeight()
                .background(Palette.white.copy(alpha = 0.6f), shape)
                .border(1.5.dp, Palette.ink, shape),
        )
        Row(Modifier.fillMaxSize().selectableGroup()) {
            ToggleSegment(stringResource(R.string.map), isSelected = !showsList) { onChange(false) }
            ToggleSegment(stringResource(R.string.list), isSelected = showsList) { onChange(true) }
        }
    }
}

@Composable
private fun RowScope.ToggleSegment(title: String, isSelected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .clip(RoundedCornerShape(12.dp))
            .selectable(isSelected, remember { MutableInteractionSource() }, indication = ripple(), role = Role.Tab, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(title, style = GadTextStyle.LargeBodyRegular.textStyle, color = Palette.ink)
    }
}

/** The selection of [MapListToggle] under a sliding finger. Pure; covered by tests. */
internal object MapListSelection {
    /** The width of one part: the control is 150 dp wide with 4 dp of padding. */
    val PART_WIDTH = ((150 - 8) / 2).dp

    /** A finger that travels less than this is a tap. */
    val SLIDE_THRESHOLD = 8.dp

    /**
     * The selection's offset from the Map part toward the List part: the selected part, moved by
     * [slide] and kept inside the control.
     */
    fun offset(showsList: Boolean, slide: Float, width: Float): Float =
        ((if (showsList) width else 0f) + slide).coerceIn(0f, width)

    /**
     * Whether the control shows List when the finger lifts: a finger that travelled less than
     * [threshold] picks the part under it, at [position] from the Map end; a slide picks the part the
     * selection ends over.
     */
    fun showsList(slide: Float, position: Float, showsList: Boolean, width: Float, threshold: Float): Boolean {
        if (abs(slide) < threshold) return position > width
        return offset(showsList, slide, width) > width / 2
    }
}

/** The glass bar under the map card (Figma `Bar_bottom`, node 6902:46776): a capsule with round buttons at both ends. */
@Composable
internal fun BottomGlassBar(modifier: Modifier = Modifier, leading: @Composable () -> Unit, trailing: @Composable () -> Unit) {
    Row(
        modifier
            .padding(horizontal = 10.dp)
            .fillMaxWidth()
            .height(93.dp)
            .glassSurface(CircleShape, shadow = false)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Spacer(Modifier.weight(1f))
        trailing()
    }
}

/** Round glass button of the bottom bar, 60 dp. */
@Composable
internal fun RoundGlassButton(onClick: () -> Unit, contentDescription: String, content: @Composable () -> Unit) {
    GlassButtonBox(
        onClick,
        Modifier.size(60.dp).semantics { this.contentDescription = contentDescription },
        shape = CircleShape,
    ) { content() }
}
