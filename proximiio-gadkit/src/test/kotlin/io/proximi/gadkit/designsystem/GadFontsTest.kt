package io.proximi.gadkit.designsystem

import androidx.compose.ui.text.font.FontWeight
import io.proximi.gadkit.GadSettings
import io.proximi.gadkit.R
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** iOS `DesignSystemTests` (1.73.0): Arabic text uses Guggenheim Arabic in the weight of the design. */
class GadFontsTest {
    @AfterTest
    fun reset() {
        GadSettings.language = null
    }

    @Test
    fun onlyArabicChangesTheFont() {
        assertEquals(GuggenheimArabic, GadFonts.family("ar"))
        assertEquals(GuggenheimSansDisplay, GadFonts.family("en"))
    }

    @Test
    fun everyWeightHasAnArabicFontFile() {
        val arabic = mapOf(
            FontWeight.Light to R.font.guggenheim_arabic_light,
            FontWeight.Normal to R.font.guggenheim_arabic_regular,
            FontWeight.Medium to R.font.guggenheim_arabic_medium,
            FontWeight.SemiBold to R.font.guggenheim_arabic_semibold,
            FontWeight.Bold to R.font.guggenheim_arabic_bold,
        )
        for (style in GadTextStyle.entries) {
            assertEquals(arabic[style.weight], GadFonts.resource(style.weight, "ar"), style.name)
        }
        assertEquals(R.font.guggenheim_sans_display_semibold, GadFonts.resource(FontWeight.SemiBold, "en"))
        assertEquals(R.font.guggenheim_sans_display_regular, GadFonts.resource(FontWeight.Normal, "en"))
    }

    @Test
    fun everyTextStyleFollowsTheLanguageTheHostAppSets() {
        GadSettings.language = "ar"
        for (style in GadTextStyle.entries) assertEquals(GuggenheimArabic, style.textStyle.fontFamily, style.name)
        GadSettings.language = "en"
        for (style in GadTextStyle.entries) assertEquals(GuggenheimSansDisplay, style.textStyle.fontFamily, style.name)
    }
}
