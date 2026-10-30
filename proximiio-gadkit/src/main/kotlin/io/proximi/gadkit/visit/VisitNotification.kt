package io.proximi.gadkit.visit

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.proximi.gadkit.FieldLog
import io.proximi.gadkit.R
import io.proximi.gadkit.visit.VisitActivity
import io.proximi.gadkit.visit.VisitActivityState
import io.proximi.gadkit.map.mapText
import io.proximi.gadkit.ui.AppLanguage

/**
 * The visit's ongoing notification, in place of the iOS Live Activity (`VisitLiveActivity`,
 * Figma "Live Updates / Activities"): the current step, "Your journey · Stop N of M", the next
 * instruction and the level of the next stop.
 *
 * A notification of its own, on its own channel, beside the SDK's positioning service
 * notification (id `ProximiioServiceOptions.NOTIFICATION_ID`), which this class does not change:
 * the SDK runs the service and rebuilds its notification. Updates follow
 * [VisitActivity.shouldSend]; [end] removes the notification. Without the notification permission
 * nothing is shown, as iOS shows nothing with Live Activities turned off.
 */
internal class VisitNotification(
    context: Context,
    /** [io.proximi.gadkit.GadSettings.journeyNotificationMinimumInterval], in seconds. */
    private val minimumIntervalSeconds: Double = VisitActivity.MINIMUM_UPDATE_INTERVAL_SECONDS,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {
    private val context = context.applicationContext
    private val manager = NotificationManagerCompat.from(this.context)
    /** The language of the texts, "ar" or "en" ([io.proximi.gadkit.ui.AppLanguage]), or `null` for the app's own. */
    var language: String? = null

    private var lastState: VisitActivityState? = null
    private var lastUpdate = 0L
    private var isShowing = false

    fun update(state: VisitActivityState) {
        val now = clock()
        val seconds = (now - lastUpdate) / 1000.0
        if (!VisitActivity.shouldSend(state, lastState, if (lastState == null) Double.MAX_VALUE else seconds, minimumIntervalSeconds)) return
        if (!isAllowed()) {
            if (lastState == null) FieldLog.log("visit notification: not allowed")
            return
        }
        lastState = state
        lastUpdate = now
        ensureChannel()
        @Suppress("MissingPermission") // Checked in isAllowed().
        manager.notify(NOTIFICATION_ID, build(state))
        isShowing = true
        if (FieldLog.isEnabled) FieldLog.log("visit notification: ${render(state).joinToString(" · ")}")
    }

    fun end() {
        if (isShowing) manager.cancel(NOTIFICATION_ID)
        isShowing = false
        lastState = null
    }

    private fun isAllowed(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return manager.areNotificationsEnabled()
    }

    /** Title, "Your journey · Stop 1 of 2", instruction (or empty). */
    private fun render(state: VisitActivityState): List<String> {
        val resources = language?.let { AppLanguage.resources(context, it) } ?: context.resources
        return listOf(
            state.title.mapText(resources),
            resources.getString(R.string.your_journey_2) + " · " + state.progress.mapText(resources),
            state.instruction?.mapText(resources).orEmpty(),
        )
    }

    private fun build(state: VisitActivityState): android.app.Notification {
        val (title, progress, instruction) = render(state)
        // The level of the next stop, as the widget's trailing badge ("L2").
        val heading = if (state.level.isEmpty()) title else "$title · ${state.level}"
        val text = instruction.ifEmpty { progress }
        val open = PendingIntent.getActivity(
            context,
            0,
            // The host app's launcher activity, brought to the front.
            (context.packageManager.getLaunchIntentForPackage(context.packageName) ?: Intent()).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(if (state.isFinished) R.drawable.ic_checkmark_notification else R.drawable.ic_notification_visit)
            .setContentTitle(heading)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(listOf(instruction, progress).filter { it.isNotEmpty() }.joinToString("\n")))
            .setContentIntent(open)
            .setOngoing(!state.isFinished)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun ensureChannel() {
        val system = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL_ID, context.getString(R.string.visit_channel_name), NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = context.getString(R.string.visit_channel_description)
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
        // Creating an existing channel updates its name and description (a language change).
        system.createNotificationChannel(channel)
    }

    private companion object {
        const val CHANNEL_ID = "visit"
        const val NOTIFICATION_ID = 2_001
    }
}
