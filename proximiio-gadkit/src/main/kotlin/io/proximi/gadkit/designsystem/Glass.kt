package io.proximi.gadkit.designsystem

import io.proximi.gadkit.ProximiGadAppApi
import androidx.compose.foundation.background
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

/**
 * Where a glass surface is. Since 1.14.0 every strength draws the same frosted glass: "Android:
 * frosted surface with a light shadow" (Figma "Glass review: iOS 26, iOS 27 and Android", node
 * 16349:60996, 2026-10-24), as iOS 26 draws Apple's Regular glass for every strength.
 */
@ProximiGadAppApi
public enum class GlassStrength {
    /** Buttons, cards and bars over the coloured backgrounds. */
    Regular,

    /** Button Small and chips. */
    Light,

    /** Cards, rows and buttons over the map and the journey list. */
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

/** The white over the blurred backdrop (Figma: white 60 % on a 10 px background blur). */
private const val GLASS_TINT = 0.6f

/** The white of a glass surface without a backdrop to blur. */
private const val GLASS_FALLBACK = 0.85f

/**
 * The glass surface of the Android design, as iOS `GlassSurface`: "frosted surface with a light
 * shadow" (Figma "Glass review: iOS 26, iOS 27 and Android", node 16349:60996, 2026-10-24): white
 * 60 % over the blurred backdrop and a shadow of y 1, blur 3, black 12 %, without the earlier
 * gradient and white stroke. Under [ProvideGlassBackdrop] the surface blurs the backdrop; without
 * one it is nearly white. [strength] does not change the look since 1.14.0.
 */
@ProximiGadAppApi
public fun Modifier.glassSurface(
    shape: Shape = RoundedCornerShape(12.dp),
    @Suppress("UNUSED_PARAMETER") strength: GlassStrength = GlassStrength.Regular,
    shadow: Boolean = true,
): Modifier = composed {
    val backdrop = LocalGlassBackdrop.current
    Modifier
        .then(if (shadow) Modifier.glassShadow(shape) else Modifier)
        .clip(shape)
        .then(
            if (backdrop != null) {
                Modifier.hazeEffect(
                    backdrop.haze,
                    HazeStyle(
                        backgroundColor = Palette.white,
                        tint = HazeTint(Color.White.copy(alpha = GLASS_TINT)),
                        blurRadius = 20.dp,
                        noiseFactor = 0f,
                        fallbackTint = HazeTint(Color.White.copy(alpha = GLASS_FALLBACK)),
                    ),
                )
            } else {
                Modifier.background(Color.White.copy(alpha = GLASS_FALLBACK))
            },
        )
}

/**
 * A drop shadow, by default the light shadow of the Android glass (y 1, blur 3, black 12 %), drawn
 * outside [shape] only, so a translucent surface does not show it through. Framework shadow layers
 * need API 28 for shapes; API 26–27 draw no shadow.
 */
internal fun Modifier.glassShadow(shape: Shape, color: Color = Color.Black.copy(alpha = 0.12f), offsetY: Dp = 1.dp, blur: Dp = 3.dp): Modifier =
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
