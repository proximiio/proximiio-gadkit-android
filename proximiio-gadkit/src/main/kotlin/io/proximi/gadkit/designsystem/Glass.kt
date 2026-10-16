package io.proximi.gadkit.designsystem

import io.proximi.gadkit.ProximiGadAppApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

@ProximiGadAppApi
public enum class GlassStrength {
    /** Buttons, cards and bars over the coloured backgrounds. iOS 26: `Glass.regular`. */
    Regular,

    /** Button Small and chips: the gradient at 20 %. iOS 26: `Glass.clear`. */
    Light,

    /** Cards, rows and buttons over the map and the journey list: frosted, nearly white. iOS 26: `Glass.regular` tinted white 35 %. */
    Frosted,
}

/**
 * The content a glass surface blurs. Mark the content behind the glass (aurora, image, map) with
 * [glassBackdrop] and provide the backdrop to the glass with [ProvideGlassBackdrop].
 */
@Stable
@ProximiGadAppApi
public class GlassBackdrop internal constructor(internal val haze: HazeState)

/** The backdrop of the glass surfaces inside [ProvideGlassBackdrop]; `null` draws the glass without blur. */
internal val LocalGlassBackdrop: ProvidableCompositionLocal<GlassBackdrop?> = staticCompositionLocalOf { null }

/**
 * Blur is enabled on every API level: RenderEffect on API 31 and later, RenderScript on API 26–30.
 * Haze draws a translucent scrim where neither is available.
 */
@Composable
@ProximiGadAppApi
public fun rememberGlassBackdrop(): GlassBackdrop {
    val haze = rememberHazeState(blurEnabled = true)
    return remember(haze) { GlassBackdrop(haze) }
}

/** Marks this content as the backdrop the glass surfaces of [backdrop] blur. */
@ProximiGadAppApi
public fun Modifier.glassBackdrop(backdrop: GlassBackdrop): Modifier = hazeSource(backdrop.haze)

@Composable
@ProximiGadAppApi
public fun ProvideGlassBackdrop(backdrop: GlassBackdrop?, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalGlassBackdrop provides backdrop, content = content)
}

/** The Figma fill: #E6E6E6 → clear → #E6E6E6, diagonal. */
private fun figmaGradient(alpha: Float) = Brush.linearGradient(
    0f to Palette.glassEdge.copy(alpha = alpha),
    0.5f to Color.White.copy(alpha = 0.01f * alpha),
    1f to Palette.glassEdge.copy(alpha = alpha),
)

/**
 * The look of one strength. [tint] is the white over the blurred backdrop, [blur] the blur radius,
 * [fallback] the white without a backdrop, [gradient] the opacity of the Figma gradient.
 */
private class GlassLook(val blur: Dp, val tint: Float, val fallback: Float, val gradient: Float)

private val GlassStrength.look: GlassLook
    get() = when (this) {
        GlassStrength.Regular -> GlassLook(blur = 24.dp, tint = 0.45f, fallback = 0.35f, gradient = 0.55f)
        GlassStrength.Light -> GlassLook(blur = 10.dp, tint = 0.12f, fallback = 0.2f, gradient = 0.2f)
        GlassStrength.Frosted -> GlassLook(blur = 24.dp, tint = 0.7f, fallback = 0.85f, gradient = 0.5f)
    }

/**
 * The design's glass surface, as iOS `GlassSurface`: Figma GLASS effect, the diagonal gradient,
 * a 1.5 dp white stroke and a shadow of x 0, y 3, blur 10, black 15 %. Under [ProvideGlassBackdrop]
 * the surface blurs the backdrop; without one it is translucent white.
 */
@ProximiGadAppApi
public fun Modifier.glassSurface(
    shape: Shape = RoundedCornerShape(12.dp),
    strength: GlassStrength = GlassStrength.Regular,
    shadow: Boolean = true,
): Modifier = composed {
    val backdrop = LocalGlassBackdrop.current
    val look = strength.look
    Modifier
        .then(if (shadow) Modifier.glassShadow(shape) else Modifier)
        .clip(shape)
        .then(
            if (backdrop != null) {
                Modifier.hazeEffect(
                    backdrop.haze,
                    HazeStyle(
                        backgroundColor = Palette.white,
                        tint = HazeTint(Color.White.copy(alpha = look.tint)),
                        blurRadius = look.blur,
                        noiseFactor = 0f,
                        fallbackTint = HazeTint(Color.White.copy(alpha = look.fallback)),
                    ),
                )
            } else {
                Modifier.background(Color.White.copy(alpha = look.fallback))
            },
        )
        .background(figmaGradient(look.gradient))
        .border(1.5.dp, Palette.white, shape)
}

/**
 * The Figma drop shadow (y 3, blur 10, black 15 %), drawn outside [shape] only, so a translucent
 * surface does not show it through. Framework shadow layers need API 28 for shapes; API 26–27
 * draw no shadow.
 */
internal fun Modifier.glassShadow(shape: Shape, color: Color = Color.Black.copy(alpha = 0.15f), offsetY: Dp = 3.dp, blur: Dp = 10.dp): Modifier =
    drawBehind {
        val path = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawBehind)) }
        // Figma blur is twice the Gaussian sigma; a framework shadow radius r has sigma 0.57735 r + 0.5.
        val sigma = blur.toPx() / 2f
        val radius = ((sigma - 0.5f) / 0.57735f).coerceAtLeast(0.1f)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            this.color = android.graphics.Color.WHITE
            setShadowLayer(radius, 0f, offsetY.toPx(), color.toArgb())
        }
        clipPath(path, ClipOp.Difference) {
            drawIntoCanvas { it.nativeCanvas.drawPath(path.asAndroidPath(), paint) }
        }
    }
