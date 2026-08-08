package cl.aguirre.cuaderno.ui.library

import android.net.Uri
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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

    var renaming by remember { mutableStateOf<NotebookMeta?>(null) }
    var deleting by remember { mutableStateOf<NotebookMeta?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { store.refresh() }

    val importPdf = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val imported = importPdfNotebook(context.contentResolver, store, uri, context.cacheDir)
            if (imported != null) onOpen(imported.id)
            else error = context.getString(R.string.import_failed)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.library_title)) })
        },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
        if (notebooks.isEmpty()) {
            EmptyState(Modifier.fillMaxSize().padding(padding))
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 180.dp),
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(notebooks, key = { it.id }) { meta ->
                    NotebookCard(
                        meta = meta,
                        onOpen = { onOpen(meta.id) },
                        onRename = { renaming = meta },
                        onDelete = { deleting = meta },
                    )
                }
            }
        }
    }

    renaming?.let { meta ->
        var text by remember(meta.id) { mutableStateOf(meta.title) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(stringResource(R.string.rename)) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { store.rename(meta.id, text.trim().ifEmpty { meta.title }) }
                    renaming = null
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = {
                TextButton(onClick = { renaming = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    deleting?.let { meta ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.confirm_delete_title)) },
            text = { Text(stringResource(R.string.confirm_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { store.delete(meta.id) }
                    deleting = null
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) }
            },
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
private fun EmptyState(modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(32.dp),
        ) {
            Text(
                stringResource(R.string.library_empty_title),
                style = MaterialTheme.typography.titleMedium,
            )
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
private fun NotebookCard(
    meta: NotebookMeta,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Card(
        onClick = onOpen,
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.75f),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (meta.pdfFileName != null) Icons.Default.PictureAsPdf else Icons.Default.Add,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
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
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
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
): NotebookMeta? = withContext(Dispatchers.IO) {
    runCatching {
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return@runCatching null

        val temp = File(cacheDir, "import-${System.currentTimeMillis()}.pdf")
        temp.writeBytes(bytes)
        val sizes = PdfPageSource.open(temp)?.use { it.pageSizes() } ?: emptyList()
        temp.delete()
        if (sizes.isEmpty()) return@runCatching null

        val name = queryDisplayName(resolver, uri) ?: "PDF"
        store.createFromPdf(name.removeSuffix(".pdf"), bytes, sizes)
    }.getOrNull()
}

private fun queryDisplayName(resolver: android.content.ContentResolver, uri: Uri): String? =
    runCatching {
        resolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull()
