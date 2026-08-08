package cl.aguirre.cuaderno.data

import android.content.Context
import cl.aguirre.cuaderno.data.model.Folder
import cl.aguirre.cuaderno.data.model.FolderIndex
import cl.aguirre.cuaderno.data.model.NotebookIndex
import cl.aguirre.cuaderno.data.model.NotebookMeta
import cl.aguirre.cuaderno.data.model.PageMeta
import cl.aguirre.cuaderno.data.model.PageSize
import cl.aguirre.cuaderno.data.model.PageTemplate
import cl.aguirre.cuaderno.ink.InkCodec
import cl.aguirre.cuaderno.ink.InkStroke
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * Almacen de cuadernos en disco.
 *
 * Un cuaderno es una carpeta:
 *
 *     files/notebooks/<id>/
 *         index.json          metadatos y orden de paginas
 *         pages/<pageId>.ink  tinta de cada pagina
 *         source.pdf          PDF de fondo (opcional)
 *
 * Se eligio esto por sobre una base de datos porque un cuaderno es un documento,
 * no un conjunto de filas: copiar la carpeta es respaldar el cuaderno, y no hay
 * migraciones de esquema que mantener para una app de uso personal.
 */
class NotebookStore(context: Context) {

    private val root = File(context.filesDir, "notebooks").apply { mkdirs() }
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    private val _notebooks = MutableStateFlow<List<NotebookMeta>>(emptyList())
    val notebooks: StateFlow<List<NotebookMeta>> = _notebooks.asStateFlow()

    private val _folders = MutableStateFlow<List<Folder>>(emptyList())
    val folders: StateFlow<List<Folder>> = _folders.asStateFlow()

    private val foldersFile = File(root, "folders.json")

    // --- Carpetas ------------------------------------------------------------

    suspend fun createFolder(name: String): Folder = withContext(Dispatchers.IO) {
        val folder = Folder(UUID.randomUUID().toString(), name, System.currentTimeMillis())
        writeFolders(readFolders() + folder)
        refresh()
        folder
    }

    suspend fun renameFolder(folderId: String, name: String) = withContext(Dispatchers.IO) {
        writeFolders(readFolders().map { if (it.id == folderId) it.copy(name = name) else it })
        refresh()
    }

    /**
     * Borra la carpeta y devuelve sus cuadernos a la raiz.
     *
     * Nunca borra cuadernos: perder un semestre de apuntes por tocar el boton
     * equivocado en una lista seria un desastre desproporcionado.
     */
    suspend fun deleteFolder(folderId: String) = withContext(Dispatchers.IO) {
        writeFolders(readFolders().filterNot { it.id == folderId })
        for (meta in _notebooks.value.filter { it.folderId == folderId }) {
            readIndex(meta.id)?.let { index ->
                writeIndex(index.copy(meta = index.meta.copy(folderId = null)))
            }
        }
        refresh()
    }

    suspend fun moveToFolder(notebookId: String, folderId: String?) = withContext(Dispatchers.IO) {
        val index = readIndex(notebookId) ?: return@withContext
        writeIndex(index.copy(meta = index.meta.copy(folderId = folderId)))
        refresh()
    }

    // --- Cuadernos -----------------------------------------------------------

    suspend fun refresh() = withContext(Dispatchers.IO) {
        val found = root.listFiles { f -> f.isDirectory }
            ?.mapNotNull { readIndex(it.name)?.meta }
            ?.sortedByDescending { it.updatedAt }
            ?: emptyList()
        _notebooks.value = found
        _folders.value = readFolders().sortedBy { it.name.lowercase() }
    }

    private fun readFolders(): List<Folder> {
        if (!foldersFile.exists()) return emptyList()
        return runCatching {
            json.decodeFromString<FolderIndex>(foldersFile.readText()).folders
        }.getOrDefault(emptyList())
    }

    private fun writeFolders(folders: List<Folder>) {
        root.mkdirs()
        foldersFile.writeText(json.encodeToString(FolderIndex(folders)))
    }

