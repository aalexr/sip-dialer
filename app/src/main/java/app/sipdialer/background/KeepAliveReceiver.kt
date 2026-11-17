package app.sipdialer.background

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import app.sipdialer.SipDialerApp

/**
 * Brings the app up after a reboot or an update (the [SipDialerApp] start does the rest), and
 * handles the periodic wake-up alarm that refreshes the SIP registration during Doze.
 */
class KeepAliveReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val app = SipDialerApp.instance
        if (intent.action != ACTION_WAKE) return // Boot / update: starting the process was the point.
        if (!app.shouldKeepAlive()) return

        // Keep the CPU up long enough for Linphone to send the REGISTER and read the answer.
        context
            .getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SipDialer:keepalive")
            .acquire(WAKE_LOCK_MS)
        app.sip.refreshRegistration()
        app.keepAlive.scheduleWake()
    }

    companion object {
        const val ACTION_WAKE = "app.sipdialer.action.KEEP_ALIVE_WAKE"
        private const val WAKE_LOCK_MS = 15_000L
    }
}
