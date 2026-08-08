package cl.aguirre.cuaderno.ink

import androidx.ink.brush.Brush
import androidx.ink.brush.BrushFamily
import androidx.ink.brush.StockBrushes

/**
 * Las familias de pincel que usa la app.
 *
 * El id de texto es lo que se guarda en disco. Nunca se debe cambiar el valor de
 * [id] de una entrada existente: los cuadernos ya guardados dejarian de abrir con
 * el pincel correcto.
 */
enum class BrushKind(val id: String) {
    PEN("pen"),
    PENCIL("pencil"),
    MARKER("marker"),
    HIGHLIGHTER("highlighter");

    val family: BrushFamily
        get() = when (this) {
            PEN -> StockBrushes.pressurePen()
            // Grafito: mismo motor sensible a presion, pero mas fino y translucido
            // desde la UI. No hay una familia "lapiz" en el set estandar.
            PENCIL -> StockBrushes.pressurePen()
            MARKER -> StockBrushes.marker()
            HIGHLIGHTER -> StockBrushes.highlighter()
        }

    companion object {
        fun fromId(id: String): BrushKind = entries.firstOrNull { it.id == id } ?: PEN
    }
}

object BrushCatalog {

    /**
     * Epsilon es la distancia mas chica que el motor considera significativa:
     * por debajo de ella, dos puntos son el mismo punto.
     *
     * La unidad de mundo de esta app es el punto PDF. A encuadre completo la
     * pagina ocupa el ancho de la pantalla, o sea ~2.7 px por punto; con el zoom
     * maximo de 8x son ~21 px por punto, asi que un pixel mide ~0.046 puntos.
     * 0.025 queda holgadamente por debajo de eso en todo el rango de zoom.
     *
     * Tiene que ser constante durante toda la vida de la app: cambiarlo mas
     * adelante altera como se reconstruyen los trazos ya guardados.
     */
    const val EPSILON = 0.025f

    fun create(kind: BrushKind, colorLong: Long, sizePt: Float): Brush =
        Brush.createWithColorLong(
            family = kind.family,
            colorLong = colorLong,
            size = sizePt,
            epsilon = EPSILON,
        )

    /** Grosores ofrecidos en la UI, en puntos de pagina. */
    val penSizes = listOf(1.2f, 2.0f, 3.2f, 5.0f, 8.0f)
    val highlighterSizes = listOf(10f, 16f, 24f)

    fun defaultSize(kind: BrushKind): Float = when (kind) {
        BrushKind.PEN -> 2.0f
        BrushKind.PENCIL -> 1.4f
        BrushKind.MARKER -> 4.0f
        BrushKind.HIGHLIGHTER -> 16f
    }

    fun sizesFor(kind: BrushKind): List<Float> =
        if (kind == BrushKind.HIGHLIGHTER) highlighterSizes else penSizes
}
