package cl.aguirre.cuaderno.data.model

import kotlinx.serialization.Serializable

/** Plantilla de fondo de una pagina. */
enum class PageTemplate {
    BLANK,
    RULED,
    GRID,
    DOTS,
}

/**
 * Dimensiones de pagina en puntos PostScript (1/72"). Usar la misma unidad que
 * PDF significa que exportar es una copia directa, sin factores de conversion.
 */
object PageSize {
    const val A4_WIDTH = 595f
    const val A4_HEIGHT = 842f
}

@Serializable
data class PageMeta(
    val id: String,
    val template: PageTemplate = PageTemplate.GRID,
    val widthPt: Float = PageSize.A4_WIDTH,
    val heightPt: Float = PageSize.A4_HEIGHT,
    /** Indice de la pagina del PDF de fondo, o -1 si la pagina no tiene PDF. */
    val pdfPageIndex: Int = -1,
)

@Serializable
data class NotebookMeta(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    /** Nombre del PDF de fondo dentro de la carpeta del cuaderno, si lo tiene. */
    val pdfFileName: String? = null,
)

@Serializable
data class NotebookIndex(
    val meta: NotebookMeta,
    val pages: List<PageMeta>,
)
