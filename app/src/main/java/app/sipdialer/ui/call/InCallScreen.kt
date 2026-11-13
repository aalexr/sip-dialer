package app.sipdialer.ui.call

import android.text.format.DateUtils
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButtonShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.telecom.CallEndpointCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sipdialer.R
import app.sipdialer.SipDialerApp
import app.sipdialer.sip.CallInfo
import app.sipdialer.sip.CallPhase
import app.sipdialer.telecom.AudioEndpoints
import app.sipdialer.ui.components.Avatar
import app.sipdialer.ui.theme.callColors
import kotlinx.coroutines.delay

@Composable
fun InCallScreen(onFinished: () -> Unit) {
    val app = SipDialerApp.instance
    val calls by app.sip.calls.collectAsStateWithLifecycle()
    val endpoints by app.telecom.endpoints.collectAsStateWithLifecycle()

    val live = calls.firstOrNull { it.phase == CallPhase.RINGING_IN } ?: calls.firstOrNull()
    // Keep showing the last call for a moment after it ends.
    var lastCall by remember { mutableStateOf<CallInfo?>(null) }
    if (live != null) lastCall = live
    val call = live ?: lastCall?.copy(phase = CallPhase.ENDED)

    LaunchedEffect(live == null) {
        if (live == null) {
            delay(if (lastCall == null) 0 else 1200)
            onFinished()
        }
    }

    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxSize()) {
        if (call == null) return@Surface
        Column(
            modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CallHeader(call, modifier = Modifier.weight(1f))
            AnimatedContent(
                targetState = call.phase == CallPhase.RINGING_IN,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "controls",
            ) { ringing ->
                if (ringing) {
                    IncomingControls(
                        onAnswer = { app.telecom.answer(call.id) },
                        onDecline = { app.sip.hangup(call.id) },
                    )
                } else {
                    ActiveControls(
                        call = call,
                        endpoints = endpoints,
                        onMute = { app.sip.setMuted(it) },
                        onHold = { app.sip.setHold(call.id, it) },
                        onDtmf = { app.sip.sendDtmf(call.id, it) },
                        onEndpoint = { app.telecom.requestEndpoint(it) },
                        onHangup = { app.sip.hangup(call.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun CallHeader(call: CallInfo, modifier: Modifier = Modifier) {
    val ringing = call.phase == CallPhase.RINGING_IN || call.phase == CallPhase.RINGING_OUT
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 1f,
        targetValue = if (ringing) 1.08f else 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "pulseScale",
    )
    Column(
        modifier = modifier.fillMaxWidth().padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Avatar(call.title, size = 128.dp, modifier = Modifier.scale(pulse))
        Spacer(Modifier.height(28.dp))
        Text(
            call.title,
            style = MaterialTheme.typography.displaySmall,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (call.displayName != null && call.displayName != call.remote) {
            Text(call.remote, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(12.dp))
        CallStatus(call)
        call.codec?.let { codec ->
            Spacer(Modifier.height(8.dp))
            Surface(
                shape = RoundedCornerShape(percent = 50),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Text(
                    codec,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun CallStatus(call: CallInfo) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(call.connectedAtMs) {
        while (call.connectedAtMs != null) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val text = when (call.phase) {
        CallPhase.RINGING_IN -> "Incoming SIP call"
        CallPhase.DIALING -> "Calling…"
        CallPhase.RINGING_OUT -> "Ringing…"
        CallPhase.HELD -> "On hold"
        CallPhase.ENDED -> "Call ended"
        CallPhase.ACTIVE -> call.connectedAtMs
            ?.let { DateUtils.formatElapsedTime(((now - it) / 1000).coerceAtLeast(0)) }
            ?: "Connecting…"
    }
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = if (call.phase == CallPhase.ENDED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun IncomingControls(onAnswer: () -> Unit, onDecline: () -> Unit) {
    val colors = MaterialTheme.callColors
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        LabeledAction("Decline") {
            FilledIconButton(
                onClick = onDecline,
                shapes = IconButtonDefaults.shapes(),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = colors.decline, contentColor = colors.onDecline),
                modifier = Modifier.size(IconButtonDefaults.extraLargeContainerSize()),
            ) { Icon(painterResource(R.drawable.ic_call_end), contentDescription = "Decline", modifier = Modifier.size(36.dp)) }
        }
        LabeledAction("Answer") {
            FilledIconButton(
                onClick = onAnswer,
                shapes = IconButtonDefaults.shapes(),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = colors.accept, contentColor = colors.onAccept),
                modifier = Modifier.size(IconButtonDefaults.extraLargeContainerSize()),
            ) { Icon(painterResource(R.drawable.ic_call), contentDescription = "Answer", modifier = Modifier.size(36.dp)) }
        }
    }
}

@Composable
private fun ActiveControls(
    call: CallInfo,
    endpoints: AudioEndpoints,
    onMute: (Boolean) -> Unit,
    onHold: (Boolean) -> Unit,
    onDtmf: (Char) -> Unit,
    onEndpoint: (CallEndpointCompat) -> Unit,
    onHangup: () -> Unit,
) {
    var showKeypad by remember { mutableStateOf(false) }
    var dtmfDigits by remember { mutableStateOf("") }
    val enabled = call.phase != CallPhase.ENDED

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(max = 400.dp)) {
        if (showKeypad) {
            Text(dtmfDigits, style = MaterialTheme.typography.headlineMedium, maxLines = 1)
            DtmfPad(onDigit = { dtmfDigits += it; onDtmf(it) })
            Spacer(Modifier.height(16.dp))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            ToggleAction(
                label = if (call.muted) "Unmute" else "Mute",
                icon = if (call.muted) R.drawable.ic_mic_off else R.drawable.ic_mic,
                checked = call.muted,
                enabled = enabled,
                onCheckedChange = onMute,
            )
            ToggleAction(
                label = "Keypad",
                icon = R.drawable.ic_dialpad,
                checked = showKeypad,
                enabled = enabled,
                onCheckedChange = { showKeypad = it },
            )
            AudioRouteAction(endpoints, enabled, onEndpoint)
            ToggleAction(
                label = if (call.phase == CallPhase.HELD) "Resume" else "Hold",
                icon = R.drawable.ic_pause,
                checked = call.phase == CallPhase.HELD,
                enabled = call.phase == CallPhase.ACTIVE || call.phase == CallPhase.HELD,
                onCheckedChange = onHold,
            )
        }

        Spacer(Modifier.height(32.dp))

        val colors = MaterialTheme.callColors
        Button(
            onClick = onHangup,
            enabled = enabled,
            shapes = ButtonDefaults.shapes(),
            colors = ButtonDefaults.buttonColors(containerColor = colors.decline, contentColor = colors.onDecline),
            modifier = Modifier.width(160.dp).height(72.dp),
        ) { Icon(painterResource(R.drawable.ic_call_end), contentDescription = "Hang up", modifier = Modifier.size(32.dp)) }
        Spacer(Modifier.height(24.dp))
    }
}

/** Large tonal toggle that morphs from a circle to a rounded square when checked. */
@Composable
private fun ToggleAction(
    label: String,
    icon: Int,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    LabeledAction(label) {
        FilledTonalIconToggleButton(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            shapes = IconToggleButtonShapes(
                shape = RoundedCornerShape(percent = 50),
                pressedShape = RoundedCornerShape(16.dp),
                checkedShape = RoundedCornerShape(24.dp),
            ),
            modifier = Modifier.size(IconButtonDefaults.largeContainerSize()),
        ) { Icon(painterResource(icon), contentDescription = label, modifier = Modifier.size(28.dp)) }
    }
}

@Composable
private fun AudioRouteAction(endpoints: AudioEndpoints, enabled: Boolean, onEndpoint: (CallEndpointCompat) -> Unit) {
    val current = endpoints.current
    val hasExternal = endpoints.available.any {
        it.type == CallEndpointCompat.TYPE_BLUETOOTH || it.type == CallEndpointCompat.TYPE_WIRED_HEADSET
    }
    val onSpeaker = current?.type == CallEndpointCompat.TYPE_SPEAKER
    var menu by remember { mutableStateOf(false) }

    Box {
        ToggleAction(
            label = when (current?.type) {
                CallEndpointCompat.TYPE_BLUETOOTH -> current.name.toString()
                CallEndpointCompat.TYPE_WIRED_HEADSET -> "Headset"
                else -> "Speaker"
            },
            icon = if (current?.type == CallEndpointCompat.TYPE_BLUETOOTH) R.drawable.ic_bluetooth else R.drawable.ic_volume_up,
            checked = onSpeaker || current?.type == CallEndpointCompat.TYPE_BLUETOOTH,
            enabled = enabled && endpoints.available.isNotEmpty(),
            onCheckedChange = {
                if (hasExternal) {
                    menu = true
                } else {
                    val target = if (onSpeaker) CallEndpointCompat.TYPE_EARPIECE else CallEndpointCompat.TYPE_SPEAKER
                    endpoints.available.firstOrNull { it.type == target }?.let(onEndpoint)
                }
            },
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, shape = MaterialTheme.shapes.large) {
            endpoints.available.forEach { endpoint ->
                DropdownMenuItem(
                    text = { Text(endpoint.name.toString()) },
                    leadingIcon = {
                        Icon(
                            painterResource(
                                when (endpoint.type) {
                                    CallEndpointCompat.TYPE_BLUETOOTH -> R.drawable.ic_bluetooth
                                    CallEndpointCompat.TYPE_EARPIECE -> R.drawable.ic_call
                                    else -> R.drawable.ic_volume_up
                                },
                            ),
                            contentDescription = null,
                        )
                    },
                    onClick = {
                        menu = false
                        onEndpoint(endpoint)
                    },
                )
            }
        }
    }
}

@Composable
private fun LabeledAction(label: String, content: @Composable () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        content()
        Spacer(Modifier.height(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun DtmfPad(onDigit: (Char) -> Unit) {
    val rows = listOf("123", "456", "789", "*0#")
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { digit ->
                    TextButton(
                        onClick = { onDigit(digit) },
                        shapes = ButtonDefaults.shapes(),
                        modifier = Modifier.size(width = 88.dp, height = 56.dp),
                    ) { Text(digit.toString(), style = MaterialTheme.typography.headlineSmall) }
                }
            }
        }
    }
}
