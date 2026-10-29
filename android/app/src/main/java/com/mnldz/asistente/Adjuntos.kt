package com.mnldz.asistente

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Base64
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

// Una foto para mandar a Claude: la miniatura para la interfaz y el JPEG en base64
class Adjunto(val miniatura: ImageBitmap, val datos: String)

// Fotos de la galería, de la cámara o compartidas desde otra app. Se reducen a 1568 px de lado
// (lo más que aprovecha Claude) y van como JPEG.
class Adjuntos(private val act: ActividadAsistente) {
    val lista = androidx.compose.runtime.mutableStateListOf<Adjunto>()
    var aviso by mutableStateOf<String?>(null)

    private val galeria = act.registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(4)) { it.forEach(::anadir) }
    private var foto: Uri? = null
    private val camara = act.registerForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) foto?.let(::anadir) }

    fun elegir() = galeria.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))

    fun hacerFoto() {
        val f = File(act.cacheDir, "fotos").apply { mkdirs() }.let { File(it, "foto-${System.currentTimeMillis()}.jpg") }
        val uri = FileProvider.getUriForFile(act, "${act.packageName}.archivos", f)
        foto = uri
        camara.launch(uri)
    }

    fun anadir(uri: Uri) {
        act.lifecycleScope.launch {
            val a = withContext(Dispatchers.IO) { try { leer(uri) } catch (e: Exception) { null } }
            if (a == null) aviso = "No se ha podido abrir la imagen." else { aviso = null; lista += a }
        }
    }

    private fun leer(uri: Uri): Adjunto {
        val fuente = ImageDecoder.createSource(act.contentResolver, uri)
        val bmp = ImageDecoder.decodeBitmap(fuente) { d, info, _ ->
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val (an, al) = info.size.width to info.size.height
            val escala = minOf(1f, MAX / maxOf(an, al).toFloat())
            if (escala < 1f) d.setTargetSize((an * escala).toInt().coerceAtLeast(1), (al * escala).toInt().coerceAtLeast(1))
        }
        val salida = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 80, salida)
        val lado = 160f / maxOf(bmp.width, bmp.height)
        val mini = Bitmap.createScaledBitmap(bmp, (bmp.width * lado).toInt().coerceAtLeast(1), (bmp.height * lado).toInt().coerceAtLeast(1), true)
        return Adjunto(mini.asImageBitmap(), Base64.encodeToString(salida.toByteArray(), Base64.NO_WRAP))
    }

    // Se lo lleva lo que se manda
    fun sacar(): List<String> = lista.map { it.datos }.also { lista.clear() }

    private companion object { const val MAX = 1568 }
}

@Composable
fun BotonAdjuntar(adjuntos: Adjuntos, tam: Dp = 40.dp) {
    val c = LocalColores.current
    var menu by remember { mutableStateOf(false) }
    Box {
        IconButton({ menu = true }, Modifier.size(tam)) { Icon(Icons.Filled.AddPhotoAlternate, "Adjuntar foto", tint = c.texto2, modifier = Modifier.size(22.dp)) }
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem({ Text("Galería") }, { menu = false; adjuntos.elegir() })
            DropdownMenuItem({ Text("Cámara") }, { menu = false; adjuntos.hacerFoto() })
        }
    }
}

@Composable
fun FilaAdjuntos(adjuntos: Adjuntos, modifier: Modifier = Modifier) {
    if (adjuntos.lista.isEmpty()) return
    val c = LocalColores.current
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        adjuntos.lista.forEach { a ->
            Box {
                Image(a.miniatura, null, Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop)
                Box(
                    Modifier.align(Alignment.TopEnd).padding(3.dp).size(20.dp).background(c.fondo.copy(alpha = .8f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    IconButton({ adjuntos.lista.remove(a) }, Modifier.size(20.dp)) { Icon(Icons.Filled.Close, "Quitar", tint = c.texto, modifier = Modifier.size(14.dp)) }
                }
            }
        }
    }
}
