package cl.aguirre.cuaderno.ink

enum class EditorTool {
    PEN,
    FINELINER,
    MARKER,
    HIGHLIGHTER,
    DASHED,
    ERASER,
    LASSO,
    PAN;

    val isDrawing: Boolean get() = brushKind != null

    val brushKind: BrushKind?
        get() = when (this) {
            PEN -> BrushKind.PEN
            FINELINER -> BrushKind.FINELINER
            MARKER -> BrushKind.MARKER
            HIGHLIGHTER -> BrushKind.HIGHLIGHTER
            DASHED -> BrushKind.DASHED
            ERASER, LASSO, PAN -> null
        }
}
