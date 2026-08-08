package cl.aguirre.cuaderno.ink

import android.graphics.RectF
import androidx.ink.strokes.Stroke

/**
 * Un trazo dibujado, junto con una polilinea simplificada del mismo trazo en
 * coordenadas de pagina.
 *
 * La polilinea existe para responder una sola pregunta: "el borrador toco este
 * trazo?". Se podria derivar de la malla que produce el motor de tinta, pero se
 * captura aparte desde los MotionEvent, que de todas formas pasan por esta app
 * para el rechazo de palma. Asi el borrado no depende de las APIs de geometria
 * del motor, que estan en alpha, y el costo es un puñado de floats por trazo.
 */
class InkStroke(
    val stroke: Stroke,
    /** Pares x,y consecutivos en coordenadas de pagina. */
    val hitPath: FloatArray,
) {
    /** Radio del trazo en puntos de pagina; ensancha el area sensible al borrado. */
    private val halfWidth: Float = stroke.brush.size / 2f

    val bounds: RectF = run {
        val r = RectF(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        var i = 0
        while (i + 1 < hitPath.size) {
            val x = hitPath[i]
            val y = hitPath[i + 1]
            if (x < r.left) r.left = x
            if (y < r.top) r.top = y
            if (x > r.right) r.right = x
            if (y > r.bottom) r.bottom = y
            i += 2
        }
        if (hitPath.isEmpty()) {
            r.set(0f, 0f, 0f, 0f)
        } else {
            r.inset(-halfWidth, -halfWidth)
        }
        r
    }

    /** True si un circulo de radio [radius] centrado en ([cx], [cy]) toca el trazo. */
    fun hits(cx: Float, cy: Float, radius: Float): Boolean {
        val reach = radius + halfWidth
        // Descarte rapido: la gran mayoria de los trazos de una pagina ni se acercan.
        if (cx + reach < bounds.left || cx - reach > bounds.right ||
            cy + reach < bounds.top || cy - reach > bounds.bottom
        ) {
            return false
        }
        if (hitPath.size < 2) return false

        val reachSq = reach * reach
        if (hitPath.size == 2) {
            val dx = cx - hitPath[0]
            val dy = cy - hitPath[1]
            return dx * dx + dy * dy <= reachSq
        }

        var i = 0
        while (i + 3 < hitPath.size) {
            if (distanceToSegmentSq(cx, cy, hitPath[i], hitPath[i + 1], hitPath[i + 2], hitPath[i + 3]) <= reachSq) {
                return true
            }
            i += 2
        }
        return false
    }

    private fun distanceToSegmentSq(
        px: Float, py: Float,
        ax: Float, ay: Float,
        bx: Float, by: Float,
    ): Float {
        val abx = bx - ax
        val aby = by - ay
        val apx = px - ax
        val apy = py - ay
        val lenSq = abx * abx + aby * aby
        val t = if (lenSq <= 0f) 0f else ((apx * abx + apy * aby) / lenSq).coerceIn(0f, 1f)
        val dx = apx - abx * t
        val dy = apy - aby * t
        return dx * dx + dy * dy
    }
}

/**
 * Acumula puntos de un trazo en curso y los decima.
 *
 * Sin decimar, un trazo largo con el lapiz a 240Hz deja miles de puntos casi
 * colineales que no aportan nada al hit-testing y solo ocupan disco.
 */
class HitPathBuilder(private val minDistance: Float = 1.5f) {
    private val points = ArrayList<Float>(256)
    private var lastX = Float.NaN
    private var lastY = Float.NaN

    fun add(x: Float, y: Float) {
        if (!lastX.isNaN()) {
            val dx = x - lastX
            val dy = y - lastY
            if (dx * dx + dy * dy < minDistance * minDistance) return
        }
        points.add(x)
        points.add(y)
        lastX = x
        lastY = y
    }

    /** Devuelve los puntos y deja el constructor listo para el proximo trazo. */
    fun build(): FloatArray {
        val result = points.toFloatArray()
        reset()
        return result
    }

    fun reset() {
        points.clear()
        lastX = Float.NaN
        lastY = Float.NaN
    }

    val isEmpty: Boolean get() = points.isEmpty()
}
