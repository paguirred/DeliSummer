package cl.aguirre.cuaderno.ink

enum class EditorTool {
    PEN,
    PENCIL,
    MARKER,
    HIGHLIGHTER,
    ERASER,
    PAN;

    val isDrawing: Boolean get() = this != ERASER && this != PAN

    val brushKind: BrushKind?
        get() = when (this) {
            PEN -> BrushKind.PEN
            PENCIL -> BrushKind.PENCIL
            MARKER -> BrushKind.MARKER
            HIGHLIGHTER -> BrushKind.HIGHLIGHTER
            ERASER, PAN -> null
        }
}
