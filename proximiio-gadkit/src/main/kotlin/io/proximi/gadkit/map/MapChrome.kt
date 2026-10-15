package io.proximi.gadkit.map

import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
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
    Box(
        modifier
            .size(50.dp)
            .alpha(if (enabled) 1f else Palette.DISABLED_OPACITY)
            .then(if (isSelected) Modifier.border(1.5.dp, Palette.ink, ControlShape) else Modifier)
            .glassSurface(ControlShape, GlassStrength.Frosted)
            .clickable(remember { MutableInteractionSource() }, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics {
                if (contentDescription != null) this.contentDescription = contentDescription
                selected = isSelected
            },
        contentAlignment = Alignment.Center,
    ) { content() }
}

/**
 * The accessible routes button of the maps, as iOS `AccessibleRoutesButton`: a wheelchair on glass
 * while it is off; white on a filled [Palette.position] square while it is on (PM 2026-10-14: an
 * indicator for both states).
 */
@Composable
internal fun AccessibleRoutesButton(isOn: Boolean, onToggle: (Boolean) -> Unit) {
    val label = stringResource(R.string.accessible_routes)
    val state = stringResource(if (isOn) R.string.on else R.string.off)
    Box(
        Modifier
            .size(50.dp)
            .glassSurface(ControlShape, GlassStrength.Frosted)
            .then(if (isOn) Modifier.background(Palette.position, ControlShape) else Modifier)
            .toggleable(
                value = isOn,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Switch,
                onValueChange = onToggle,
            )
            .semantics {
                contentDescription = label
                stateDescription = state
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(R.drawable.ic_accessible), contentDescription = null, Modifier.size(22.dp), tint = if (isOn) Palette.white else Palette.ink)
    }
}

/** An icon of the design, 24 dp, in ink. */
@Composable
internal fun MapIcon(icon: Int, modifier: Modifier = Modifier, size: Int = 24) {
    Icon(painterResource(icon), contentDescription = null, modifier.size(size.dp), tint = Palette.ink)
}

/** Amenity shortcuts as a trailing-aligned stack of small chips (Figma search state). */
@Composable
internal fun AmenityChipList(kinds: List<AmenityKind>, selected: AmenityKind?, onPick: (AmenityKind) -> Unit) {
    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        kinds.forEach { kind ->
            AmenityChip(kind, selected = kind == selected, onClick = { onPick(kind) })
        }
    }
}

/** "Button Small" with the amenity icon before the title. */
@Composable
internal fun AmenityChip(kind: AmenityKind, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .defaultMinSize(minHeight = 32.dp)
            .then(if (selected) Modifier.border(1.5.dp, Palette.ink, ControlShape) else Modifier)
            .glassSurface(ControlShape, GlassStrength.Light)
            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MapIcon(kind.gadIcon.glyph, size = 16)
        Text(kind.title(), style = GadTextStyle.BodySmallRegular.textStyle, color = Palette.ink)
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
            Text(caption.uppercase(), style = GadTextStyle.Caption.textStyle, color = Palette.ink)
            Text(title, style = GadTextStyle.LargeBodyBold.textStyle, color = Palette.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (instruction != null || remaining != null) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (instruction != null) {
                        Text(
                            instruction,
                            Modifier.weight(1f),
                            style = GadTextStyle.BodyExtraSmall.textStyle.copy(fontWeight = FontWeight.Bold),
                            color = Palette.ink,
                        )
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
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

/** Ghost button stretched to half the card, 50 dp high ("Go here", "Close"). */
@Composable
internal fun CardGhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .defaultMinSize(minHeight = 50.dp)
            .border(1.5.dp, Palette.ink, ControlShape)
            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = GadTextStyle.BodyMedium.textStyle, color = Palette.ink)
    }
}

/**
 * Gallery categories of the venue style (Figma "Explore Museum" map legend). The colours are those
 * the design uses; the map style on the server draws the rooms.
 */
@Composable
internal fun GalleryLegend(modifier: Modifier = Modifier) {
    val entries: List<Pair<Int, Color>> = listOf(
        R.string.thematic_gallery to Palette.orange,
        R.string.single_artist_gallery to Palette.yellow,
        R.string.other to Palette.lavender,
        R.string.learning_engagement to Palette.cyan,
    )
    Column(modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        entries.forEach { (title, color) ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(color, CircleShape))
                Text(stringResource(title), style = GadTextStyle.BodyExtraSmall.textStyle, color = Palette.ink)
            }
        }
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

// The journey screen layout (iOS `MapGlassFrame`, `MapListToggle`, `BottomGlassBar`,
// `RoundGlassButtonStyle`).

private val FrameShape = RoundedCornerShape(50.dp)

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

/** "Map | List" toggle: glass 150 × 50 with the selected item outlined. */
@Composable
internal fun MapListToggle(showsList: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .size(width = 150.dp, height = 50.dp)
            .glassSurface(ControlShape, GlassStrength.Frosted)
            .padding(5.dp),
    ) {
        ToggleItem(stringResource(R.string.map), selected = !showsList, Modifier.weight(1f)) { onChange(false) }
        ToggleItem(stringResource(R.string.list), selected = showsList, Modifier.weight(1f)) { onChange(true) }
    }
}

@Composable
private fun ToggleItem(title: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier
            .fillMaxHeight()
            .then(if (selected) Modifier.border(1.5.dp, Palette.ink, shape) else Modifier)
            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Tab, onClick = onClick)
            .semantics { this.selected = selected },
        contentAlignment = Alignment.Center,
    ) {
        Text(title, style = GadTextStyle.BodySmallRegular.textStyle, color = Palette.ink)
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
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        Modifier
            .size(60.dp)
            .scale(if (pressed) 0.92f else 1f)
            .glassSurface(CircleShape)
            .clickable(interaction, indication = null, role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) { content() }
}
