package io.proximi.gadkit

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class FieldLogTest {
    @Test
    fun redactsEveryToken() {
        val line = FieldLog.redact("start failed: 401 for token abc123 and relay r-9 (abc123)", listOf("abc123", "r-9"))
        assertEquals("start failed: 401 for token <redacted> and relay <redacted> (<redacted>)", line)
    }

    /** iOS 1.4.2: ISO 8601 in UTC with the date and milliseconds. */
    @Test
    fun timeStampsHaveTheDateInUtc() {
        assertEquals("2026-10-19T12:52:10.120Z", FieldLog.TIME_STAMP.format(Instant.parse("2026-10-19T12:52:10.12Z")))
    }

    @Test
    fun ignoresEmptyTokens() {
        assertEquals("wristband: unbound", FieldLog.redact("wristband: unbound", listOf("", "")))
    }
}
