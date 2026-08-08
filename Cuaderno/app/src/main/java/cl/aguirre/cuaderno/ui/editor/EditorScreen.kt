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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BorderColor
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.HighlightAlt
import androidx.compose.material.icons.filled.LinearScale
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import cl.aguirre.cuaderno.R
import cl.aguirre.cuaderno.data.NotebookStore
import cl.aguirre.cuaderno.data.model.PageTemplate
import cl.aguirre.cuaderno.ink.BrushCatalog
import cl.aguirre.cuaderno.ink.EditorTool
import cl.aguirre.cuaderno.ink.PageCanvasView
import cl.aguirre.cuaderno.ui.theme.toComposeColor
import cl.aguirre.cuaderno.ui.theme.toPackedLong
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToInt

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
    DisposableEffect(notebookId) { onDispose { state.dispose() } }

    var canvasView by remember { mutableStateOf<PageCanvasView?>(null) }
    var templateMenu by remember { mutableStateOf(false) }
    var pageMenu by remember { mutableStateOf(false) }
    var showColorPicker by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    fun share(file: File?, mime: String) {
        if (file == null) return
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, context.getString(R.string.share)))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { scope.launch { state.flushSave(); onClose() } }) {
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
                            for ((template, label) in TEMPLATES) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(label)) },
                                    onClick = { state.setTemplate(template); templateMenu = false },
                                )
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

                    IconButton(onClick = { canvasView?.fitWholePage() }) {
                        Icon(Icons.Default.FitScreen, stringResource(R.string.fit_page))
                    }
                    IconButton(onClick = { canvasView?.resetZoom() }) {
                        Icon(Icons.Default.ZoomOutMap, stringResource(R.string.zoom_reset))
                    }

                    IconButton(onClick = {
                        scope.launch { share(state.exportPdf(), "application/pdf") }
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
                ToolRail(
                    state = state,
                    onOpenColor = { showColorPicker = true },
                    onOpenSettings = { showSettings = true },
                )
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f)) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { ctx ->
                                PageCanvasView(ctx).also { view ->
                                    view.onStrokeFinished = { state.addStroke(it) }
                                    view.onErase = { state.erase(it) }
                                    view.onTransformChanged = { state.zoomFactor = it }
                                    view.onLassoComplete = { state.selectFromLasso(it) }
                                    view.onSelectionTransform = { matrix ->
                                        state.transformSelection(matrix)
                                        view.clearLiveTransform()
                                    }
                                    canvasView = view
                                }
                            },
                            update = { view ->
                                state.currentPage?.let { page ->
                                    view.pageWidthPt = page.widthPt
                                    view.pageHeightPt = page.heightPt
                                    view.template = page.template
                                }
                                view.pdfBackground = state.pdfBackground
                                view.strokes = state.strokes
                                view.selection = state.selection
                                view.tool = state.tool
                                view.colorLong = state.colorLong
                                view.strokeSizePt = state.sizePt
                                view.stylusOnly = state.stylusOnly
                                view.stabilization = state.stabilization
                                view.pressureGamma = state.pressureGamma
                                view.scribbleToErase = state.scribbleToErase
                            },
                        )
                    }

                    when {
                        state.selection.isNotEmpty() -> SelectionBar(
                            state = state,
                            onScreenshot = {
                                scope.launch { share(state.exportSelectionImage(), "image/png") }
                            },
                        )
                        state.tool.isDrawing -> SizePanel(state, onOpenColor = { showColorPicker = true })
                    }
                }
            }
        }
    }

    if (showColorPicker) {
        AlertDialog(
            onDismissRequest = { showColorPicker = false },
            title = { Text(stringResource(R.string.color)) },
            text = {
                ColorPicker(
                    color = state.colorLong.toComposeColor(),
                    onColorChange = { state.setColor(it.toPackedLong()) },
                    showAlpha = state.tool == EditorTool.HIGHLIGHTER,
                )
            },
            confirmButton = { TextButton(onClick = { showColorPicker = false }) { Text("OK") } },
        )
    }

    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text(stringResource(R.string.pen_settings)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LabeledSlider(
                        label = stringResource(R.string.pressure_sensitivity),
                        hint = stringResource(R.string.pressure_sensitivity_hint),
                        value = 1f - (state.pressureGamma - 0.4f) / 2f,
                        onValueChange = { state.pressureGamma = (1f - it) * 2f + 0.4f },
                        readout = "%.2f".format(state.pressureGamma),
                    )
                    LabeledSlider(
                        label = stringResource(R.string.stabilization),
                        hint = stringResource(R.string.stabilization_hint),
                        value = state.stabilization,
                        onValueChange = { state.stabilization = it },
                        readout = "${(state.stabilization * 100).roundToInt()}%",
                    )
                    Spacer(Modifier.height(8.dp))
                    SettingSwitch(
                        checked = state.scribbleToErase,
                        onCheckedChange = { state.scribbleToErase = it },
                        title = stringResource(R.string.scribble_to_erase),
                        subtitle = stringResource(R.string.scribble_to_erase_desc),
                    )
                    SettingSwitch(
                        checked = state.stylusOnly,
                        onCheckedChange = { state.stylusOnly = it },
                        title = stringResource(R.string.stylus_only),
                        subtitle = stringResource(R.string.stylus_only_desc),
                    )
                }
            },
            confirmButton = { TextButton(onClick = { showSettings = false }) { Text("OK") } },
        )
    }
}

