package app.sipdialer.sip

enum class Transport { UDP, TCP, TLS }

data class SipAccountConfig(
    val username: String,
    val password: String,
    val domain: String,
    val transport: Transport = Transport.TLS,
    val displayName: String = "",
    /** Optional outbound proxy / registrar host[:port]. Defaults to [domain]. */
    val proxy: String = "",
    /** Optional auth username when it differs from [username]. */
    val authUsername: String = "",
)

sealed interface RegistrationStatus {
    data object NoAccount : RegistrationStatus
    data object InProgress : RegistrationStatus
    data object Registered : RegistrationStatus
    data object Unregistered : RegistrationStatus
    data class Failed(val reason: String) : RegistrationStatus
}

enum class CallDirection { INCOMING, OUTGOING }

enum class CallPhase { RINGING_IN, DIALING, RINGING_OUT, ACTIVE, HELD, ENDED }

data class CallInfo(
    val id: String,
    /** The user part (number) or the full SIP URI when there is no user part. */
    val remote: String,
    val remoteUri: String,
    val displayName: String?,
    val direction: CallDirection,
    val phase: CallPhase,
    val connectedAtMs: Long?,
    val muted: Boolean,
) {
    val title: String get() = displayName?.takeIf { it.isNotBlank() } ?: remote
}

enum class EndReason { LOCAL, REMOTE, REJECTED, MISSED, ERROR }

sealed interface CallEvent {
    data class Added(val call: CallInfo) : CallEvent
    data class Connected(val id: String) : CallEvent
    data class Held(val id: String, val held: Boolean) : CallEvent
    data class Ended(val id: String, val reason: EndReason) : CallEvent
}

data class RecentCall(
    val remote: String,
    val remoteUri: String,
    val displayName: String?,
    val direction: CallDirection,
    val missed: Boolean,
    val startMs: Long,
    val durationSec: Int,
) {
    val title: String get() = displayName?.takeIf { it.isNotBlank() } ?: remote
}

/** Audio outputs, mapped from Telecom endpoints to Linphone audio devices. */
enum class AudioRoute { EARPIECE, SPEAKER, BLUETOOTH, WIRED_HEADSET }
