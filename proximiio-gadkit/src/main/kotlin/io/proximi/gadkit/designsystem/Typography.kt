package io.proximi.gadkit.designsystem

import io.proximi.gadkit.ProximiGadAppApi
import io.proximi.gadkit.R
import io.proximi.gadkit.ui.AppLanguage
import androidx.annotation.FontRes
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * Guggenheim Sans Display. It has no Arabic glyphs. While the library's language is Arabic, the
 * text styles use [GuggenheimArabic] (iOS 1.73.0); up to 1.72.0 Android drew Arabic text in the
 * system Arabic font.
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
 * Guggenheim Arabic (BETA v2), for Arabic text. It holds the Latin glyphs and digits of
 * [GuggenheimSansDisplay] with the same advance widths, and has a line gap between the lines of a
 * text (iOS 1.73.0).
 */
@ProximiGadAppApi
public val GuggenheimArabic: FontFamily = FontFamily(
    Font(R.font.guggenheim_arabic_light, FontWeight.Light),
    Font(R.font.guggenheim_arabic_regular, FontWeight.Normal),
    Font(R.font.guggenheim_arabic_medium, FontWeight.Medium),
    Font(R.font.guggenheim_arabic_semibold, FontWeight.SemiBold),
    Font(R.font.guggenheim_arabic_bold, FontWeight.Bold),
)

/** The library's font by language, as iOS `GADFonts.name(_:language:)`. Pure; covered by tests. */
internal object GadFonts {
    /** [GuggenheimArabic] for "ar", else [GuggenheimSansDisplay]. */
    fun family(language: String): FontFamily = if (language == "ar") GuggenheimArabic else GuggenheimSansDisplay

    /** The font file of [weight] in [language], for text drawn on a canvas. */
    @FontRes
    fun resource(weight: FontWeight, language: String): Int = if (language == "ar") {
        when (weight) {
            FontWeight.Light -> R.font.guggenheim_arabic_light
            FontWeight.Medium -> R.font.guggenheim_arabic_medium
            FontWeight.SemiBold -> R.font.guggenheim_arabic_semibold
            FontWeight.Bold -> R.font.guggenheim_arabic_bold
            else -> R.font.guggenheim_arabic_regular
        }
    } else {
        when (weight) {
            FontWeight.Light -> R.font.guggenheim_sans_display_light
            FontWeight.Medium -> R.font.guggenheim_sans_display_medium
            FontWeight.SemiBold -> R.font.guggenheim_sans_display_semibold
            FontWeight.Bold -> R.font.guggenheim_sans_display_bold
            else -> R.font.guggenheim_sans_display_regular
        }
    }
}

/**
 * Text styles from the Figma "Typography" section (node 6902:46986), as in iOS `Typography.swift`.
 * Caption has 6 % letter spacing. The line height is the font's own (ascent + descent, 1.21 × the
 * size), as on iOS and in the Figma text layers ("leading normal"). Sizes are in sp, so every style
 * scales with the system font size. In Arabic every style uses [GuggenheimArabic] in its weight.
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

    /** The style in the library's language. A composition that reads it runs again when the language changes. */
    public val textStyle: TextStyle
        get() = TextStyle(
            fontFamily = GadFonts.family(AppLanguage.current),
            fontWeight = weight,
            fontSize = sizeSp.sp,
            letterSpacing = letterSpacingEm.em,
        )
}
