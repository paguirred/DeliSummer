package cl.aguirre.cuaderno.ink

enum class EditorTool {
    PEN,
    FINELINER,
    MARKER,
    HIGHLIGHTER,
    ERASER,
    PAN;

    val isDrawing: Boolean get() = this != ERASER && this != PAN

    val brushKind: BrushKind?
        get() = when (this) {
            PEN -> BrushKind.PEN
            FINELINER -> BrushKind.FINELINER
            MARKER -> BrushKind.MARKER
            HIGHLIGHTER -> BrushKind.HIGHLIGHTER
            ERASER, PAN -> null
        }
}
