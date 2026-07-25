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

// ── Brand Colors — ZeroGram identity palette ────────────────────────

private val LightTeal = Color(0xFF006D5B)
private val OnLightTeal = Color(0xFFFFFFFF)
private val LightTealContainer = Color(0xFFA7F2DD)
private val OnLightTealContainer = Color(0xFF002019)

private val DarkTeal = Color(0xFF8CD5C5)
private val OnDarkTeal = Color(0xFF00382D)
private val DarkTealContainer = Color(0xFF005143)
private val OnDarkTealContainer = Color(0xFFA7F2DD)

private val LightAccent = Color(0xFF7C5800)
private val OnLightAccent = Color(0xFFFFFFFF)
private val LightAccentContainer = Color(0xFFFFDEA6)
private val OnLightAccentContainer = Color(0xFF271900)

private val DarkAccent = Color(0xFFEFC04B)
private val OnDarkAccent = Color(0xFF412D00)
private val DarkAccentContainer = Color(0xFF5E4300)
private val OnDarkAccentContainer = Color(0xFFFFDEA6)

private val LightError = Color(0xFFBA1A1A)
private val OnLightError = Color(0xFFFFFFFF)
private val LightErrorContainer = Color(0xFFFFDAD6)
private val OnLightErrorContainer = Color(0xFF410002)

private val DarkError = Color(0xFFFFB4AB)
private val OnDarkError = Color(0xFF690005)
private val DarkErrorContainer = Color(0xFF93000A)
private val OnDarkErrorContainer = Color(0xFFFFDAD6)

// ── Color Schemes ──────────────────────────────────────────────────

private val LightColorScheme = lightColorScheme(
    primary = LightTeal,
    onPrimary = OnLightTeal,
    primaryContainer = LightTealContainer,
    onPrimaryContainer = OnLightTealContainer,
    secondary = LightAccent,
    onSecondary = OnLightAccent,
    secondaryContainer = LightAccentContainer,
    onSecondaryContainer = OnLightAccentContainer,
    error = LightError,
    onError = OnLightError,
    errorContainer = LightErrorContainer,
    onErrorContainer = OnLightErrorContainer,
)

private val DarkColorScheme = darkColorScheme(
    primary = DarkTeal,
    onPrimary = OnDarkTeal,
    primaryContainer = DarkTealContainer,
    onPrimaryContainer = OnDarkTealContainer,
    secondary = DarkAccent,
    onSecondary = OnDarkAccent,
    secondaryContainer = DarkAccentContainer,
    onSecondaryContainer = OnDarkAccentContainer,
    error = DarkError,
    onError = OnDarkError,
    errorContainer = DarkErrorContainer,
    onErrorContainer = OnDarkErrorContainer,
)

// ── Dynamic Color ──────────────────────────────────────────────────

@ChecksSdkIntAtLeast(api = Build.VERSION_CODES.S)
private fun supportsDynamicColor() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

@Composable
fun ZeroGramTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
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
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
