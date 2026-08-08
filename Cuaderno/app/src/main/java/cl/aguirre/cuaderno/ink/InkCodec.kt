package cl.aguirre.cuaderno.ink

import androidx.ink.brush.Brush
import androidx.ink.brush.BrushFamily
import androidx.ink.storage.decode
import androidx.ink.storage.encode
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInputBatch
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException

/**
 * Lectura y escritura de la tinta de una pagina.
 *
 * Formato propio, binario y secuencial. La alternativa era JSON con los puntos en
 * base64, pero una pagina densa de apuntes son decenas de miles de puntos y el
 * costo de parsear texto se nota al pasar de pagina. Aca cada trazo son cuatro
 * campos fijos, la polilinea de hit-testing y el batch de entrada ya codificado
 * por el propio motor de tinta, que es la representacion mas compacta disponible.
 *
 * Este archivo concentra el contacto con `androidx.ink.storage`, que esta en alpha.
 * Si esa API cambia, el arreglo queda local a este archivo.
 */
object InkCodec {

    private const val MAGIC = 0x43554144 // "CUAD"
    private const val VERSION = 1

    private val familyToKind: Map<BrushFamily, BrushKind> by lazy {
        BrushKind.entries.associateBy { it.family }
    }

    fun write(file: File, strokes: List<InkStroke>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        DataOutputStream(BufferedOutputStream(tmp.outputStream())).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(VERSION)
            out.writeInt(strokes.size)
            for (inkStroke in strokes) {
                val brush = inkStroke.stroke.brush
                val kind = familyToKind[brush.family] ?: BrushKind.PEN
                out.writeUTF(kind.id)
                out.writeLong(brush.colorLong)
                out.writeFloat(brush.size)
                out.writeFloat(brush.epsilon)

                out.writeInt(inkStroke.hitPath.size)
                for (v in inkStroke.hitPath) out.writeFloat(v)

                val encoded = ByteArrayOutputStream().use { buffer ->
                    inkStroke.stroke.inputs.encode(buffer)
                    buffer.toByteArray()
                }
                out.writeInt(encoded.size)
                out.write(encoded)
            }
            out.flush()
        }
        // Escritura atomica: si la app muere a mitad de guardado, la pagina
        // anterior sigue intacta en vez de quedar truncada.
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }

    fun read(file: File): List<InkStroke> {
        if (!file.exists()) return emptyList()
        return try {
            DataInputStream(BufferedInputStream(file.inputStream())).use { input ->
                require(input.readInt() == MAGIC) { "archivo de tinta no reconocido" }
                val version = input.readInt()
                require(version <= VERSION) { "tinta escrita por una version mas nueva" }

                val count = input.readInt()
                buildList(count) {
                    repeat(count) {
                        val kind = BrushKind.fromId(input.readUTF())
                        val colorLong = input.readLong()
                        val size = input.readFloat()
                        val epsilon = input.readFloat()

                        val hitPath = FloatArray(input.readInt())
                        for (i in hitPath.indices) hitPath[i] = input.readFloat()

                        val encoded = ByteArray(input.readInt())
                        input.readFully(encoded)
                        val inputs = ByteArrayInputStream(encoded).use { bytes ->
                            StrokeInputBatch.decode(bytes)
                        }

                        val brush = Brush.createWithColorLong(
                            family = kind.family,
                            colorLong = colorLong,
                            size = size,
                            epsilon = epsilon,
                        )
                        add(InkStroke(Stroke(brush = brush, inputs = inputs), hitPath))
                    }
                }
            }
        } catch (e: IOException) {
            // Una pagina corrupta no debe tumbar la app ni el cuaderno completo.
            emptyList()
        } catch (e: IllegalArgumentException) {
            emptyList()
        }
    }
}
