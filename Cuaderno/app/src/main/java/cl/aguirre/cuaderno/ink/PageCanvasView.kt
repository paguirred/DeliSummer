package cl.aguirre.cuaderno.ink

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.FrameLayout
import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.authoring.InProgressStrokesFinishedListener
import androidx.ink.authoring.InProgressStrokesView
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.Stroke
import androidx.input.motionprediction.MotionEventPredictor
import cl.aguirre.cuaderno.data.model.PageTemplate
import kotlin.math.max

/**
 * El lienzo de una pagina.
 *
 * Tiene dos capas de tinta, que es lo que hace que escribir se sienta inmediato:
 *
 *  - La capa mojada la maneja [InProgressStrokesView], que dibuja el trazo en
 *    curso con front buffer, sin esperar el ciclo normal de composicion.
 *  - La capa seca son los trazos ya terminados, que viven rasterizados en un
 *    bitmap de cache y se vuelcan de una sola pasada.
 *
 * Al levantar el lapiz el trazo pasa de una capa a la otra.
 *
 * Los trazos se guardan en coordenadas de pagina (puntos PDF), nunca de pantalla.
 * El zoom y el desplazamiento viven solo en las matrices, asi que hacer zoom no
 * degrada la tinta ni cambia lo que se escribe en disco.
 */
class PageCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs), InProgressStrokesFinishedListener {

    private val inProgressView = InProgressStrokesView(context)
    private val renderer = CanvasStrokeRenderer.create()
    private val predictor: MotionEventPredictor by lazy { MotionEventPredictor.newInstance(this) }
    private val conditioner = InputConditioner()

    // --- Estado de la pagina -------------------------------------------------

    var strokes: List<InkStroke> = emptyList()
        set(value) {
            if (field === value) return
            field = value
            invalidate()
        }

    var template: PageTemplate = PageTemplate.GRID
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    var pageWidthPt: Float = 595f
        set(value) {
            if (field == value) return
            field = value
            requestFit()
        }

    var pageHeightPt: Float = 842f
        set(value) {
            if (field == value) return
            field = value
            requestFit()
        }

    /** Fondo rasterizado de la pagina del PDF, si el cuaderno viene de uno. */
    var pdfBackground: Bitmap? = null
        set(value) {
            if (field === value) return
            field = value
            invalidate()
        }

    // --- Herramienta ---------------------------------------------------------

    var tool: EditorTool = EditorTool.PEN
    var colorLong: Long = Color.pack(Color.BLACK)
    var strokeSizePt: Float = BrushCatalog.defaultSize(BrushKind.PEN)

    /** Radio del borrador en puntos de pagina. */
    var eraserRadiusPt: Float = 10f

    /** 0 = sin estabilizacion, 1 = maxima. */
    var stabilization: Float
        get() = conditioner.stabilization
        set(value) { conditioner.stabilization = value.coerceIn(0f, 1f) }

    /** Menor a 1 engorda el trazo antes; mayor a 1 exige apretar mas. */
    var pressureGamma: Float
        get() = conditioner.pressureGamma
        set(value) { conditioner.pressureGamma = value.coerceIn(0.2f, 4f) }

    /**
     * Si esta activo, el dedo nunca dibuja: solo hace zoom y desplaza. Es el
     * modo correcto cuando hay lapiz, porque ademas hace que apoyar la mano en
     * la pantalla no deje marcas.
     */
    var stylusOnly: Boolean = true

    // --- Callbacks -----------------------------------------------------------

    var onStrokeFinished: ((InkStroke) -> Unit)? = null
    var onErase: ((List<InkStroke>) -> Unit)? = null
    var onTransformChanged: ((zoom: Float) -> Unit)? = null

    // --- Transformacion pagina <-> vista -------------------------------------

    private val pageToView = Matrix()
    private val viewToPage = Matrix()
    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f
    private var fitZoom = 1f
    private var needsFit = true

    private val minZoomFactor = 1f
    private val maxZoomFactor = 8f

    // --- Cache de la capa seca -----------------------------------------------

    /**
     * Los trazos secos se rasterizan una vez y despues solo se vuelcan.
     *
     * Sin esto, cada frame vuelve a recorrer y rasterizar la pagina completa: con
     * la hoja vacia no se nota, pero a media pagina de apuntes el dibujado se
     * degrada justo mientras alguien escribe, que es cuando peor se siente.
     * Al terminar un trazo se pinta solo ese trazo encima del cache.
     */
    private var dryCache: Bitmap? = null
    private var dryCanvas: Canvas? = null
    private var drawnCount = 0
    private var drawnLast: InkStroke? = null
    private var cacheValid = false

