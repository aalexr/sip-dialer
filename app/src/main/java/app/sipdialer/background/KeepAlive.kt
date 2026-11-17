package app.sipdialer.background

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Keeps the SIP registration alive while the app is not open, without push.
 *
 * Android does not keep a background process alive for long, so as long as an account
 * is set up and the user has not turned it off, this runs [KeepAliveService], a foreground
 * service with a quiet "ready for calls" notification. On top of that, an idle-allowed alarm
 * wakes the device every [WAKE_INTERVAL_MS] to refresh the registration: Linphone's own
 * timers stop while the CPU is in deep sleep, so the server could otherwise drop us.
 */
class KeepAlive(
    private val context: Context,
) {
    private val prefs = context.getSharedPreferences("background", Context.MODE_PRIVATE)

    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, true))

    /** The user's setting: stay connected while the app is closed. */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun setEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_ENABLED, enabled) }
        _enabled.value = enabled
    }

    /** Starts or stops the service and the wake-up alarm to match [shouldRun]. */
    fun apply(shouldRun: Boolean) {
        if (shouldRun) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, KeepAliveService::class.java))
            } catch (e: IllegalStateException) {
                // ForegroundServiceStartNotAllowedException: started from the background without
                // a battery-optimisation exemption. The next app start or boot will try again.
                Log.w(TAG, "Could not start the background service", e)
            }
            scheduleWake()
        } else {
            context.stopService(Intent(context, KeepAliveService::class.java))
            cancelWake()
        }
    }

    /**
     * Doze also cuts the network for apps that are not exempt from battery optimisation,
     * foreground service or not. Exempt apps may also start the service from the background.
     */
    fun isIgnoringBatteryOptimizations(): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    /** The system dialog that asks the user to exempt this app. Allowed for VoIP apps. */
    @SuppressLint("BatteryLife")
    fun batteryOptimizationRequest(): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri())

    fun scheduleWake() {
        context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + WAKE_INTERVAL_MS,
            wakeIntent(),
        )
    }

    private fun cancelWake() {
        context.getSystemService(AlarmManager::class.java).cancel(wakeIntent())
    }

    private fun wakeIntent(): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, KeepAliveReceiver::class.java).setAction(KeepAliveReceiver.ACTION_WAKE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private companion object {
        const val TAG = "KeepAlive"
        const val KEY_ENABLED = "keep_alive"

        // Doze lets an app fire an idle-allowed alarm about once every 9 minutes at most.
        const val WAKE_INTERVAL_MS = 10 * 60 * 1000L
    }
}
