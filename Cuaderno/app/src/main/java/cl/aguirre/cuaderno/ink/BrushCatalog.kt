package cl.aguirre.cuaderno.ink

import androidx.ink.brush.Brush
import androidx.ink.brush.BrushFamily
import androidx.ink.brush.StockBrushes
import kotlin.math.ln
import kotlin.math.pow

/**
 * Las familias de pincel que usa la app.
 *
 * El id de texto es lo que se guarda en disco. Nunca se debe cambiar el valor de
 * [id] de una entrada existente: los cuadernos ya guardados dejarian de abrir con
 * el pincel correcto.
 *
 * El set estandar del motor solo trae unas pocas familias, asi que la diferencia
 * entre herramientas viene sobre todo de como responden a la presion:
 * [PEN] varia de grosor con la fuerza, [FINELINER] no varia nunca.
 */
enum class BrushKind(val id: String) {
    /** Sensible a la presion: engorda al apretar. */
    PEN("pen"),

    /**
     * Grosor constante, como un fineliner. El id sigue siendo "pencil" porque asi
     * quedo escrito en los cuadernos de la primera version.
     */
    FINELINER("pencil"),

    /** Grosor constante y ancho, para titulos y diagramas. */
    MARKER("marker"),

    /** Translucido y ancho, se multiplica sobre lo que hay debajo. */
    HIGHLIGHTER("highlighter");

    val family: BrushFamily
        get() = when (this) {
            PEN -> StockBrushes.pressurePen()
            FINELINER -> StockBrushes.marker()
            MARKER -> StockBrushes.marker()
            HIGHLIGHTER -> StockBrushes.highlighter()
        }

    /** True si el grosor del trazo responde a la fuerza del lapiz. */
    val respondsToPressure: Boolean get() = this == PEN

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

    private const val MIN_PT = 0.3f
    private const val MAX_PT = 48f

    /**
     * Convierte el valor 1..100 de la UI a puntos de pagina.
     *
     * La escala es exponencial y no lineal a proposito: en trazo fino, un punto
     * de diferencia se ve muchisimo, y en trazo grueso casi nada. Con escala
     * lineal, la mitad util del control quedaria apretada en los primeros pasos.
     */
    fun sizeToPoints(value: Int): Float {
        val t = (value.coerceIn(1, 100) - 1) / 99f
        return MIN_PT * (MAX_PT / MIN_PT).pow(t)
    }

    /** Inversa de [sizeToPoints], para mostrar el control en la posicion correcta. */
    fun pointsToSize(points: Float): Int {
        val clamped = points.coerceIn(MIN_PT, MAX_PT)
        val t = ln(clamped / MIN_PT) / ln(MAX_PT / MIN_PT)
        return (t * 99f + 1f).toInt().coerceIn(1, 100)
    }

    fun create(kind: BrushKind, colorLong: Long, sizePt: Float): Brush =
        Brush.createWithColorLong(
            family = kind.family,
            colorLong = colorLong,
            size = sizePt.coerceIn(MIN_PT, MAX_PT),
            epsilon = EPSILON,
        )

    /** Valor 1..100 por defecto de cada herramienta. */
    fun defaultSize(kind: BrushKind): Int = when (kind) {
        BrushKind.PEN -> 34
        BrushKind.FINELINER -> 26
        BrushKind.MARKER -> 52
        BrushKind.HIGHLIGHTER -> 78
    }
}