    suspend fun create(
        title: String,
        template: PageTemplate = PageTemplate.GRID,
        folderId: String? = null,
    ): NotebookMeta = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val meta = NotebookMeta(
            id = id,
            title = title,
            createdAt = now,
            updatedAt = now,
            folderId = folderId,
        )
        val index = NotebookIndex(
            meta = meta,
            pages = listOf(PageMeta(id = UUID.randomUUID().toString(), template = template)),
        )
        writeIndex(index)
        refresh()
        meta
    }

    suspend fun delete(notebookId: String) = withContext(Dispatchers.IO) {
        dir(notebookId).deleteRecursively()
        refresh()
    }

    suspend fun rename(notebookId: String, title: String) = withContext(Dispatchers.IO) {
        val index = readIndex(notebookId) ?: return@withContext
        writeIndex(index.copy(meta = index.meta.copy(title = title, updatedAt = System.currentTimeMillis())))
        refresh()
    }

    // --- Indice --------------------------------------------------------------

    suspend fun loadIndex(notebookId: String): NotebookIndex? = withContext(Dispatchers.IO) {
        readIndex(notebookId)
    }

    suspend fun saveIndex(index: NotebookIndex) = withContext(Dispatchers.IO) {
        writeIndex(index.copy(meta = index.meta.copy(updatedAt = System.currentTimeMillis())))
        refresh()
    }

    // --- Paginas -------------------------------------------------------------

    suspend fun loadPage(notebookId: String, pageId: String): List<InkStroke> =
        withContext(Dispatchers.IO) { InkCodec.read(pageFile(notebookId, pageId)) }

    suspend fun savePage(notebookId: String, pageId: String, strokes: List<InkStroke>) =
        withContext(Dispatchers.IO) { InkCodec.write(pageFile(notebookId, pageId), strokes) }

    suspend fun addPage(
        notebookId: String,
        template: PageTemplate,
        after: Int,
    ): NotebookIndex? = withContext(Dispatchers.IO) {
        val index = readIndex(notebookId) ?: return@withContext null
        val page = PageMeta(id = UUID.randomUUID().toString(), template = template)
        val pages = index.pages.toMutableList().apply {
            add((after + 1).coerceIn(0, size), page)
        }
        val updated = index.copy(
            meta = index.meta.copy(updatedAt = System.currentTimeMillis()),
            pages = pages,
        )
        writeIndex(updated)
        updated
    }

    suspend fun deletePage(notebookId: String, pageId: String): NotebookIndex? =
        withContext(Dispatchers.IO) {
            val index = readIndex(notebookId) ?: return@withContext null
            // Un cuaderno sin paginas no tiene sentido y complica toda la UI.
            if (index.pages.size <= 1) return@withContext index
            pageFile(notebookId, pageId).delete()
            val updated = index.copy(
                meta = index.meta.copy(updatedAt = System.currentTimeMillis()),
                pages = index.pages.filterNot { it.id == pageId },
            )
            writeIndex(updated)
            updated
        }

    // --- PDF -----------------------------------------------------------------

    fun pdfFile(notebookId: String, fileName: String): File = File(dir(notebookId), fileName)

    /**
     * Crea un cuaderno cuyas paginas son las de un PDF. Las dimensiones vienen ya
     * medidas por quien importa, porque abrir el PDF requiere un descriptor de
     * archivo que se maneja mejor arriba.
     */
    suspend fun createFromPdf(
        title: String,
        pdfBytes: ByteArray,
        pageSizes: List<Pair<Float, Float>>,
        folderId: String? = null,
    ): NotebookMeta = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val fileName = "source.pdf"
        dir(id).mkdirs()
        File(dir(id), fileName).writeBytes(pdfBytes)

        val pages = pageSizes.mapIndexed { i, (w, h) ->
            PageMeta(
                id = UUID.randomUUID().toString(),
                template = PageTemplate.BLANK,
                widthPt = if (w > 0) w else PageSize.A4_WIDTH,
                heightPt = if (h > 0) h else PageSize.A4_HEIGHT,
                pdfPageIndex = i,
            )
        }.ifEmpty {
            listOf(PageMeta(id = UUID.randomUUID().toString(), template = PageTemplate.BLANK))
        }

        val meta = NotebookMeta(
            id = id,
            title = title,
            createdAt = now,
            updatedAt = now,
            pdfFileName = fileName,
            folderId = folderId,
        )
        writeIndex(NotebookIndex(meta = meta, pages = pages))
        refresh()
        meta
    }

    // --- Interno -------------------------------------------------------------

    private fun dir(notebookId: String) = File(root, notebookId)

    private fun pageFile(notebookId: String, pageId: String) =
        File(File(dir(notebookId), "pages"), "$pageId.ink")

    private fun indexFile(notebookId: String) = File(dir(notebookId), "index.json")

    private fun readIndex(notebookId: String): NotebookIndex? {
        val file = indexFile(notebookId)
        if (!file.exists()) return null
        return runCatching { json.decodeFromString<NotebookIndex>(file.readText()) }.getOrNull()
    }

    private fun writeIndex(index: NotebookIndex) {
        dir(index.meta.id).mkdirs()
        indexFile(index.meta.id).writeText(json.encodeToString(index))
    }
}
