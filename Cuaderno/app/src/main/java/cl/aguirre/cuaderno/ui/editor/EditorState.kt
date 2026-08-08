package cl.aguirre.cuaderno.ui.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cl.aguirre.cuaderno.data.NotebookStore
import cl.aguirre.cuaderno.data.model.NotebookIndex
import cl.aguirre.cuaderno.data.model.PageMeta
import cl.aguirre.cuaderno.data.model.PageTemplate
import cl.aguirre.cuaderno.ink.BrushCatalog
import cl.aguirre.cuaderno.ink.BrushKind
import cl.aguirre.cuaderno.ink.EditorTool
import cl.aguirre.cuaderno.ink.InkStroke
import cl.aguirre.cuaderno.pdf.ImageExporter
import cl.aguirre.cuaderno.pdf.PdfExporter
import cl.aguirre.cuaderno.pdf.PdfPageSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * Estado del editor de un cuaderno.
 *
 * Mantiene la pagina abierta en memoria y persiste con retardo. Cada trazo que
 * se termina dispara un guardado diferido, de manera que escribir sin pausa no
 * golpea el disco en cada trazo, pero una pausa normal al pensar ya deja todo
 * guardado.
 */
class EditorState(
    private val store: NotebookStore,
    private val notebookId: String,
    private val scope: CoroutineScope,
    private val context: Context,
) {
    var index by mutableStateOf<NotebookIndex?>(null)
        private set
    var pageIndex by mutableStateOf(0)
        private set
    var strokes by mutableStateOf<List<InkStroke>>(emptyList())
        private set

    var tool by mutableStateOf(EditorTool.PEN)
    var stylusOnly by mutableStateOf(true)
    var zoomFactor by mutableStateOf(1f)
    var busy by mutableStateOf(false)
        private set

    /**
     * Estabilizacion del trazo, 0..1. Por defecto baja: suavizar siempre cuesta
     * latencia, y el punto de esta app es no tener latencia.
     */
    var stabilization by mutableStateOf(0.15f)

    /**
     * Gamma de la curva de presion, propia de cada herramienta.
     *
     * Es por herramienta y no global porque es justo lo que separa una
     * estilografica de una pluma pincel: el motor les da el mismo pincel, y la
     * diferencia de caracter sale de como se traduce la fuerza en grosor.
     */
    var pressureGamma: Float
        get() = toolGammas[tool] ?: (tool.brushKind?.defaultPressureGamma ?: 1f)
        set(value) { toolGammas[tool] = value.coerceIn(0.2f, 4f) }

    /** Tachar con el lapiz borra lo que hay debajo. */
    var scribbleToErase by mutableStateOf(true)

    /** Cada herramienta recuerda su propio color y grosor. */
    private val toolColors = mutableStateMapOf<EditorTool, Long>()
    private val toolSizes = mutableStateMapOf<EditorTool, Int>()
    private val toolGammas = mutableStateMapOf<EditorTool, Float>()

    var pdfBackground by mutableStateOf<Bitmap?>(null)
        private set

    private var undoStack = ArrayDeque<InkOp>()
    private var redoStack = ArrayDeque<InkOp>()
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    private var saveJob: Job? = null
    private var pdfSource: PdfPageSource? = null

    /**
     * El guardado corre en su propio scope, no en el de Compose.
     *
     * El scope de Compose muere apenas la pantalla sale de composicion, asi que
     * un autoguardado pendiente moriria con el: cerrar el cuaderno antes de que
     * venciera el retardo perderia los ultimos trazos escritos.
     */
    private val persistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val currentPage: PageMeta? get() = index?.pages?.getOrNull(pageIndex)
    val pageCount: Int get() = index?.pages?.size ?: 0

    val colorLong: Long
        get() = toolColors[tool] ?: defaultColor(tool)

    /** Grosor de la herramienta actual en la escala 1..100 de la UI. */
    val sizeValue: Int
        get() = toolSizes[tool] ?: BrushCatalog.defaultSize(tool.brushKind ?: BrushKind.PEN)

    /** El mismo grosor en puntos de pagina, que es lo que consume el pincel. */
    val sizePt: Float
        get() = BrushCatalog.sizeToPoints(sizeValue)

    fun setColor(value: Long) { toolColors[tool] = value }
    fun setSize(value: Int) { toolSizes[tool] = value.coerceIn(1, 100) }

    private fun defaultColor(tool: EditorTool): Long = when (tool) {
        EditorTool.HIGHLIGHTER -> Color.pack(0x80FFEB3B.toInt())
        else -> Color.pack(0xFF15202B.toInt())
    }

    // --- Carga ---------------------------------------------------------------

    fun load() {
        scope.launch {
            val loaded = store.loadIndex(notebookId) ?: return@launch
            index = loaded
            loaded.meta.pdfFileName?.let { name ->
                val file = store.pdfFile(notebookId, name)
                if (file.exists()) pdfSource = PdfPageSource.open(file)
            }
            openPage(0)
        }
    }

    fun openPage(target: Int) {
        val idx = index ?: return
        val clamped = target.coerceIn(0, (idx.pages.size - 1).coerceAtLeast(0))
        scope.launch {
            flushSave()
            pageIndex = clamped
            val page = idx.pages[clamped]
            strokes = store.loadPage(notebookId, page.id)
            undoStack.clear()
            redoStack.clear()
            refreshUndoFlags()
            loadBackgroundFor(page)
        }
    }

    private suspend fun loadBackgroundFor(page: PageMeta) {
        val source = pdfSource
        pdfBackground?.recycle()
        pdfBackground = null
        if (source != null && page.pdfPageIndex >= 0) {
            // Se rasteriza a mas del ancho de pantalla para que el fondo aguante
            // el zoom sin verse pixelado bajo la tinta.
            pdfBackground = source.renderPage(page.pdfPageIndex, 2048)
        }
    }

    // --- Edicion -------------------------------------------------------------

    fun addStroke(stroke: InkStroke) {
        strokes = strokes + stroke
        push(InkOp.AddMany(listOf(stroke)))
        scheduleSave()
    }

    fun erase(targets: List<InkStroke>) {
        if (targets.isEmpty()) return
        val removed = targets.mapNotNull { target ->
            val i = strokes.indexOf(target)
            if (i >= 0) IndexedValue(i, target) else null
        }
        if (removed.isEmpty()) return
        val targetSet = removed.map { it.value }.toHashSet()
        strokes = strokes.filterNot { it in targetSet }
        push(InkOp.Remove(removed))
        scheduleSave()
    }

    // --- Seleccion -----------------------------------------------------------

    var selection by mutableStateOf<Set<InkStroke>>(emptySet())
        private set

    private var clipboard: List<InkStroke> = emptyList()
    val hasClipboard: Boolean get() = clipboard.isNotEmpty()

    fun selectFromLasso(polygon: FloatArray) {
        selection = if (polygon.size < 6) {
            emptySet()
        } else {
            strokes.filter { it.isInside(polygon) }.toSet()
        }
    }

    fun clearSelection() { selection = emptySet() }

    /** Aplica una transformacion confirmada a los trazos seleccionados. */
    fun transformSelection(matrix: android.graphics.Matrix) {
        if (selection.isEmpty()) return
        val changes = ArrayList<Triple<Int, InkStroke, InkStroke>>()
        strokes.forEachIndexed { index, stroke ->
            if (stroke in selection) changes.add(Triple(index, stroke, stroke.transformedBy(matrix)))
        }
        if (changes.isEmpty()) return
        val op = InkOp.Replace(changes)
        strokes = op.apply(strokes)
        // La seleccion tiene que apuntar a los objetos nuevos, no a los de antes.
        selection = changes.map { it.third }.toSet()
        push(op)
        scheduleSave()
    }

    fun deleteSelection() {
        if (selection.isEmpty()) return
        val removed = strokes.withIndex()
            .filter { it.value in selection }
            .map { IndexedValue(it.index, it.value) }
        if (removed.isEmpty()) return
        val op = InkOp.Remove(removed)
        strokes = op.apply(strokes)
        selection = emptySet()
        push(op)
        scheduleSave()
    }

    fun copySelection() {
        if (selection.isNotEmpty()) clipboard = strokes.filter { it in selection }
    }

    fun cutSelection() {
        copySelection()
        deleteSelection()
    }

    /**
     * Pega el portapapeles desplazado. El desplazamiento existe para que la copia
     * no quede exactamente encima del original, donde seria invisible.
     */
    fun paste() {
        if (clipboard.isEmpty()) return
        val offset = android.graphics.Matrix().apply { setTranslate(PASTE_OFFSET, PASTE_OFFSET) }
        val pasted = clipboard.map { it.transformedBy(offset) }
        val op = InkOp.AddMany(pasted)
        strokes = op.apply(strokes)
        selection = pasted.toSet()
        clipboard = pasted
        push(op)
        scheduleSave()
    }

    fun duplicateSelection() {
        copySelection()
        paste()
    }

    fun undo() {
        val op = undoStack.removeLastOrNull() ?: return
        selection = emptySet()
        redoStack.addLast(op)
        strokes = op.revert(strokes)
        refreshUndoFlags()
        scheduleSave()
    }

    fun redo() {
        val op = redoStack.removeLastOrNull() ?: return
        selection = emptySet()
        undoStack.addLast(op)
        strokes = op.apply(strokes)
        refreshUndoFlags()
        scheduleSave()
    }

    private fun push(op: InkOp) {
        undoStack.addLast(op)
        // Un historial ilimitado en una pagina densa termina reteniendo mallas
        // de tinta que ya nadie va a recuperar.
        if (undoStack.size > MAX_HISTORY) undoStack.removeFirst()
        redoStack.clear()
        refreshUndoFlags()
    }

    private fun refreshUndoFlags() {
        canUndo = undoStack.isNotEmpty()
        canRedo = redoStack.isNotEmpty()
    }

    // --- Paginas -------------------------------------------------------------

    fun addPage(template: PageTemplate) {
        scope.launch {
            flushSave()
            val updated = store.addPage(notebookId, template, pageIndex) ?: return@launch
            index = updated
            openPage(pageIndex + 1)
        }
    }

    fun deleteCurrentPage() {
        val page = currentPage ?: return
        scope.launch {
            val updated = store.deletePage(notebookId, page.id) ?: return@launch
            index = updated
            openPage(pageIndex.coerceAtMost(updated.pages.size - 1))
        }
    }

    fun setTemplate(template: PageTemplate) {
        val idx = index ?: return
        val page = currentPage ?: return
        scope.launch {
            val pages = idx.pages.toMutableList()
            pages[pageIndex] = page.copy(template = template)
            val updated = idx.copy(pages = pages)
            store.saveIndex(updated)
            index = updated
        }
    }

    // --- Guardado ------------------------------------------------------------

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = persistenceScope.launch {
            delay(SAVE_DELAY_MS)
            persist()
        }
    }

    suspend fun flushSave() {
        saveJob?.cancel()
        saveJob = null
        persist()
    }

    private suspend fun persist() {
        val page = currentPage ?: return
        store.savePage(notebookId, page.id, strokes)
    }

    // --- Exportar ------------------------------------------------------------

    /** Captura la seleccion como PNG para compartirla. */
    suspend fun exportSelectionImage(): File? {
        if (selection.isEmpty()) return null
        busy = true
        return try {
            ImageExporter.exportSelection(context, strokes.filter { it in selection })
        } finally {
            busy = false
        }
    }

    suspend fun exportPdf(): File? {
        val idx = index ?: return null
        busy = true
        return try {
            flushSave()
            PdfExporter.export(context, store, idx)
        } finally {
            busy = false
        }
    }

    /**
     * Cierra el editor. Guarda de forma sincrona lo que quede pendiente antes de
     * soltar los recursos: es el ultimo punto en que la pagina abierta todavia
     * existe en memoria.
     */
    fun dispose() {
        // El guardado final se lanza y el scope se cierra recien cuando termina.
        // Bloquear aqui seria bloquear el hilo principal justo al cerrar la
        // pantalla, que es cuando mas se nota un tiron.
        val snapshot = strokes
        val page = currentPage
        persistenceScope.launch {
            saveJob?.cancel()
            if (page != null) store.savePage(notebookId, page.id, snapshot)
        }.invokeOnCompletion { persistenceScope.cancel() }

        pdfSource?.close()
        pdfSource = null
        pdfBackground?.recycle()
        pdfBackground = null
    }

    private companion object {
        const val SAVE_DELAY_MS = 800L
        const val MAX_HISTORY = 60
        const val PASTE_OFFSET = 18f
    }
}

