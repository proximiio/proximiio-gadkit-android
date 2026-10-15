package io.proximi.gadkit

import kotlin.test.Test
import kotlin.test.assertEquals

class FieldLogTest {
    @Test
    fun redactsEveryToken() {
        val line = FieldLog.redact("start failed: 401 for token abc123 and relay r-9 (abc123)", listOf("abc123", "r-9"))
        assertEquals("start failed: 401 for token <redacted> and relay <redacted> (<redacted>)", line)
    }

    @Test
    fun ignoresEmptyTokens() {
        assertEquals("wristband: unbound", FieldLog.redact("wristband: unbound", listOf("", "")))
    }
}
