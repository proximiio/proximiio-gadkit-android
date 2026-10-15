package io.proximi.gadkit.map

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.proximi.gadkit.R
import io.proximi.gadkit.UiText
import io.proximi.gadkit.ui.resolve

/**
 * A local notification: the iOS `UNNotificationRequest` of `GeofenceNotifier` and
 * `StopNearbyNotifier`. [tag] is the iOS identifier: a new notice replaces the earlier one with the
 * same tag. [group] is the iOS thread identifier.
 */
internal data class LocalNotice(val tag: String, val title: String, val body: UiText, val group: String)

/**
 * Posts [LocalNotice]s on the channel `alerts`. iOS has no channel; the channel shows heads-up
 * notifications without sound, as the iOS banner does, also while the app is in the foreground.
 */
internal object LocalNotices {
    const val CHANNEL_ID = "alerts"

    /** `true` when notifications may be shown: the permission on API 33+, and the app's switch. */
    fun isAllowed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    /** Shows [notice] with [id]. The tag and id pair replaces an earlier notice. Nothing without permission. */
    fun post(context: Context, notice: LocalNotice, id: Int) {
        if (!isAllowed(context)) return
        val manager = NotificationManagerCompat.from(context)
        createChannel(context)
        val open = PendingIntent.getActivity(
            context,
            0,
            // The host app's launcher activity, brought to the front.
            (context.packageManager.getLaunchIntentForPackage(context.packageName) ?: Intent()).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_visit)
            .setContentTitle(notice.title)
            .setContentText(notice.body.resolve(context))
            .setGroup(notice.group)
            .setAutoCancel(true)
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        try {
            manager.notify(notice.tag, id, notification)
        } catch (_: SecurityException) {
            // The permission was revoked between the check and the post.
        }
    }

    private fun createChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(CHANNEL_ID, context.getString(R.string.alerts_channel_name), NotificationManager.IMPORTANCE_HIGH).apply {
            description = context.getString(R.string.alerts_channel_description)
            setSound(null, null)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
    }
}
