package cl.aguirre.cuaderno.ui.library

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cl.aguirre.cuaderno.R
import cl.aguirre.cuaderno.data.NotebookStore
import cl.aguirre.cuaderno.data.model.Folder as FolderModel
import cl.aguirre.cuaderno.data.model.NotebookMeta
import cl.aguirre.cuaderno.data.model.PageTemplate
import cl.aguirre.cuaderno.pdf.PdfPageSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    store: NotebookStore,
    onOpen: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val notebooks by store.notebooks.collectAsState()
    val folders by store.folders.collectAsState()

    // null = raiz. Las carpetas no anidan: para organizar ramos, un nivel basta
    // y evita que alguien pierda un cuaderno tres niveles adentro.
    var openFolder by remember { mutableStateOf<FolderModel?>(null) }

    var renaming by remember { mutableStateOf<NotebookMeta?>(null) }
    var deleting by remember { mutableStateOf<NotebookMeta?>(null) }
    var movingNotebook by remember { mutableStateOf<NotebookMeta?>(null) }
    var creatingFolder by remember { mutableStateOf(false) }
    var renamingFolder by remember { mutableStateOf<FolderModel?>(null) }
    var deletingFolder by remember { mutableStateOf<FolderModel?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { store.refresh() }
    BackHandler(enabled = openFolder != null) { openFolder = null }

    val currentFolderId = openFolder?.id
    val visibleNotebooks = notebooks.filter { it.folderId == currentFolderId }
    val visibleFolders = if (openFolder == null) folders else emptyList()

    val importPdf = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val imported = importPdfNotebook(
                context.contentResolver, store, uri, context.cacheDir, currentFolderId,
            )
            if (imported != null) onOpen(imported.id) else error = context.getString(R.string.import_failed)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    if (openFolder != null) {
                        IconButton(onClick = { openFolder = null }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                        }
                    }
                },
                title = { Text(openFolder?.name ?: stringResource(R.string.library_title)) },
            )
        },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (openFolder == null) {
                    SmallFloatingActionButton(onClick = { creatingFolder = true }) {
                        Icon(Icons.Default.CreateNewFolder, stringResource(R.string.new_folder))
                    }
                }
                ExtendedFloatingActionButton(
                    onClick = { importPdf.launch(arrayOf("application/pdf")) },
                    icon = { Icon(Icons.Default.PictureAsPdf, null) },
                    text = { Text(stringResource(R.string.import_pdf)) },
                )
                ExtendedFloatingActionButton(
                    onClick = {
                        scope.launch {
                            val meta = store.create(
                                context.getString(R.string.untitled_notebook),
                                PageTemplate.GRID,
                                currentFolderId,
                            )
                            onOpen(meta.id)
                        }
                    },
                    icon = { Icon(Icons.Default.Add, null) },
                    text = { Text(stringResource(R.string.new_notebook)) },
                )
            }
        },
    ) { padding ->
        if (visibleNotebooks.isEmpty() && visibleFolders.isEmpty()) {
            EmptyState(Modifier.fillMaxSize().padding(padding))
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 180.dp),
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(visibleFolders, key = { "f-${it.id}" }) { folder ->
                    FolderCard(
                        folder = folder,
                        count = notebooks.count { it.folderId == folder.id },
                        onOpen = { openFolder = folder },
                        onRename = { renamingFolder = folder },
                        onDelete = { deletingFolder = folder },
                    )
                }

                if (visibleFolders.isNotEmpty() && visibleNotebooks.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    }
                }

                items(visibleNotebooks, key = { it.id }) { meta ->
                    NotebookCard(
                        meta = meta,
                        onOpen = { onOpen(meta.id) },
                        onRename = { renaming = meta },
                        onDelete = { deleting = meta },
                        onMove = { movingNotebook = meta },
                    )
                }
            }
        }
    }

    renaming?.let { meta ->
        TextPrompt(
            title = stringResource(R.string.rename),
            initial = meta.title,
            onConfirm = { scope.launch { store.rename(meta.id, it) }; renaming = null },
            onDismiss = { renaming = null },
        )
    }

    renamingFolder?.let { folder ->
        TextPrompt(
            title = stringResource(R.string.rename),
            initial = folder.name,
            onConfirm = { scope.launch { store.renameFolder(folder.id, it) }; renamingFolder = null },
            onDismiss = { renamingFolder = null },
        )
    }

    if (creatingFolder) {
        TextPrompt(
            title = stringResource(R.string.new_folder),
            initial = "",
            onConfirm = { scope.launch { store.createFolder(it) }; creatingFolder = false },
            onDismiss = { creatingFolder = false },
        )
    }

    movingNotebook?.let { meta ->
        AlertDialog(
            onDismissRequest = { movingNotebook = null },
            title = { Text(stringResource(R.string.move_to_folder)) },
            text = {
                Column {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.no_folder)) },
                        onClick = {
                            scope.launch { store.moveToFolder(meta.id, null) }
                            movingNotebook = null
                        },
                    )
                    for (folder in folders) {
                        DropdownMenuItem(
                            text = { Text(folder.name) },
                            leadingIcon = { Icon(Icons.Default.Folder, null) },
                            onClick = {
                                scope.launch { store.moveToFolder(meta.id, folder.id) }
                                movingNotebook = null
                            },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { movingNotebook = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    deleting?.let { meta ->
        ConfirmDialog(
            title = stringResource(R.string.confirm_delete_title),
            body = stringResource(R.string.confirm_delete_body),
            onConfirm = { scope.launch { store.delete(meta.id) }; deleting = null },
            onDismiss = { deleting = null },
        )
    }

    deletingFolder?.let { folder ->
        ConfirmDialog(
            title = stringResource(R.string.confirm_delete_folder_title),
            body = stringResource(R.string.confirm_delete_folder_body),
            onConfirm = { scope.launch { store.deleteFolder(folder.id) }; deletingFolder = null },
            onDismiss = { deletingFolder = null },
        )
    }

    error?.let { message ->
        AlertDialog(
            onDismissRequest = { error = null },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { error = null }) { Text("OK") } },
        )
    }
}

