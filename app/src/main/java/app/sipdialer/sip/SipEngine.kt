package app.sipdialer.sip

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.linphone.core.AudioDevice
import org.linphone.core.Call
import org.linphone.core.Core
import org.linphone.core.CoreListenerStub
import org.linphone.core.Factory
import org.linphone.core.Reason
import org.linphone.core.RegistrationState
import org.linphone.core.TransportType
import java.util.UUID

/**
 * Thin wrapper around the Linphone [Core].
 *
 * Everything here runs on the main thread: Linphone's Android core auto-iterates on the
 * main looper and delivers listener callbacks there.
 */
class SipEngine(private val context: Context) {

    private val factory = Factory.instance()
    private lateinit var core: Core

    private val _registration = MutableStateFlow<RegistrationStatus>(RegistrationStatus.NoAccount)
    val registration: StateFlow<RegistrationStatus> = _registration.asStateFlow()

    private val _account = MutableStateFlow<SipAccountConfig?>(null)
    val account: StateFlow<SipAccountConfig?> = _account.asStateFlow()

    private val _calls = MutableStateFlow<List<CallInfo>>(emptyList())
    val calls: StateFlow<List<CallInfo>> = _calls.asStateFlow()

    private val _recents = MutableStateFlow<List<RecentCall>>(emptyList())
    val recents: StateFlow<List<RecentCall>> = _recents.asStateFlow()

    private val _events = MutableSharedFlow<CallEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<CallEvent> = _events.asSharedFlow()

    // Linphone's Java wrapper hands out the same object for the same native call,
    // so the Call instance itself is a stable key.
    private val ids = HashMap<Call, String>()
    private val connectedAt = HashMap<String, Long>()
    private val endedLocally = HashSet<String>()
    private val endedIds = HashSet<String>()

    fun start() {
        val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        factory.setDebugMode(debuggable, "SipDialer")

        // Persist accounts and settings across process restarts.
        core = factory.createCore("${context.filesDir.absolutePath}/linphonerc", null, context)
        core.setUserAgent("SipDialer", "0.1")
        preferOpus()
        // Ring with the system ringtone and vibration settings.
        core.isNativeRingingEnabled = true
        // Audio-only for now.
        core.isVideoCaptureEnabled = false
        core.isVideoDisplayEnabled = false
        // Telecom owns audio focus for our calls; don't let Linphone pause calls on focus loss.
        core.config.setBool("audio", "android_pause_calls_when_audio_focus_lost", false)
        // Keep mouth-to-ear delay low: the default adaptive jitter buffer may grow to 500 ms.
        core.config.setInt("rtp", "jitter_buffer_max_size", 200)

        core.addListener(listener)
        core.start()

        loadAccount()
        refreshRecents()
    }

    /**
     * Put Opus first in our SDP offer, keeping G.722 and G.711 as fallbacks. Note that in SIP
     * the *answerer* chooses: if the server or callee prefers G.711, it can still pick it.
     */
    private fun preferOpus() {
        val preferred = listOf("opus", "G722", "PCMA", "PCMU")
        val types = core.audioPayloadTypes
        for (pt in types) pt.enable(pt.mimeType in preferred || pt.mimeType == "telephone-event")
        val ordered = types.sortedBy { pt ->
            preferred.indexOf(pt.mimeType).let { if (it < 0) Int.MAX_VALUE else it }
        }
        core.setAudioPayloadTypes(ordered.toTypedArray())
        Log.i(TAG, "Audio codec offer: " + core.audioPayloadTypes.filter { it.enabled() }
            .joinToString { "${it.mimeType}/${it.clockRate}" })
    }

    // ---- Account -------------------------------------------------------------------------

    fun saveAccount(cfg: SipAccountConfig): Result<Unit> = runCatching {
        val identity = factory.createAddress("sip:${cfg.username}@${cfg.domain}")
            ?: error("Invalid username or domain")
        if (cfg.displayName.isNotBlank()) identity.displayName = cfg.displayName

        val server = factory.createAddress("sip:${cfg.proxy.ifBlank { cfg.domain }}")
            ?: error("Invalid server address")
        server.transport = when (cfg.transport) {
            Transport.UDP -> TransportType.Udp
            Transport.TCP -> TransportType.Tcp
            Transport.TLS -> TransportType.Tls
        }

        removeAccount()

        val params = core.createAccountParams()
        params.setIdentityAddress(identity)
        params.setServerAddress(server)
        // Send calls and other requests through the server too, not only REGISTER. Otherwise
        // an INVITE is routed by the domain in its request URI (e.g. a public DynDNS name).
        params.isOutboundProxyEnabled = true
        params.isRegisterEnabled = true

        val auth = factory.createAuthInfo(
            cfg.username,
            cfg.authUsername.ifBlank { null },
            cfg.password,
            null,
            null,
            cfg.domain,
            null,
        )
        core.addAuthInfo(auth)

        val account = core.createAccount(params)
        check(core.addAccount(account) == 0) { "Linphone refused the account" }
        core.defaultAccount = account
        _registration.value = RegistrationStatus.InProgress
        loadAccount()
    }

