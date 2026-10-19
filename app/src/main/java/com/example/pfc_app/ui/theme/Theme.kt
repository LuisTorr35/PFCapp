package com.example.pfc_app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

// Esquema monocromo como en las maquetas: botón principal negro, tarjetas con borde.
private val LightColorScheme = lightColorScheme(
    primary = Negro,
    onPrimary = Blanco,
    secondary = GrisOscuro,
    onSecondary = Blanco,
    tertiary = GrisTexto,
    background = Blanco,
    onBackground = Negro,
    surface = Blanco,
    onSurface = Negro,
    surfaceVariant = GrisClaro,
    onSurfaceVariant = GrisTexto,
    outline = Negro,
    outlineVariant = GrisBorde,
    error = RojoError,
    errorContainer = RojoErrorClaro,
    onErrorContainer = Negro,
)

private val DarkColorScheme = darkColorScheme(
    primary = Blanco,
    onPrimary = Negro,
    secondary = GrisClaro,
    onSecondary = Negro,
    tertiary = GrisBorde,
    background = Negro,
    onBackground = Blanco,
    surface = Negro,
    onSurface = Blanco,
    surfaceVariant = GrisOscuro,
    onSurfaceVariant = GrisBorde,
    outline = Blanco,
    outlineVariant = GrisTexto,
)

@Composable
fun PFCappTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Sin color dinámico: la app conserva la estética monocroma de las maquetas.
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
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
        typography = Typography,
        content = content
    )
}
