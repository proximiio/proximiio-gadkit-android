package io.proximi.gadkit.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Port of ProximiioGADKit `MapDiagnosticsTests`: the field log's map state and map timing lines. */
class MapDiagnosticsTest {
    @Test
    fun theLineNamesTheMapStateAndTheZoomIsRoundedToAHalf() {
        assertEquals(20.5, MapDiagnostics.Snapshot.rounded(20.74))
        assertEquals(21.0, MapDiagnostics.Snapshot.rounded(20.76))
        assertNull(MapDiagnostics.Snapshot.rounded(null))
        val snapshot = MapDiagnostics.Snapshot(
            features = 968, dropped = 16, error = "16 features left out", style = "cache",
            level = 1.0, zoom = 19.0, isCameraReady = true, name = "journey",
        )
        assertEquals("map state journey: features 968 · dropped 16 · style cache · L1 · zoom 19.0 · camera ready true · error 16 features left out", snapshot.line)
    }

    /** Map 6.0.0-beta.29 names the style's fonts the glyph server does not have. */
    @Test
    fun theLineNamesTheMissingFonts() {
        val snapshot = MapDiagnostics.Snapshot(
            features = 968, dropped = 0, error = null, style = "organization", level = 1.0, zoom = 20.0, isCameraReady = true,
            missingFonts = listOf("Guggenheim Sans Bold", "Open Sans Bold"), name = "museum",
        )
        assertEquals("map state museum: features 968 · dropped 0 · style organization · L1 · zoom 20.0 · camera ready true · missing fonts Guggenheim Sans Bold, Open Sans Bold", snapshot.line)
    }

    /** The map shows the venue when its first venue style, its features and the camera are all there (user, 2026-10-28). */
    @Test
    fun theTimingNamesEachStepAndTheDrawnFrame() {
        var timing = MapDiagnostics.Timing(
            name = "explore",
            styles = listOf(MapDiagnostics.Timing.Style("fallback", false, 0.1), MapDiagnostics.Timing.Style("cache", true, 0.412)),
            features = 968,
            featuresSeconds = 2.104,
        )
        assertNull(timing.shownSeconds)
        assertEquals("map timing explore: style fallback 0.10 s · style cache 0.41 s · features 968 2.10 s · closed 2.50 s before drawn", timing.line(2.5))
        timing = timing.copy(cameraSeconds = 1.9)
        assertEquals(2.104, timing.shownSeconds)
        assertEquals("map timing explore: style fallback 0.10 s · style cache 0.41 s · features 968 2.10 s · camera ready 1.90 s · shown 2.10 s · closed 2.50 s before drawn", timing.line(2.5))
        assertEquals("map timing journey: closed 0.05 s before drawn", MapDiagnostics.Timing(name = "journey").line(0.05))
        timing = timing.copy(drawnSeconds = 2.6, openSeconds = 1.02)
        assertTrue(timing.line().endsWith(" · shown 2.10 s · drawn 2.60 s, 3.62 s after open()"), timing.line())
    }
}
