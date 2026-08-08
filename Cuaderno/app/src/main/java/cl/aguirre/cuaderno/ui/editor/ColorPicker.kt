package cl.aguirre.cuaderno.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/**
 * Selector de color HSV.
 *
 * El cuadro grande cubre saturacion y brillo, y la franja de abajo el tono. Se
 * separan asi porque el tono es lo que se busca primero y en un solo gesto,
 * mientras que saturacion y brillo se ajustan despues y conviene verlos juntos:
 * en un cuadro se entiende de inmediato hacia donde hay que moverse.
 */
@Composable
fun ColorPicker(
    color: Color,
    onColorChange: (Color) -> Unit,
    modifier: Modifier = Modifier,
    showAlpha: Boolean = false,
) {
    val initial = remember(Unit) { FloatArray(3).also { rgbToHsv(color, it) } }
    var hue by remember { mutableFloatStateOf(initial[0]) }
    var saturation by remember { mutableFloatStateOf(initial[1]) }
    var value by remember { mutableFloatStateOf(initial[2]) }
    var alpha by remember { mutableFloatStateOf(color.alpha) }

    fun emit() {
        onColorChange(Color.hsv(hue, saturation, value).copy(alpha = alpha))
    }

    val pureHue = Color.hsv(hue, 1f, 1f)

    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {

        // Saturacion (eje X) y brillo (eje Y)
        Box(
            Modifier
                .fillMaxWidth()
                .height(160.dp)
                .clip(RoundedCornerShape(12.dp))
                .pointerInput(Unit) {
                    detectTapGestures { position ->
                        saturation = (position.x / size.width).coerceIn(0f, 1f)
                        value = 1f - (position.y / size.height).coerceIn(0f, 1f)
                        emit()
                    }
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        // El arrastre tiene que tomar el color desde el primer
                        // contacto, no recien al superar el umbral de arrastre.
                        onDragStart = { position ->
                            saturation = (position.x / size.width).coerceIn(0f, 1f)
                            value = 1f - (position.y / size.height).coerceIn(0f, 1f)
                            emit()
                        },
                    ) { change, _ ->
                        saturation = (change.position.x / size.width).coerceIn(0f, 1f)
                        value = 1f - (change.position.y / size.height).coerceIn(0f, 1f)
                        change.consume()
                        emit()
                    }
                },
        ) {
            Canvas(Modifier.fillMaxWidth().height(160.dp)) {
                drawRect(Brush.horizontalGradient(listOf(Color.White, pureHue)))
                drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
            }
            // Marcador de la seleccion actual
            Canvas(Modifier.fillMaxWidth().height(160.dp)) {
                val cx = saturation * size.width
                val cy = (1f - value) * size.height
                drawCircle(Color.White, radius = 9f, center = Offset(cx, cy), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f))
                drawCircle(Color.Black, radius = 12f, center = Offset(cx, cy), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5f))
            }
        }

        GradientSlider(
            fraction = hue / 360f,
            colors = HUE_STOPS,
            onFractionChange = { hue = (it * 360f).coerceIn(0f, 359.99f); emit() },
        )

        if (showAlpha) {
            GradientSlider(
                fraction = alpha,
                colors = listOf(Color.hsv(hue, saturation, value).copy(alpha = 0f), Color.hsv(hue, saturation, value)),
                onFractionChange = { alpha = it.coerceIn(0.05f, 1f); emit() },
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier
                    .size(36.dp)
                    .background(Color.hsv(hue, saturation, value).copy(alpha = alpha), CircleShape)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
            )
            Text(
                hexOf(Color.hsv(hue, saturation, value).copy(alpha = alpha)),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        // Atajos: la mayoria de los apuntes usan estos y no vale la pena
        // navegar el cuadro cada vez.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (preset in PRESETS) {
                Box(
                    Modifier
                        .size(30.dp)
                        .background(preset, CircleShape)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                        .clickable {
                            val hsv = FloatArray(3)
                            rgbToHsv(preset, hsv)
                            hue = hsv[0]; saturation = hsv[1]; value = hsv[2]
                            alpha = preset.alpha
                            emit()
                        },
                )
            }
        }
    }
}

@Composable
private fun GradientSlider(
    fraction: Float,
    colors: List<Color>,
    onFractionChange: (Float) -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(28.dp)
            .clip(RoundedCornerShape(14.dp))
            .pointerInput(colors) {
                detectTapGestures { position ->
                    onFractionChange((position.x / size.width).coerceIn(0f, 1f))
                }
            }
            .pointerInput(colors) {
                detectDragGestures(
                    onDragStart = { position ->
                        onFractionChange((position.x / size.width).coerceIn(0f, 1f))
                    },
                ) { change, _ ->
                    onFractionChange((change.position.x / size.width).coerceIn(0f, 1f))
                    change.consume()
                }
            },
    ) {
        Canvas(Modifier.fillMaxWidth().height(28.dp)) {
            drawRect(Brush.horizontalGradient(colors))
            val x = fraction.coerceIn(0f, 1f) * size.width
            drawCircle(Color.White, radius = size.height / 2f - 3f, center = Offset(x, size.height / 2f), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f))
        }
    }
}

private val HUE_STOPS = listOf(
    Color(0xFFFF0000), Color(0xFFFFFF00), Color(0xFF00FF00),
    Color(0xFF00FFFF), Color(0xFF0000FF), Color(0xFFFF00FF), Color(0xFFFF0000),
)

private val PRESETS = listOf(
    Color(0xFF15202B), Color(0xFF1B57B8), Color(0xFFC62828),
    Color(0xFF2E7D32), Color(0xFF6A1B9A), Color(0xFFEF6C00),
)

private fun rgbToHsv(color: Color, out: FloatArray) {
    android.graphics.Color.RGBToHSV(
        (color.red * 255).toInt(),
        (color.green * 255).toInt(),
        (color.blue * 255).toInt(),
        out,
    )
}

private fun hexOf(color: Color): String {
    val a = (color.alpha * 255).toInt()
    val r = (color.red * 255).toInt()
    val g = (color.green * 255).toInt()
    val b = (color.blue * 255).toInt()
    return if (a >= 255) String.format("#%02X%02X%02X", r, g, b)
    else String.format("#%02X%02X%02X%02X", a, r, g, b)
}
