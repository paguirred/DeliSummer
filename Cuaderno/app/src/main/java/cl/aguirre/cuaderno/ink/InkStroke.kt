package cl.aguirre.cuaderno.ink

import android.graphics.Matrix
import android.graphics.RectF
import androidx.ink.strokes.Stroke

/**
 * Un trazo dibujado, con su polilinea simplificada en coordenadas de pagina y la
 * transformacion que se le haya aplicado despues de dibujarlo.
 *
 * La polilinea responde una sola pregunta: "el borrador o el lazo tocaron este
 * trazo?". Se captura desde los MotionEvent, que de todas formas pasan por la
 * app para el rechazo de palma, asi que el borrado no depende de las APIs de
 * geometria del motor, que estan en alpha.
 *
 * La matriz existe porque la geometria de un [Stroke] es inmutable: el motor la
 * calcula una vez y no se puede editar. Mover o escalar lo escrito seria
 * imposible sin reconstruir el trazo entero desde sus puntos de entrada. En vez
 * de eso se guarda aparte la transformacion y se aplica al dibujar. El costo es
 * una multiplicacion de matrices por trazo; la alternativa costaba recalcular
 * la malla completa en cada arrastre.
 */
class InkStroke(
    val stroke: Stroke,
    /** Pares x,y consecutivos, ya en coordenadas de pagina actuales. */
    val hitPath: FloatArray,
    /** De coordenadas propias del trazo a coordenadas de pagina. */
    val transform: Matrix = Matrix(),
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
        if (hitPath.isEmpty()) r.set(0f, 0f, 0f, 0f) else r.inset(-halfWidth, -halfWidth)
        r
    }

    /** Copia del trazo con [matrix] aplicada encima de lo que ya tenia. */
    fun transformedBy(matrix: Matrix): InkStroke {
        val movedPath = hitPath.copyOf()
        matrix.mapPoints(movedPath)
        val combined = Matrix(transform)
        combined.postConcat(matrix)
        return InkStroke(stroke, movedPath, combined)
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
            if (segmentDistanceSq(cx, cy, hitPath[i], hitPath[i + 1], hitPath[i + 2], hitPath[i + 3]) <= reachSq) {
                return true
            }
            i += 2
        }
        return false
    }

    /**
     * True si el trazo cae dentro del poligono del lazo.
     *
     * Basta con que la mayoria de sus puntos esten adentro: exigir el trazo
     * completo obligaria a rodear con precision milimetrica, y aceptar un solo
     * punto haria que rozar el borde de una palabra la seleccione entera.
     */
    fun isInside(polygon: FloatArray): Boolean {
        if (hitPath.size < 2 || polygon.size < 6) return false
        var inside = 0
        var total = 0
        var i = 0
        while (i + 1 < hitPath.size) {
            total++
            if (pointInPolygon(hitPath[i], hitPath[i + 1], polygon)) inside++
            i += 2
        }
        return total > 0 && inside * 2 >= total
    }

    private fun segmentDistanceSq(
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

/** Test de punto en poligono por conteo de cruces. */
fun pointInPolygon(x: Float, y: Float, polygon: FloatArray): Boolean {
    var inside = false
    val n = polygon.size / 2
    var j = n - 1
    for (i in 0 until n) {
        val xi = polygon[i * 2]
        val yi = polygon[i * 2 + 1]
        val xj = polygon[j * 2]
        val yj = polygon[j * 2 + 1]
        if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) {
            inside = !inside
        }
        j = i
    }
    return inside
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

    fun snapshot(): FloatArray = points.toFloatArray()

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
