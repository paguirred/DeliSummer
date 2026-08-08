package cl.aguirre.cuaderno.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import cl.aguirre.cuaderno.ink.InkStroke
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * Exporta un conjunto de trazos como PNG.
 *
 * Sirve para sacar una captura de lo seleccionado y pegarlo en otro lado: un
 * mensaje, un informe, un resumen. Se rasteriza a mayor resolucion que la
 * pantalla porque el destino habitual es un documento, donde un recorte pixelado
 * se nota de inmediato.
 */
object ImageExporter {

    private const val TARGET_LONG_SIDE = 2000
    private const val PADDING_PT = 12f

    suspend fun exportSelection(
        context: Context,
        strokes: Collection<InkStroke>,
        transparent: Boolean = false,
    ): File? = withContext(Dispatchers.IO) {
        if (strokes.isEmpty()) return@withContext null

        val bounds = RectF(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        for (s in strokes) {
            bounds.left = min(bounds.left, s.bounds.left)
            bounds.top = min(bounds.top, s.bounds.top)
            bounds.right = max(bounds.right, s.bounds.right)
            bounds.bottom = max(bounds.bottom, s.bounds.bottom)
        }
        if (bounds.left > bounds.right) return@withContext null
        bounds.inset(-PADDING_PT, -PADDING_PT)

        val widthPt = bounds.width()
        val heightPt = bounds.height()
        if (widthPt <= 0f || heightPt <= 0f) return@withContext null

        val scale = TARGET_LONG_SIDE / max(widthPt, heightPt)
        val w = (widthPt * scale).toInt().coerceIn(1, 4096)
        val h = (heightPt * scale).toInt().coerceIn(1, 4096)

        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        if (!transparent) canvas.drawColor(Color.WHITE)

        // De coordenadas de pagina al recorte, con el origen en la esquina.
        val toImage = Matrix().apply {
            setTranslate(-bounds.left, -bounds.top)
            postScale(scale, scale)
        }

        val renderer = CanvasStrokeRenderer.create()
        val combined = Matrix()
        for (s in strokes) {
            combined.set(toImage)
            combined.preConcat(s.transform)
            renderer.draw(canvas = canvas, stroke = s.stroke, strokeToScreenTransform = combined)
        }

        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val out = File(dir, "seleccion.png")
        return@withContext try {
            out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            out
        } catch (e: Exception) {
            null
        } finally {
            bitmap.recycle()
        }
    }
}
