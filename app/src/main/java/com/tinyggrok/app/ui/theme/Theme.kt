package com.tinyggrok.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

enum class AppTheme {
    LIGHT, DARK, TOKYO_NIGHT
}

@Composable
fun TinyGrokTheme(
    appTheme: AppTheme = AppTheme.DARK,
    content: @Composable () -> Unit
) {
    val colorScheme = when (appTheme) {
        AppTheme.LIGHT -> lightColorScheme(
            primary = LightPrimary,
            onPrimary = LightOnPrimary,
            background = LightBackground,
            surface = LightSurface,
            onSurface = LightOnSurface
        )
        AppTheme.DARK -> darkColorScheme(
            primary = DarkPrimary,
            onPrimary = DarkOnPrimary,
            background = DarkBackground,
            surface = DarkSurface,
            onSurface = DarkOnSurface
        )
        AppTheme.TOKYO_NIGHT -> darkColorScheme(
            primary = TokyoNightPrimary,
            onPrimary = TokyoNightOnPrimary,
            background = TokyoNightBackground,
            surface = TokyoNightSurface,
            onSurface = TokyoNightOnSurface,
            secondary = TokyoNightSecondary,
            onSecondary = TokyoNightOnSecondary,
            tertiary = TokyoNightAccent,
            onTertiary = TokyoNightText
        )
    }

    // The clock and status icons follow the theme: dark on the light theme, where they were
    // white on near-white and all but invisible, light on the dark ones.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = appTheme == AppTheme.LIGHT
                isAppearanceLightNavigationBars = appTheme == AppTheme.LIGHT
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        content = content
    )
}
