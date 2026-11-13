package app.sipdialer.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Fallback palette for Android 10/11 (no dynamic color). Seed: #1B6C3A.
private val LightFallback = lightColorScheme(
    primary = Color(0xFF2B6A46),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFB0F1C3),
    onPrimaryContainer = Color(0xFF0F5130),
    secondary = Color(0xFF4F6354),
    secondaryContainer = Color(0xFFD1E8D5),
    onSecondaryContainer = Color(0xFF384B3D),
    tertiary = Color(0xFF3B6470),
    tertiaryContainer = Color(0xFFBEEAF7),
    surface = Color(0xFFF6FBF4),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF0F5EE),
    surfaceContainer = Color(0xFFEAEFE9),
    surfaceContainerHigh = Color(0xFFE4EAE3),
    surfaceContainerHighest = Color(0xFFDFE4DD),
)

private val DarkFallback = darkColorScheme(
    primary = Color(0xFF95D5A8),
    onPrimary = Color(0xFF00391E),
    primaryContainer = Color(0xFF0F5130),
    onPrimaryContainer = Color(0xFFB0F1C3),
    secondary = Color(0xFFB5CCB9),
    secondaryContainer = Color(0xFF384B3D),
    onSecondaryContainer = Color(0xFFD1E8D5),
    tertiary = Color(0xFFA3CDDB),
    tertiaryContainer = Color(0xFF214C57),
    surface = Color(0xFF0F1511),
    surfaceContainerLowest = Color(0xFF0A0F0C),
    surfaceContainerLow = Color(0xFF171D19),
    surfaceContainer = Color(0xFF1B211D),
    surfaceContainerHigh = Color(0xFF252B27),
    surfaceContainerHighest = Color(0xFF303632),
)

/** Fixed colors for answer/decline. They must stay recognisable whatever the wallpaper is. */
@Immutable
data class CallColors(
    val accept: Color,
    val onAccept: Color,
    val decline: Color,
    val onDecline: Color,
)

private val LightCallColors = CallColors(Color(0xFF1E8E3E), Color.White, Color(0xFFD93025), Color.White)
private val DarkCallColors = CallColors(Color(0xFF6DD58C), Color(0xFF00391C), Color(0xFFFFB4AB), Color(0xFF690005))

val LocalCallColors = staticCompositionLocalOf { LightCallColors }

@Composable
fun SipDialerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme: ColorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkFallback
        else -> LightFallback
    }
    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = MotionScheme.expressive(),
    ) {
        androidx.compose.runtime.CompositionLocalProvider(
            LocalCallColors provides if (darkTheme) DarkCallColors else LightCallColors,
            content = content,
        )
    }
}

val MaterialTheme.callColors: CallColors
    @Composable get() = LocalCallColors.current
