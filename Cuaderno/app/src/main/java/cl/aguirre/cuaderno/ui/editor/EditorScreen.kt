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
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Highlight
import androidx.compose.material.icons.filled.PanTool
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
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
                                view.tool = state.tool
                                view.colorLong = state.colorLong
                                view.strokeSizePt = state.sizePt
                                view.stylusOnly = state.stylusOnly
                                view.stabilization = state.stabilization
                                view.pressureGamma = state.pressureGamma
                            },
                        )
                    }
                    if (state.tool.isDrawing) {
                        SizePanel(state, onOpenColor = { showColorPicker = true })
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
            confirmButton = {
                TextButton(onClick = { showColorPicker = false }) { Text("OK") }
            },
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
                        // Se invierte: mover a la derecha debe sentirse como
                        // "mas sensible", y eso es una gamma mas baja.
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = state.stylusOnly,
                            onCheckedChange = { state.stylusOnly = it },
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                stringResource(R.string.stylus_only),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                stringResource(R.string.stylus_only_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSettings = false }) { Text("OK") }
            },
        )
    }
}

private val TEMPLATES = listOf(
    PageTemplate.GRID to R.string.template_grid,
    PageTemplate.RULED to R.string.template_ruled,
    PageTemplate.DOTS to R.string.template_dots,
    PageTemplate.BLANK to R.string.template_blank,
)

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

/**
 * Panel de grosor y color de la herramienta activa.
 *
 * Vive abajo y no en la barra lateral porque la barra tiene que caber en
 * pantalla entera sin scroll para que las herramientas esten siempre a la vista;
 * meter tambien colores y grosores ahi era lo que dejaba el resaltador fuera de
 * cuadro en la primera version.
 */
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

            Text(
                stringResource(R.string.size),
                style = MaterialTheme.typography.labelLarge,
            )

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

            // Muestra del trazo al tamaño y color elegidos, para no tener que
            // probar en la hoja cada vez que se mueve el control.
            Box(
                Modifier.size(44.dp),
                contentAlignment = Alignment.Center,
            ) {
                val diameter = (BrushCatalog.sizeToPoints(state.sizeValue) * 0.9f)
                    .coerceIn(2f, 40f)
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
        modifier = Modifier.fillMaxHeight().width(68.dp),
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ToolButton(state, EditorTool.PEN, Icons.Default.Edit, R.string.tool_pen)
            ToolButton(state, EditorTool.FINELINER, Icons.Default.Create, R.string.tool_fineliner)
            ToolButton(state, EditorTool.MARKER, Icons.Default.Brush, R.string.tool_marker)
            ToolButton(state, EditorTool.HIGHLIGHTER, Icons.Default.Highlight, R.string.tool_highlighter)
            ToolButton(state, EditorTool.ERASER, Icons.Default.Clear, R.string.tool_eraser)
            ToolButton(state, EditorTool.PAN, Icons.Default.PanTool, R.string.tool_pan)

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
