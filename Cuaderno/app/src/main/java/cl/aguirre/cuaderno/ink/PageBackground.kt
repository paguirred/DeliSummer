package cl.aguirre.cuaderno.ink

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import cl.aguirre.cuaderno.data.model.PageTemplate

/**
 * Dibuja el fondo de una pagina en coordenadas de pagina (puntos PDF).
 *
 * Las medidas estan en puntos y no en pixeles a proposito: asi el mismo codigo
 * sirve para la pantalla y para exportar a PDF, y el cuadriculado mide lo mismo
 * en papel que en la tablet.
 */
object PageBackground {

    /** 5mm en puntos: el cuadriculado clasico de cuaderno de ingenieria. */
    private const val GRID_MM = 5f
    private const val PT_PER_MM = 72f / 25.4f
    private const val GRID_SPACING = GRID_MM * PT_PER_MM

    /** Interlineado de ~7mm, comodo para escribir a mano. */
    private const val RULE_SPACING = 7f * PT_PER_MM

    private const val MARGIN = 36f // 0.5"

    private val paper = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val line = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 0.5f
        color = Color.parseColor("#D8DEE6")
    }

    private val dot = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
        color = Color.parseColor("#C4CCD6")
    }

    private val marginLine = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 0.7f
        color = Color.parseColor("#E9C3C3")
    }

    fun draw(canvas: Canvas, template: PageTemplate, widthPt: Float, heightPt: Float) {
        canvas.drawRect(0f, 0f, widthPt, heightPt, paper)
        when (template) {
            PageTemplate.BLANK -> Unit
            PageTemplate.RULED -> drawRuled(canvas, widthPt, heightPt)
            PageTemplate.GRID -> drawGrid(canvas, widthPt, heightPt)
            PageTemplate.DOTS -> drawDots(canvas, widthPt, heightPt)
        }
    }

    private fun drawRuled(canvas: Canvas, w: Float, h: Float) {
        var y = MARGIN + RULE_SPACING
        while (y < h - MARGIN) {
            canvas.drawLine(MARGIN, y, w - MARGIN, y, line)
            y += RULE_SPACING
        }
        canvas.drawLine(MARGIN * 2, MARGIN, MARGIN * 2, h - MARGIN, marginLine)
    }

    private fun drawGrid(canvas: Canvas, w: Float, h: Float) {
        var x = 0f
        while (x <= w) {
            canvas.drawLine(x, 0f, x, h, line)
            x += GRID_SPACING
        }
        var y = 0f
        while (y <= h) {
            canvas.drawLine(0f, y, w, y, line)
            y += GRID_SPACING
        }
    }

    private fun drawDots(canvas: Canvas, w: Float, h: Float) {
        var y = GRID_SPACING
        while (y < h) {
            var x = GRID_SPACING
            while (x < w) {
                canvas.drawCircle(x, y, 0.8f, dot)
                x += GRID_SPACING
            }
            y += GRID_SPACING
        }
    }
}
