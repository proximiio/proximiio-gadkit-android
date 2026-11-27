package io.proximi.gadkit

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import io.proximi.gadkit.wristband.BindingFieldLog
import io.proximi.gadkit.wristband.BindingLog
import io.proximi.sdk.blueiot.binding.BlueiotBindingSession
import io.proximi.sdk.blueiot.binding.BlueiotWristbandBinding
import java.io.File
import java.net.URI
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A text log of positions, tracking health, geofence, journey and binding events for field tests,
 * as iOS `FieldLog`. It is on by default since 1.49.0: `ProximiMap` opens the file when it is
 * created, and the host app turns the log off with [isEnabled]. The file is
 * `files/fieldlog/gad-debug.log` in app-private storage; [share] hands it to the share sheet through
 * the FileProvider `<applicationId>.gadkit.fieldlog`, and [startUploading] sends it to the relay-api
 * (`FieldLogUpload.kt`). Each line also goes to Logcat (tag `GAD`).
 *
 * A line is `[GAD] <time stamp> <message>`. The time stamp is ISO 8601 in UTC with the date and
 * milliseconds, for example `2026-10-19T12:52:10.123Z` ([TIME_STAMP]).
 *
 * The secrets passed to [start] are replaced by `<redacted>`. The file is cut to its last
 * [KEEP_BYTES] when it grows past [MAX_BYTES]. While it is off, every call returns at once.
 */
@Suppress("StaticFieldLeak") // The application context.
public object FieldLog {
    private const val MAX_BYTES = 8_000_000L
    private const val KEEP_BYTES = 4_000_000
    private const val FILE_NAME = "gad-debug.log"
    private const val TAG = "GAD"

    /** The version of the library, sent with each upload. iOS `FieldLog.packageVersion`. */
    public const val PACKAGE_VERSION: String = "1.76.0"

    /** The thread of every read and write of the file and of the place of the upload. */
    internal val writer = lazy { Executors.newSingleThreadExecutor { Thread(it, "gad-field-log").apply { isDaemon = true } } }

    /** The log file, `files/fieldlog/gad-debug.log`, after [start]; `null` before. iOS `FieldLog.url`. */
    @Volatile
    public var file: File? = null
        private set
    @Volatile
    private var secrets: List<String> = emptyList()

    /** The application context, after [start]. */
    @Volatile
    internal var context: Context? = null
        private set

    /**
     * Whether [log] writes. `true` by default since 1.49.0. To turn the log off, set it to `false`
     * once at launch, before the host app creates its `ProximiMap`. The log writes after its file
     * is open: `ProximiMap` opens it when it is created, a host app without `ProximiMap` calls
     * [start] or [startUploading].
     */
    @Volatile
    public var isEnabled: Boolean = true

    /**
     * Turns the log on, opens the file and writes the header of a new session: the host app's
     * version, its version code and the date. Call it at launch, before `ProximiMap.start`.
     * [secrets] are the tokens to redact, such as the application token.
     */
    public fun start(context: Context, secrets: List<String>) {
        this.secrets = secrets.filter { it.isNotEmpty() }
        this.context = context.applicationContext
        file = File(File(context.filesDir, "fieldlog").apply { mkdirs() }, FILE_NAME)
        isEnabled = true
        val now = OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS)
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        log("launch ${info.versionName} (${PackageInfoCompat.getLongVersionCode(info)}) $now")
        // The device model and the system version (iOS 1.52.0).
        log("device ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
    }

    /**
     * Opens the file as [start] does, unless the host app turned the log off or the file is open.
     * With an open file it adds [secrets] to the tokens to redact.
     */
    @Synchronized
    internal fun ensureStarted(context: Context, secrets: List<String>) {
        if (!isEnabled) return
        if (file == null) start(context, secrets) else this.secrets = (this.secrets + secrets.filter { it.isNotEmpty() }).distinct()
    }

    /**
     * Starts sending the log to the relay-api of [binding], with its app token: `POST /v1/app-logs`
     * 10 s after the call, every [interval], and when the app goes to the background. For a host
     * app on `ProximiMapView`: call it once, after the app created its `BlueiotWristbandBinding`.
     * `ProximiMap` calls it in `start()` with `GadSettings.fieldLogUploadInterval`. A second call
     * replaces the first. Does nothing while [isEnabled] is `false`. Opens the log file when it is
     * not open. iOS `FieldLog.startUploading(binding:interval:)`.
     *
     * Since 1.50.1 it also writes the lines of [binding] to the log: its states, sessions,
     * diagnostics, the relay stream and one relay position every 5 s. The relay-api links the log to
     * the wristband through them.
     *
     * @param interval the time between uploads. Zero or less sends nothing.
     */
    public fun startUploading(context: Context, binding: BlueiotWristbandBinding, interval: Duration = 900.seconds) {
        startUploading(context, binding.configuration.relayURL.baseUrl, binding.configuration.appToken, interval)
        BindingFieldLog.follow(binding, logsStates = true)
    }

