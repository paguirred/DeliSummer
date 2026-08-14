package cl.aguirre.cuaderno.ink

import android.view.MotionEvent
import kotlin.math.hypot
import kotlin.math.pow

/**
 * Acondiciona la entrada del lapiz antes de que llegue al motor de tinta.
 *
 * Hace tres cosas sobre los mismos puntos:
 *
 *  - **Estabilizacion**: suaviza la posicion con un filtro exponencial. Cuanto
 *    mas suaviza, mas "atras" va la punta de la tinta: por eso es ajustable y
 *    por eso el valor por defecto es bajo.
 *  - **Curva de presion**: reasigna la presion cruda con una gamma, porque los
 *    lapices activos no la entregan lineal y cada persona apoya distinto.
 *  - **Afinado de extremos**: adelgaza el trazo al entrar y al salir, que es lo
 *    que separa una linea de tinta de una franja de plumon.
 *
 * Todo se aplica construyendo un MotionEvent equivalente, porque el motor de
 * tinta consume eventos y no puntos sueltos. Se preservan los puntos historicos:
 * son las muestras que el sensor entrego entre dos frames, y a 265 Hz tirarlas
 * volveria poligonal cualquier curva rapida.
 */
class InputConditioner {

    /** 0 = sin suavizado, 1 = maximo. */
    var stabilization: Float = 0f

    /** Menor a 1 engorda antes; mayor a 1 exige apretar mas. */
    var pressureGamma: Float = 1f

    /** 0 = sin afinado de extremos, 1 = maximo. */
    var taper: Float = 0.7f

    /**
     * Recibe cada muestra ya estabilizada, con la presion pasada por la curva
     * pero **sin** la envolvente de extremos. Quien reconstruye el trazo al
     * soltarlo aplica su propia envolvente, ahora si conociendolo entero.
     */
    var sampleSink: ((x: Float, y: Float, pressure: Float, timeMs: Long) -> Unit)? = null

    private var shapedPressure = 0f

    var pressureFloor: Float = 0.05f
    var pressureCeiling: Float = 1f

    private var filteredX = Float.NaN
    private var filteredY = Float.NaN
    private var pathLength = 0f
    private var lastRawX = Float.NaN
    private var lastRawY = Float.NaN

    // Reutilizados: a 265 Hz, asignar por muestra genera basura justo mientras
    // alguien escribe, que es cuando peor cae una pausa del recolector.
    private val scratch = MotionEvent.PointerCoords()
    private val coordsOut = arrayOf(MotionEvent.PointerCoords())
    private val propertiesOut = arrayOf(MotionEvent.PointerProperties())

    val isActive: Boolean
        get() = true

    fun reset() {
        filteredX = Float.NaN
        filteredY = Float.NaN
        lastRawX = Float.NaN
        lastRawY = Float.NaN
        pathLength = 0f
    }

    /**
     * Devuelve un evento equivalente con la entrada ya acondicionada. El llamador
     * debe reciclar lo que se devuelva.
     */
    fun condition(event: MotionEvent, pointerIndex: Int): MotionEvent? {
        if (pointerIndex < 0 || pointerIndex >= event.pointerCount) return null

        event.getPointerProperties(pointerIndex, propertiesOut[0])
        val ending = event.actionMasked == MotionEvent.ACTION_UP ||
            event.actionMasked == MotionEvent.ACTION_POINTER_UP

        var result: MotionEvent? = null

        for (h in 0 until event.historySize) {
            event.getHistoricalPointerCoords(pointerIndex, h, scratch)
            apply(scratch, isFinal = false)
            val time = event.getHistoricalEventTime(h)
            sampleSink?.invoke(scratch.x, scratch.y, shapedPressure, time)
            result = append(result, event, time)
        }

        event.getPointerCoords(pointerIndex, scratch)
        apply(scratch, isFinal = ending)
        sampleSink?.invoke(scratch.x, scratch.y, shapedPressure, event.eventTime)
        result = append(result, event, event.eventTime)

        return result
    }

    private fun append(current: MotionEvent?, source: MotionEvent, eventTime: Long): MotionEvent {
        coordsOut[0].copyFrom(scratch)
        // addBatch copia los valores, asi que reutilizar el mismo objeto es
        // seguro y evita una asignacion por muestra.
        if (current != null && source.actionMasked == MotionEvent.ACTION_MOVE) {
            current.addBatch(eventTime, coordsOut, source.metaState)
            return current
        }
        current?.recycle()
        return MotionEvent.obtain(
            source.downTime,
            eventTime,
            source.actionMasked,
            1,
            propertiesOut,
            coordsOut,
            source.metaState,
            source.buttonState,
            source.xPrecision,
            source.yPrecision,
            source.deviceId,
            source.edgeFlags,
            source.source,
            source.flags,
        )
    }

    private fun apply(coords: MotionEvent.PointerCoords, isFinal: Boolean) {
        // El largo recorrido se mide sobre la posicion cruda: si se midiera sobre
        // la filtrada, subir la estabilizacion acortaria el afinado de entrada.
        if (!lastRawX.isNaN()) {
            pathLength += hypot(coords.x - lastRawX, coords.y - lastRawY)
        }
        lastRawX = coords.x
        lastRawY = coords.y

        if (stabilization > 0.001f) {
            val alpha = 1f - stabilization * 0.85f
            if (filteredX.isNaN()) {
                filteredX = coords.x
                filteredY = coords.y
            } else {
                filteredX += (coords.x - filteredX) * alpha
                filteredY += (coords.y - filteredY) * alpha
            }
            coords.x = filteredX
            coords.y = filteredY
        }

        val raw = coords.pressure.coerceIn(0f, 1f)
        val value = if (pressureGamma == 1f) raw else raw.pow(pressureGamma)
        shapedPressure = pressureFloor + value * (pressureCeiling - pressureFloor)
        coords.pressure = pressureFloor +
            value * envelope(isFinal) * (pressureCeiling - pressureFloor)
    }

    /**
     * Envolvente que adelgaza los extremos.
     *
     * La entrada se resuelve completa: se conoce cuanto lleva recorrido el trazo.
     * La salida solo puede afinarse en el ultimo punto, porque un trazo no avisa
     * que va a terminar: saberlo antes exigiria retrasar la tinta unos
     * milisegundos, que es exactamente lo que se esta tratando de no hacer.
     */
    private fun envelope(isFinal: Boolean): Float {
        if (taper <= 0.001f) return 1f
        val depth = TAPER_MIN + (1f - TAPER_MIN) * (1f - taper)
        if (isFinal) return depth

        val t = (pathLength / TAPER_DISTANCE).coerceIn(0f, 1f)
        // Suavizado cubico: una rampa lineal deja un quiebre visible donde
        // termina el afinado.
        val eased = t * t * (3f - 2f * t)
        return depth + (1f - depth) * eased
    }

    private companion object {
        /** Puntos de pantalla en que el trazo alcanza su grosor pleno. */
        const val TAPER_DISTANCE = 34f
        const val TAPER_MIN = 0.18f
    }
}
