package io.proximi.gadkit.visit

import io.proximi.gadkit.DebugLaunch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Port of ProximiioGADKit `JourneyKindTests`. */
class JourneyKindTest {
    @Test
    fun personalisedShowsGalleriesAndAllowsSkip() {
        assertTrue(JourneyKind.PERSONALISED.guidesToGalleryFirst)
        assertTrue(JourneyKind.PERSONALISED.listsGalleries)
        assertTrue(JourneyKind.PERSONALISED.allowsSkip)
    }

    @Test
    fun curatedGoesStraightToTheArtworkWithoutSkip() {
        assertFalse(JourneyKind.CURATED.guidesToGalleryFirst)
        assertFalse(JourneyKind.CURATED.listsGalleries)
        assertFalse(JourneyKind.CURATED.allowsSkip)
    }

    @Test
    fun storedMetaWithoutKindIsPersonalised() {
        val old = VisitStoreCodec.decodeMeta("""{"title":"Light & Perception","summary":"8 stops"}""")
        assertEquals(JourneyKind.PERSONALISED, old?.kind)
        val curated = VisitMeta("Your journey", "3 stops", JourneyKind.CURATED)
        assertEquals(curated, VisitStoreCodec.decodeMeta(VisitStoreCodec.encodeMeta(curated)))
    }

    @Test
    fun launchArgumentSetsTheKind() {
        assertEquals(JourneyKind.CURATED, JourneyKind.of("curated"))
        assertEquals(JourneyKind.PERSONALISED, JourneyKind.of(null))
        assertEquals(JourneyKind.PERSONALISED, JourneyKind.of("guided"))
        assertEquals(JourneyKind.PERSONALISED, DebugLaunch.Options(null, 1.0, false, false, null, false).visitKind)
    }
}