@Composable
private fun TextPrompt(
    title: String,
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember(title, initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true)
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text.trim().ifEmpty { initial.ifEmpty { "Sin título" } }) },
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun ConfirmDialog(
    title: String,
    body: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.delete)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(32.dp),
        ) {
            Text(stringResource(R.string.library_empty_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.library_empty_body),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FolderCard(
    folder: FolderModel,
    count: Int,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Card(onClick = onOpen, elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(32.dp),
            )
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(
                    folder.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "$count",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = null)
                }
                DropdownMenu(menuOpen, { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.rename)) },
                        onClick = { menuOpen = false; onRename() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.delete)) },
                        onClick = { menuOpen = false; onDelete() },
                    )
                }
            }
        }
    }
}

@Composable
private fun NotebookCard(
    meta: NotebookMeta,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onMove: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Card(onClick = onOpen, elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
        Column {
            Box(
                Modifier.fillMaxWidth().aspectRatio(0.75f),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (meta.pdfFileName != null) Icons.Default.PictureAsPdf else Icons.Default.MenuBook,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(48.dp),
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        meta.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(meta.updatedAt)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = null)
                    }
                    DropdownMenu(menuOpen, { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.rename)) },
                            onClick = { menuOpen = false; onRename() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.move_to_folder)) },
                            onClick = { menuOpen = false; onMove() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.delete)) },
                            onClick = { menuOpen = false; onDelete() },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Copia el PDF elegido dentro de la app y crea un cuaderno con una pagina por
 * pagina del PDF.
 *
 * Se copia en vez de referenciar el Uri original a proposito: los permisos sobre
 * un Uri del selector no sobreviven de forma confiable a un reinicio, y un
 * cuaderno que un dia deja de abrir seria peor que gastar el espacio.
 */
private suspend fun importPdfNotebook(
    resolver: android.content.ContentResolver,
    store: NotebookStore,
    uri: Uri,
    cacheDir: File,
    folderId: String?,
): NotebookMeta? = withContext(Dispatchers.IO) {
    runCatching {
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return@runCatching null

        val temp = File(cacheDir, "import-${System.currentTimeMillis()}.pdf")
        temp.writeBytes(bytes)
        val sizes = PdfPageSource.open(temp)?.use { it.pageSizes() } ?: emptyList()
        temp.delete()
        if (sizes.isEmpty()) return@runCatching null

        val name = queryDisplayName(resolver, uri) ?: "PDF"
        store.createFromPdf(name.removeSuffix(".pdf"), bytes, sizes, folderId)
    }.getOrNull()
}

private fun queryDisplayName(resolver: android.content.ContentResolver, uri: Uri): String? =
    runCatching {
        resolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull()
