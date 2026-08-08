package cl.aguirre.cuaderno.ink

import android.view.MotionEvent
import kotlin.math.pow

/**
 * Acondiciona la entrada del lapiz antes de que llegue al motor de tinta.
 *
 * Hace dos cosas sobre los mismos puntos:
 *
 *  - **Estabilizacion**: suaviza la posicion con un filtro exponencial. Sirve
 *    para pulso tembloroso y para trazos lentos, donde el ruido del digitalizador
 *    se ve como ondulacion. Tiene un costo inevitable: cuanto mas suaviza, mas
 *    "atras" va la punta de la tinta respecto del lapiz. Por eso es ajustable y
 *    por eso el valor por defecto es bajo.
 *
 *  - **Curva de presion**: reasigna la presion cruda con una gamma. Los lapices
 *    activos no entregan presion lineal y cada persona apoya distinto; sin esto,
 *    el trazo o satura enseguida o nunca llega a engordar.
 *
 * La reasignacion se hace construyendo un MotionEvent sintetico porque el motor
 * de tinta consume eventos, no puntos sueltos. Se preservan los puntos
 * historicos: son las muestras que el sensor entrego entre dos frames y tirarlas
 * volveria poligonal cualquier curva rapida.
 */
class InputConditioner {

    /** 0 = sin suavizado, 1 = maximo. */
    var stabilization: Float = 0f

    /**
     * Gamma de la curva de presion. 1 = curva del sistema sin tocar; menor a 1
     * engorda antes (mas sensible); mayor a 1 exige apretar mas.
     */
    var pressureGamma: Float = 1f

    /** Presion minima y maxima resultantes, para acotar el rango del trazo. */
    var pressureFloor: Float = 0.05f
    var pressureCeiling: Float = 1f

    private var filteredX = Float.NaN
    private var filteredY = Float.NaN

    val isActive: Boolean
        get() = stabilization > 0.001f || pressureGamma != 1f ||
            pressureFloor > 0.001f || pressureCeiling < 0.999f

    fun reset() {
        filteredX = Float.NaN
        filteredY = Float.NaN
    }

    /**
     * Devuelve un evento equivalente con la entrada ya acondicionada, o null si
     * no hay nada que cambiar. El llamador debe reciclar lo que se devuelva.
     */
    fun condition(event: MotionEvent, pointerIndex: Int): MotionEvent? {
        if (!isActive) return null
        if (pointerIndex < 0 || pointerIndex >= event.pointerCount) return null

        val properties = MotionEvent.PointerProperties()
        event.getPointerProperties(pointerIndex, properties)
        val propertiesArray = arrayOf(properties)

        val scratch = MotionEvent.PointerCoords()
        var result: MotionEvent? = null

        // Los historicos van primero y en orden, para que el lote quede igual que
        // el original salvo por los valores.
        for (h in 0 until event.historySize) {
            event.getHistoricalPointerCoords(pointerIndex, h, scratch)
            apply(scratch)
            val time = event.getHistoricalEventTime(h)
            result = append(result, event, propertiesArray, scratch, time)
        }

        event.getPointerCoords(pointerIndex, scratch)
        apply(scratch)
        result = append(result, event, propertiesArray, scratch, event.eventTime)

        return result
    }

    private fun append(
        current: MotionEvent?,
        source: MotionEvent,
        properties: Array<MotionEvent.PointerProperties>,
        coords: MotionEvent.PointerCoords,
        eventTime: Long,
    ): MotionEvent {
        val copy = MotionEvent.PointerCoords(coords)
        // addBatch solo es valido sobre un ACTION_MOVE; para el resto se emite un
        // evento por muestra, que es justo lo que hace el sistema.
        if (current != null && source.actionMasked == MotionEvent.ACTION_MOVE) {
            current.addBatch(eventTime, arrayOf(copy), source.metaState)
            return current
        }
        current?.recycle()
        return MotionEvent.obtain(
            source.downTime,
            eventTime,
            source.actionMasked,
            1,
            properties,
            arrayOf(copy),
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

    private fun apply(coords: MotionEvent.PointerCoords) {
        if (stabilization > 0.001f) {
            // alpha alto = sigue de cerca al lapiz. Se limita a 0.15 por abajo
            // para que ni en el maximo el trazo se despegue tanto que moleste.
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
        val curved = if (pressureGamma == 1f) raw else raw.pow(pressureGamma)
        coords.pressure = pressureFloor + curved * (pressureCeiling - pressureFloor)
    }
}
