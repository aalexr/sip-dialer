package app.sipdialer.ui.dialer

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.sipdialer.R
import app.sipdialer.ui.theme.callColors

private data class Key(val digit: Char, val letters: String = "", val longPress: Char? = null)

private val Keys = listOf(
    listOf(Key('1'), Key('2', "ABC"), Key('3', "DEF")),
    listOf(Key('4', "GHI"), Key('5', "JKL"), Key('6', "MNO")),
    listOf(Key('7', "PQRS"), Key('8', "TUV"), Key('9', "WXYZ")),
    listOf(Key('*'), Key('0', "+", longPress = '+'), Key('#')),
)

@Composable
fun DialerScreen(
    initialNumber: String?,
    onCall: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var number by rememberSaveable(initialNumber) { mutableStateOf(initialNumber.orEmpty()) }
    var textMode by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            if (textMode) {
                OutlinedTextField(
                    value = number,
                    onValueChange = { number = it },
                    label = { Text("Number or SIP address") },
                    placeholder = { Text("alice@sip.example.com") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.large,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { if (number.isNotBlank()) onCall(number) }),
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                BasicText(
                    text = number,
                    maxLines = 1,
                    autoSize = TextAutoSize.StepBased(minFontSize = 24.sp, maxFontSize = 44.sp),
                    style = MaterialTheme.typography.displayMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Column(
            modifier = Modifier.widthIn(max = 360.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!textMode) {
                for (row in Keys) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        for (key in row) {
                            DialKey(
                                key = key,
                                onPress = { number += it },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    IconButton(onClick = { textMode = !textMode }) {
                        Icon(
                            painterResource(if (textMode) R.drawable.ic_dialpad else R.drawable.ic_keyboard),
                            contentDescription = if (textMode) "Show keypad" else "Type a SIP address",
                        )
                    }
                }
                CallButton(
                    enabled = number.isNotBlank(),
                    onClick = { onCall(number) },
                    modifier = Modifier.weight(1f),
                )
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    androidx.compose.animation.AnimatedVisibility(
                        visible = number.isNotEmpty() && !textMode,
                        enter = fadeIn() + scaleIn(),
                        exit = fadeOut() + scaleOut(),
                    ) {
                        BackspaceButton(
                            onClick = { number = number.dropLast(1) },
                            onLongClick = { number = "" },
                        )
                    }
                }
            }
        }
    }
}

/**
 * A dial key that morphs from a circle to a rounded square while pressed,
 * using the theme's expressive spring.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DialKey(key: Key, onPress: (Char) -> Unit, modifier: Modifier = Modifier) {
    val view = LocalView.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val corner by animateIntAsState(
        targetValue = if (pressed) 28 else 50,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "keyShape",
    )
    val shape = RoundedCornerShape(percent = corner)

    Box(
        modifier = modifier
            .aspectRatio(1.35f)
            .clip(shape)
            .background(if (pressed) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
            .combinedClickable(
                interactionSource = interaction,
                indication = ripple(),
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    onPress(key.digit)
                },
                onLongClick = key.longPress?.let { alt ->
                    {
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        onPress(alt)
                    }
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = key.digit.toString(),
                style = MaterialTheme.typography.headlineMedium,
                color = if (pressed) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
            )
            if (key.letters.isNotEmpty()) {
                Text(
                    text = key.letters,
                    style = MaterialTheme.typography.labelSmall.merge(TextStyle(letterSpacing = 1.5.sp)),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CallButton(enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.callColors
    Button(
        onClick = onClick,
        enabled = enabled,
        shapes = ButtonDefaults.shapes(),
        colors = ButtonDefaults.buttonColors(containerColor = colors.accept, contentColor = colors.onAccept),
        modifier = modifier.height(64.dp),
    ) {
        Icon(painterResource(R.drawable.ic_call), contentDescription = "Call", modifier = Modifier.size(28.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BackspaceButton(onClick: () -> Unit, onLongClick: () -> Unit) {
    val view = LocalView.current
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(RoundedCornerShape(percent = 50))
            .combinedClickable(
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    onClick()
                },
                onLongClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    onLongClick()
                },
                onClickLabel = "Delete",
                onLongClickLabel = "Clear",
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(R.drawable.ic_backspace),
            contentDescription = "Delete",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
