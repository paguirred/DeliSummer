package cl.aguirre.cuaderno.ink

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.FrameLayout
import androidx.ink.brush.Brush
import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.authoring.InProgressStrokesFinishedListener
import androidx.ink.authoring.InProgressStrokesView
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.Stroke
import androidx.input.motionprediction.MotionEventPredictor
import cl.aguirre.cuaderno.data.model.PageTemplate
import kotlin.math.max
import kotlin.math.min

/**
 * El lienzo de una pagina.
 *
 * Tiene dos capas de tinta, que es lo que hace que escribir se sienta inmediato:
 *
 *  - La capa mojada la maneja [InProgressStrokesView], que dibuja el trazo en
 *    curso con front buffer, sin esperar el ciclo normal de composicion.
 *  - La capa seca son los trazos ya terminados, que se redibujan en [onDraw].
 *
 * La capa seca estuvo un tiempo cacheada en un bitmap, pero eso obligaba a
 * rasterizar la tinta en un Canvas por software mientras que [onDraw] recibe uno
 * acelerado por GPU. Cambiar hardware por software para "optimizar" resulto ser
 * el peor negocio posible en el camino caliente.
 *
 * Los trazos se guardan en coordenadas de pagina (puntos PDF), nunca de pantalla.
 * El zoom y el desplazamiento viven solo en las matrices.
 */
class PageCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs), InProgressStrokesFinishedListener {

    private val inProgressView = InProgressStrokesView(context)
    private val renderer = CanvasStrokeRenderer.create()
    private val predictor: MotionEventPredictor by lazy { MotionEventPredictor.newInstance(this) }
    private val conditioner = InputConditioner()
    private val scribbleDetector = ScribbleDetector()
    private val recorder = StrokeRecorder()
    private var currentBrush: Brush? = null
    private val sinkPoint = FloatArray(2)
    private val perf = PerfMonitor()

    /**
     * Modo diagnostico: numeros de latencia y un circulo en la posicion cruda
     * del lapiz. La distancia entre ese circulo y la punta de la tinta es la
     * latencia, hecha visible.
     */
    var showDiagnostics: Boolean = false
        set(value) { if (field != value) { field = value; invalidate() } }

    var onDiagnostics: ((String) -> Unit)? = null

    private var rawX = Float.NaN
    private var rawY = Float.NaN

    // --- Estado de la pagina -------------------------------------------------

    var strokes: List<InkStroke> = emptyList()
        set(value) {
            if (field === value) return
            field = value
            invalidate()
        }

