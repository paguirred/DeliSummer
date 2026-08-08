package cl.aguirre.cuaderno.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

private val Ink = Color(0xFF1B3A5C)
private val InkLight = Color(0xFF7FB2E5)
private val Accent = Color(0xFFF0A030)

private val LightColors = lightColorScheme(
    primary = Ink,
    onPrimary = Color.White,
    secondary = InkLight,
    tertiary = Accent,
    background = Color(0xFFF5F7FA),
    surface = Color.White,
    onSurface = Color(0xFF15202B),
)

private val DarkColors = darkColorScheme(
    primary = InkLight,
    onPrimary = Color(0xFF0B1926),
    secondary = Ink,
    tertiary = Accent,
    background = Color(0xFF11171E),
    surface = Color(0xFF19212B),
    onSurface = Color(0xFFE6ECF2),
)

@Composable
fun CuadernoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}

/**
 * Convierte un color de Compose al Long empaquetado que espera el pincel.
 *
 * Existe para no tener que traer `android.graphics.Color` a los archivos de UI,
 * donde ya vive el `Color` de Compose: dos tipos con el mismo nombre simple en
 * el mismo archivo es una fuente segura de confusion.
 */
fun Color.toPackedLong(): Long = android.graphics.Color.pack(toArgb())

/** Paleta de tinta. Los valores son ARGB y se empaquetan a Long para el pincel. */
object InkPalette {
    val colors = listOf(
        Color(0xFF15202B), // negro tinta
        Color(0xFF1B57B8), // azul
        Color(0xFFC62828), // rojo
        Color(0xFF2E7D32), // verde
        Color(0xFF6A1B9A), // morado
        Color(0xFFEF6C00), // naranjo
    )

    val highlighterColors = listOf(
        Color(0x80FFEB3B),
        Color(0x8076FF03),
        Color(0x80FF4081),
        Color(0x8040C4FF),
    )
}