    /** Starts sending the log to [relayUrl] (scheme and host of the relay-api) with [appToken]. */
    public fun startUploading(context: Context, relayUrl: URI, appToken: String, interval: Duration = 900.seconds) {
        ensureStarted(context, listOf(appToken))
        FieldLogUploader.start(context.applicationContext, relayUrl, appToken, interval)
    }

    /** Stops the uploads. The log file stays. iOS `FieldLog.stopUploading()`. */
    public fun stopUploading() {
        FieldLogUploader.stop()
    }

    /**
     * Binds this phone to the band with [tagID] through [binding], as `binding.bind(tagID)` does,
     * and writes the attempt to the field log (iOS `FieldLog.bind(tagID:with:)`, 1.72.0):
     *
     *     wristband bind: tag <label> · started
     *     wristband bind: tag <label> · binding <id>
     *     wristband bind: tag <label> · failed: <error>
     *
     * The first line is written before the request, so an attempt that does not return, or that
     * the app cancels, is in the log too. A host app that binds itself calls this in place of
     * `binding.bind(tagID)`.
     *
     * @return the session of the bind.
     * @throws io.proximi.sdk.blueiot.binding.BlueiotBindingError what `binding.bind(tagID)` throws.
     */
    public suspend fun bind(tagID: String, binding: BlueiotWristbandBinding): BlueiotBindingSession =
        logged(tagID) { binding.bind(tagID = tagID) }

    /** The lines of [bind] around [bind]. A cancelled bind is logged and thrown on. Covered by tests. */
    internal suspend fun logged(tagID: String, bind: suspend () -> BlueiotBindingSession): BlueiotBindingSession {
        log("wristband bind: ${BindingLog.bindStarted(tagID)}")
        try {
            val session = bind()
            log("wristband bind: ${BindingLog.bind(tagID, session.bindingID)}")
            return session
        } catch (error: Throwable) {
            log("wristband bind: ${BindingLog.bind(tagID, error)}")
            throw error
        }
    }

    /** Appends one line with a time stamp. */
    @SuppressLint("LogNotTimber")
    public fun log(message: String) {
        if (!isEnabled) return
        // Before the file is open nothing is written, also not to Logcat.
        val file = file ?: return
        val text = redact(message, secrets)
        val line = "[GAD] ${TIME_STAMP.format(Instant.now())} $text\n"
        Log.i(TAG, text)
        writer.value.execute { append(file, line) }
    }

    /** Appends the line [message] builds, without building it while the log is off. */
    public inline fun log(message: () -> String) {
        if (isEnabled) log(message())
    }

    /** Opens the share sheet with the log, for the host app's "Share debug log" button. */
    public fun share(context: Context) {
        if (!isEnabled) return
        share(context, file?.takeIf { it.isFile } ?: return)
    }

    /**
     * Opens the share sheet with [file], a text file in the log's folder ([file]'s parent), for
     * example an excerpt of the log.
     */
    public fun share(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.gadkit.fieldlog", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, file.name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(file.name, uri)
        try {
            context.startActivity(Intent.createChooser(send, null))
        } catch (_: ActivityNotFoundException) {
            log("share: no app")
        }
    }

    /** [message] with every non-empty secret replaced by `<redacted>`. */
    internal fun redact(message: String, secrets: List<String>): String =
        secrets.filter { it.isNotEmpty() }.fold(message) { line, secret -> line.replace(secret, "<redacted>") }

    private fun append(file: File, line: String) {
        runCatching {
            file.appendText(line)
            if (file.length() > MAX_BYTES) truncate(file)
        }
    }

    private fun truncate(file: File) {
        val data = file.readBytes()
        if (data.size <= KEEP_BYTES) return
        var start = data.size - KEEP_BYTES
        val newline = (start until data.size).firstOrNull { data[it] == '\n'.code.toByte() }
        if (newline != null) start = newline + 1
        file.writeBytes(data.copyOfRange(start, data.size))
        // The upload counts its place from the start of the log, not of the file.
        context?.let { FieldLogUploadState(FieldLogUploadState.store(it)).addRemoved(start.toLong()) }
    }

    /** The time stamp of a line: ISO 8601 in UTC with the date and milliseconds, as iOS `FieldLog.timeStamp`. */
    public val TIME_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).withZone(ZoneOffset.UTC)
}