    fun refreshRegistration() {
        core.refreshRegisters()
    }

    fun removeAccount() {
        core.clearAccounts()
        core.clearAllAuthInfo()
        _account.value = null
        _registration.value = RegistrationStatus.NoAccount
    }

    private fun loadAccount() {
        val account = core.defaultAccount ?: run {
            _account.value = null
            _registration.value = RegistrationStatus.NoAccount
            return
        }
        // Accounts saved before calls were routed through the server: enable that now.
        if (!account.params.isOutboundProxyEnabled) {
            account.params = account.params.clone().apply { isOutboundProxyEnabled = true }
        }
        val params = account.params
        val identity = params.identityAddress ?: return
        val server = params.serverAddress
        val username = identity.username.orEmpty()
        val domain = identity.domain.orEmpty()
        val auth = core.findAuthInfo(null, username, domain)
        val serverHost = server?.let { s -> s.domain + (s.port.takeIf { it > 0 }?.let { ":$it" } ?: "") }
        _account.value = SipAccountConfig(
            username = username,
            password = auth?.password.orEmpty(),
            domain = domain,
            transport = when (server?.transport) {
                TransportType.Udp -> Transport.UDP
                TransportType.Tcp -> Transport.TCP
                else -> Transport.TLS
            },
            displayName = identity.displayName.orEmpty(),
            proxy = serverHost?.takeIf { it != domain }.orEmpty(),
            authUsername = auth?.userid?.takeIf { it != username }.orEmpty(),
        )
        _registration.value = account.state.toStatus(null)
    }

    // ---- Calls ---------------------------------------------------------------------------

    /** Starts an outgoing call. [destination] can be a number, a user name or a SIP URI. */
    fun dial(destination: String): Result<Unit> = runCatching {
        check(core.defaultAccount != null) { "Set up a SIP account first" }
        val address = core.interpretUrl(destination.trim(), true) ?: error("Invalid number")
        val params = core.createCallParams(null) ?: error("Could not create call")
        params.isVideoEnabled = false
        core.inviteAddressWithParams(address, params) ?: error("Could not start call")
    }

    fun answer(id: String) {
        val call = find(id) ?: return
        val params = core.createCallParams(call)
        params?.isVideoEnabled = false
        if (params != null) call.acceptWithParams(params) else call.accept()
    }

    fun hangup(id: String) {
        val call = find(id) ?: return
        endedLocally += id
        when (call.state) {
            Call.State.IncomingReceived, Call.State.IncomingEarlyMedia -> call.decline(Reason.Declined)
            else -> call.terminate()
        }
    }

    fun setHold(id: String, hold: Boolean) {
        val call = find(id) ?: return
        if (hold) call.pause() else call.resume()
    }

    fun setMuted(muted: Boolean) {
        core.isMicEnabled = !muted
        publishCalls()
    }

    fun sendDtmf(id: String, digit: Char) {
        find(id)?.sendDtmf(digit)
    }

    /** Called when Telecom switches the call endpoint, so Linphone plays to the same device. */
    fun routeAudio(route: AudioRoute) {
        val types = when (route) {
            AudioRoute.EARPIECE -> setOf(AudioDevice.Type.Earpiece)
            AudioRoute.SPEAKER -> setOf(AudioDevice.Type.Speaker)
            AudioRoute.BLUETOOTH -> setOf(AudioDevice.Type.Bluetooth, AudioDevice.Type.HearingAid)
            AudioRoute.WIRED_HEADSET -> setOf(AudioDevice.Type.Headset, AudioDevice.Type.Headphones)
        }
        val devices = core.audioDevices.filter { it.type in types }
        val output = devices.firstOrNull { it.hasCapability(AudioDevice.Capabilities.CapabilityPlay) }
        val input = devices.firstOrNull { it.hasCapability(AudioDevice.Capabilities.CapabilityRecord) }
        for (call in core.calls) {
            output?.let { call.outputAudioDevice = it }
            // Earpiece and speaker share the built-in mic; headsets bring their own.
            if (route == AudioRoute.BLUETOOTH || route == AudioRoute.WIRED_HEADSET) {
                input?.let { call.inputAudioDevice = it }
            }
        }
    }

    fun clearRecents() {
        core.clearCallLogs()
        refreshRecents()
    }

    private fun find(id: String): Call? = ids.entries.firstOrNull { it.value == id }?.key

    private val listener = object : CoreListenerStub() {
        override fun onAccountRegistrationStateChanged(
            core: Core,
            account: org.linphone.core.Account,
            state: RegistrationState,
            message: String,
        ) {
            if (account == core.defaultAccount) _registration.value = state.toStatus(message)
        }

        override fun onCallStateChanged(core: Core, call: Call, state: Call.State, message: String) {
            onCallState(call, state, message)
        }
    }

