package eu.elraro.babytracker.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Fallback palette for Android 11 and older, where the system offers no dynamic colors.
private val LightColors =
    lightColorScheme(
        primary = Color(0xFF2E6F6A),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFB3EFE8),
        onPrimaryContainer = Color(0xFF00201E),
        secondary = Color(0xFF4A6360),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFCCE8E4),
        onSecondaryContainer = Color(0xFF051F1D),
        tertiary = Color(0xFF7A5733),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFFFDCBC),
        onTertiaryContainer = Color(0xFF2B1700),
        error = Color(0xFFBA1A1A),
        onError = Color(0xFFFFFFFF),
        background = Color(0xFFF5FBF9),
        onBackground = Color(0xFF171D1C),
        surface = Color(0xFFF5FBF9),
        onSurface = Color(0xFF171D1C),
        surfaceVariant = Color(0xFFDAE5E2),
        onSurfaceVariant = Color(0xFF3F4947),
        outline = Color(0xFF6F7977),
    )

private val DarkColors =
    darkColorScheme(
        primary = Color(0xFF97D3CC),
        onPrimary = Color(0xFF003734),
        primaryContainer = Color(0xFF0F504B),
        onPrimaryContainer = Color(0xFFB3EFE8),
        secondary = Color(0xFFB1CCC8),
        onSecondary = Color(0xFF1C3532),
        secondaryContainer = Color(0xFF334B48),
        onSecondaryContainer = Color(0xFFCCE8E4),
        tertiary = Color(0xFFECBE91),
        onTertiary = Color(0xFF452B08),
        tertiaryContainer = Color(0xFF5F411D),
        onTertiaryContainer = Color(0xFFFFDCBC),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        background = Color(0xFF0F1514),
        onBackground = Color(0xFFDEE4E2),
        surface = Color(0xFF0F1514),
        onSurface = Color(0xFFDEE4E2),
        surfaceVariant = Color(0xFF3F4947),
        onSurfaceVariant = Color(0xFFBEC9C6),
        outline = Color(0xFF899391),
    )

@Composable
fun BabyTrackerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors =
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                val context = LocalContext.current
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            }
            darkTheme -> DarkColors
            else -> LightColors
        }
    MaterialTheme(colorScheme = colors, content = content)
}
