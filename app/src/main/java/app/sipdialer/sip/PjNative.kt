package app.sipdialer.sip

/**
 * JNI binding to PJSIP's pjsua API (app/src/main/cpp/pjsip_jni.c).
 *
 * Calls are identified by a token: Kotlin picks it for outgoing calls, native code assigns
 * one (>= [INCOMING_TOKEN_BASE]) to incoming calls. Functions returning `String?` return null
 * on success and an error message otherwise. All functions may be called from any thread.
 */
internal object PjNative {
    const val INCOMING_TOKEN_BASE = 0x40000000

    const val TRANSPORT_UDP = 0
    const val TRANSPORT_TCP = 1
    const val TRANSPORT_TLS = 2

    init {
        System.loadLibrary("sipdialer")
    }

    external fun start(
        listener: PjListener,
        userAgent: String,
        debug: Boolean,
        caPath: String,
    ): String?

    /** Enables exactly the codecs whose id starts with one of [prefixes], in that order. */
    external fun setCodecOrder(prefixes: Array<String>): Array<String>

    external fun setAccount(
        id: String,
        registrar: String,
        proxy: String,
        authUser: String,
        password: String,
        transport: Int,
    ): String?

    external fun removeAccount()

    external fun refreshRegistration()

    external fun ipChanged()

    external fun makeCall(
        uri: String,
        token: Int,
    ): String?

    external fun answer(token: Int)

    external fun hangup(
        token: Int,
        code: Int,
    )

    external fun setHold(
        token: Int,
        hold: Boolean,
    )

    external fun sendDtmf(
        token: Int,
        digits: String,
    )

    external fun setMicMuted(muted: Boolean)
}

/**
 * Callbacks from PJSIP's worker threads. Implementations must not block or call back into
 * [PjNative] synchronously: post to another thread instead. Byte arrays are UTF-8 text.
 */
internal interface PjListener {
    /** [expires] is 0 when unregistered; [renew] is false for an un-REGISTER. */
    fun onRegState(
        code: Int,
        reason: ByteArray,
        expires: Int,
        renew: Boolean,
    )

    /** [state] is a pjsip_inv_state ([InvState]); [remote] the remote name-addr. */
    fun onCallState(
        token: Int,
        state: Int,
        incoming: Boolean,
        lastStatus: Int,
        lastReason: ByteArray,
        remote: ByteArray,
    )

    /** [status] is a pjsua_call_media_status ([MediaStatus]); [codec] e.g. "AMR-WB". */
    fun onMediaState(
        token: Int,
        status: Int,
        codec: String,
        clockRate: Int,
    )
}

/** pjsip_inv_state */
internal object InvState {
    const val CALLING = 1
    const val INCOMING = 2
    const val EARLY = 3
    const val CONNECTING = 4
    const val CONFIRMED = 5
    const val DISCONNECTED = 6
}

/** pjsua_call_media_status */
internal object MediaStatus {
    const val NONE = 0
    const val ACTIVE = 1
    const val LOCAL_HOLD = 2
    const val REMOTE_HOLD = 3
    const val ERROR = 4
}
