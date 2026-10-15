package io.proximi.gadkit.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** The colour scheme of [AuroraBackground], as iOS `AuroraBackground.Variant`. */
public enum class AuroraVariant {
    /** Peach top, pastel band along the bottom. */
    Standard,

    /** Lime, mint and cyan (Journey Complete, PM review 2026-10-12). */
    Mint,
}

/**
 * The soft colour background of the Hub and the onboarding screens, as in iOS `AuroraBackground`.
 * iOS blurs solid circles; radial gradients give the same falloff without a blur pass.
 */
@Composable
public fun AuroraBackground(modifier: Modifier = Modifier, variant: AuroraVariant = AuroraVariant.Standard) {
    Canvas(modifier.fillMaxSize()) {
        drawRect(Palette.white)
        fun blob(color: Color, diameter: Float, x: Float, y: Float) {
            val radius = diameter * 0.9f
            drawCircle(
                brush = Brush.radialGradient(
                    0f to color,
                    0.45f to color.copy(alpha = color.alpha * 0.6f),
                    1f to color.copy(alpha = 0f),
                    center = Offset(x, y),
                    radius = radius,
                ),
                radius = radius,
                center = Offset(x, y),
            )
        }
        val w = size.width
        val h = size.height
        if (variant == AuroraVariant.Mint) {
            blob(Palette.mint.copy(alpha = 0.8f), w * 0.9f, w * 0.05f, h * 0.05f)
            blob(Palette.lime.copy(alpha = 0.6f), w * 0.6f, w * 0.05f, h * 0.3f)
            blob(Palette.cyan.copy(alpha = 0.85f), w * 1.1f, w * 0.6f, h * 0.62f)
            blob(Palette.mint.copy(alpha = 0.7f), w * 0.7f, w * 1.0f, h * 0.5f)
            blob(Palette.white.copy(alpha = 0.8f), w * 0.6f, w * 0.75f, h * 0.12f)
            blob(Palette.white.copy(alpha = 0.7f), w * 0.8f, w * 0.3f, h * 0.98f)
            return@Canvas
        }
        // Peach glow in the top leading corner.
        blob(Palette.orange.copy(alpha = 0.75f), w * 0.8f, w * -0.05f, h * 0.03f)
        blob(Color(0xFFFFC488).copy(alpha = 0.7f), w * 0.5f, w * 0.05f, h * 0.12f)
        // Pastel band along the bottom edge.
        blob(Palette.cyan.copy(alpha = 0.55f), w * 0.9f, 0f, h * 0.9f)
        blob(Palette.lavender.copy(alpha = 0.6f), w * 0.7f, w * 0.55f, h * 0.98f)
        blob(Palette.red.copy(alpha = 0.45f), w * 0.6f, w * 0.75f, h * 1.05f)
        blob(Palette.orange.copy(alpha = 0.45f), w * 0.6f, w * 1.05f, h * 0.93f)
    }
}

/** A box on [AuroraBackground] whose glass surfaces blur the aurora. */
@Composable
public fun AuroraBox(modifier: Modifier = Modifier, variant: AuroraVariant = AuroraVariant.Standard, content: @Composable BoxScope.() -> Unit) {
    val backdrop = rememberGlassBackdrop()
    Box(modifier) {
        AuroraBackground(Modifier.matchParentSize().glassBackdrop(backdrop), variant)
        ProvideGlassBackdrop(backdrop) { content() }
    }
}
