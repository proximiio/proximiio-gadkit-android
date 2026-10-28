package io.proximi.gadkit.designsystem

import io.proximi.gadkit.ProximiGadAppApi
import io.proximi.gadkit.R
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * Guggenheim Sans Display. It has no Arabic glyphs; Android falls back to the system Arabic
 * font per glyph, as iOS does.
 */
@ProximiGadAppApi
public val GuggenheimSansDisplay: FontFamily = FontFamily(
    Font(R.font.guggenheim_sans_display_light, FontWeight.Light),
    Font(R.font.guggenheim_sans_display_regular, FontWeight.Normal),
    Font(R.font.guggenheim_sans_display_medium, FontWeight.Medium),
    Font(R.font.guggenheim_sans_display_semibold, FontWeight.SemiBold),
    Font(R.font.guggenheim_sans_display_bold, FontWeight.Bold),
)

/**
 * Text styles from the Figma "Typography" section (node 6902:46986), as in iOS `Typography.swift`.
 * Caption has 6 % letter spacing. The line height is the font's own (ascent + descent, 1.21 × the
 * size), as on iOS and in the Figma text layers ("leading normal"). Sizes are in sp, so every style
 * scales with the system font size.
 */
@ProximiGadAppApi
public enum class GadTextStyle(public val weight: FontWeight, public val sizeSp: Int) {
    CardTitle(FontWeight.SemiBold, 32),
    H1Regular(FontWeight.Normal, 24),
    H1Bold(FontWeight.Bold, 24),
    LargeBodyRegular(FontWeight.Normal, 18),
    LargeBodyBold(FontWeight.Bold, 18),
    BodyRegular(FontWeight.Normal, 16),
    BodyMedium(FontWeight.Medium, 16),
    BodyBold(FontWeight.Bold, 16),
    BodySmallRegular(FontWeight.Normal, 14),
    BodySmallSemiBold(FontWeight.SemiBold, 14),
    BodySmallBold(FontWeight.Bold, 14),
    BodyExtraSmall(FontWeight.Normal, 12),
    Caption(FontWeight.Normal, 12),
    ;

    /** Letter spacing (Figma gives Caption 6 %). */
    public val letterSpacingEm: Float get() = if (this == Caption) 0.06f else 0f

    public val textStyle: TextStyle
        get() = TextStyle(
            fontFamily = GuggenheimSansDisplay,
            fontWeight = weight,
            fontSize = sizeSp.sp,
            letterSpacing = letterSpacingEm.em,
        )
}