private val TEMPLATES = listOf(
    PageTemplate.GRID to R.string.template_grid,
    PageTemplate.RULED to R.string.template_ruled,
    PageTemplate.DOTS to R.string.template_dots,
    PageTemplate.BLANK to R.string.template_blank,
)

private val TOOLS: List<Triple<EditorTool, ImageVector, Int>> = listOf(
    Triple(EditorTool.PEN, Icons.Default.Draw, R.string.tool_pen),
    Triple(EditorTool.FINELINER, Icons.Default.Edit, R.string.tool_fineliner),
    Triple(EditorTool.MARKER, Icons.Default.Brush, R.string.tool_marker),
    // BorderColor es un marcador con una franja de color debajo. Se usaba
    // Highlight, que en Material es una ampolleta: nadie lo leia como resaltador.
    Triple(EditorTool.HIGHLIGHTER, Icons.Default.BorderColor, R.string.tool_highlighter),
    Triple(EditorTool.DASHED, Icons.Default.LinearScale, R.string.tool_dashed),
    Triple(EditorTool.ERASER, Icons.Default.CleaningServices, R.string.tool_eraser),
    Triple(EditorTool.LASSO, Icons.Default.HighlightAlt, R.string.tool_lasso),
    Triple(EditorTool.PAN, Icons.Default.PanTool, R.string.tool_pan),
)

@Composable
private fun SettingSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    title: String,
    subtitle: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        Switch(checked = checked, onCheckedChange = onCheckedChange)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    hint: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    readout: String,
) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                readout,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(value = value.coerceIn(0f, 1f), onValueChange = onValueChange)
        Text(
            hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Acciones sobre lo seleccionado con el lazo. */
@Composable
private fun SelectionBar(state: EditorState, onScreenshot: () -> Unit) {
    Surface(tonalElevation = 3.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                stringResource(R.string.selection_count, state.selection.size),
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(Modifier.width(8.dp))

            IconButton(onClick = { state.copySelection() }) {
                Icon(Icons.Default.ContentCopy, stringResource(R.string.copy))
            }
            IconButton(onClick = { state.cutSelection() }) {
                Icon(Icons.Default.ContentCut, stringResource(R.string.cut))
            }
            IconButton(onClick = { state.paste() }, enabled = state.hasClipboard) {
                Icon(Icons.Default.ContentPaste, stringResource(R.string.paste))
            }
            IconButton(onClick = { state.duplicateSelection() }) {
                Icon(Icons.Default.Add, stringResource(R.string.duplicate))
            }
            IconButton(onClick = onScreenshot) {
                Icon(Icons.Default.PhotoCamera, stringResource(R.string.screenshot))
            }
            IconButton(onClick = { state.deleteSelection() }) {
                Icon(Icons.Default.Delete, stringResource(R.string.delete))
            }

            Spacer(Modifier.weight(1f))

            Text(
                stringResource(R.string.selection_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            IconButton(onClick = { state.clearSelection() }) {
                Icon(Icons.Default.Close, stringResource(R.string.deselect))
            }
        }
    }
}

@Composable
private fun SizePanel(state: EditorState, onOpenColor: () -> Unit) {
    Surface(tonalElevation = 3.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                Modifier
                    .size(32.dp)
                    .background(state.colorLong.toComposeColor(), CircleShape)
                    .border(2.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                    .clickable { onOpenColor() },
            )

            Text(stringResource(R.string.size), style = MaterialTheme.typography.labelLarge)

            Slider(
                value = state.sizeValue.toFloat(),
                onValueChange = { state.setSize(it.roundToInt()) },
                valueRange = 1f..100f,
                modifier = Modifier.weight(1f),
            )

            Text(
                state.sizeValue.toString(),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.width(32.dp),
            )

            // Muestra del trazo al tamaño y color elegidos.
            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                val diameter = (BrushCatalog.sizeToPoints(state.sizeValue) * 0.9f).coerceIn(2f, 40f)
                Box(
                    Modifier
                        .size(diameter.dp)
                        .background(state.colorLong.toComposeColor(), CircleShape),
                )
            }
        }
    }
}

@Composable
private fun ToolRail(
    state: EditorState,
    onOpenColor: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Surface(
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxHeight().width(84.dp),
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            for ((tool, icon, label) in TOOLS) {
                ToolButton(state, tool, icon, label)
            }

            HorizontalDivider(Modifier.padding(vertical = 6.dp))

            Box(
                Modifier
                    .size(30.dp)
                    .background(state.colorLong.toComposeColor(), CircleShape)
                    .border(2.dp, MaterialTheme.colorScheme.outline, CircleShape)
                    .clickable { onOpenColor() },
            )

            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Default.Settings, stringResource(R.string.pen_settings))
            }
        }
    }
}

/**
 * Boton de herramienta con su nombre debajo.
 *
 * La etiqueta ocupa espacio, pero sin ella hay que adivinar que hace cada icono:
 * un pincel y un lapiz se parecen bastante en 24dp.
 */
@Composable
private fun ToolButton(
    state: EditorState,
    tool: EditorTool,
    icon: ImageVector,
    labelRes: Int,
) {
    val selected = state.tool == tool
    val label = stringResource(labelRes)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable { state.tool = tool }
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                RoundedCornerShape(10.dp),
            )
            .padding(vertical = 4.dp, horizontal = 2.dp)
            .width(76.dp),
    ) {
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
            Icon(icon, contentDescription = label)
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 9.sp,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}
