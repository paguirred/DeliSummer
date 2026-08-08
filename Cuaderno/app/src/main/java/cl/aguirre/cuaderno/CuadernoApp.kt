package cl.aguirre.cuaderno

import android.app.Application
import cl.aguirre.cuaderno.data.NotebookStore

class CuadernoApp : Application() {
    /**
     * El almacen es unico para toda la app y no tiene estado que valga la pena
     * inyectar. Para una app de este tamaño, un contenedor de dependencias seria
     * mas ceremonia que beneficio.
     */
    val store: NotebookStore by lazy { NotebookStore(this) }
}
