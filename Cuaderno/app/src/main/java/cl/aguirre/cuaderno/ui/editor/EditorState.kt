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
import cl.aguirre.cuaderno.ink.EditorTool
import cl.aguirre.cuaderno.ink.InkStroke
import cl.aguirre.cuaderno.pdf.PdfExporter
import cl.aguirre.cuaderno.pdf.PdfPageSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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

    /** Cada herramienta recuerda su propio color y grosor. */
    private val toolColors = mutableStateMapOf<EditorTool, Long>()
    private val toolSizes = mutableStateMapOf<EditorTool, Float>()

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

    val currentPage: PageMeta? get() = index?.pages?.getOrNull(pageIndex)
    val pageCount: Int get() = index?.pages?.size ?: 0

    val colorLong: Long
        get() = toolColors[tool] ?: defaultColor(tool)

    val sizePt: Float
        get() = toolSizes[tool] ?: BrushCatalog.defaultSize(tool.brushKind ?: cl.aguirre.cuaderno.ink.BrushKind.PEN)

    fun setColor(value: Long) { toolColors[tool] = value }
    fun setSize(value: Float) { toolSizes[tool] = value }

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
        push(InkOp.Add(stroke))
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

    fun undo() {
        val op = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(op)
        strokes = op.revert(strokes)
        refreshUndoFlags()
        scheduleSave()
    }

    fun redo() {
        val op = redoStack.removeLastOrNull() ?: return
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
        saveJob = scope.launch {
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

    fun dispose() {
        pdfSource?.close()
        pdfSource = null
        pdfBackground?.recycle()
        pdfBackground = null
    }

    private companion object {
        const val SAVE_DELAY_MS = 800L
        const val MAX_HISTORY = 60
    }
}

/** Una edicion reversible sobre la lista de trazos de la pagina. */
private sealed interface InkOp {

    fun apply(list: List<InkStroke>): List<InkStroke>
    fun revert(list: List<InkStroke>): List<InkStroke>

    data class Add(val stroke: InkStroke) : InkOp {
        override fun apply(list: List<InkStroke>) = list + stroke
        override fun revert(list: List<InkStroke>) = list - stroke
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
