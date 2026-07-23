package com.zerochat.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Color palette
private val LightPrimary = Color(0xFF1B6EF3)
private val LightOnPrimary = Color(0xFFFFFFFF)
private val LightPrimaryContainer = Color(0xFFD6E3FF)
private val LightSecondary = Color(0xFF535F70)
private val LightBackground = Color(0xFFFDFBFF)
private val LightSurface = Color(0xFFFDFBFF)
private val LightSurfaceVariant = Color(0xFFE0E2EC)

private val DarkPrimary = Color(0xFFA8C8FF)
private val DarkOnPrimary = Color(0xFF00317A)
private val DarkPrimaryContainer = Color(0xFF004BAD)
private val DarkSecondary = Color(0xFFBAC7DB)
private val DarkBackground = Color(0xFF1A1C1E)
private val DarkSurface = Color(0xFF1A1C1E)
private val DarkSurfaceVariant = Color(0xFF44474E)

// Message bubble colors
val SentMessageColor = Color(0xFF1B6EF3)
val ReceivedMessageColor = Color(0xFFE8E8EC)
val OnlineIndicator = Color(0xFF4CAF50)
val OfflineIndicator = Color(0xFFBDBDBD)

private val LightColorScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    primaryContainer = LightPrimaryContainer,
    secondary = LightSecondary,
    background = LightBackground,
    surface = LightSurface,
    surfaceVariant = LightSurfaceVariant,
)

private val DarkColorScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer,
    secondary = DarkSecondary,
    background = DarkBackground,
    surface = DarkSurface,
    surfaceVariant = DarkSurfaceVariant,
)

@Composable
fun ZeroChatTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        content = content,
    )
}
