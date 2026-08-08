package cl.aguirre.cuaderno.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import cl.aguirre.cuaderno.ink.BrushCatalog
import cl.aguirre.cuaderno.ink.BrushKind
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * Muestra de como sale el trazo con la herramienta y los ajustes actuales.
 *
 * Dibuja una curva con la presion subiendo y bajando a lo largo del recorrido,
 * que es lo que hace un trazo real. Sin esto, el control de sensibilidad a la
 * presion se ajusta a ciegas: hay que probar en la hoja, borrar, y volver a
 * probar.
 *
 * No usa el motor de tinta sino una aproximacion propia. Rasterizar un trazo de
 * verdad para una miniatura obligaria a construir un lote de entrada sintetico
 * en cada recomposicion, y lo que se necesita mostrar aqui es el caracter del
 * trazo, no su geometria exacta.
 */
@Composable
fun StrokePreview(
    kind: BrushKind,
    sizeValue: Int,
    color: Color,
    pressureGamma: Float,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas

        val maxHalfWidth = (BrushCatalog.sizeToPoints(sizeValue) * 0.9f)
            .coerceIn(1f, h / 2.5f) / 2f

        val steps = 96
        val top = ArrayList<Offset>(steps + 1)
        val bottom = ArrayList<Offset>(steps + 1)

        for (i in 0..steps) {
            val t = i / steps.toFloat()
            val x = w * (0.06f + 0.88f * t)
            // Onda suave: dos curvas encadenadas, como una firma.
            val y = h / 2f - sin(t * 2f * PI.toFloat()) * h * 0.22f

            // Presion simulada: entra suave, sube al medio, sale suave. Es el
            // perfil tipico de un trazo hecho de un tiron.
            val rawPressure = sin(t * PI.toFloat()).coerceIn(0f, 1f).pow(0.7f)
            val curved = if (kind.respondsToPressure) {
                rawPressure.pow(pressureGamma)
            } else {
                // Sin respuesta a la presion el grosor es constante: eso es
                // exactamente lo que distingue un boligrafo de una estilografica.
                1f
            }
            val halfWidth = maxHalfWidth * (0.18f + 0.82f * curved)

            // Normal a la tangente de la curva, para engrosar perpendicular.
            val slope = -cos(t * 2f * PI.toFloat()) * 2f * PI.toFloat() * h * 0.22f / w
            val nx = -slope
            val ny = 1f
            val len = kotlin.math.hypot(nx, ny)
            val ux = nx / len
            val uy = ny / len

            top.add(Offset(x + ux * halfWidth, y + uy * halfWidth))
            bottom.add(Offset(x - ux * halfWidth, y - uy * halfWidth))
        }

        val path = Path().apply {
            moveTo(top.first().x, top.first().y)
            for (p in top.drop(1)) lineTo(p.x, p.y)
            for (p in bottom.reversed()) lineTo(p.x, p.y)
            close()
        }

        drawPath(path, color)
    }
}
