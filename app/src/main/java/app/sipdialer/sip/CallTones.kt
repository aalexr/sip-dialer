package app.sipdialer.sip

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

/**
 * Ringtone, vibration and ringback. Self-managed Telecom calls don't get any of these from
 * the system, and PJSIP doesn't play them either. Main thread only.
 */
internal class CallTones(
    private val context: Context,
) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val vibrator: Vibrator =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }

    private var ringtone: Ringtone? = null
    private var vibrating = false
    private var ringback: ToneGenerator? = null
    private var waitingTone: ToneGenerator? = null

    /** Rings with the system ringtone and vibration, honouring the ringer mode. */
    fun startRinging() {
        if (ringtone != null || vibrating) return
        val mode = audio.ringerMode
        if (mode == AudioManager.RINGER_MODE_NORMAL) {
            runCatching {
                val uri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE)
                ringtone =
                    RingtoneManager.getRingtone(context, uri)?.apply {
                        audioAttributes =
                            AudioAttributes
                                .Builder()
                                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .build()
                        isLooping = true
                        play()
                    }
            }.onFailure { Log.w(TAG, "Could not play ringtone", it) }
        }
        if (mode != AudioManager.RINGER_MODE_SILENT && vibrator.hasVibrator()) {
            val effect = VibrationEffect.createWaveform(longArrayOf(0, 1000, 1000), 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_RINGTONE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(
                    effect,
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build(),
                )
            }
            vibrating = true
        }
    }

    fun stopRinging() {
        ringtone?.stop()
        ringtone = null
        if (vibrating) vibrator.cancel()
        vibrating = false
    }

    /** A second call is ringing while we're in a call: beep in the call audio instead. */
    fun startCallWaiting() {
        if (waitingTone != null) return
        waitingTone =
            runCatching { ToneGenerator(AudioManager.STREAM_VOICE_CALL, 60) }
                .getOrNull()
                ?.apply { startTone(ToneGenerator.TONE_SUP_CALL_WAITING) }
    }

    fun stopCallWaiting() {
        waitingTone?.release()
        waitingTone = null
    }

    /** Local ringback while the callee rings (180 without early media). */
    fun startRingback() {
        if (ringback != null) return
        ringback =
            runCatching { ToneGenerator(AudioManager.STREAM_VOICE_CALL, 80) }
                .getOrNull()
                ?.apply { startTone(ToneGenerator.TONE_SUP_RINGTONE) }
    }

    fun stopRingback() {
        ringback?.release()
        ringback = null
    }

    fun stopAll() {
        stopRinging()
        stopCallWaiting()
        stopRingback()
    }

    private companion object {
        const val TAG = "CallTones"
    }
}
