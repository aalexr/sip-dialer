package app.sipdialer.call

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.sipdialer.SipDialerApp

/** Handles notification buttons that don't need to open UI (decline / hang up). */
class CallActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_CALL_ID) ?: return
        when (intent.action) {
            ACTION_HANGUP -> SipDialerApp.instance.sip.hangup(id)
        }
    }

    companion object {
        const val ACTION_HANGUP = "app.sipdialer.action.HANGUP"
        private const val EXTRA_CALL_ID = "call_id"

        fun pendingIntent(context: Context, action: String, callId: String): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                action.hashCode(),
                Intent(context, CallActionReceiver::class.java)
                    .setAction(action)
                    .putExtra(EXTRA_CALL_ID, callId),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
    }
}
