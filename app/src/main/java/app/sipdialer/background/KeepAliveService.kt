package app.sipdialer.background

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import app.sipdialer.R
import app.sipdialer.SipDialerApp
import app.sipdialer.sip.RegistrationStatus
import app.sipdialer.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the process, and with it the Linphone core and its SIP
 * registration, alive while no screen of the app is open, so incoming calls still arrive.
 */
class KeepAliveService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val app = SipDialerApp.instance
        // startForeground must always follow startForegroundService, even if we stop right away.
        val type =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, build(this, app.sip.registration.value), type)
        } catch (e: IllegalStateException) {
            // A sticky restart from the background can be refused without a battery exemption.
            Log.w(TAG, "Not allowed to run in the foreground right now", e)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!app.shouldKeepAlive()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (observer == null) {
            observer =
                scope.launch {
                    app.sip.registration.collect { status -> notify(status) }
                }
        }
        // If the system kills the process anyway, have it restart the service when it can.
        return START_STICKY
    }

    @Suppress("MissingPermission") // A foreground service may always update its own notification.
    private fun notify(status: RegistrationStatus) {
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, build(this, status))
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "KeepAliveService"
        private const val NOTIFICATION_ID = 1002
        private const val CHANNEL = "background"

        fun createChannel(context: Context) {
            NotificationManagerCompat.from(context).createNotificationChannel(
                NotificationChannelCompat
                    .Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_MIN)
                    .setName("Ready for calls")
                    .setDescription("Shown while the app stays connected to receive calls. You can hide this category.")
                    .setShowBadge(false)
                    .build(),
            )
        }

        private fun build(
            context: Context,
            status: RegistrationStatus,
        ): Notification {
            val open =
                PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            val text =
                when (status) {
                    RegistrationStatus.Registered -> "Ready for incoming calls"
                    RegistrationStatus.InProgress -> "Connecting…"
                    is RegistrationStatus.Failed -> "Not connected: ${status.reason}"
                    RegistrationStatus.Unregistered, RegistrationStatus.NoAccount -> "Not connected"
                }
            return NotificationCompat
                .Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_call)
                .setContentTitle(context.getString(R.string.app_name))
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build()
        }
    }
}
