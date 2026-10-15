package io.proximi.gadkit.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/** Set once the permission dialog was shown in this process, by any screen. */
@Volatile
private var hasAsked = false

/**
 * Asks for the notification permission (Android 13 and later) while [isNeeded] is `true`, once per
 * process, as iOS asks only while the decision is undetermined. The geofence and next-stop
 * notifications and the journey notification need it. `VisitScreen` asks when it opens; a host app
 * asks once a wristband session is active, as the Proximi GAD app does. A refusal shows no
 * notification.
 */
@Composable
public fun AskNotificationPermission(isNeeded: Boolean = true) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(isNeeded) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (isNeeded && !granted && !hasAsked) {
            hasAsked = true
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