    /** Trazos seleccionados con el lazo. Se dibujan aparte porque pueden moverse. */
    var selection: Set<InkStroke> = emptySet()
        set(value) {
            if (field == value) return
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
        set(value) { if (field != value) { field = value; requestFit() } }

    var pageHeightPt: Float = 842f
        set(value) { if (field != value) { field = value; requestFit() } }

    var pdfBackground: Bitmap? = null
        set(value) { if (field !== value) { field = value; invalidate() } }

    // --- Herramienta ---------------------------------------------------------

    var tool: EditorTool = EditorTool.PEN
    var colorLong: Long = Color.pack(Color.BLACK)
    var strokeSizePt: Float =
        BrushCatalog.sizeToPoints(BrushCatalog.defaultSize(BrushKind.PEN))
    var eraserRadiusPt: Float = 10f

    /** Tachar con el lapiz borra lo que hay debajo. */
    var scribbleToErase: Boolean = true

    var stabilization: Float
        get() = conditioner.stabilization
        set(value) { conditioner.stabilization = value.coerceIn(0f, 1f) }

    var pressureGamma: Float
        get() = conditioner.pressureGamma
        set(value) { conditioner.pressureGamma = value.coerceIn(0.2f, 4f) }

    /** Cuanto adelgaza el trazo al entrar y al salir. */
    var taper: Float
        get() = conditioner.taper
        set(value) { conditioner.taper = value.coerceIn(0f, 1f) }

    var stylusOnly: Boolean = true

    // --- Callbacks -----------------------------------------------------------

    var onStrokeFinished: ((InkStroke) -> Unit)? = null
    var onErase: ((List<InkStroke>) -> Unit)? = null
    var onTransformChanged: ((zoom: Float) -> Unit)? = null
    var onLassoComplete: ((polygon: FloatArray) -> Unit)? = null
    var onSelectionTransform: ((Matrix) -> Unit)? = null

    // --- Transformacion pagina <-> vista -------------------------------------

    private val pageToView = Matrix()
    private val viewToPage = Matrix()
    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f
    private var fitZoom = 1f
    private var minZoom = 0.1f
    private var needsFit = true

    // --- Dibujo --------------------------------------------------------------

    private val shadowPaint = Paint().apply {
        isAntiAlias = true
        color = Color.parseColor("#22000000")
    }
    private val bitmapPaint = Paint().apply {
        isAntiAlias = true
        isFilterBitmap = true
    }
    private val lassoPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.parseColor("#1B57B8")
        pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
    }
    private val lassoFill = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
        color = Color.parseColor("#221B57B8")
    }
    private val selectionPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.parseColor("#1B57B8")
        pathEffect = DashPathEffect(floatArrayOf(10f, 6f), 0f)
    }
    private val handlePaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
        color = Color.parseColor("#1B57B8")
    }
    private val diagMarkerPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.parseColor("#E53935")
    }
    private val diagTextPaint = Paint().apply {
        isAntiAlias = true
        color = Color.parseColor("#E53935")
        textSize = 30f
    }

    private val combined = Matrix()
    private val pageRect = RectF()
    private val srcRect = Rect()
    private val destRect = RectF()
    private val lassoPath = Path()
    private val selectionRect = RectF()

    // --- Entrada -------------------------------------------------------------

    private var activeStylusPointerId = MotionEvent.INVALID_POINTER_ID
    private var currentStrokeId: InProgressStrokeId? = null
    private val hitPathBuilder = HitPathBuilder()
    private val tmpPoint = FloatArray(2)

    /** Mientras dura un tachon, el trazo se descarta y se pasa a borrar. */
    private var scribbleErasing = false

    private var lassoBuilder: HitPathBuilder? = null
    private var lassoLive: FloatArray? = null

    /** Transformacion en curso de la seleccion, aun sin confirmar. */
    private val liveTransform = Matrix()
    private var movingSelection = false
    private var lastMoveX = 0f
    private var lastMoveY = 0f

    private var lastPanFocusX = 0f
    private var lastPanFocusY = 0f
    private var panPointerCount = 0

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (movingSelection) {
                    scaleSelection(detector.scaleFactor, detector.focusX, detector.focusY)
                } else {
                    applyZoom(detector.scaleFactor, detector.focusX, detector.focusY)
                }
                return true
            }
        },
    )

    init {
        setWillNotDraw(false)
        setBackgroundColor(Color.parseColor("#EEF1F5"))
        addView(inProgressView)
        inProgressView.addFinishedStrokesListener(this)

        // Cada muestra que pasa por el acondicionador se guarda en coordenadas
        // de pagina, que es donde vivira el trazo reconstruido.
        conditioner.sampleSink = { x, y, pressure, timeMs ->
            sinkPoint[0] = x
            sinkPoint[1] = y
            viewToPage.mapPoints(sinkPoint)
            recorder.add(sinkPoint[0], sinkPoint[1], pressure, timeMs)
        }
    }

    // --- Ciclo de vida -------------------------------------------------------

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (needsFit || oldw == 0) fitToWidth()
    }

    private fun requestFit() {
        needsFit = true
        if (width > 0) fitToWidth()
        invalidate()
    }

    fun fitToWidth() {
        if (width == 0 || pageWidthPt <= 0f) return
        val margin = 24f
        fitZoom = (width - margin * 2) / pageWidthPt
        // El piso del zoom permite ver la hoja completa y algo mas de aire.
        // Antes el piso era el propio encuadre a lo ancho, asi que en una hoja
        // vertical no habia forma de ver el pie de la pagina de un vistazo.
        val fitWholePage = min(
            (width - margin * 2) / pageWidthPt,
            (height - margin * 2) / pageHeightPt,
        )
        minZoom = min(fitZoom, fitWholePage) * 0.85f
        zoom = fitZoom
        panX = margin
        panY = margin
        needsFit = false
        updateMatrices()
    }

    /** Encuadra la hoja entera. */
    fun fitWholePage() {
        if (width == 0 || height == 0) return
        val margin = 24f
        zoom = min(
            (width - margin * 2) / pageWidthPt,
            (height - margin * 2) / pageHeightPt,
        )
        panX = (width - pageWidthPt * zoom) / 2f
        panY = (height - pageHeightPt * zoom) / 2f
        updateMatrices()
    }

    private fun applyZoom(factor: Float, focusX: Float, focusY: Float) {
        val target = (zoom * factor).coerceIn(minZoom, fitZoom * MAX_ZOOM_FACTOR)
        val actual = target / zoom
        if (actual == 1f) return
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
        onTransformChanged?.invoke(if (fitZoom > 0f) zoom / fitZoom else 1f)
        invalidate()
    }

    private fun clampPan() {
        val scaledW = pageWidthPt * zoom
        val scaledH = pageHeightPt * zoom
        panX = if (scaledW <= width) (width - scaledW) / 2f else panX.coerceIn(width - scaledW, 0f)
        panY = if (scaledH <= height) (height - scaledH) / 2f else panY.coerceIn(height - scaledH, 0f)
    }

    fun resetZoom() = fitToWidth()

    // --- Render --------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (showDiagnostics) {
            perf.beginDraw(strokes.size, activeStylusPointerId != MotionEvent.INVALID_POINTER_ID)
        }

        // Nada se pinta fuera de la vista. Sin esto, al alejar el zoom la hoja
        // se veia invadiendo la barra de herramientas de arriba.
        canvas.clipRect(0f, 0f, width.toFloat(), height.toFloat())

        pageRect.set(0f, 0f, pageWidthPt, pageHeightPt)
        pageToView.mapRect(pageRect)

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

        for (inkStroke in strokes) {
            val selected = inkStroke in selection
            combined.set(pageToView)
            if (selected) combined.preConcat(liveTransform)
            combined.preConcat(inkStroke.transform)
            drawStroke(canvas, inkStroke, combined)
        }
        if (selection.isNotEmpty()) drawSelectionFrame(canvas)

        lassoLive?.let { points ->
            if (points.size >= 4) {
                lassoPath.reset()
                lassoPath.moveTo(points[0], points[1])
                var i = 2
                while (i + 1 < points.size) {
                    lassoPath.lineTo(points[i], points[i + 1])
                    i += 2
                }
                lassoPath.close()
                canvas.save()
                canvas.concat(pageToView)
                canvas.drawPath(lassoPath, lassoFill)
                canvas.restore()
                // El contorno se dibuja sin escalar para que el grosor del
                // punteado se vea igual con cualquier zoom.
                lassoPath.transform(pageToView)
                canvas.drawPath(lassoPath, lassoPaint)
            }
        }

        if (showDiagnostics) {
            if (!rawX.isNaN()) canvas.drawCircle(rawX, rawY, 14f, diagMarkerPaint)
            val text = perf.summary()
            canvas.drawText(text, 16f, height - 20f, diagTextPaint)
            perf.endDraw()
            onDiagnostics?.invoke(text)
            // Con el diagnostico activo hace falta repintar de continuo para que
            // los numeros se muevan; por eso no queda encendido por defecto.
            invalidate()
        }
    }

    private fun drawSelectionFrame(canvas: Canvas) {
        val box = selectionBoundsInView() ?: return
        canvas.drawRect(box, selectionPaint)
        val r = HANDLE_RADIUS_PX
        canvas.drawCircle(box.right, box.bottom, r, handlePaint)
        canvas.drawCircle(box.left, box.top, r, handlePaint)
    }

    private fun selectionBoundsInView(): RectF? {
        if (selection.isEmpty()) return null
        val box = RectF(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        for (s in selection) {
            box.left = min(box.left, s.bounds.left)
            box.top = min(box.top, s.bounds.top)
            box.right = max(box.right, s.bounds.right)
            box.bottom = max(box.bottom, s.bounds.bottom)
        }
        if (box.left > box.right) return null
        liveTransform.mapRect(box)
        pageToView.mapRect(box)
        box.inset(-10f, -10f)
        return box
    }

    /**
     * Dibuja un trazo ya seco aplicando [matrix].
     *
     * La matriz va al canvas y ademas se pasa al renderizador. No es redundante:
     * el renderizador no transforma la geometria, solo usa la matriz para elegir
     * el nivel de detalle. Quien coloca el trazo en la pagina es el canvas.
     * Olvidar el concat deja los trazos diminutos pegados al origen.
     */
    private fun drawStroke(canvas: Canvas, inkStroke: InkStroke, matrix: Matrix) {
        canvas.save()
        canvas.concat(matrix)
        renderer.draw(canvas = canvas, stroke = inkStroke.stroke, strokeToScreenTransform = matrix)
        canvas.restore()
    }

    // --- Entrada -------------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (showDiagnostics) {
            perf.onInput(event)
            rawX = event.getX(0)
            rawY = event.getY(0)
        }
        val index = event.actionIndex
        val toolType = event.getToolType(index)
        val isStylus = toolType == MotionEvent.TOOL_TYPE_STYLUS
        val isPenEraser = toolType == MotionEvent.TOOL_TYPE_ERASER

        val drawingWithStylus = activeStylusPointerId != MotionEvent.INVALID_POINTER_ID
        if (drawingWithStylus && !isStylus && !isPenEraser) return true

        val penInput = isStylus || isPenEraser
        val fingerMayDraw = !stylusOnly && toolType == MotionEvent.TOOL_TYPE_FINGER

        return when {
            tool == EditorTool.LASSO -> handleLassoTool(event)

            (isPenEraser || tool == EditorTool.ERASER) && (penInput || fingerMayDraw) ->
                handleErase(event)

            tool.isDrawing && (penInput || fingerMayDraw) -> handleInk(event)

            else -> handleNavigation(event)
        }
    }

    // --- Lazo y seleccion ----------------------------------------------------

    private fun handleLassoTool(event: MotionEvent): Boolean {
        // Con dos dedos siempre se transforma o navega, nunca se dibuja el lazo.
        if (event.pointerCount > 1) {
            movingSelection = selection.isNotEmpty()
            scaleDetector.onTouchEvent(event)
            if (!movingSelection) return handleNavigation(event)
            if (event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL
            ) {
                commitSelectionTransform()
            }
            return true
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                toPageCoords(event.getX(0), event.getY(0))
                val inside = selectionBoundsInView()?.contains(event.getX(0), event.getY(0)) == true
                if (inside) {
                    movingSelection = true
                    lastMoveX = event.getX(0)
                    lastMoveY = event.getY(0)
                } else {
                    if (selection.isNotEmpty()) onLassoComplete?.invoke(FloatArray(0))
                    movingSelection = false
                    lassoBuilder = HitPathBuilder(minDistance = 3f).also {
                        it.add(tmpPoint[0], tmpPoint[1])
                    }
                    lassoLive = lassoBuilder?.snapshot()
                    invalidate()
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (movingSelection) {
                    val dx = event.getX(0) - lastMoveX
                    val dy = event.getY(0) - lastMoveY
                    lastMoveX = event.getX(0)
                    lastMoveY = event.getY(0)
                    // El arrastre viene en pixeles y la seleccion vive en puntos.
                    liveTransform.postTranslate(dx / zoom, dy / zoom)
                    invalidate()
                } else {
                    val builder = lassoBuilder ?: return true
                    for (h in 0 until event.historySize) {
                        toPageCoords(event.getHistoricalX(0, h), event.getHistoricalY(0, h))
                        builder.add(tmpPoint[0], tmpPoint[1])
                    }
                    toPageCoords(event.getX(0), event.getY(0))
                    builder.add(tmpPoint[0], tmpPoint[1])
                    lassoLive = builder.snapshot()
                    invalidate()
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (movingSelection) {
                    commitSelectionTransform()
                } else {
                    val polygon = lassoBuilder?.build() ?: FloatArray(0)
                    lassoBuilder = null
                    lassoLive = null
                    if (polygon.size >= 6) onLassoComplete?.invoke(polygon)
                    invalidate()
                }
            }
        }
        return true
    }

    private fun scaleSelection(factor: Float, focusX: Float, focusY: Float) {
        toPageCoords(focusX, focusY)
        liveTransform.postScale(factor, factor, tmpPoint[0], tmpPoint[1])
        invalidate()
    }

    private fun commitSelectionTransform() {
        movingSelection = false
        if (liveTransform.isIdentity) return
        onSelectionTransform?.invoke(Matrix(liveTransform))
        liveTransform.reset()
        invalidate()
    }

    /** Limpia la transformacion en vivo tras confirmarla desde el estado. */
    fun clearLiveTransform() {
        liveTransform.reset()
        invalidate()
    }

    // --- Tinta ---------------------------------------------------------------

    private fun handleInk(event: MotionEvent): Boolean {
        val index = event.actionIndex
        val pointerId = event.getPointerId(index)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (activeStylusPointerId != MotionEvent.INVALID_POINTER_ID) return true
                requestUnbufferedDispatch(event)
                parent?.requestDisallowInterceptTouchEvent(true)

                val kind = tool.brushKind ?: BrushKind.PEN
                val brush = BrushCatalog.create(kind, colorLong, strokeSizePt)
                currentBrush = brush
                recorder.begin(event.eventTime)

                activeStylusPointerId = pointerId
                hitPathBuilder.reset()
                conditioner.reset()
                scribbleDetector.reset()
                scribbleErasing = false
                if (showDiagnostics) perf.beginStroke()

                val conditioned = conditioner.condition(event, index)
                val source = conditioned ?: event
                recordHitPoint(source, source.findPointerIndex(pointerId).coerceAtLeast(0))

                currentStrokeId = inProgressView.startStroke(
                    event = source,
                    pointerId = pointerId,
                    brush = brush,
                    motionEventToWorldTransform = viewToPage,
                    strokeToWorldTransform = Matrix(),
                )
                conditioned?.recycle()
            }

            MotionEvent.ACTION_MOVE -> {
                if (scribbleErasing) return eraseAlong(event)

                val strokeId = currentStrokeId ?: return true
                val moveIndex = pointerIndexOf(event, activeStylusPointerId)
                if (moveIndex < 0) return true

                val conditioned = conditioner.condition(event, moveIndex)
                val source = conditioned ?: event
                val sourceIndex = source.findPointerIndex(activeStylusPointerId).coerceAtLeast(0)

                for (h in 0 until source.historySize) {
                    recordHistoricalHitPoint(source, sourceIndex, h)
                }
                recordHitPoint(source, sourceIndex)

                if (scribbleToErase && detectScribble()) {
                    // Es un tachon: se descarta el garabato y se borra debajo.
                    inProgressView.cancelStroke(strokeId, event)
                    currentStrokeId = null
                    scribbleErasing = true
                    conditioned?.recycle()
                    return eraseAlongPath(hitPathBuilder.snapshot())
                }

                predictor.record(source)
                val predicted = predictor.predict()
                inProgressView.addToStroke(source, activeStylusPointerId, strokeId, predicted)
                conditioned?.recycle()
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                activeStylusPointerId = MotionEvent.INVALID_POINTER_ID
                if (scribbleErasing) {
                    scribbleErasing = false
                    hitPathBuilder.reset()
                    return true
                }
                val strokeId = currentStrokeId ?: return true
                if (pointerId != event.getPointerId(index)) return true
                val conditioned = conditioner.condition(event, index)
                val source = conditioned ?: event
                recordHitPoint(source, source.findPointerIndex(pointerId).coerceAtLeast(0))
                inProgressView.finishStroke(source, pointerId, strokeId)
                conditioned?.recycle()
            }

            MotionEvent.ACTION_CANCEL -> {
                currentStrokeId?.let { inProgressView.cancelStroke(it, event) }
                currentStrokeId = null
                activeStylusPointerId = MotionEvent.INVALID_POINTER_ID
                scribbleErasing = false
                hitPathBuilder.reset()
                conditioner.reset()
            }
        }
        return true
    }

    private fun detectScribble(): Boolean {
        val path = hitPathBuilder.snapshot()
        if (path.size < 4) return false
        // Solo hace falta alimentar el ultimo punto: el detector es incremental.
        return scribbleDetector.accept(path[path.size - 2], path[path.size - 1])
    }

    // --- Borrado -------------------------------------------------------------

    private fun handleErase(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                return eraseAlong(event)
            }
        }
        return true
    }

    private fun eraseAlong(event: MotionEvent): Boolean {
        val removed = ArrayList<InkStroke>()
        for (h in 0 until event.historySize) {
            toPageCoords(event.getHistoricalX(0, h), event.getHistoricalY(0, h))
            collectHits(tmpPoint[0], tmpPoint[1], removed)
        }
        toPageCoords(event.getX(0), event.getY(0))
        collectHits(tmpPoint[0], tmpPoint[1], removed)
        if (removed.isNotEmpty()) onErase?.invoke(removed)
        return true
    }

    private fun eraseAlongPath(path: FloatArray): Boolean {
        val removed = ArrayList<InkStroke>()
        var i = 0
        while (i + 1 < path.size) {
            collectHits(path[i], path[i + 1], removed)
            i += 2
        }
        if (removed.isNotEmpty()) onErase?.invoke(removed)
        return true
    }

    private fun collectHits(x: Float, y: Float, into: MutableList<InkStroke>) {
        for (inkStroke in strokes) {
            if (inkStroke in into) continue
            if (inkStroke.hits(x, y, eraserRadiusPt)) into.add(inkStroke)
        }
    }

    // --- Navegacion ----------------------------------------------------------

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
        toPageCoords(event.getHistoricalX(index, historyPos), event.getHistoricalY(index, historyPos))
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
        // Al soltar, el trazo ya se conoce entero, asi que se rehace con la
        // envolvente completa. La capa mojada solo pudo afinar el comienzo,
        // porque mientras se escribe nadie sabe donde va a terminar.
        val brush = currentBrush
        var rebuilt = if (brush != null) recorder.build(brush, conditioner.taper) else null
        recorder.clear()
        for ((_, stroke) in strokes) {
            onStrokeFinished?.invoke(InkStroke(rebuilt ?: stroke, path))
            rebuilt = null
        }
        inProgressView.removeFinishedStrokes(strokes.keys)
        currentStrokeId = null
        invalidate()
    }

    override fun onDetachedFromWindow() {
        inProgressView.removeFinishedStrokesListener(this)
        super.onDetachedFromWindow()
    }

    val zoomFactor: Float get() = if (fitZoom > 0f) zoom / fitZoom else 1f

    private companion object {
        const val MAX_ZOOM_FACTOR = 8f
        const val HANDLE_RADIUS_PX = 10f
    }
}
