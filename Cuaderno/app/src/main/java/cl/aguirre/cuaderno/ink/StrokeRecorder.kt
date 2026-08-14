package cl.aguirre.cuaderno.ink

import androidx.ink.brush.Brush
import androidx.ink.brush.InputToolType
import androidx.ink.strokes.InProgressStroke
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import kotlin.math.hypot
import kotlin.math.min

/**
 * Guarda las muestras de un trazo y lo reconstruye al soltarlo.
 *
 * La capa mojada solo puede afinar el comienzo del trazo, porque mientras se
 * escribe nadie sabe donde va a terminar. Anticiparlo exigiria retrasar la tinta
 * unos milisegundos, que es exactamente lo que se esta evitando.
 *
 * Al levantar el lapiz el trazo ya se conoce entero, asi que aca se rehace con
 * la envolvente completa: delgado al entrar, pleno al medio, delgado al salir.
 * Cuesta reconstruir la malla una vez por trazo, en el momento en que nadie esta
 * escribiendo, y no toca la latencia.
 */
class StrokeRecorder {

    private val xs = ArrayList<Float>(512)
    private val ys = ArrayList<Float>(512)
    private val pressures = ArrayList<Float>(512)
    private val times = ArrayList<Long>(512)
    private var startTime = 0L

    val size: Int get() = xs.size

    fun begin(timeMs: Long) {
        xs.clear(); ys.clear(); pressures.clear(); times.clear()
        startTime = timeMs
    }

    /** Coordenadas en espacio de pagina y presion ya pasada por la curva. */
    fun add(x: Float, y: Float, pressure: Float, timeMs: Long) {
        val t = (timeMs - startTime).coerceAtLeast(0L)
        // El motor exige tiempos estrictamente crecientes: a 265 Hz llegan
        // muestras que comparten milisegundo y hay que descartarlas.
        if (times.isNotEmpty() && t <= times[times.size - 1]) return
        xs.add(x); ys.add(y); pressures.add(pressure); times.add(t)
    }

    fun clear() = begin(0L)

    /**
     * Rehace el trazo con la envolvente aplicada. Devuelve null si no hay
     * material suficiente o si el motor rechaza la entrada, y en ese caso el
     * llamador se queda con el trazo original.
     */
    fun build(brush: Brush, taper: Float): Stroke? {
        val n = xs.size
        if (n < 2) return null

        val cumulative = FloatArray(n)
        for (i in 1 until n) {
            cumulative[i] = cumulative[i - 1] + hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
        }
        val total = cumulative[n - 1]
        if (total <= 0f) return null

        return runCatching {
            val batch = MutableStrokeInputBatch()
            for (i in 0 until n) {
                batch.add(
                    type = InputToolType.STYLUS,
                    x = xs[i],
                    y = ys[i],
                    elapsedTimeMillis = times[i],
                    pressure = (pressures[i] * envelope(cumulative[i], total, taper))
                        .coerceIn(0.01f, 1f),
                )
            }
            val inProgress = InProgressStroke()
            inProgress.start(brush)
            inProgress.enqueueInputs(batch, MutableStrokeInputBatch())
            inProgress.updateShape(times[n - 1])
            inProgress.toImmutable()
        }.getOrNull()
    }

    /**
     * Rampa simetrica: sube desde el inicio, baja hacia el final.
     *
     * El tramo de afinado se limita al 40% del largo para que un trazo corto no
     * quede afinado de punta a punta y termine pareciendo un grano de arroz.
     */
    private fun envelope(distance: Float, total: Float, taper: Float): Float {
        if (taper <= 0.001f) return 1f
        val depth = TAPER_MIN + (1f - TAPER_MIN) * (1f - taper)
        val span = min(TAPER_DISTANCE, total * 0.4f)
        if (span <= 0f) return 1f

        val t = min(distance / span, (total - distance) / span).coerceIn(0f, 1f)
        // Suavizado cubico: una rampa lineal deja un quiebre visible donde
        // termina el afinado.
        val eased = t * t * (3f - 2f * t)
        return depth + (1f - depth) * eased
    }

    private companion object {
        const val TAPER_DISTANCE = 26f
        const val TAPER_MIN = 0.15f
    }
}
