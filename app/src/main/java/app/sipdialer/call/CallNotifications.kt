package app.sipdialer.call

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import app.sipdialer.R
import app.sipdialer.sip.CallInfo
import app.sipdialer.sip.CallPhase
import app.sipdialer.ui.call.InCallActivity

object CallNotifications {
    const val NOTIFICATION_ID = 1001
    private const val CHANNEL_INCOMING = "incoming_calls"
    private const val CHANNEL_ONGOING = "ongoing_calls"

    fun createChannels(context: Context) {
        val nm = NotificationManagerCompat.from(context)
        nm.createNotificationChannelsCompat(
            listOf(
                // Linphone plays the ringtone itself, so the channel stays silent.
                NotificationChannelCompat.Builder(CHANNEL_INCOMING, NotificationManagerCompat.IMPORTANCE_HIGH)
                    .setName("Incoming calls")
                    .setSound(null, null)
                    .setVibrationEnabled(false)
                    .build(),
                NotificationChannelCompat.Builder(CHANNEL_ONGOING, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                    .setName("Ongoing calls")
                    .setSound(null, null)
                    .setVibrationEnabled(false)
                    .build(),
            ),
        )
    }

    fun build(context: Context, call: CallInfo): Notification {
        val person = Person.Builder().setName(call.title).setImportant(true).build()
        val openCall = PendingIntent.getActivity(
            context,
            0,
            InCallActivity.intent(context),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val hangup = CallActionReceiver.pendingIntent(context, CallActionReceiver.ACTION_HANGUP, call.id)
        val incoming = call.phase == CallPhase.RINGING_IN

        val builder = NotificationCompat.Builder(context, if (incoming) CHANNEL_INCOMING else CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.ic_call)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setOnlyAlertOnce(!incoming)
            .setContentIntent(openCall)

        if (incoming) {
            // Answering opens the in-call screen directly: Android blocks activity
            // launches from broadcast receivers ("notification trampolines").
            val answer = PendingIntent.getActivity(
                context,
                1,
                InCallActivity.intent(context, answerCallId = call.id),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder
                .setStyle(NotificationCompat.CallStyle.forIncomingCall(person, hangup, answer))
                .setFullScreenIntent(openCall, true)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setContentText("Incoming SIP call")
        } else {
            builder
                .setStyle(NotificationCompat.CallStyle.forOngoingCall(person, hangup))
                .setContentText(
                    when (call.phase) {
                        CallPhase.DIALING -> "Calling…"
                        CallPhase.RINGING_OUT -> "Ringing…"
                        CallPhase.HELD -> "On hold"
                        else -> "SIP call"
                    },
                )
            call.connectedAtMs?.let { builder.setWhen(it).setShowWhen(true).setUsesChronometer(true) }
        }
        return builder.build()
    }
}
