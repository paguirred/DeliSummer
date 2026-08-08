package cl.aguirre.cuaderno.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File

/**
 * Acceso de solo lectura a un PDF de fondo.
 *
 * [PdfRenderer] no permite tener dos paginas abiertas a la vez, asi que esta
 * clase serializa el acceso y entrega bitmaps ya rasterizados.
 */
class PdfPageSource private constructor(
    private val descriptor: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
) : Closeable {

    val pageCount: Int get() = renderer.pageCount

    /**
     * Rasteriza una pagina a [targetWidthPx] de ancho, manteniendo proporcion.
     *
     * Se rasteriza mas grande que la pantalla a proposito (el llamador decide
     * cuanto): al hacer zoom sobre un apunte, un fondo pixelado se nota de
     * inmediato aunque la tinta encima se vea perfecta.
     */
    suspend fun renderPage(index: Int, targetWidthPx: Int): Bitmap? =
        withContext(Dispatchers.IO) {
            if (index < 0 || index >= renderer.pageCount) return@withContext null
            synchronized(this@PdfPageSource) {
                runCatching {
                    renderer.openPage(index).use { page ->
                        val ratio = page.height.toFloat() / page.width.toFloat()
                        val w = targetWidthPx.coerceIn(320, 4096)
                        val h = (w * ratio).toInt().coerceAtLeast(1)
                        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bitmap
                    }
                }.getOrNull()
            }
        }

    /** Tamaño de cada pagina en puntos PDF. */
    fun pageSizes(): List<Pair<Float, Float>> = synchronized(this) {
        (0 until renderer.pageCount).map { i ->
            runCatching {
                renderer.openPage(i).use { it.width.toFloat() to it.height.toFloat() }
            }.getOrElse { 595f to 842f }
        }
    }

    override fun close() {
        runCatching { renderer.close() }
        runCatching { descriptor.close() }
    }

    companion object {
        fun open(file: File): PdfPageSource? = runCatching {
            val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            PdfPageSource(fd, PdfRenderer(fd))
        }.getOrNull()
    }
}
