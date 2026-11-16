package io.proximi.gadkit

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import java.net.HttpURLConnection
import java.net.URI
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/*
 * Sends the field log to the relay-api of the build (operator, 2026-11-16: the logs of every tester
 * on site are collected), as iOS `FieldLogUpload.swift` (1.49.0). The app sends the lines written
 * since the last accepted upload to `POST <relay>/v1/app-logs` with the relay app token, every
 * `interval`, 10 s after the start and when the app goes to the background. A chunk that fails is
 * sent again unchanged, with the same sequence number, so the relay stores it once. The log goes
 * only to the relay base URL the host app set: a production build sends it to the client's relay.
 */

/**
 * Where the upload is in the log, as iOS `FieldLogUploadState`. Positions count bytes from the start
 * of the log, so they hold when [FieldLog] cuts the head of the file. Read and written on
 * [FieldLog.writer].
 */
internal class FieldLogUploadState(private val store: Store) {
    /** The stored numbers and the install id. */
    internal interface Store {
        fun long(key: String): Long

        fun set(key: String, value: Long)

        fun text(key: String): String?

        fun set(key: String, value: String)
    }

    /** A chunk of the log: its sequence number and bytes. */
    internal class Chunk(val sequence: Long, val data: ByteArray)

    /** Bytes [FieldLog] cut from the head of the file since the install. */
    fun addRemoved(count: Long) {
        store.set(REMOVED, store.long(REMOVED) + count)
    }

    /** A random id of this install. */
    fun install(): String = store.text(INSTALL) ?: UUID.randomUUID().toString().lowercase().also { store.set(INSTALL, it) }

    /**
     * The next chunk, or `null` when every line is sent. A chunk that was sent and not accepted
     * comes again with the same bytes.
     */
    fun nextChunk(file: ByteArray, limit: Int): Chunk? {
        val removed = store.long(REMOVED)
        var uploaded = store.long(UPLOADED)
        var pending = store.long(PENDING)
        // The file is shorter than the place of the upload: it was deleted. Start at its head.
        if (uploaded > removed + file.size) {
            uploaded = removed
            pending = 0
        }
        // The head of the chunk was cut from the file: those lines are gone.
        if (uploaded < removed) {
            uploaded = removed
            pending = 0
        }
        store.set(UPLOADED, uploaded)
        val range = chunkRange(file, (uploaded - removed).toInt(), pending.toInt(), limit)
        if (range == null) {
            store.set(PENDING, 0)
            return null
        }
        store.set(PENDING, (range.last - range.first + 1).toLong())
        return Chunk(store.long(SEQUENCE), file.copyOfRange(range.first, range.last + 1))
    }

    /** The relay stored the chunk of [count] bytes. */
    fun accepted(count: Int) {
        store.set(UPLOADED, store.long(UPLOADED) + count)
        store.set(SEQUENCE, store.long(SEQUENCE) + 1)
        store.set(PENDING, 0)
    }

    /** The relay refused the chunk for its size: the next one is cut again. */
    fun forgetPending() {
        store.set(PENDING, 0)
    }

    internal companion object {
        private const val REMOVED = "removedBytes"
        private const val UPLOADED = "uploadedBytes"
        private const val SEQUENCE = "sequence"
        private const val PENDING = "pendingLength"
        private const val INSTALL = "install"
        private const val NEWLINE = '\n'.code.toByte()

        /**
         * The byte range of the next chunk in [file]: [pending] bytes when a chunk waits, else up
         * to [limit] bytes that end with a line. A line longer than [limit] is sent whole.
         */
        fun chunkRange(file: ByteArray, offset: Int, pending: Int, limit: Int): IntRange? {
            val count = file.size
            if (offset < 0 || offset >= count) return null
            if (pending > 0 && offset + pending <= count) return offset until offset + pending
            val end = minOf(count.toLong(), offset.toLong() + maxOf(1, limit)).toInt()
            val newline = (end - 1 downTo offset).firstOrNull { file[it] == NEWLINE }
            if (newline != null) return offset..newline
            // Only whole lines: the last line may still be written.
            if (end == count) return null
            // One line longer than the limit.
            val next = (end until count).firstOrNull { file[it] == NEWLINE }
            return offset until (next?.plus(1) ?: count)
        }

        /** The store in the app's preferences `io.proximi.gadkit.fieldlog`. */
        fun store(context: Context): Store = PreferencesStore(
            context.applicationContext.getSharedPreferences("io.proximi.gadkit.fieldlog", Context.MODE_PRIVATE),
        )
    }

    private class PreferencesStore(private val preferences: SharedPreferences) : Store {
        override fun long(key: String): Long = preferences.getLong(key, 0)

        override fun set(key: String, value: Long) = preferences.edit().putLong(key, value).apply()

        override fun text(key: String): String? = preferences.getString(key, null)

        override fun set(key: String, value: String) = preferences.edit().putString(key, value).apply()
    }
}

