package com.github.unldenis.easyplannerapp.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColorScheme = lightColorScheme(
    primary = ShadcnZinc.z900,
    onPrimary = ShadcnZinc.z50,
    primaryContainer = ShadcnZinc.z100,
    onPrimaryContainer = ShadcnZinc.z900,
    secondary = ShadcnZinc.z100,
    onSecondary = ShadcnZinc.z900,
    secondaryContainer = ShadcnZinc.z200,
    onSecondaryContainer = ShadcnZinc.z900,
    tertiary = ShadcnZinc.z200,
    onTertiary = ShadcnZinc.z900,
    background = ShadcnZinc.z50,
    onBackground = ShadcnZinc.z950,
    surface = Color.White,
    onSurface = ShadcnZinc.z950,
    surfaceVariant = ShadcnZinc.z100,
    onSurfaceVariant = ShadcnZinc.z600,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = ShadcnZinc.z50,
    surfaceContainer = ShadcnZinc.z100,
    surfaceContainerHigh = ShadcnZinc.z100,
    surfaceContainerHighest = ShadcnZinc.z200,
    outline = ShadcnZinc.z200,
    outlineVariant = ShadcnZinc.z100,
    error = ShadcnZinc.destructive,
    onError = ShadcnZinc.onDestructive,
)

private val DarkColorScheme = darkColorScheme(
    primary = ShadcnZinc.z50,
    onPrimary = ShadcnZinc.z950,
    primaryContainer = ShadcnZinc.z800,
    onPrimaryContainer = ShadcnZinc.z50,
    secondary = ShadcnZinc.z800,
    onSecondary = ShadcnZinc.z50,
    secondaryContainer = ShadcnZinc.z700,
    onSecondaryContainer = ShadcnZinc.z50,
    tertiary = ShadcnZinc.z700,
    onTertiary = ShadcnZinc.z50,
    background = ShadcnZinc.z950,
    onBackground = ShadcnZinc.z50,
    surface = ShadcnZinc.z950,
    onSurface = ShadcnZinc.z50,
    surfaceVariant = ShadcnZinc.z800,
    onSurfaceVariant = ShadcnZinc.z400,
    surfaceContainerLowest = ShadcnZinc.z950,
    surfaceContainerLow = ShadcnZinc.z900,
    surfaceContainer = ShadcnZinc.z800,
    surfaceContainerHigh = ShadcnZinc.z800,
    surfaceContainerHighest = ShadcnZinc.z700,
    outline = ShadcnZinc.z700,
    outlineVariant = ShadcnZinc.z800,
    error = Color(0xFFEF4444),
    onError = ShadcnZinc.z950,
)

/**
 * Fixed neutral appearance (shadcn-style zinc). Choose light or dark explicitly (no system/wallpaper color).
 */
@Composable
fun EasyPlannerTheme(
    themeMode: ThemeMode = ThemeMode.LIGHT,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}
