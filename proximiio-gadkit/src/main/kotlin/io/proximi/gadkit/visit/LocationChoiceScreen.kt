package io.proximi.gadkit.visit

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.proximi.gadkit.R
import io.proximi.gadkit.visit.LocationChoice
import io.proximi.gadkit.designsystem.AuroraBox
import io.proximi.gadkit.designsystem.GadTextStyle
import io.proximi.gadkit.designsystem.GlassStrength
import io.proximi.gadkit.designsystem.GuggenheimSansDisplay
import io.proximi.gadkit.designsystem.IconGlassButton
import io.proximi.gadkit.designsystem.Palette
import io.proximi.gadkit.designsystem.glassSurface
import io.proximi.gadkit.map.mapText
import io.proximi.gadkit.ui.asHeading

/**
 * Full-screen choice when the position is ambiguous (Figma nodes 6902:38047 EC04a, 6902:38073
 * EC04b, 6902:38099 EC04c), as iOS `LocationChoiceView`: a header with the level and the gallery
 * and a close button, a two-weight question, and the options as frosted glass cards.
 */
@Composable
internal fun LocationChoiceScreen(
    choice: LocationChoice,
    level: String?,
    gallery: String?,
    onPick: (LocationChoice.Option) -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    AuroraBox(Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, indication = null, onClick = {})) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 40.dp).padding(top = 8.dp, bottom = 24.dp),
        ) {
            Row {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    level?.let { Text(it.uppercase(), style = GadTextStyle.BodyExtraSmall.textStyle, color = Palette.ink) }
                    gallery?.let { Text(it, style = GadTextStyle.LargeBodyBold.textStyle, color = Palette.ink) }
                }
                IconGlassButton(onClose) {
                    Icon(painterResource(R.drawable.ic_map_close), stringResource(R.string.close), Modifier.size(14.dp), tint = Palette.ink)
                }
            }
            Column(Modifier.padding(top = 100.dp).asHeading(merge = true)) {
                choice.questionLight?.let {
                    Text(
                        it.mapText(),
                        style = GadTextStyle.H1Regular.textStyle.copy(fontFamily = GuggenheimSansDisplay, fontWeight = FontWeight.Normal, fontSize = 24.sp),
                        color = Palette.ink,
                    )
                }
                Text(choice.questionBold.mapText(), style = GadTextStyle.H1Bold.textStyle, color = Palette.ink)
            }
            choice.caption?.let {
                Text(it.mapText(), Modifier.padding(top = 50.dp), style = GadTextStyle.BodySmallRegular.textStyle, color = Palette.ink)
            }
            Column(
                Modifier.padding(top = if (choice.caption == null) 40.dp else 15.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                choice.options.forEach { option -> OptionCard(option) { onPick(option) } }
            }
            Spacer(Modifier.weight(1f, fill = false))
        }
    }
}

/** An option as a frosted glass card; scales to 0.95 while pressed (iOS `PressScaleStyle`). */
@Composable
private fun OptionCard(option: LocationChoice.Option, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.95f else 1f, tween(150), label = "option")
    Column(
        Modifier
            .fillMaxWidth()
            .scale(scale)
            .glassSurface(RoundedCornerShape(12.dp), GlassStrength.Frosted)
            .clickable(interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = if (option.subtitle == null) 20.dp else 18.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(option.title.mapText(), style = GadTextStyle.BodyMedium.textStyle, color = Palette.ink)
        option.subtitle?.let { Text(it.mapText(), style = GadTextStyle.BodySmallRegular.textStyle, color = Palette.ink) }
    }
}
