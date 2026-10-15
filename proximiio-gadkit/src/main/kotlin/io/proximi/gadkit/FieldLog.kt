package io.proximi.gadkit

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.concurrent.Executors

/**
 * A text log of positions, tracking health, geofence, journey and binding events for field tests,
 * as iOS `FieldLog`. It is off until the host app calls [start], in test builds only. The file is
 * `files/fieldlog/gad-debug.log` in app-private storage; [share] hands it to the share sheet through
 * the FileProvider `<applicationId>.gadkit.fieldlog`. Each line also goes to Logcat (tag `GAD`).
 *
 * The secrets passed to [start] are replaced by `<redacted>`. The file is cut to its last
 * [KEEP_BYTES] when it grows past [MAX_BYTES]. While it is off, every call returns at once.
 */
public object FieldLog {
    private const val MAX_BYTES = 8_000_000L
    private const val KEEP_BYTES = 4_000_000
    private const val FILE_NAME = "gad-debug.log"
    private const val TAG = "GAD"

    private val writer = lazy { Executors.newSingleThreadExecutor { Thread(it, "gad-field-log").apply { isDaemon = true } } }
    private var file: File? = null
    private var secrets: List<String> = emptyList()

    /** True after [start]. */
    @Volatile
    public var isEnabled: Boolean = false
        private set

    /**
     * Turns the log on, opens the file and writes the header of a new session: the host app's
     * version, its version code and the date. Call it at launch, before `VenueService.start`.
     * [secrets] are the tokens to redact, such as the application token.
     */
    public fun start(context: Context, secrets: List<String>) {
        this.secrets = secrets.filter { it.isNotEmpty() }
        file = File(File(context.filesDir, "fieldlog").apply { mkdirs() }, FILE_NAME)
        isEnabled = true
        val now = OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS)
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        log("launch ${info.versionName} (${PackageInfoCompat.getLongVersionCode(info)}) $now")
    }

    /** Appends one line with a time stamp. */
    @SuppressLint("LogNotTimber")
    public fun log(message: String) {
        if (!isEnabled) return
        val text = redact(message, secrets)
        val line = "[GAD] ${LocalTime.now().format(TIME)} $text\n"
        Log.i(TAG, text)
        val file = file ?: return
        writer.value.execute { append(file, line) }
    }

    /** Opens the share sheet with the log, for the host app's "Share debug log" button. */
    public fun share(context: Context) {
        if (!isEnabled) return
        val file = file?.takeIf { it.isFile } ?: return
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.gadkit.fieldlog", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, FILE_NAME)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(FILE_NAME, uri)
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
    }

    private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
}
