package com.mnldz.asistente

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Colores de Claude (los mismos que la app de escritorio)
@Immutable
data class Colores(
    val fondo: Color, val superficie: Color, val elevado: Color, val burbuja: Color,
    val texto: Color, val texto2: Color, val apagado: Color, val borde: Color,
    val acento: Color, val codigo: Color, val error: Color,
)

private val Oscuro = Colores(
    fondo = Color(0xFF262624), superficie = Color(0xFF30302E), elevado = Color(0xFF3A3A37), burbuja = Color(0xFF141413),
    texto = Color(0xFFF5F4EE), texto2 = Color(0xFFC9C7BE), apagado = Color(0xFF9A988F), borde = Color(0x26FFFFFF),
    acento = Color(0xFFD97757), codigo = Color(0xFF1F1E1D), error = Color(0xFFE5675A),
)
private val Claro = Colores(
    fondo = Color(0xFFFAF9F5), superficie = Color(0xFFFFFFFF), elevado = Color(0xFFF0EEE6), burbuja = Color(0xFFF0EEE6),
    texto = Color(0xFF141413), texto2 = Color(0xFF3D3D3A), apagado = Color(0xFF73726C), borde = Color(0x1F000000),
    acento = Color(0xFFC6613F), codigo = Color(0xFFF2F0E8), error = Color(0xFFB53B2E),
)

val LocalColores = staticCompositionLocalOf { Oscuro }

@Composable
fun TemaAsistente(content: @Composable () -> Unit) {
    val c = if (isSystemInDarkTheme()) Oscuro else Claro
    val esquema = if (isSystemInDarkTheme())
        darkColorScheme(primary = c.acento, background = c.fondo, surface = c.superficie, onBackground = c.texto, onSurface = c.texto, surfaceVariant = c.elevado, outline = c.borde)
    else
        lightColorScheme(primary = c.acento, background = c.fondo, surface = c.superficie, onBackground = c.texto, onSurface = c.texto, surfaceVariant = c.elevado, outline = c.borde)
    androidx.compose.runtime.CompositionLocalProvider(LocalColores provides c) {
        MaterialTheme(colorScheme = esquema, content = content)
    }
}
