package cl.aguirre.cuaderno.ink

import android.os.SystemClock
import android.view.MotionEvent

/**
 * Instrumentacion de latencia del lienzo.
 *
 * Existe porque la pregunta importante de esta app no se puede responder
 * leyendo el codigo: si el retardo es constante desde el primer trazo, el
 * problema esta en la ruta de entrada; si empeora a medida que la hoja se llena,
 * esta en el dibujado. Son dos arreglos completamente distintos, y adivinar cual
 * es cuesta varias vueltas.
 *
 * Todas las medidas se toman en el hilo principal y sin asignar memoria: un
 * medidor que genera basura falsea justamente lo que intenta medir.
 */
class PerfMonitor {

    /** Muestras por segundo que entrega el digitalizador, incluidos historicos. */
    var inputHz = 0f
        private set

    /** Milisegundos entre el ultimo evento de entrada y el frame que lo dibujo. */
    var inputToDrawMs = 0f
        private set

    /** Duracion media de onDraw. Es lo que crece si el dibujado se degrada. */
    var drawMs = 0f
        private set

    /** Trazos secos en la pagina, para correlacionar con lo anterior. */
    var strokeCount = 0
        private set

    private var samplesInWindow = 0
    private var windowStart = 0L
    private var lastEventUptime = 0L
    private var drawStart = 0L

    fun onInput(event: MotionEvent) {
        // history + 1: el sistema agrupa varias muestras del sensor en un evento,
        // y contar solo el evento subestimaria la tasa real por un factor grande.
        samplesInWindow += event.historySize + 1
        lastEventUptime = event.eventTime

        val now = SystemClock.uptimeMillis()
        if (windowStart == 0L) windowStart = now
        val elapsed = now - windowStart
        if (elapsed >= WINDOW_MS) {
            inputHz = samplesInWindow * 1000f / elapsed
            samplesInWindow = 0
            windowStart = now
        }
    }

    /** Pico de latencia del ultimo trazo. Es lo que de verdad se siente. */
    var worstMs = 0f
        private set

    fun beginStroke() {
        worstMs = 0f
        inputToDrawMs = 0f
    }

    /**
     * @param drawing true solo mientras el lapiz esta apoyado.
     *
     * La distincion importa: al levantar el lapiz dejan de llegar eventos, pero
     * los frames siguen, y la diferencia entre "ahora" y "el ultimo evento"
     * empieza a crecer sola. Medir en ese rato reporta el tiempo que alguien
     * paso mirando la pantalla, no la latencia del trazo.
     */
    fun beginDraw(strokes: Int, drawing: Boolean) {
        strokeCount = strokes
        drawStart = SystemClock.uptimeMillis()
        if (!drawing || lastEventUptime == 0L) return

        val delta = (drawStart - lastEventUptime).toFloat()
        if (delta in 0f..MAX_MEANINGFUL_MS) {
            inputToDrawMs = if (inputToDrawMs == 0f) delta else inputToDrawMs * 0.8f + delta * 0.2f
            if (delta > worstMs) worstMs = delta
        }
    }

    fun endDraw() {
        val elapsed = (SystemClock.uptimeMillis() - drawStart).toFloat()
        drawMs = drawMs * 0.8f + elapsed * 0.2f
    }

    fun summary(): String = buildString {
        append("entrada ").append(inputHz.toInt()).append(" Hz")
        append("  ·  a pantalla ").append(inputToDrawMs.toInt())
        append("/").append(worstMs.toInt()).append(" ms")
        append("  ·  dibujado ").append(String.format("%.1f", drawMs)).append(" ms")
        append("  ·  trazos ").append(strokeCount)
    }

    private companion object {
        const val WINDOW_MS = 500L
        const val MAX_MEANINGFUL_MS = 250f
    }
}
