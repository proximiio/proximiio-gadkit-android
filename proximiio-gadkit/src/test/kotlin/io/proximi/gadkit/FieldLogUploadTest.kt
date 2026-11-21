package io.proximi.gadkit

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** iOS `FieldLogUploadTests` (1.49.0). */
class FieldLogUploadTest {
    private class MemoryStore : FieldLogUploadState.Store {
        private val numbers = mutableMapOf<String, Long>()
        private val texts = mutableMapOf<String, String>()

        override fun long(key: String): Long = numbers[key] ?: 0

        override fun set(key: String, value: Long) {
            numbers[key] = value
        }

        override fun text(key: String): String? = texts[key]

        override fun set(key: String, value: String) {
            texts[key] = value
        }
    }

    private fun bytes(text: String) = text.toByteArray()

    private fun range(file: ByteArray, offset: Int, limit: Int) = FieldLogUploadState.chunkRange(file, offset, pending = 0, limit = limit)

    @Test
    fun aChunkEndsWithAWholeLine() {
        val file = bytes("one\ntwo\nthr")
        assertEquals(0..7, range(file, 0, 100))
        assertEquals(0..3, range(file, 0, 5))
        // Only an unfinished line is left.
        assertNull(range(file, 8, 100))
        assertNull(range(file, 11, 100))
    }

    @Test
    fun aLineLongerThanTheLimitIsSentWhole() {
        assertEquals(0..11, range(bytes("a long line\nnext\n"), 0, 4))
    }

    @Test
    fun aChunkThatFailedComesAgainUnchanged() {
        val state = FieldLogUploadState(MemoryStore())
        var file = bytes("one\ntwo\n")
        val first = assertNotNull(state.nextChunk(file, 100))
        assertEquals(0, first.sequence)
        assertContentEquals(bytes("one\ntwo\n"), first.data)
        // More lines are written before the retry.
        file += bytes("three\n")
        val retry = assertNotNull(state.nextChunk(file, 100))
        assertEquals(0, retry.sequence)
        assertContentEquals(first.data, retry.data)
        state.accepted(retry.data.size)
        val second = assertNotNull(state.nextChunk(file, 100))
        assertEquals(1, second.sequence)
        assertContentEquals(bytes("three\n"), second.data)
        state.accepted(second.data.size)
        assertNull(state.nextChunk(file, 100))
    }

    @Test
    fun aChunkRefusedForItsSizeIsCutAgainWithTheSameSequence() {
        val state = FieldLogUploadState(MemoryStore())
        val file = bytes("one\ntwo\n")
        assertContentEquals(file, assertNotNull(state.nextChunk(file, 100)).data)
        state.forgetPending()
        val smaller = assertNotNull(state.nextChunk(file, 4))
        assertEquals(0, smaller.sequence)
        assertContentEquals(bytes("one\n"), smaller.data)
    }

    @Test
    fun theUploadGoesOnAfterTheHeadOfTheFileIsCut() {
        val state = FieldLogUploadState(MemoryStore())
        val first = assertNotNull(state.nextChunk(bytes("one\ntwo\nthree\n"), 4))
        assertContentEquals(bytes("one\n"), first.data)
        state.accepted(4)
        // `FieldLog` cut "one\ntwo\n": "two" was never sent and is gone.
        state.addRemoved(8)
        val cut = bytes("three\nfour\n")
        val next = assertNotNull(state.nextChunk(cut, 100))
        assertEquals(1, next.sequence)
        assertContentEquals(cut, next.data)
    }

    @Test
    fun aDeletedFileStartsAtItsHead() {
        val state = FieldLogUploadState(MemoryStore())
        state.nextChunk(bytes("one\ntwo\n"), 100)
        state.accepted(8)
        val fresh = bytes("new\n")
        assertContentEquals(fresh, state.nextChunk(fresh, 100)?.data)
    }

    /**
     * Production hosts, 2026-11-21: Cloudflare answers some requests with 403 and
     * `cf-mitigated: challenge`. The uploads stay on (iOS 1.66.3).
     */
    @Test
    fun aProxyChallengeIsNotTheRelaysRefusal() {
        assertEquals(true, FieldLogUploader.isProxyChallenge("challenge", "text/html; charset=UTF-8"))
        assertEquals(true, FieldLogUploader.isProxyChallenge(null, "Text/HTML"))
        assertEquals(false, FieldLogUploader.isProxyChallenge(null, "application/json"))
        assertEquals(false, FieldLogUploader.isProxyChallenge(null, null))
        assertEquals(false, FieldLogUploader.isProxyChallenge("", null))
        assertEquals(FieldLogUploader.Outcome.RETRY, FieldLogUploader.outcome(403, isProxyChallenge = true))
        assertEquals(FieldLogUploader.Outcome.RETRY, FieldLogUploader.outcome(401, isProxyChallenge = true))
        assertEquals(FieldLogUploader.Outcome.REFUSED, FieldLogUploader.outcome(403, isProxyChallenge = false))
    }

    @Test
    fun theRelaysAnswerDecidesTheNextStep() {
        assertEquals(FieldLogUploader.Outcome.STORED, FieldLogUploader.outcome(204))
        assertEquals(FieldLogUploader.Outcome.STORED, FieldLogUploader.outcome(409))
        assertEquals(FieldLogUploader.Outcome.TOO_LARGE, FieldLogUploader.outcome(413))
        assertEquals(FieldLogUploader.Outcome.REFUSED, FieldLogUploader.outcome(401))
        assertEquals(FieldLogUploader.Outcome.REFUSED, FieldLogUploader.outcome(403))
        assertEquals(FieldLogUploader.Outcome.RETRY, FieldLogUploader.outcome(404))
        assertEquals(FieldLogUploader.Outcome.RETRY, FieldLogUploader.outcome(500))
        assertEquals(FieldLogUploader.Outcome.RETRY, FieldLogUploader.outcome(0))
    }

    @Test
    fun theUploadCarriesTheInstallAndTheSequence() {
        val state = FieldLogUploadState(MemoryStore())
        val install = state.install()
        assertEquals(install, state.install())
        assertEquals(install.lowercase(), install)
        val headers = FieldLogUploader.headers(sequence = 7, appToken = "t", install = install, app = "io.proximi.gad", build = "2026111601")
        assertEquals("Bearer t", headers["Authorization"])
        assertEquals("text/plain; charset=utf-8", headers["Content-Type"])
        assertEquals("7", headers["X-Log-Seq"])
        assertEquals("android", headers["X-Log-Platform"])
        assertEquals("io.proximi.gad", headers["X-Log-App"])
        assertEquals("2026111601", headers["X-Log-App-Build"])
        assertEquals(FieldLog.PACKAGE_VERSION, headers["X-Log-GADKit"])
        assertEquals(install, headers["X-Log-Install"])
    }

    /** The version sent with each upload is the version of the release. */
    @Test
    fun thePackageVersionIsTheVersionOfTheBuild() {
        val properties = listOf(File("gradle.properties"), File("../gradle.properties")).first { it.isFile && "gadkit.version=" in it.readText() }
        assertTrue("gadkit.version=${FieldLog.PACKAGE_VERSION}" in properties.readText().lines())
    }
}
