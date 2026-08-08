package cl.aguirre.cuaderno

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.activity.compose.BackHandler
import cl.aguirre.cuaderno.ui.editor.EditorScreen
import cl.aguirre.cuaderno.ui.library.LibraryScreen
import cl.aguirre.cuaderno.ui.theme.CuadernoTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val store = (application as CuadernoApp).store

        setContent {
            CuadernoTheme {
                // Dos pantallas y una transicion. Una libreria de navegacion aqui
                // solo agregaria indireccion.
                var openNotebookId by rememberSaveable { mutableStateOf<String?>(null) }

                val id = openNotebookId
                if (id == null) {
                    LibraryScreen(
                        store = store,
                        onOpen = { openNotebookId = it },
                    )
                } else {
                    BackHandler { openNotebookId = null }
                    EditorScreen(
                        store = store,
                        notebookId = id,
                        onClose = { openNotebookId = null },
                    )
                }
            }
        }
    }
}
