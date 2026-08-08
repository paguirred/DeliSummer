package cl.aguirre.cuaderno.ink

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Detecta el gesto de tachar: el garabato de ida y vuelta que todo el mundo hace
 * sobre lo que quiere eliminar.
 *
 * El problema de este gesto es distinguirlo de la escritura normal. Una "m", una
 * "w" o una "e" tambien cambian de direccion. Se piden tres condiciones a la vez:
 *
 *  1. Varias inversiones de direccion casi opuestas, no simples curvas.
 *  2. Que el recorrido sea mucho mas largo que la diagonal de su propia caja: es
 *     decir que vuelva sobre si mismo en vez de avanzar.
 *  3. Que la caja sea claramente apaisada o alta, no cuadrada.
 *
 * Una letra suelta falla la segunda condicion; un tachon la cumple de sobra.
 */
class ScribbleDetector {

    private var reversals = 0
    private var pathLength = 0f
    private var lastX = Float.NaN
    private var lastY = Float.NaN
    private var lastDx = 0f
    private var lastDy = 0f
    private var minX = Float.MAX_VALUE
    private var minY = Float.MAX_VALUE
    private var maxX = -Float.MAX_VALUE
    private var maxY = -Float.MAX_VALUE
    private var fired = false

    fun reset() {
        reversals = 0
        pathLength = 0f
        lastX = Float.NaN
        lastY = Float.NaN
        lastDx = 0f
        lastDy = 0f
        minX = Float.MAX_VALUE
        minY = Float.MAX_VALUE
        maxX = -Float.MAX_VALUE
        maxY = -Float.MAX_VALUE
        fired = false
    }

    /** Alimenta un punto en coordenadas de pagina. True si esto es un tachon. */
    fun accept(x: Float, y: Float): Boolean {
        if (fired) return true

        if (x < minX) minX = x
        if (y < minY) minY = y
        if (x > maxX) maxX = x
        if (y > maxY) maxY = y

        if (lastX.isNaN()) {
            lastX = x
            lastY = y
            return false
        }

        val dx = x - lastX
        val dy = y - lastY
        val step = hypot(dx, dy)
        // Los micro-movimientos son ruido del digitalizador y falsean el conteo.
        if (step < MIN_STEP) return false

        pathLength += step

        if (lastDx != 0f || lastDy != 0f) {
            val dot = dx * lastDx + dy * lastDy
            val norm = hypot(dx, dy) * hypot(lastDx, lastDy)
            // coseno < -0.5 es un giro de mas de 120 grados: media vuelta, no una curva
            if (norm > 0f && dot / norm < -0.5f) reversals++
        }

        lastDx = dx
        lastDy = dy
        lastX = x
        lastY = y

        if (reversals < MIN_REVERSALS) return false

        val w = abs(maxX - minX)
        val h = abs(maxY - minY)
        val diagonal = hypot(w, h)
        if (diagonal < MIN_SPAN) return false
        if (pathLength < diagonal * MIN_LENGTH_RATIO) return false

        // Un tachon se hace a lo largo de un renglon o de una columna; algo que
        // ocupa una caja cuadrada y densa es mas probable que sea un dibujo.
        val elongation = if (h > 0f && w > 0f) maxOf(w / h, h / w) else Float.MAX_VALUE
        if (elongation < MIN_ELONGATION) return false

        fired = true
        return true
    }

    private companion object {
        const val MIN_STEP = 2f
        const val MIN_REVERSALS = 4
        const val MIN_SPAN = 24f
        const val MIN_LENGTH_RATIO = 2.6f
        const val MIN_ELONGATION = 1.8f
    }
}