    private fun onCallState(call: Call, state: Call.State, message: String) {
        Log.d(TAG, "call state $state: $message")
        val isNew = call !in ids
        val id = ids.getOrPut(call) { UUID.randomUUID().toString() }

        when (state) {
            Call.State.IncomingReceived, Call.State.OutgoingInit -> Unit
            Call.State.Connected, Call.State.StreamsRunning -> {
                if (state == Call.State.StreamsRunning) {
                    Log.i(TAG, "Negotiated audio codec: ${call.codecLabel()}")
                }
                if (id !in connectedAt) {
                    connectedAt[id] = System.currentTimeMillis()
                    _events.tryEmit(CallEvent.Connected(id))
                }
            }
            Call.State.Paused -> _events.tryEmit(CallEvent.Held(id, true))
            Call.State.Resuming -> _events.tryEmit(CallEvent.Held(id, false))
            Call.State.End, Call.State.Error -> if (endedIds.add(id)) {
                _events.tryEmit(CallEvent.Ended(id, endReason(call, id, state)))
            }
            Call.State.Released -> {
                if (endedIds.add(id)) _events.tryEmit(CallEvent.Ended(id, endReason(call, id, state)))
                ids.remove(call)
                connectedAt.remove(id)
                endedLocally.remove(id)
                endedIds.remove(id)
                refreshRecents()
            }
            else -> Unit
        }

        publishCalls()
        if (isNew && state != Call.State.Released && state != Call.State.End) {
            _calls.value.firstOrNull { it.id == id }?.let { _events.tryEmit(CallEvent.Added(it)) }
        }
    }

    private fun endReason(call: Call, id: String, state: Call.State): EndReason {
        val local = id in endedLocally
        val neverConnected = id !in connectedAt
        return when {
            state == Call.State.Error -> EndReason.ERROR
            call.dir == Call.Dir.Incoming && neverConnected && local -> EndReason.REJECTED
            call.dir == Call.Dir.Incoming && neverConnected -> EndReason.MISSED
            local -> EndReason.LOCAL
            else -> EndReason.REMOTE
        }
    }

    private fun publishCalls() {
        _calls.value = ids.mapNotNull { (call, id) ->
            if (id in endedIds) return@mapNotNull null
            val address = call.remoteAddress
            CallInfo(
                id = id,
                remote = address.username?.takeIf { it.isNotBlank() } ?: address.asStringUriOnly(),
                remoteUri = address.asStringUriOnly(),
                displayName = address.displayName,
                direction = if (call.dir == Call.Dir.Incoming) CallDirection.INCOMING else CallDirection.OUTGOING,
                phase = call.state.toPhase(),
                connectedAtMs = connectedAt[id],
                muted = !core.isMicEnabled,
                codec = if (id in connectedAt) call.codecLabel() else null,
            )
        }
    }

    private fun Call.codecLabel(): String? {
        val pt = currentParams.usedAudioPayloadType ?: return null
        val name = when (pt.mimeType.lowercase()) {
            "opus" -> "Opus"
            "pcmu" -> "G.711 µ-law"
            "pcma" -> "G.711 A-law"
            "g722" -> "G.722"
            else -> pt.mimeType
        }
        // G.722 advertises 8 kHz in SDP for historical reasons but is really 16 kHz wideband.
        val khz = if (pt.mimeType.equals("G722", ignoreCase = true)) 16 else pt.clockRate / 1000
        return "$name · $khz kHz"
    }

    private fun refreshRecents() {
        _recents.value = core.callLogs
            .map { log ->
                val address = log.remoteAddress
                RecentCall(
                    remote = address.username?.takeIf { it.isNotBlank() } ?: address.asStringUriOnly(),
                    remoteUri = address.asStringUriOnly(),
                    displayName = address.displayName,
                    direction = if (log.dir == Call.Dir.Incoming) CallDirection.INCOMING else CallDirection.OUTGOING,
                    missed = log.status == Call.Status.Missed,
                    startMs = log.startDate * 1000,
                    durationSec = log.duration,
                )
            }
            .sortedByDescending { it.startMs }
    }

    private fun RegistrationState.toStatus(message: String?): RegistrationStatus = when (this) {
        RegistrationState.Ok -> RegistrationStatus.Registered
        RegistrationState.Progress, RegistrationState.Refreshing -> RegistrationStatus.InProgress
        RegistrationState.Failed -> RegistrationStatus.Failed(message?.takeIf { it.isNotBlank() } ?: "Registration failed")
        else -> RegistrationStatus.Unregistered
    }

    private fun Call.State.toPhase(): CallPhase = when (this) {
        Call.State.IncomingReceived, Call.State.IncomingEarlyMedia, Call.State.PushIncomingReceived -> CallPhase.RINGING_IN
        Call.State.OutgoingInit, Call.State.OutgoingProgress -> CallPhase.DIALING
        Call.State.OutgoingRinging, Call.State.OutgoingEarlyMedia -> CallPhase.RINGING_OUT
        Call.State.Paused, Call.State.Pausing, Call.State.PausedByRemote -> CallPhase.HELD
        Call.State.End, Call.State.Error, Call.State.Released -> CallPhase.ENDED
        else -> CallPhase.ACTIVE
    }

    private companion object {
        const val TAG = "SipEngine"
    }
}
