package app.sipdialer.call

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.sipdialer.SipDialerApp
import app.sipdialer.sip.CallInfo
import app.sipdialer.sip.CallPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that lives for as long as there is a call. It keeps the process
 * (and microphone access) alive while the UI is in the background, and owns the call notification.
 */
class CallService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: Job? = null
    private var inForeground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val sip = SipDialerApp.instance.sip
        val call = sip.calls.value.primary()
        if (call == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!inForeground) {
            goForeground(call)
        }
        if (observer == null) {
            observer = scope.launch {
                sip.calls.collect { calls ->
                    val current = calls.primary()
                    if (current == null) {
                        ServiceCompat.stopForeground(this@CallService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        inForeground = false
                        stopSelf()
                    } else {
                        notify(current)
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun goForeground(call: CallInfo) {
        val notification = CallNotifications.build(this, call)
        val micGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
        if (micGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        try {
            ServiceCompat.startForeground(this, CallNotifications.NOTIFICATION_ID, notification, types)
        } catch (e: Exception) {
            // The microphone type can be refused while the app is in the background.
            Log.w(TAG, "startForeground with microphone failed, retrying as phoneCall only", e)
            ServiceCompat.startForeground(
                this,
                CallNotifications.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL,
            )
        }
        inForeground = true
    }

    @Suppress("MissingPermission") // POST_NOTIFICATIONS is not needed for a foreground service's own notification.
    private fun notify(call: CallInfo) {
        NotificationManagerCompat.from(this).notify(CallNotifications.NOTIFICATION_ID, CallNotifications.build(this, call))
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun List<CallInfo>.primary(): CallInfo? =
        firstOrNull { it.phase == CallPhase.RINGING_IN } ?: firstOrNull { it.phase != CallPhase.ENDED }

    companion object {
        private const val TAG = "CallService"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, CallService::class.java))
        }
    }
}
