package io.proximi.gadkit.map

import android.content.Context
import io.proximi.gadkit.FieldLog
import io.proximi.gadkit.UiText
import io.proximi.sdk.Proximiio
import io.proximi.sdk.geofenceEvents
import io.proximi.sdk.geofencing.GeofenceEvent

/**
 * Posts a local notification when the visitor enters or leaves a geofence, as iOS
 * `GeofenceNotifier`. The title is the geofence name. Every geofence type posts one: galleries,
 * artworks and any other type. A geofence without a name posts nothing. Privacy zones post nothing.
 *
 * A notification replaces the earlier one of the same geofence and direction. Walking along a
 * geofence edge leaves one enter and one exit notification per geofence, not a stack.
 *
 * Android needs the `POST_NOTIFICATIONS` permission on API 33 and later. The host app requests it,
 * for example once a wristband session is active, because geofence events need positions from
 * the band. Without it nothing is shown.
 */
public class GeofenceNotifier(context: Context) {
    private val context = context.applicationContext

    /**
     * Posts a notification for every geofence enter and exit event of [sdk]. Returns when the
     * stream finishes; cancel the calling coroutine to stop it.
     */
    public suspend fun run(sdk: Proximiio) {
        sdk.geofenceEvents().collect { event ->
            val notice = notice(event) ?: return@collect
            FieldLog.log("notification: ${notice.tag} ${notice.title}")
            LocalNotices.post(context, notice, NOTIFICATION_ID)
        }
    }

    internal companion object {
        const val NOTIFICATION_ID = 2_002

        /**
         * The notice for a geofence enter or exit, or `null` for a geofence without a name and for
         * privacy zones. Pure; covered by tests.
         */
        fun notice(event: GeofenceEvent): LocalNotice? {
            val (geofence, direction, body) = when (event) {
                is GeofenceEvent.Entered -> Triple(event.geofence, "entered", "geofence_entered")
                is GeofenceEvent.Exited -> Triple(event.geofence, "exited", "geofence_exited")
                else -> return null
            }
            val name = geofence.name?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return LocalNotice(tag = "geofence-$direction-${geofence.id}", title = name, body = UiText.Resource(body), group = "geofence")
        }
    }
}