    // --- Dibujo --------------------------------------------------------------

    private val shadowPaint = Paint().apply {
        isAntiAlias = true
        color = Color.parseColor("#22000000")
    }
    private val bitmapPaint = Paint().apply {
        isAntiAlias = true
        isFilterBitmap = true
    }
    private val identity = Matrix()
    private val pageRect = RectF()
    private val srcRect = Rect()
    private val destRect = RectF()

    // --- Entrada -------------------------------------------------------------

    private var activeStylusPointerId = MotionEvent.INVALID_POINTER_ID
    private var currentStrokeId: InProgressStrokeId? = null
    private val hitPathBuilder = HitPathBuilder()
    private val tmpPoint = FloatArray(2)

    private var lastPanFocusX = 0f
    private var lastPanFocusY = 0f
    private var panPointerCount = 0

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                applyZoom(detector.scaleFactor, detector.focusX, detector.focusY)
                return true
            }
        },
    )

    init {
        setWillNotDraw(false)
        setBackgroundColor(Color.parseColor("#EEF1F5"))
        addView(inProgressView)
        inProgressView.addFinishedStrokesListener(this)
    }

    // --- Ciclo de vida de la vista -------------------------------------------

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        releaseCache()
        if (w > 0 && h > 0) {
            dryCache = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            dryCanvas = Canvas(dryCache!!)
        }
        cacheValid = false
        if (needsFit || oldw == 0) fitToWidth()
    }

    private fun releaseCache() {
        dryCanvas = null
        dryCache?.recycle()
        dryCache = null
        drawnCount = 0
        drawnLast = null
        cacheValid = false
    }

    private fun requestFit() {
        needsFit = true
        if (width > 0) fitToWidth()
        invalidate()
    }

    /** Encuadra la pagina a lo ancho, con un margen para que respire. */
    fun fitToWidth() {
        if (width == 0 || pageWidthPt <= 0f) return
        val margin = 24f
        fitZoom = (width - margin * 2) / pageWidthPt
        zoom = fitZoom
        panX = margin
        panY = margin
        needsFit = false
        updateMatrices()
    }

    private fun applyZoom(factor: Float, focusX: Float, focusY: Float) {
        val target = (zoom * factor).coerceIn(fitZoom * minZoomFactor, fitZoom * maxZoomFactor)
        val actual = target / zoom
        if (actual == 1f) return
        // Mantener fijo el punto bajo los dedos mientras se hace zoom.
        panX = focusX - (focusX - panX) * actual
        panY = focusY - (focusY - panY) * actual
        zoom = target
        updateMatrices()
    }

    private fun applyPan(dx: Float, dy: Float) {
        panX += dx
        panY += dy
        updateMatrices()
    }

    private fun updateMatrices() {
        clampPan()
        pageToView.reset()
        pageToView.postScale(zoom, zoom)
        pageToView.postTranslate(panX, panY)
        pageToView.invert(viewToPage)
        // El cache esta rasterizado para una transformacion concreta: al cambiar
        // el zoom o el desplazamiento hay que rehacerlo.
        cacheValid = false
        onTransformChanged?.invoke(if (fitZoom > 0f) zoom / fitZoom else 1f)
        invalidate()
    }

    /** Evita que la pagina se pierda fuera de la pantalla al desplazar. */
    private fun clampPan() {
        val scaledW = pageWidthPt * zoom
        val scaledH = pageHeightPt * zoom
        panX = if (scaledW <= width) {
            (width - scaledW) / 2f
        } else {
            panX.coerceIn(width - scaledW, 0f)
        }
        panY = if (scaledH <= height) {
            max((height - scaledH) / 2f, 0f)
        } else {
            panY.coerceIn(height - scaledH, 0f)
        }
    }

    fun resetZoom() {
        fitToWidth()
    }

    // --- Render --------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        pageRect.set(0f, 0f, pageWidthPt, pageHeightPt)
        pageToView.mapRect(pageRect)

        // Sombra suave para que la hoja se lea como una hoja sobre el escritorio.
        canvas.drawRoundRect(
            pageRect.left - 1f, pageRect.top - 1f,
            pageRect.right + 3f, pageRect.bottom + 3f,
            2f, 2f, shadowPaint,
        )

        canvas.save()
        canvas.concat(pageToView)
        val pdf = pdfBackground
        if (pdf != null && !pdf.isRecycled) {
            srcRect.set(0, 0, pdf.width, pdf.height)
            destRect.set(0f, 0f, pageWidthPt, pageHeightPt)
            canvas.drawBitmap(pdf, srcRect, destRect, bitmapPaint)
        } else {
            PageBackground.draw(canvas, template, pageWidthPt, pageHeightPt)
        }
        canvas.restore()

        syncDryCache()
        dryCache?.let { canvas.drawBitmap(it, 0f, 0f, null) }
    }

    /**
     * Deja el cache al dia. Si lo unico que paso es que se agregaron trazos al
     * final, pinta solo esos; si hubo borrado, deshacer o cambio de zoom, rehace
     * la pagina completa.
     */
    private fun syncDryCache() {
        val target = dryCanvas ?: return

        val appendOnly = cacheValid &&
            strokes.size >= drawnCount &&
            (drawnCount == 0 || strokes.getOrNull(drawnCount - 1) === drawnLast)

        if (!appendOnly) {
            dryCache?.eraseColor(Color.TRANSPARENT)
            drawnCount = 0
        } else if (drawnCount == strokes.size) {
            return
        }

        target.save()
        target.concat(pageToView)
        for (i in drawnCount until strokes.size) {
            // El canvas ya lleva la transformacion; la matriz se pasa igual
            // porque el renderizador la usa para elegir el nivel de detalle.
            renderer.draw(
                canvas = target,
                stroke = strokes[i].stroke,
                strokeToScreenTransform = pageToView,
            )
        }
        target.restore()

        drawnCount = strokes.size
        drawnLast = strokes.lastOrNull()
        cacheValid = true
    }

    // --- Entrada -------------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val index = event.actionIndex
        val toolType = event.getToolType(index)
        val isStylus = toolType == MotionEvent.TOOL_TYPE_STYLUS
        val isPenEraser = toolType == MotionEvent.TOOL_TYPE_ERASER

        // Rechazo de palma: mientras el lapiz esta apoyado, el dedo no existe.
        val drawingWithStylus = activeStylusPointerId != MotionEvent.INVALID_POINTER_ID
        if (drawingWithStylus && !isStylus && !isPenEraser) return true

        val penInput = isStylus || isPenEraser
        val fingerMayDraw = !stylusOnly && toolType == MotionEvent.TOOL_TYPE_FINGER

        return when {
            (isPenEraser || tool == EditorTool.ERASER) && (penInput || fingerMayDraw) ->
                handleErase(event)

            tool.isDrawing && (penInput || fingerMayDraw) ->
                handleInk(event)

            else -> handleNavigation(event)
        }
    }

    private fun handleInk(event: MotionEvent): Boolean {
        val index = event.actionIndex
        val pointerId = event.getPointerId(index)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (activeStylusPointerId != MotionEvent.INVALID_POINTER_ID) return true
                // Pide al sistema entregar los eventos sin agrupar por frame.
                // Es una de las palancas mas directas contra la latencia.
                requestUnbufferedDispatch(event)
                parent?.requestDisallowInterceptTouchEvent(true)

                val kind = tool.brushKind ?: BrushKind.PEN
                val brush = BrushCatalog.create(kind, colorLong, strokeSizePt)

                activeStylusPointerId = pointerId
                hitPathBuilder.reset()
                conditioner.reset()

                val conditioned = conditioner.condition(event, index)
                val source = conditioned ?: event
                recordHitPoint(source, source.findPointerIndex(pointerId).coerceAtLeast(0))

                currentStrokeId = inProgressView.startStroke(
                    event = source,
                    pointerId = pointerId,
                    brush = brush,
                    motionEventToWorldTransform = viewToPage,
                    strokeToWorldTransform = identity,
                )
                conditioned?.recycle()
            }

            MotionEvent.ACTION_MOVE -> {
                val strokeId = currentStrokeId ?: return true
                val moveIndex = pointerIndexOf(event, activeStylusPointerId)
                if (moveIndex < 0) return true

                val conditioned = conditioner.condition(event, moveIndex)
                val source = conditioned ?: event
                val sourceIndex = source.findPointerIndex(activeStylusPointerId).coerceAtLeast(0)

                // Los puntos historicos son las muestras que el sensor entrego
                // entre dos frames. Ignorarlos es lo que hace que las curvas
                // salgan poligonales en tantas apps de dibujo.
                for (h in 0 until source.historySize) {
                    recordHistoricalHitPoint(source, sourceIndex, h)
                }
                recordHitPoint(source, sourceIndex)

                // La prediccion adelanta unos milisegundos la punta del trazo, que
                // es lo que cierra la brecha visual entre la punta del lapiz y la
                // tinta. El evento predicho lo administra el predictor.
                predictor.record(source)
                val predicted = predictor.predict()
                inProgressView.addToStroke(source, activeStylusPointerId, strokeId, predicted)
                conditioned?.recycle()
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val strokeId = currentStrokeId ?: return true
                if (pointerId != activeStylusPointerId) return true
                val conditioned = conditioner.condition(event, index)
                val source = conditioned ?: event
                recordHitPoint(source, source.findPointerIndex(pointerId).coerceAtLeast(0))
                inProgressView.finishStroke(source, pointerId, strokeId)
                conditioned?.recycle()
                activeStylusPointerId = MotionEvent.INVALID_POINTER_ID
            }

            MotionEvent.ACTION_CANCEL -> {
                currentStrokeId?.let { inProgressView.cancelStroke(it, event) }
                currentStrokeId = null
                activeStylusPointerId = MotionEvent.INVALID_POINTER_ID
                hitPathBuilder.reset()
                conditioner.reset()
            }
        }
        return true
    }

    private fun handleErase(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val index = 0
                val removed = ArrayList<InkStroke>()

                for (h in 0 until event.historySize) {
                    toPageCoords(event.getHistoricalX(index, h), event.getHistoricalY(index, h))
                    collectHits(tmpPoint[0], tmpPoint[1], removed)
                }
                toPageCoords(event.getX(index), event.getY(index))
                collectHits(tmpPoint[0], tmpPoint[1], removed)

                if (removed.isNotEmpty()) onErase?.invoke(removed)
            }
        }
        return true
    }

    private fun collectHits(x: Float, y: Float, into: MutableList<InkStroke>) {
        for (inkStroke in strokes) {
            if (inkStroke in into) continue
            if (inkStroke.hits(x, y, eraserRadiusPt)) into.add(inkStroke)
        }
    }

    private fun handleNavigation(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN,
            MotionEvent.ACTION_POINTER_UP,
            -> {
                lastPanFocusX = focusX(event)
                lastPanFocusY = focusY(event)
                panPointerCount = event.pointerCount
            }

            MotionEvent.ACTION_MOVE -> {
                val fx = focusX(event)
                val fy = focusY(event)
                if (!scaleDetector.isInProgress && panPointerCount == event.pointerCount) {
                    applyPan(fx - lastPanFocusX, fy - lastPanFocusY)
                }
                lastPanFocusX = fx
                lastPanFocusY = fy
            }
        }
        return true
    }

    private fun focusX(event: MotionEvent): Float {
        var sum = 0f
        for (i in 0 until event.pointerCount) sum += event.getX(i)
        return sum / event.pointerCount
    }

    private fun focusY(event: MotionEvent): Float {
        var sum = 0f
        for (i in 0 until event.pointerCount) sum += event.getY(i)
        return sum / event.pointerCount
    }

    private fun pointerIndexOf(event: MotionEvent, pointerId: Int): Int {
        if (pointerId == MotionEvent.INVALID_POINTER_ID) return -1
        return event.findPointerIndex(pointerId)
    }

    private fun recordHitPoint(event: MotionEvent, index: Int) {
        if (index < 0 || index >= event.pointerCount) return
        toPageCoords(event.getX(index), event.getY(index))
        hitPathBuilder.add(tmpPoint[0], tmpPoint[1])
    }

    private fun recordHistoricalHitPoint(event: MotionEvent, index: Int, historyPos: Int) {
        if (index < 0 || index >= event.pointerCount) return
        toPageCoords(
            event.getHistoricalX(index, historyPos),
            event.getHistoricalY(index, historyPos),
        )
        hitPathBuilder.add(tmpPoint[0], tmpPoint[1])
    }

    private fun toPageCoords(viewX: Float, viewY: Float) {
        tmpPoint[0] = viewX
        tmpPoint[1] = viewY
        viewToPage.mapPoints(tmpPoint)
    }

    // --- Trazos terminados ---------------------------------------------------

    override fun onStrokesFinished(strokes: Map<InProgressStrokeId, Stroke>) {
        val path = hitPathBuilder.build()
        for ((_, stroke) in strokes) {
            onStrokeFinished?.invoke(InkStroke(stroke, path))
        }
        // El trazo ya vive en la capa seca; sacarlo de la mojada evita dibujarlo
        // dos veces y libera el buffer para el siguiente.
        inProgressView.removeFinishedStrokes(strokes.keys)
        currentStrokeId = null
        invalidate()
    }

    override fun onDetachedFromWindow() {
        inProgressView.removeFinishedStrokesListener(this)
        releaseCache()
        super.onDetachedFromWindow()
    }

    /** Zoom actual como multiplo del encuadre inicial, para mostrarlo en la UI. */
    val zoomFactor: Float get() = if (fitZoom > 0f) zoom / fitZoom else 1f
}
