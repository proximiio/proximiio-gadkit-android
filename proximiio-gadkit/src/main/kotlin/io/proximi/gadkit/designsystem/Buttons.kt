package io.proximi.gadkit.designsystem

import io.proximi.gadkit.ProximiGadAppApi
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp

// The buttons of the design (Figma "Glass review: iOS 26, iOS 27 and Android", node 16349:60996,
// 2026-10-24), as iOS `Buttons.swift`:
//
// | Button | Figma component | Shape |
// |---|---|---|
// | `PrimaryButton` | Button Primary Large | glass, 60 dp high, full width, Body Medium |
// | `GhostButton` | Button Ghost Medium | glass, 40 dp high, Body Medium |
// | `SmallButton` | Button Small | glass, 32 dp high, Body Small |
// | `IconGlassButton` | Button_medium | glass, 40 × 40 dp |
// | `TextActionButton` | "Skip for now", "Carry on" | Body Small, underlined |
//
// The glass is the frosted surface with a light shadow (`glassSurface`). Pressed and selected are a
// 1 dp ink outline, over the system ripple; the button does not grow or shrink (PM, Monday "Buttons
// to have black border when pressed", 2026-10-28). Disabled is 40 % opacity.

private val ButtonShape = RoundedCornerShape(12.dp)

/**
 * [content] on glass in [shape], as iOS `GlassButton`. The ripple and a 1 dp ink outline draw the
 * pressed state inside the shape. [modifier] sizes the button; [content] lies inside the glass.
 */
@Composable
internal fun GlassButtonBox(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = ButtonShape,
    enabled: Boolean = true,
    isSelected: Boolean = false,
    role: Role = Role.Button,
    content: @Composable BoxScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val isPressed by interaction.collectIsPressedAsState()
    Box(
        modifier
            .alpha(if (enabled) 1f else Palette.DISABLED_OPACITY)
            .glassSurface(shape)
            .then(if (isSelected || isPressed) Modifier.border(1.dp, Palette.ink, shape) else Modifier)
            .clickable(interaction, indication = ripple(), enabled = enabled, role = role, onClick = onClick),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

/** Figma "Button Large": full width, 60 dp, glass. */
@Composable
@ProximiGadAppApi
public fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    GlassButtonBox(onClick, modifier.fillMaxWidth().defaultMinSize(minHeight = 60.dp), enabled = enabled) {
        // The padding only shows with large text: the title then wraps inside the button.
        Text(
            text,
            Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            style = GadTextStyle.BodyMedium.textStyle,
            color = Palette.ink,
            textAlign = TextAlign.Center,
        )
    }
}

/** Figma "Button Ghost Medium": glass, 40 dp. Before 1.14.0 an outlined button. */
@Composable
@ProximiGadAppApi
public fun GhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    GlassButtonBox(onClick, modifier.defaultMinSize(minHeight = 40.dp), enabled = enabled) {
        Text(
            text,
            Modifier.padding(horizontal = 30.dp, vertical = 6.dp),
            style = GadTextStyle.BodyMedium.textStyle,
            color = Palette.ink,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Figma "Button Small" and chips, 32 dp; a selected chip has a 1 dp ink outline. [strength] does
 * not change the look since 1.14.0 (`glassSurface`).
 */
@Composable
@ProximiGadAppApi
public fun SmallButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    @Suppress("UNUSED_PARAMETER") strength: GlassStrength = GlassStrength.Light,
) {
    GlassButtonBox(onClick, modifier.defaultMinSize(minHeight = 32.dp), isSelected = selected) {
        Text(text, Modifier.padding(horizontal = 16.dp), style = GadTextStyle.BodySmallRegular.textStyle, color = Palette.ink)
    }
}

/** 40 × 40 glass button for an icon. */
@Composable
@ProximiGadAppApi
public fun IconGlassButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable () -> Unit) {
    GlassButtonBox(onClick, modifier.size(40.dp), enabled = enabled) { content() }
}

/** Underlined text action, 44 dp touch height. Pressed, it is at 60 %. */
@Composable
@ProximiGadAppApi
public fun TextActionButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier
            .defaultMinSize(minHeight = 44.dp)
            .alpha(if (pressed) 0.6f else 1f)
            .clickable(interaction, indication = null, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = GadTextStyle.BodySmallRegular.textStyle.copy(textDecoration = TextDecoration.Underline),
            color = Palette.ink,
        )
    }
}
