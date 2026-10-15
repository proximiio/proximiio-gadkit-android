package io.proximi.gadkit.designsystem

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp

private val ButtonShape = RoundedCornerShape(12.dp)

/** Shared press handling: no ripple, a scale on press as on iOS. */
@Composable
private fun PressableBox(
    onClick: () -> Unit,
    enabled: Boolean,
    pressedScale: Float,
    pressedAlpha: Float,
    modifier: Modifier,
    content: @Composable (Modifier) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) pressedScale else 1f, tween(150), label = "press")
    val alpha = when {
        !enabled -> Palette.DISABLED_OPACITY
        pressed -> pressedAlpha
        else -> 1f
    }
    content(
        modifier
            .scale(scale)
            .alpha(alpha)
            .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick),
    )
}

/** Figma "Button Large": full width, 60 dp, glass. */
@Composable
public fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    PressableBox(onClick, enabled, 0.95f, 1f, modifier) { m ->
        Box(
            // The padding only shows with large text: the title then wraps inside the button.
            m.fillMaxWidth().defaultMinSize(minHeight = 60.dp).glassSurface(ButtonShape, shadow = enabled).padding(horizontal = 20.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(text, style = GadTextStyle.BodyMedium.textStyle, color = Palette.ink, textAlign = TextAlign.Center)
        }
    }
}

/** Outlined button, 40 dp. */
@Composable
public fun GhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    PressableBox(onClick, enabled, 1f, 0.6f, modifier) { m ->
        Box(
            m.defaultMinSize(minHeight = 40.dp).border(1.5.dp, Palette.ink, ButtonShape).padding(horizontal = 30.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(text, style = GadTextStyle.BodyMedium.textStyle, color = Palette.ink, textAlign = TextAlign.Center)
        }
    }
}

/** Figma "Button Small" and chips, 32 dp; a selected chip has an ink outline. */
@Composable
public fun SmallButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, selected: Boolean = false) {
    PressableBox(onClick, true, 0.95f, 1f, modifier) { m ->
        Box(
            m.defaultMinSize(minHeight = 32.dp)
                // Before the glass: a border earlier in the chain draws over the glass's white edge.
                .then(if (selected) Modifier.border(1.5.dp, Palette.ink, ButtonShape) else Modifier)
                .glassSurface(ButtonShape, GlassStrength.Light)
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(text, style = GadTextStyle.BodySmallRegular.textStyle, color = Palette.ink)
        }
    }
}

/** 40 × 40 glass button for an icon. */
@Composable
public fun IconGlassButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable () -> Unit) {
    PressableBox(onClick, enabled, 0.92f, 1f, modifier) { m ->
        Box(m.size(40.dp).glassSurface(ButtonShape, shadow = enabled), contentAlignment = Alignment.Center) { content() }
    }
}

/** Underlined text action, 44 dp touch height. */
@Composable
public fun TextActionButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    PressableBox(onClick, true, 1f, 0.6f, modifier) { m ->
        Box(m.defaultMinSize(minHeight = 44.dp), contentAlignment = Alignment.Center) {
            Text(
                text,
                style = GadTextStyle.BodySmallRegular.textStyle.copy(textDecoration = TextDecoration.Underline),
                color = Palette.ink,
            )
        }
    }
}
