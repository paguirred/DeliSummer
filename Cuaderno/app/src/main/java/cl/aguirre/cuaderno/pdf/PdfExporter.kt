package cl.aguirre.cuaderno.pdf

import android.content.Context
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import cl.aguirre.cuaderno.data.NotebookStore
import cl.aguirre.cuaderno.data.model.NotebookIndex
import cl.aguirre.cuaderno.ink.PageBackground
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Exporta un cuaderno a PDF.
 *
 * La tinta se dibuja directamente en el canvas del PDF en coordenadas de pagina.
 * Como los trazos ya estan guardados en esas mismas coordenadas, no hay escalado
 * de por medio y salen como vectores nitidos a cualquier zoom del lector.
 */
object PdfExporter {

    /** Resolucion a la que se rasteriza el PDF de fondo, si lo hay. */
    private const val BACKGROUND_WIDTH_PX = 1654 // ~A4 a 200dpi

    suspend fun export(
        context: Context,
        store: NotebookStore,
        index: NotebookIndex,
    ): File? = withContext(Dispatchers.IO) {
        val exportsDir = File(context.cacheDir, "exports").apply { mkdirs() }
        // Un nombre estable por cuaderno evita llenar la cache de copias.
        val safeTitle = index.meta.title.replace(Regex("[^\\p{L}\\p{N} _-]"), "").trim()
            .ifEmpty { "cuaderno" }
        val out = File(exportsDir, "$safeTitle.pdf")

        val pdfSource = index.meta.pdfFileName
            ?.let { store.pdfFile(index.meta.id, it) }
            ?.takeIf { it.exists() }
            ?.let { PdfPageSource.open(it) }

        try {
            val document = PdfDocument()
            val renderer = CanvasStrokeRenderer.create()
            val identity = Matrix()
            val bitmapPaint = Paint().apply { isAntiAlias = true; isFilterBitmap = true }

            index.pages.forEachIndexed { i, page ->
                val w = page.widthPt.toInt().coerceAtLeast(1)
                val h = page.heightPt.toInt().coerceAtLeast(1)
                val pdfPage = document.startPage(
                    PdfDocument.PageInfo.Builder(w, h, i + 1).create(),
                )
                val canvas = pdfPage.canvas

                if (pdfSource != null && page.pdfPageIndex >= 0) {
                    val bitmap = pdfSource.renderPage(page.pdfPageIndex, BACKGROUND_WIDTH_PX)
                    if (bitmap != null) {
                        canvas.drawBitmap(
                            bitmap,
                            Rect(0, 0, bitmap.width, bitmap.height),
                            RectF(0f, 0f, page.widthPt, page.heightPt),
                            bitmapPaint,
                        )
                        bitmap.recycle()
                    }
                } else {
                    PageBackground.draw(canvas, page.template, page.widthPt, page.heightPt)
                }

                // El canvas del PDF ya esta en puntos de pagina, el mismo sistema
                // en que viven los trazos, asi que la tinta sale vectorial y sin
                // reescalar. Igual hay que aplicar la matriz propia de cada
                // trazo: si se movio o escalo con el lazo, vive ahi.
                for (inkStroke in store.loadPage(index.meta.id, page.id)) {
                    canvas.save()
                    canvas.concat(inkStroke.transform)
                    renderer.draw(
                        canvas = canvas,
                        stroke = inkStroke.stroke,
                        strokeToScreenTransform = inkStroke.transform,
                    )
                    canvas.restore()
                }

                document.finishPage(pdfPage)
            }

            out.outputStream().use { document.writeTo(it) }
            document.close()
            out
        } catch (e: Exception) {
            null
        } finally {
            pdfSource?.close()
        }
    }
}
