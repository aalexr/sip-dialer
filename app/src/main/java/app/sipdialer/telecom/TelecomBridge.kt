package app.sipdialer.telecom

import android.content.Context
import androidx.core.net.toUri
import android.telecom.DisconnectCause
import android.util.Log
import androidx.core.telecom.CallAttributesCompat
import androidx.core.telecom.CallControlScope
import androidx.core.telecom.CallEndpointCompat
import androidx.core.telecom.CallsManager
import app.sipdialer.call.CallService
import app.sipdialer.sip.AudioRoute
import app.sipdialer.sip.CallDirection
import app.sipdialer.sip.CallEvent
import app.sipdialer.sip.CallInfo
import app.sipdialer.sip.EndReason
import app.sipdialer.sip.SipEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AudioEndpoints(
    val current: CallEndpointCompat? = null,
    val available: List<CallEndpointCompat> = emptyList(),
)

/**
 * Mirrors every Linphone call into the Android Telecom framework via androidx.core.telecom.
 *
 * Telecom then handles audio focus and routing, Bluetooth headsets, car kits, watches and
 * call waiting against cellular calls. Linphone still carries the actual SIP signalling and media.
 */
class TelecomBridge(private val context: Context, private val sip: SipEngine) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val callsManager = CallsManager(context)

    private val controls = HashMap<String, CallControlScope>()
    /** Calls that ended before Telecom finished adding them. */
    private val endedEarly = HashMap<String, EndReason>()

    private val _endpoints = MutableStateFlow(AudioEndpoints())
    val endpoints: StateFlow<AudioEndpoints> = _endpoints.asStateFlow()

    fun start() {
        callsManager.registerAppWithTelecom(CallsManager.CAPABILITY_BASELINE)
        scope.launch {
            sip.events.collect { event ->
                when (event) {
                    is CallEvent.Added -> addCall(event.call)
                    is CallEvent.Connected -> controls[event.id]?.let { launch { it.setActive() } }
                    is CallEvent.Held -> controls[event.id]?.let {
                        launch { if (event.held) it.setInactive() else it.setActive() }
                    }
                    is CallEvent.Ended -> {
                        val control = controls.remove(event.id)
                        if (control == null) {
                            endedEarly[event.id] = event.reason
                        } else {
                            launch { control.disconnect(event.reason.toDisconnectCause()) }
                        }
                    }
                }
            }
        }
    }

    /** Answer from our own UI (in-call screen or notification). */
    fun answer(id: String) {
        val control = controls[id]
        if (control == null) {
            sip.answer(id)
            return
        }
        scope.launch {
            control.answer(CallAttributesCompat.CALL_TYPE_AUDIO_CALL)
            sip.answer(id)
        }
    }

    fun requestEndpoint(endpoint: CallEndpointCompat) {
        val control = controls.values.firstOrNull() ?: return
        scope.launch { control.requestEndpointChange(endpoint) }
    }

    private fun addCall(call: CallInfo) {
        CallService.start(context)

        val attributes = CallAttributesCompat(
            displayName = call.title,
            address = call.remoteUri.toUri(),
            direction = if (call.direction == CallDirection.INCOMING) {
                CallAttributesCompat.DIRECTION_INCOMING
            } else {
                CallAttributesCompat.DIRECTION_OUTGOING
            },
            callType = CallAttributesCompat.CALL_TYPE_AUDIO_CALL,
            callCapabilities = CallAttributesCompat.SUPPORTS_SET_INACTIVE,
        )

        scope.launch {
            try {
                callsManager.addCall(
                    attributes,
                    // These four are requests coming *from* the system (Bluetooth button, watch, car, ...).
                    onAnswer = { sip.answer(call.id) },
                    onDisconnect = {
                        controls.remove(call.id)
                        sip.hangup(call.id)
                    },
                    onSetActive = { sip.setHold(call.id, false) },
                    onSetInactive = { sip.setHold(call.id, true) },
                ) {
                    val early = endedEarly.remove(call.id)
                    if (early != null) {
                        launch { disconnect(early.toDisconnectCause()) }
                        return@addCall
                    }
                    controls[call.id] = this

                    launch {
                        currentCallEndpoint.collect { endpoint ->
                            _endpoints.update { it.copy(current = endpoint) }
                            endpoint.toAudioRoute()?.let(sip::routeAudio)
                        }
                    }
                    launch {
                        availableEndpoints.collect { list -> _endpoints.update { it.copy(available = list) } }
                    }
                    launch { isMuted.collect { muted -> sip.setMuted(muted) } }
                }
            } catch (e: Exception) {
                // Telecom refused the call (e.g. an emergency call is in progress).
                Log.e(TAG, "Telecom addCall failed", e)
                controls.remove(call.id)
                sip.hangup(call.id)
            }
        }
    }

    private fun EndReason.toDisconnectCause() = DisconnectCause(
        when (this) {
            EndReason.LOCAL -> DisconnectCause.LOCAL
            EndReason.REMOTE -> DisconnectCause.REMOTE
            EndReason.REJECTED -> DisconnectCause.REJECTED
            EndReason.MISSED -> DisconnectCause.MISSED
            EndReason.ERROR -> DisconnectCause.ERROR
        },
    )

    private fun CallEndpointCompat.toAudioRoute(): AudioRoute? = when (type) {
        CallEndpointCompat.TYPE_EARPIECE -> AudioRoute.EARPIECE
        CallEndpointCompat.TYPE_SPEAKER -> AudioRoute.SPEAKER
        CallEndpointCompat.TYPE_BLUETOOTH -> AudioRoute.BLUETOOTH
        CallEndpointCompat.TYPE_WIRED_HEADSET -> AudioRoute.WIRED_HEADSET
        else -> null
    }

    private companion object {
        const val TAG = "TelecomBridge"
    }
}
