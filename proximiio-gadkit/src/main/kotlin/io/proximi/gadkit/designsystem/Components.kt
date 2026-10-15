package io.proximi.gadkit.designsystem

import androidx.compose.ui.res.painterResource
import androidx.compose.material3.Icon
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * Stop number badge (Figma journey list, node 11820:71364; PM review 2026-10-12): a 34 dp circle,
 * Body Medium. A pending stop is white with a 2 dp #EEEDED stroke. The next stop and a stop being
 * acted on (swiped) are filled red with a white number. A visited stop is white with a red stroke.
 */
@Composable
public fun StopNumberBadge(number: Int, modifier: Modifier = Modifier, emphasis: Emphasis = Emphasis.Normal) {
    Box(
        modifier
            .size(34.dp)
            .background(if (emphasis == Emphasis.Filled) Palette.activeRed else Palette.white, CircleShape)
            .border(2.dp, if (emphasis == Emphasis.Normal) Palette.badgeStroke else Palette.activeRed, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            number.toString(),
            style = GadTextStyle.BodyMedium.textStyle,
            color = if (emphasis == Emphasis.Filled) Palette.white else Palette.ink,
        )
    }
}

public enum class Emphasis { Normal, Filled, Visited }

/** Small outlined label, e.g. a level ("L2") or a gallery code. */
@Composable
public fun OutlineBadge(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .defaultMinSize(minWidth = 25.dp, minHeight = 20.dp)
            .border(1.dp, Palette.ink, RoundedCornerShape(6.dp))
            .padding(horizontal = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = GadTextStyle.Caption.textStyle.copy(fontWeight = FontWeight.SemiBold, fontSize = 10.sp, letterSpacing = 0.06.em),
            color = Palette.ink,
        )
    }
}

/** [OutlineBadge] with a 10 dp glyph in place of the text, for example the audio note of a journey stop. */
@Composable
public fun OutlineBadge(@DrawableRes icon: Int, contentDescription: String?, modifier: Modifier = Modifier) {
    Box(
        modifier
            .defaultMinSize(minWidth = 25.dp, minHeight = 20.dp)
            .border(1.dp, Palette.ink, RoundedCornerShape(6.dp))
            .padding(horizontal = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription, Modifier.size(10.dp), tint = Palette.ink)
    }
}

/** Figma input: label above a 60 dp field with the input fill, gradient and white or error stroke. */
@Composable
public fun LabeledField(label: String, modifier: Modifier = Modifier, isError: Boolean = false, field: @Composable () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, style = GadTextStyle.BodySmallRegular.textStyle, color = if (isError) Palette.errorLabel else Palette.ink)
        Box(
            Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 60.dp)
                .shadow(2.5.dp, shape, ambientColor = Color.Black.copy(alpha = 0.15f), spotColor = Color.Black.copy(alpha = 0.15f))
                .background(Palette.inputFill, shape)
                .background(
                    Brush.linearGradient(
                        0.16f to Color(0xFFD6D6D6).copy(alpha = 0.55f),
                        0.43f to Color.White.copy(alpha = 0f),
                        1f to Color(0xFFE7E7E7).copy(alpha = 0.55f),
                    ),
                    shape,
                )
                .border(1.5.dp, if (isError) Palette.errorStroke else Palette.white, shape)
                .padding(horizontal = 20.dp),
            contentAlignment = Alignment.CenterStart,
        ) { field() }
    }
}