/** Una edicion reversible sobre la lista de trazos de la pagina. */
private sealed interface InkOp {

    fun apply(list: List<InkStroke>): List<InkStroke>
    fun revert(list: List<InkStroke>): List<InkStroke>

    data class AddMany(val added: List<InkStroke>) : InkOp {
        override fun apply(list: List<InkStroke>) = list + added
        override fun revert(list: List<InkStroke>): List<InkStroke> {
            val gone = added.toHashSet()
            return list.filterNot { it in gone }
        }
    }

    /**
     * Sustituye trazos en su posicion. Es lo que produce mover o escalar: la
     * geometria del motor es inmutable, asi que "mover" es reemplazar el trazo
     * por otro con distinta matriz, y hay que conservar su lugar en la lista
     * para no alterar que queda encima de que.
     */
    data class Replace(val items: List<Triple<Int, InkStroke, InkStroke>>) : InkOp {
        override fun apply(list: List<InkStroke>): List<InkStroke> {
            val result = list.toMutableList()
            for ((index, _, after) in items) {
                if (index in result.indices) result[index] = after
            }
            return result
        }

        override fun revert(list: List<InkStroke>): List<InkStroke> {
            val result = list.toMutableList()
            for ((index, before, _) in items) {
                if (index in result.indices) result[index] = before
            }
            return result
        }
    }

    /**
     * Guarda la posicion original de cada trazo borrado. Sin eso, deshacer un
     * borrado devolveria los trazos al final de la lista y cambiaria que queda
     * encima de que.
     */
    data class Remove(val items: List<IndexedValue<InkStroke>>) : InkOp {
        override fun apply(list: List<InkStroke>): List<InkStroke> {
            val removed = items.map { it.value }.toHashSet()
            return list.filterNot { it in removed }
        }

        override fun revert(list: List<InkStroke>): List<InkStroke> {
            val result = list.toMutableList()
            for ((position, stroke) in items.sortedBy { it.index }) {
                result.add(position.coerceIn(0, result.size), stroke)
            }
            return result
        }
    }
}
