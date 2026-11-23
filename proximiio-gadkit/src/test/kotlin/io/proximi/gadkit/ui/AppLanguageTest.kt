package io.proximi.gadkit.ui

import io.proximi.gadkit.GadSettings
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** iOS `AppLanguageTests` (1.72.0): `GadSettings.language` goes before the host app's locale. */
class AppLanguageTest {
    @AfterTest
    fun reset() {
        GadSettings.language = null
    }

    @Test
    fun theLanguageTheHostAppSetsGoesFirst() {
        assertEquals("ar", AppLanguage.resolved(fixed = "ar", host = "en"))
        assertEquals("en", AppLanguage.resolved(fixed = "en", host = "ar"))
        assertEquals("ar", AppLanguage.resolved(fixed = null, host = "ar"))
        assertEquals("en", AppLanguage.resolved(fixed = null, host = null))
        // The value is "ar" or "en" for any identifier.
        assertEquals("ar", AppLanguage.code("ar-AE"))
        assertEquals("ar", AppLanguage.code("AR"))
        assertEquals("en", AppLanguage.code("fr"))
        assertEquals("en", AppLanguage.code(null))
    }

    @Test
    fun theSettingIsOneValueForTheProcess() {
        assertNull(GadSettings.language)
        assertEquals("en-GB", AppLanguage.tag("en-GB"))
        GadSettings.language = "ar-AE"
        assertEquals("ar-AE", GadSettings.language)
        assertEquals("ar", AppLanguage.fixed)
        assertEquals("ar", AppLanguage.tag("en-GB"))
        GadSettings.language = "fr"
        assertEquals("en", AppLanguage.tag("ar"))
        GadSettings.language = null
        assertNull(AppLanguage.fixed)
        assertEquals("ar", AppLanguage.tag("ar"))
    }
}
