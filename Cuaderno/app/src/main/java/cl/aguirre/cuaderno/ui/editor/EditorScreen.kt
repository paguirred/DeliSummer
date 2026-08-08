package cl.aguirre.cuaderno.ui.editor

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Highlight
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import cl.aguirre.cuaderno.R
import cl.aguirre.cuaderno.data.NotebookStore
import cl.aguirre.cuaderno.data.model.PageTemplate
import cl.aguirre.cuaderno.ink.EditorTool
import cl.aguirre.cuaderno.ink.BrushCatalog
import cl.aguirre.cuaderno.ink.BrushKind
import cl.aguirre.cuaderno.ink.PageCanvasView
import cl.aguirre.cuaderno.ui.theme.InkPalette
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    store: NotebookStore,
    notebookId: String,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = remember(notebookId) { EditorState(store, notebookId, scope, context) }

    LaunchedEffect(notebookId) { state.load() }

    DisposableEffect(notebookId) {
        onDispose {
            // El scope de Compose ya esta muriendo aqui, asi que el guardado
            // final se lanza contra el scope del store, no contra este.
            state.dispose()
        }
    }

    var canvasView by remember { mutableStateOf<PageCanvasView?>(null) }
    var templateMenu by remember { mutableStateOf(false) }
    var pageMenu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = {
                        scope.launch { state.flushSave(); onClose() }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                title = {
                    Text(
                        state.index?.meta?.title ?: "",
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                actions = {
                    IconButton(onClick = { state.undo() }, enabled = state.canUndo) {
                        Icon(Icons.AutoMirrored.Filled.Undo, stringResource(R.string.undo))
                    }
                    IconButton(onClick = { state.redo() }, enabled = state.canRedo) {
                        Icon(Icons.AutoMirrored.Filled.Redo, stringResource(R.string.redo))
                    }

                    Spacer(Modifier.width(8.dp))

                    IconButton(
                        onClick = { state.openPage(state.pageIndex - 1) },
                        enabled = state.pageIndex > 0,
                    ) { Icon(Icons.Default.ChevronLeft, null) }

                    Text(
                        stringResource(
                            R.string.page_of,
                            state.pageIndex + 1,
                            state.pageCount.coerceAtLeast(1),
                        ),
                        style = MaterialTheme.typography.labelLarge,
                    )

                    IconButton(
                        onClick = { state.openPage(state.pageIndex + 1) },
                        enabled = state.pageIndex < state.pageCount - 1,
                    ) { Icon(Icons.Default.ChevronRight, null) }

                    Spacer(Modifier.width(8.dp))

                    Box {
                        IconButton(onClick = { templateMenu = true }) {
                            Icon(Icons.Default.GridOn, stringResource(R.string.template))
                        }
                        DropdownMenu(templateMenu, { templateMenu = false }) {
                            TemplateItem(PageTemplate.GRID, R.string.template_grid) {
                                state.setTemplate(it); templateMenu = false
                            }
                            TemplateItem(PageTemplate.RULED, R.string.template_ruled) {
                                state.setTemplate(it); templateMenu = false
                            }
                            TemplateItem(PageTemplate.DOTS, R.string.template_dots) {
                                state.setTemplate(it); templateMenu = false
                            }
                            TemplateItem(PageTemplate.BLANK, R.string.template_blank) {
                                state.setTemplate(it); templateMenu = false
                            }
                        }
                    }

                    Box {
                        IconButton(onClick = { pageMenu = true }) {
                            Icon(Icons.Default.Add, stringResource(R.string.add_page))
                        }
                        DropdownMenu(pageMenu, { pageMenu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.add_page)) },
                                onClick = {
                                    state.addPage(state.currentPage?.template ?: PageTemplate.GRID)
                                    pageMenu = false
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.delete_page)) },
                                onClick = { state.deleteCurrentPage(); pageMenu = false },
                            )
                        }
                    }

                    IconButton(onClick = { canvasView?.resetZoom() }) {
                        Icon(Icons.Default.ZoomOutMap, stringResource(R.string.zoom_reset))
                    }

                    IconButton(onClick = {
                        scope.launch {
                            val file = state.exportPdf() ?: return@launch
                            val uri = FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.fileprovider",
                                file,
                            )
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "application/pdf"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(
                                Intent.createChooser(send, context.getString(R.string.share)),
                            )
                        }
                    }) { Icon(Icons.Default.Share, stringResource(R.string.export_pdf)) }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
            }
            Row(Modifier.fillMaxSize()) {
                ToolRail(state)
                Box(Modifier.fillMaxSize()) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            PageCanvasView(ctx).also { view ->
                                view.onStrokeFinished = { state.addStroke(it) }
                                view.onErase = { state.erase(it) }
                                view.onTransformChanged = { state.zoomFactor = it }
                                canvasView = view
                            }
                        },
                        update = { view ->
                            val page = state.currentPage
                            if (page != null) {
                                view.pageWidthPt = page.widthPt
                                view.pageHeightPt = page.heightPt
                                view.template = page.template
                            }
                            view.pdfBackground = state.pdfBackground
                            view.strokes = state.strokes
                            view.tool = state.tool
                            view.colorLong = state.colorLong
                            view.strokeSizePt = state.sizePt
                            view.stylusOnly = state.stylusOnly
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun TemplateItem(
    template: PageTemplate,
    labelRes: Int,
    onPick: (PageTemplate) -> Unit,
) {
    DropdownMenuItem(
        text = { Text(stringResource(labelRes)) },
        onClick = { onPick(template) },
    )
}

@Composable
private fun ToolRail(state: EditorState) {
    Surface(
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxHeight().width(72.dp),
    ) {
        Column(
            modifier = Modifier.padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ToolButton(state, EditorTool.PEN, Icons.Default.Edit, R.string.tool_pen)
            ToolButton(state, EditorTool.PENCIL, Icons.Default.Create, R.string.tool_pencil)
            ToolButton(state, EditorTool.MARKER, Icons.Default.Brush, R.string.tool_marker)
            ToolButton(state, EditorTool.HIGHLIGHTER, Icons.Default.Highlight, R.string.tool_highlighter)
            ToolButton(state, EditorTool.ERASER, Icons.Default.Clear, R.string.tool_eraser)
            ToolButton(state, EditorTool.PAN, Icons.Default.PanTool, R.string.tool_pan)

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            if (state.tool.isDrawing) {
                val palette = if (state.tool == EditorTool.HIGHLIGHTER) {
                    InkPalette.highlighterColors
                } else {
                    InkPalette.colors
                }
                for (color in palette) {
                    val packed = Color.pack(color.toArgb())
                    Box(
                        Modifier
                            .size(28.dp)
                            .background(color, CircleShape)
                            .border(
                                width = if (state.colorLong == packed) 3.dp else 1.dp,
                                color = if (state.colorLong == packed) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.outlineVariant
                                },
                                shape = CircleShape,
                            )
                            .clickable { state.setColor(packed) },
                    )
                }

                HorizontalDivider(Modifier.padding(vertical = 8.dp))

                val kind = state.tool.brushKind ?: BrushKind.PEN
                for (size in BrushCatalog.sizesFor(kind)) {
                    val selected = state.sizePt == size
                    Box(
                        Modifier
                            .size(28.dp)
                            .background(
                                if (selected) MaterialTheme.colorScheme.secondaryContainer
                                else Color.Transparent,
                                RoundedCornerShape(8.dp),
                            )
                            .clickable { state.setSize(size) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier
                                .size((size.coerceIn(1.5f, 18f)).dp)
                                .background(MaterialTheme.colorScheme.onSurface, CircleShape),
                        )
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            // Solo-lapiz es el interruptor mas importante de la app: con el
            // activo, apoyar la mano en la pantalla no deja marcas.
            Switch(
                checked = state.stylusOnly,
                onCheckedChange = { state.stylusOnly = it },
            )
            Text(
                stringResource(R.string.stylus_only),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

@Composable
private fun ToolButton(
    state: EditorState,
    tool: EditorTool,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    labelRes: Int,
) {
    val selected = state.tool == tool
    FilledIconButton(
        onClick = { state.tool = tool },
        colors = if (selected) {
            IconButtonDefaults.filledIconButtonColors()
        } else {
            IconButtonDefaults.filledIconButtonColors(
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    ) {
        Icon(icon, contentDescription = stringResource(labelRes))
    }
}
