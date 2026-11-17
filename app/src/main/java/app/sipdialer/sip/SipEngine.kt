package app.sipdialer.sip

import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.UUID

/**
 * The app's SIP user agent, built on PJSIP (see [PjNative]).
 *
 * Public state and methods are main-thread only. PJSIP calls back on its own threads;
 * [listener] hops every callback onto the main thread before touching any state.
 */
class SipEngine(
    private val context: Context,
) {
    private val main = Handler(Looper.getMainLooper())
    private val storage = SipStorage(context)
    private val tones = CallTones(context)

    private val _registration = MutableStateFlow<RegistrationStatus>(RegistrationStatus.NoAccount)
    val registration: StateFlow<RegistrationStatus> = _registration.asStateFlow()

    private val _account = MutableStateFlow<SipAccountConfig?>(null)
    val account: StateFlow<SipAccountConfig?> = _account.asStateFlow()

    private val _calls = MutableStateFlow<List<CallInfo>>(emptyList())
    val calls: StateFlow<List<CallInfo>> = _calls.asStateFlow()

    private val _recents = MutableStateFlow<List<RecentCall>>(emptyList())
    val recents: StateFlow<List<RecentCall>> = _recents.asStateFlow()

    private val _events =
        MutableSharedFlow<CallEvent>(
            extraBufferCapacity = 64,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    val events: SharedFlow<CallEvent> = _events.asSharedFlow()

    private class CallRecord(
        val token: Int,
        val id: String,
        val incoming: Boolean,
        var remote: SipAddress,
    ) {
        val startMs = System.currentTimeMillis()
        var state = 0
        var lastStatus = 0
        var connectedAt: Long? = null
        var media = MediaStatus.NONE
        var codec: String? = null
        var earlyMedia = false
        var localHold = false
        var endedLocally = false
    }

    /** Live calls by token, in creation order. */
    private val records = LinkedHashMap<Int, CallRecord>()
    private var nextToken = 1
    private var muted = false
    private var started = false

    fun start() {
        val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName
        val error = PjNative.start(listener, "SipDialer/$version", debuggable, systemCaPath())
        if (error != null) {
            Log.e(TAG, "PJSIP failed to start: $error")
            _registration.value = RegistrationStatus.Failed("SIP stack failed to start: $error")
            return
        }
        started = true
        val codecs = PjNative.setCodecOrder(CODEC_ORDER)
        Log.i(TAG, "Audio codecs: ${codecs.joinToString()}")
        if (codecs.none { it.startsWith("AMR-WB/", ignoreCase = true) }) {
            Log.w(TAG, "No AMR-WB: this device has no MediaCodec AMR-WB encoder/decoder")
        }

        _recents.value = storage.loadRecents()
        storage.loadAccount()?.let { cfg -> applyAccount(cfg).onFailure { Log.e(TAG, "Saved account rejected", it) } }
        watchNetwork()
    }

    // ---- Account -------------------------------------------------------------------------

    fun saveAccount(cfg: SipAccountConfig): Result<Unit> = applyAccount(cfg).onSuccess { storage.saveAccount(cfg) }

    private fun applyAccount(cfg: SipAccountConfig): Result<Unit> =
        runCatching {
            check(started) { "SIP stack is not running" }
            require(cfg.username.isNotBlank() && cfg.domain.isNotBlank()) { "Invalid username or domain" }
            val domain = cfg.domain.trim()
            val server = cfg.proxy.trim().ifBlank { domain }
            val (transport, param) =
                when (cfg.transport) {
                    Transport.UDP -> PjNative.TRANSPORT_UDP to "udp"
                    Transport.TCP -> PjNative.TRANSPORT_TCP to "tcp"
                    Transport.TLS -> PjNative.TRANSPORT_TLS to "tls"
                }
            val aor = "sip:${escapeUser(cfg.username.trim())}@$domain"
            val name =
                cfg.displayName
                    .trim()
                    .replace("\\", "\\\\")
                    .replace("\"", "\\\"")
            val identity = if (name.isNotEmpty()) "\"$name\" <$aor>" else "<$aor>"
            val error =
                PjNative.setAccount(
                    id = identity,
                    registrar = "sip:$domain",
                    proxy = "<sip:$server;transport=$param;lr>",
                    authUser = cfg.authUsername.trim().ifBlank { cfg.username.trim() },
                    password = cfg.password,
                    transport = transport,
                )
            check(error == null) { error ?: "" }
            _account.value = cfg
            _registration.value = RegistrationStatus.InProgress
        }

    fun removeAccount() {
        if (started) PjNative.removeAccount()
        storage.clearAccount()
        _account.value = null
        _registration.value = RegistrationStatus.NoAccount
    }

    /** Re-sends REGISTER now, e.g. after the app was woken up. */
    fun refreshRegistration() {
        if (started && _account.value != null) PjNative.refreshRegistration()
    }

    // ---- Calls ---------------------------------------------------------------------------

    /** Starts an outgoing call. [destination] can be a number, a user name or a SIP URI. */
    fun dial(destination: String): Result<Unit> =
        runCatching {
            val account = _account.value
            check(started && account != null) { "Set up a SIP account first" }
            val uri = dialUri(destination, account.domain.trim()) ?: error("Invalid number")
            val token = nextToken++
            val rec = CallRecord(token, UUID.randomUUID().toString(), incoming = false, remote = SipAddress.parse(uri))
            records[token] = rec
            val err = PjNative.makeCall(uri, token)
            if (err != null) {
                records.remove(token)
                error(err)
            }
            // The CALLING callback may still be in flight; show the call right away.
            publishCalls()
            _calls.value.firstOrNull { it.id == rec.id }?.let { _events.tryEmit(CallEvent.Added(it)) }
        }

    fun answer(id: String) {
        val rec = find(id) ?: return
        tones.stopRinging()
        PjNative.answer(rec.token)
    }

    fun hangup(id: String) {
        val rec = find(id) ?: return
        rec.endedLocally = true
        // 0 lets PJSIP pick: 603 Decline for an unanswered incoming call, CANCEL or BYE otherwise.
        PjNative.hangup(rec.token, 0)
    }

    fun setHold(
        id: String,
        hold: Boolean,
    ) {
        val rec = find(id) ?: return
        if (rec.state != InvState.CONFIRMED) return
        if (hold != rec.localHold) PjNative.setHold(rec.token, hold)
    }

    fun setMuted(muted: Boolean) {
        this.muted = muted
        if (started) PjNative.setMicMuted(muted)
        publishCalls()
    }

    fun sendDtmf(
        id: String,
        digit: Char,
    ) {
        find(id)?.let { PjNative.sendDtmf(it.token, digit.toString()) }
    }

    /**
     * Called when Telecom switches the call endpoint. Nothing to do: PJSIP plays and records
     * through the voice-call streams, which follow the communication device Telecom selects.
     */
    @Suppress("UNUSED_PARAMETER")
    fun routeAudio(route: AudioRoute) = Unit

    fun clearRecents() {
        _recents.value = emptyList()
        storage.saveRecents(emptyList())
    }

    private fun find(id: String): CallRecord? = records.values.firstOrNull { it.id == id }

    // ---- PJSIP callbacks (posted to the main thread) --------------------------------------

    private val listener =
        object : PjListener {
            override fun onRegState(
                code: Int,
                reason: ByteArray,
                expires: Int,
                renew: Boolean,
            ) {
                val text = reason.decodeToString()
                main.post { onRegistration(code, text, expires, renew) }
            }

            override fun onCallState(
                token: Int,
                state: Int,
                incoming: Boolean,
                lastStatus: Int,
                lastReason: ByteArray,
                remote: ByteArray,
            ) {
                val reasonText = lastReason.decodeToString()
                val remoteText = remote.decodeToString()
                main.post { onCallState(token, state, incoming, lastStatus, reasonText, remoteText) }
            }

            override fun onMediaState(
                token: Int,
                status: Int,
                codec: String,
                clockRate: Int,
            ) {
                main.post { onMedia(token, status, codec, clockRate) }
            }
        }

    private fun onRegistration(
        code: Int,
        reason: String,
        expires: Int,
        renew: Boolean,
    ) {
        Log.i(TAG, "registration: $code $reason (expires $expires, renew $renew)")
        // Un-REGISTERs come from replacing/removing the account or a network change.
        if (!renew || _account.value == null) return
        _registration.value =
            when {
                code / 100 == 2 && expires > 0 -> RegistrationStatus.Registered
                code / 100 == 1 -> RegistrationStatus.InProgress
                else ->
                    RegistrationStatus.Failed(
                        when {
                            code == 0 || reason.isBlank() -> reason.ifBlank { "Registration failed" }
                            else -> "$code $reason"
                        },
                    )
            }
    }

    private fun onCallState(
        token: Int,
        state: Int,
        incoming: Boolean,
        lastStatus: Int,
        reason: String,
        remote: String,
    ) {
        Log.d(TAG, "call $token state $state ($lastStatus $reason)")
        val isNew = token !in records
        val rec =
            records.getOrPut(token) {
                CallRecord(token, UUID.randomUUID().toString(), incoming, SipAddress.parse(remote))
            }
        if (remote.isNotBlank()) rec.remote = SipAddress.parse(remote)
        rec.state = state
        rec.lastStatus = lastStatus

        when (state) {
            InvState.CONFIRMED ->
                if (rec.connectedAt == null) {
                    rec.connectedAt = System.currentTimeMillis()
                    _events.tryEmit(CallEvent.Connected(rec.id))
                }
            InvState.DISCONNECTED -> {
                records.remove(token)
                _events.tryEmit(CallEvent.Ended(rec.id, endReason(rec)))
                addRecent(rec)
            }
        }

        publishCalls()
        if (isNew && state != InvState.DISCONNECTED) {
            _calls.value.firstOrNull { it.id == rec.id }?.let { _events.tryEmit(CallEvent.Added(it)) }
        }
        updateTones()
    }

    private fun onMedia(
        token: Int,
        status: Int,
        codec: String,
        clockRate: Int,
    ) {
        val rec = records[token] ?: return
        rec.media = status
        if (codec.isNotEmpty()) rec.codec = codecLabel(codec, clockRate)
        if (rec.state == InvState.EARLY && status == MediaStatus.ACTIVE) rec.earlyMedia = true
        if (status == MediaStatus.ACTIVE || status == MediaStatus.REMOTE_HOLD) {
            Log.i(TAG, "Negotiated audio codec: ${rec.codec}")
        }
        when {
            status == MediaStatus.LOCAL_HOLD && !rec.localHold -> {
                rec.localHold = true
                _events.tryEmit(CallEvent.Held(rec.id, true))
            }
            status != MediaStatus.LOCAL_HOLD && rec.localHold -> {
                rec.localHold = false
                _events.tryEmit(CallEvent.Held(rec.id, false))
            }
        }
        publishCalls()
        updateTones()
    }

    private fun endReason(rec: CallRecord): EndReason {
        val neverConnected = rec.connectedAt == null
        return when {
            rec.incoming && neverConnected && rec.endedLocally -> EndReason.REJECTED
            rec.incoming && neverConnected -> EndReason.MISSED
            rec.endedLocally -> EndReason.LOCAL
            neverConnected && rec.lastStatus >= 300 -> EndReason.ERROR
            else -> EndReason.REMOTE
        }
    }

    private fun addRecent(rec: CallRecord) {
        val recent =
            RecentCall(
                remote = rec.remote.shortForm,
                remoteUri = rec.remote.uri,
                displayName = rec.remote.displayName,
                direction = if (rec.incoming) CallDirection.INCOMING else CallDirection.OUTGOING,
                missed = rec.incoming && rec.connectedAt == null && !rec.endedLocally,
                startMs = rec.startMs,
                durationSec = rec.connectedAt?.let { ((System.currentTimeMillis() - it) / 1000).toInt() } ?: 0,
            )
        _recents.value = listOf(recent) + _recents.value
        storage.saveRecents(_recents.value)
    }

    private fun publishCalls() {
        _calls.value =
            records.values.map { rec ->
                CallInfo(
                    id = rec.id,
                    remote = rec.remote.shortForm,
                    remoteUri = rec.remote.uri,
                    displayName = rec.remote.displayName,
                    direction = if (rec.incoming) CallDirection.INCOMING else CallDirection.OUTGOING,
                    phase = rec.phase(),
                    connectedAtMs = rec.connectedAt,
                    muted = muted,
                    codec = if (rec.connectedAt != null) rec.codec else null,
                )
            }
    }

    private fun CallRecord.phase(): CallPhase =
        when (state) {
            InvState.DISCONNECTED -> CallPhase.ENDED
            InvState.INCOMING -> CallPhase.RINGING_IN
            InvState.EARLY -> if (incoming) CallPhase.RINGING_IN else CallPhase.RINGING_OUT
            InvState.CONNECTING, InvState.CONFIRMED ->
                if (media == MediaStatus.LOCAL_HOLD || media == MediaStatus.REMOTE_HOLD) CallPhase.HELD else CallPhase.ACTIVE
            else -> if (incoming) CallPhase.RINGING_IN else CallPhase.DIALING
        }

    private fun updateTones() {
        val calls = records.values
        val ringingIn = calls.any { it.incoming && it.phase() == CallPhase.RINGING_IN }
        val inCall = calls.any { it.state == InvState.CONNECTING || it.state == InvState.CONFIRMED }
        if (ringingIn && !inCall) tones.startRinging() else tones.stopRinging()
        if (ringingIn && inCall) tones.startCallWaiting() else tones.stopCallWaiting()
        val ringback = calls.any { !it.incoming && it.state == InvState.EARLY && it.lastStatus == 180 && !it.earlyMedia }
        if (ringback) tones.startRingback() else tones.stopRingback()
    }

    private fun codecLabel(
        codec: String,
        clockRate: Int,
    ): String {
        val name =
            when (codec.lowercase()) {
                "opus" -> "Opus"
                "pcmu" -> "G.711 µ-law"
                "pcma" -> "G.711 A-law"
                "g722" -> "G.722"
                "amr" -> "AMR-NB"
                "amr-wb" -> "AMR-WB"
                else -> codec
            }
        // G.722 advertises 8 kHz in SDP for historical reasons but is really 16 kHz wideband.
        val khz = if (codec.equals("G722", ignoreCase = true)) 16 else clockRate / 1000
        return "$name · $khz kHz"
    }

    // ---- Network -------------------------------------------------------------------------

    /** When the default network changes (Wi-Fi <-> mobile), rebind and re-register. */
    private fun watchNetwork() {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        cm.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                private var current: Network? = null
                private var first = true

                override fun onAvailable(network: Network) {
                    main.post {
                        val changed = !first && network != current
                        first = false
                        current = network
                        if (changed) {
                            Log.i(TAG, "Default network changed, re-registering")
                            PjNative.ipChanged()
                        }
                    }
                }

                override fun onLost(network: Network) {
                    main.post { if (network == current) current = null }
                }
            },
            main,
        )
    }

    private fun systemCaPath(): String {
        // Android 14+ ships updatable root certificates in the Conscrypt APEX.
        val apex = File("/apex/com.android.conscrypt/cacerts")
        return if (apex.isDirectory) apex.path else "/system/etc/security/cacerts"
    }

    private companion object {
        const val TAG = "SipEngine"

        /**
         * Offer order. AMR-WB first so the gateway can pass carrier AMR-WB straight through;
         * AMR-NB likewise for narrowband carrier calls. Note that in SIP the answerer picks.
         */
        val CODEC_ORDER = arrayOf("AMR-WB/", "opus/", "G722/", "AMR/", "PCMA/", "PCMU/")
    }
}
