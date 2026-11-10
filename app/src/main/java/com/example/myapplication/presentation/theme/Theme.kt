package com.example.myapplication.presentation.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

val LocalIsDarkTheme = staticCompositionLocalOf { false }

private val LightColors = lightColorScheme(
    primary                 = LightPrimary,
    onPrimary               = LightOnPrimary,
    primaryContainer        = LightPrimaryContainer,
    onPrimaryContainer      = LightOnPrimaryContainer,
    secondary               = LightSecondary,
    onSecondary             = LightOnSecondary,
    secondaryContainer      = LightSecondaryContainer,
    onSecondaryContainer    = LightOnSecondaryContainer,
    tertiary                = LightTertiary,
    onTertiary              = LightOnTertiary,
    tertiaryContainer       = LightTertiaryContainer,
    onTertiaryContainer     = LightOnTertiaryContainer,
    error                   = LightError,
    onError                 = LightOnError,
    errorContainer          = LightErrorContainer,
    onErrorContainer        = LightOnErrorContainer,
    background              = LightBackground,
    onBackground            = LightOnBackground,
    surface                 = LightSurface,
    onSurface               = LightOnSurface,
    surfaceVariant          = LightSurfaceVariant,
    onSurfaceVariant        = LightOnSurfaceVariant,
    outline                 = LightOutline,
    outlineVariant          = LightOutlineVariant,
    inverseSurface          = LightInverseSurface,
    inverseOnSurface        = LightInverseOnSurface,
    inversePrimary          = LightInversePrimary,
    surfaceTint             = LightSurfaceTint,
)

private val DarkColors = darkColorScheme(
    primary                 = DarkPrimary,
    onPrimary               = DarkOnPrimary,
    primaryContainer        = DarkPrimaryContainer,
    onPrimaryContainer      = DarkOnPrimaryContainer,
    secondary               = DarkSecondary,
    onSecondary             = DarkOnSecondary,
    secondaryContainer      = DarkSecondaryContainer,
    onSecondaryContainer    = DarkOnSecondaryContainer,
    tertiary                = DarkTertiary,
    onTertiary              = DarkOnTertiary,
    tertiaryContainer       = DarkTertiaryContainer,
    onTertiaryContainer     = DarkOnTertiaryContainer,
    error                   = DarkError,
    onError                 = DarkOnError,
    errorContainer          = DarkErrorContainer,
    onErrorContainer        = DarkOnErrorContainer,
    background              = DarkBackground,
    onBackground            = DarkOnBackground,
    surface                 = DarkSurface,
    onSurface               = DarkOnSurface,
    surfaceVariant          = DarkSurfaceVariant,
    onSurfaceVariant        = DarkOnSurfaceVariant,
    outline                 = DarkOutline,
    outlineVariant          = DarkOutlineVariant,
    inverseSurface          = DarkInverseSurface,
    inverseOnSurface        = DarkInverseOnSurface,
    inversePrimary          = DarkInversePrimary,
    surfaceTint             = DarkSurfaceTint,
)

@Composable
fun TranswireTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.setDecorFitsSystemWindows(window, false)
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                window.statusBarColor = android.graphics.Color.TRANSPARENT
            }
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    CompositionLocalProvider(LocalIsDarkTheme provides darkTheme) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography  = TranswireTypography,
            shapes      = TranswireShapes,
            content     = content
        )
    }
}