/** The uploads of the field log, as iOS `FieldLogUploader`. */
internal object FieldLogUploader {
    private const val FIRST_CHUNK_BYTES = 512_000
    private const val SMALLEST_CHUNK_BYTES = 16_000
    private const val CHUNKS_PER_RUN = 8
    private const val TIMEOUT_MILLIS = 30_000

    internal enum class Outcome { STORED, TOO_LARGE, REFUSED, RETRY }

    private class Target(val context: Context, val url: URI, val appToken: String)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sending = Mutex()
    private val observes = AtomicBoolean(false)

    @Volatile
    private var target: Target? = null
    private var loop: Job? = null

    /** Bytes per request. Halved when the relay answers 413. */
    @Volatile
    private var chunkBytes = FIRST_CHUNK_BYTES

    @Synchronized
    fun start(context: Context, relayUrl: URI, appToken: String, interval: Duration) {
        stop()
        if (!FieldLog.isEnabled || interval <= Duration.ZERO || appToken.isEmpty()) return
        target = Target(context.applicationContext, URI(relayUrl.scheme, relayUrl.authority, "/v1/app-logs", null, null), appToken)
        FieldLog.log("log upload: on · every ${interval.inWholeSeconds} s · ${relayUrl.host ?: "-"}")
        loop = scope.launch {
            // The first upload sends what a run that was killed left behind.
            delay(10.seconds)
            while (isActive) {
                send()
                delay(interval)
            }
        }
        if (observes.compareAndSet(false, true)) {
            Handler(Looper.getMainLooper()).post {
                ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                    // The app went to the background.
                    override fun onStop(owner: LifecycleOwner) {
                        scope.launch { send() }
                    }
                })
            }
        }
    }

    @Synchronized
    fun stop() {
        loop?.cancel()
        loop = null
        target = null
    }

    /** Sends the chunks that wait, at most [CHUNKS_PER_RUN] per call. */
    suspend fun send() {
        if (!sending.tryLock()) return
        try {
            repeat(CHUNKS_PER_RUN) {
                val target = target ?: return
                val file = FieldLog.file ?: return
                val state = FieldLogUploadState(FieldLogUploadState.store(target.context))
                val writer = FieldLog.writer.value.asCoroutineDispatcher()
                val limit = chunkBytes
                val chunk = withContext(writer) {
                    state.nextChunk(runCatching { file.readBytes() }.getOrDefault(ByteArray(0)), limit)
                } ?: return
                val status = try {
                    post(target, headers(target.context, chunk.sequence, target.appToken, state.install()), chunk.data)
                } catch (error: Exception) {
                    FieldLog.log("log upload: ${chunk.sequence} failed: ${error.message ?: error.javaClass.simpleName}")
                    return
                }
                when (outcome(status)) {
                    Outcome.STORED -> withContext(writer) { state.accepted(chunk.data.size) }
                    Outcome.TOO_LARGE -> {
                        chunkBytes = maxOf(SMALLEST_CHUNK_BYTES, chunkBytes / 2)
                        withContext(writer) { state.forgetPending() }
                        FieldLog.log("log upload: ${chunk.sequence} too large · next chunks up to $chunkBytes bytes")
                    }
                    Outcome.REFUSED -> {
                        FieldLog.log("log upload: ${chunk.sequence} refused with $status · off until the next start")
                        // Not `stop()`: it would cancel this call when the loop made it.
                        this.target = null
                        return
                    }
                    Outcome.RETRY -> {
                        FieldLog.log("log upload: ${chunk.sequence} answered $status")
                        return
                    }
                }
            }
        } finally {
            sending.unlock()
        }
    }

    private fun post(target: Target, headers: Map<String, String>, body: ByteArray): Int {
        val connection = target.url.toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = TIMEOUT_MILLIS
            connection.readTimeout = TIMEOUT_MILLIS
            connection.doOutput = true
            connection.useCaches = false
            headers.forEach { (field, value) -> connection.setRequestProperty(field, value) }
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            return connection.responseCode
        } finally {
            connection.disconnect()
        }
    }

    /** What the app does with the relay's answer. 409: the relay already holds this sequence number. */
    fun outcome(status: Int): Outcome = when (status) {
        in 200..299, 409 -> Outcome.STORED
        413 -> Outcome.TOO_LARGE
        401, 403 -> Outcome.REFUSED
        else -> Outcome.RETRY
    }

    /** The headers of an upload. The app token goes to the relay only, as on `GET /v1/meta`. */
    fun headers(context: Context, sequence: Long, appToken: String, install: String): Map<String, String> {
        val build = runCatching {
            PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0)).toString()
        }.getOrDefault("-")
        return headers(sequence, appToken, install, context.packageName, build)
    }

    fun headers(sequence: Long, appToken: String, install: String, app: String, build: String): Map<String, String> = mapOf(
        "Authorization" to "Bearer $appToken",
        "Content-Type" to "text/plain; charset=utf-8",
        "X-Log-Install" to install,
        "X-Log-Seq" to sequence.toString(),
        "X-Log-Platform" to "android",
        "X-Log-App" to app,
        "X-Log-App-Build" to build,
        "X-Log-GADKit" to FieldLog.PACKAGE_VERSION,
    )
}
