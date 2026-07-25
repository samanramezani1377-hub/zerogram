package com.zerochat.ui.theme

import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// ── Telegram-Inspired Colors ────────────────────────────────────────

val TelegramBlue = Color(0xFF2AABEE)
val TelegramBlueDark = Color(0xFF1C93D6)
val TelegramBlueLight = Color(0xFF4FC3F7)
val TelegramBlueSurface = Color(0xFFE3F2FD)

val TelegramDarkBg = Color(0xFF0E1621)
val TelegramDarkSurface = Color(0xFF182533)
val TelegramDarkCard = Color(0xFF1F2D3E)
val TelegramDarkInput = Color(0xFF253548)

val TelegramGreen = Color(0xFF4CAF50)
val TelegramGreenLight = Color(0xFF81C784)
val TelegramRed = Color(0xFFE53935)
val TelegramOrange = Color(0xFFFF9800)

val TelegramWhite = Color(0xFFFFFFFF)
val TelegramBlack = Color(0xFF000000)
val TelegramGray = Color(0xFF8E99A4)
val TelegramDivider = Color(0xFFE0E0E0)
val TelegramDarkDivider = Color(0xFF2A3A4A)

val TelegramMsgBlue = Color(0xFF2AABEE)
val TelegramMsgGreen = Color(0xFFE0F7FA)
val TelegramMsgBlueDark = Color(0xFF1A6D96)
val TelegramMsgGreenDark = Color(0xFF1A3A3C)

// ── Light Color Scheme ─────────────────────────────────────────────

private val LightColorScheme = lightColorScheme(
    primary = TelegramBlue,
    onPrimary = TelegramWhite,
    primaryContainer = TelegramBlueSurface,
    onPrimaryContainer = Color(0xFF003548),
    secondary = TelegramGreen,
    onSecondary = TelegramWhite,
    secondaryContainer = Color(0xFFC8E6C9),
    onSecondaryContainer = Color(0xFF1B5E20),
    tertiary = TelegramOrange,
    onTertiary = TelegramWhite,
    tertiaryContainer = Color(0xFFFFF3E0),
    onTertiaryContainer = Color(0xFFE65100),
    error = TelegramRed,
    onError = TelegramWhite,
    errorContainer = Color(0xFFFFCDD2),
    onErrorContainer = Color(0xFFB71C1C),
    background = Color(0xFFF5F5F5),
    onBackground = Color(0xFF1C1C1E),
    surface = TelegramWhite,
    onSurface = Color(0xFF1C1C1E),
    surfaceVariant = Color(0xFFF0F0F0),
    onSurfaceVariant = Color(0xFF707579),
    outline = Color(0xFFC4C4C6),
    outlineVariant = Color(0xFFE5E5EA),
    inverseSurface = Color(0xFF2C2C2E),
    inverseOnSurface = Color(0xFFF2F2F7),
    inversePrimary = TelegramBlueLight,
)

// ── Dark Color Scheme ──────────────────────────────────────────────

private val DarkColorScheme = darkColorScheme(
    primary = TelegramBlue,
    onPrimary = TelegramWhite,
    primaryContainer = Color(0xFF003548),
    onPrimaryContainer = TelegramBlueSurface,
    secondary = TelegramGreenLight,
    onSecondary = Color(0xFF1B5E20),
    secondaryContainer = Color(0xFF1B5E20),
    onSecondaryContainer = Color(0xFFC8E6C9),
    tertiary = TelegramOrange,
    onTertiary = Color(0xFFE65100),
    tertiaryContainer = Color(0xFF4E342E),
    onTertiaryContainer = Color(0xFFFFF3E0),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFFB71C1C),
    errorContainer = Color(0xFF4E1515),
    onErrorContainer = Color(0xFFFFCDD2),
    background = TelegramDarkBg,
    onBackground = Color(0xFFE5E5EA),
    surface = TelegramDarkSurface,
    onSurface = Color(0xFFE5E5EA),
    surfaceVariant = TelegramDarkCard,
    onSurfaceVariant = Color(0xFF8E99A4),
    outline = Color(0xFF3A4A5A),
    outlineVariant = TelegramDarkDivider,
    inverseSurface = Color(0xFFE5E5EA),
    inverseOnSurface = Color(0xFF1C1C1E),
    inversePrimary = TelegramBlue,
)

// ── Dynamic Color ──────────────────────────────────────────────────

@ChecksSdkIntAtLeast(api = Build.VERSION_CODES.S)
private fun supportsDynamicColor() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

@Composable
fun ZeroGramTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && supportsDynamicColor() -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context)
            else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as android.app.Activity).window
            WindowCompat.getInsetsController(window, view)
                .isAppearanceLightStatusBars = !darkTheme
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = if (darkTheme)
                android.graphics.Color.parseColor("#FF182533")
            else
                android.graphics.Color.parseColor("#FFFFFFFF")
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
