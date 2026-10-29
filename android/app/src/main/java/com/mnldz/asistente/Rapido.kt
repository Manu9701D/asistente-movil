package com.mnldz.asistente

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow

// ---------- Voz en la interfaz ----------

// Lo que se pide por voz se manda solo; al terminar el turno, la respuesta se lee en voz alta
@Stable
class Voz(
    private val act: ActividadAsistente,
    private val _escuchando: State<Boolean>, private val _parcial: State<String>,
    private val _nivel: State<Float>, private val _hablando: State<Boolean>,
) {
    val escuchando get() = _escuchando.value
    val parcial get() = _parcial.value
    val nivel get() = _nivel.value
    val hablando get() = _hablando.value
    var aviso by mutableStateOf<String?>(null)
    // Índice del mensaje desde el que llega la respuesta que hay que leer
    var esperando by mutableStateOf<Int?>(null)
    // Cuántos mensajes había al mandar lo último (el panel rápido enseña desde ahí)
    var ultimoEnvio by mutableStateOf<Int?>(null)
    internal var conv = Conversacion()
    // Delante de lo siguiente que se mande (la respuesta a un mensaje anunciado en el coche)
    var prefijo: String? = null

    fun escuchar() {
        aviso = null
        // Si estaba leyendo, se calla y se omite el resto; espera a que deje de sonar
        // para que el micrófono no oiga la cola de la respuesta por los altavoces
        val leia = act.lector.hablando.value
        callar()
        val empezar = { if (!act.isDestroyed) act.dictado.escuchar({ mandar(it, porVoz = true) }, { aviso = it }) }
        if (leia) Handler(Looper.getMainLooper()).postDelayed(empezar, 400) else empezar()
    }

    fun mandar(texto: String, porVoz: Boolean = false) {
        val p = prefijo
        prefijo = null
        // «No», «déjalo»…: no se contesta al mensaje y se cierra
        if (p != null && NEGATIVA.matches(texto.trim().lowercase())) {
            act.lector.callar()
            if (act is AsistenteRapido) act.finish()
            return
        }
        // En el coche se lee siempre
        esperando = if ((porVoz && act.ajustes.leerRespuestas) || act.app.enCoche.value) conv.mensajes.size else null
        ultimoEnvio = conv.mensajes.size
        act.mandar(if (p != null) "$p $texto" else texto)
    }

    fun parar() { act.dictado.parar() }

    fun callar() {
        esperando = null
        act.lector.callar()
    }

    fun pulsar() {
        when {
            escuchando -> parar()
            // En el coche, pulsar mientras lee corta la respuesta y escucha
            hablando && !act.app.enCoche.value -> callar()
            else -> escuchar()
        }
    }
}

@Composable
fun rememberVoz(act: ActividadAsistente, conv: Conversacion): Voz {
    val escuchando = act.dictado.escuchando.collectAsStateWithLifecycle()
    val parcial = act.dictado.parcial.collectAsStateWithLifecycle()
    val nivel = act.dictado.nivel.collectAsStateWithLifecycle()
    val hablando = act.lector.hablando.collectAsStateWithLifecycle()
    val voz = remember(act) { Voz(act, escuchando, parcial, nivel, hablando) }
    SideEffect { voz.conv = conv }
    LaunchedEffect(conv, voz.esperando) {
        val desde = voz.esperando ?: return@LaunchedEffect
        if (turnoTerminado(conv, desde)) {
            voz.esperando = null
            val id = conv.mensajes.lastOrNull()?.id
            if (id != act.app.leido) respuestaDesde(conv, desde)?.let { r ->
                act.app.leido = id
                // En el coche, si Claude pregunta algo, vuelve a escuchar para contestarle hablando
                act.lector.leer(r, if (act.app.enCoche.value && pregunta(r)) ({ if (!act.isDestroyed) voz.escuchar() }) else null)
            }
        }
    }
    return voz
}

private val NEGATIVA = Regex("^(no|nada|d[eé]jalo|no,? gracias|ahora no|paso|no contestes|no hace falta)[.!]*$")

fun pregunta(texto: String) = paraLeer(texto).trimEnd().endsWith("?")

// Claude ha acabado de responder a lo que se mandó cuando había `desde` mensajes
fun turnoTerminado(conv: Conversacion, desde: Int) =
    !conv.ocupado && conv.cola.isEmpty() && conv.avisos.isEmpty() &&
        conv.mensajes.drop(desde).any { it.rol != "user" } && conv.mensajes.lastOrNull()?.terminado != false

// La respuesta final: los textos tras la última herramienta
fun respuestaDesde(conv: Conversacion, desde: Int): String? {
    val bloques = conv.mensajes.drop(desde).filter { it.rol != "user" }.flatMap { it.bloques }
    val ultima = bloques.indexOfLast { it.tipo != "text" && it.tipo != "thinking" }
    return bloques.drop(ultima + 1).filter { it.tipo == "text" }.joinToString("\n\n") { it.texto }.ifBlank { null }
}

@Composable
fun BotonMicro(voz: Voz, tam: Dp = 40.dp) {
    val c = LocalColores.current
    val escala by animateFloatAsState(1f + voz.nivel * .45f, label = "nivel")
    Box(contentAlignment = Alignment.Center) {
        if (voz.escuchando) Box(
            Modifier.size(tam).graphicsLayer { scaleX = escala; scaleY = escala }.background(c.acento.copy(alpha = .3f), CircleShape),
        )
        IconButton(voz::pulsar, Modifier.size(tam).background(if (voz.escuchando) c.acento else c.elevado, CircleShape)) {
            when {
                voz.escuchando -> Icon(Icons.Filled.Mic, "Dejar de escuchar", tint = Color.White, modifier = Modifier.size(22.dp))
                voz.hablando -> Icon(Icons.AutoMirrored.Filled.VolumeOff, "Callar", tint = c.texto, modifier = Modifier.size(20.dp))
                else -> Icon(Icons.Filled.Mic, "Hablar", tint = c.texto, modifier = Modifier.size(22.dp))
            }
        }
    }
}

// ---------- Asistente rápido ----------

const val ACCION_HABLAR = "com.mnldz.asistente.HABLAR"
const val ACCION_ESCRIBIR = "com.mnldz.asistente.ESCRIBIR"
// Volver al panel sin escuchar ni teclado, con lo último que se ha hablado (desde el aviso flotante)
const val ACCION_VER = "com.mnldz.asistente.VER"
// Leer un mensaje recibido en el coche y escuchar la respuesta (Notificaciones.kt)
const val ACCION_MENSAJE = "com.mnldz.asistente.MENSAJE"

// Panel sobre cualquier app: lo abren el botón lateral (asistente predeterminado), el widget,
// los atajos y el icono de Ajustes rápidos. Solo enseña lo que se habla desde que se abre.
class AsistenteRapido : ActividadAsistente() {
    // Cada apertura: un número nuevo y con qué acción se abre
    private val apertura = MutableStateFlow(0 to ACCION_HABLAR)
    // El mensaje anunciado (ACCION_MENSAJE) y el texto compartido desde otra app (ACTION_SEND)
    var anuncio by mutableStateOf<Anuncio?>(null)
    var compartido: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        abrir(intent)
        setContent { TemaAsistente { PanelRapido(this, apertura.collectAsStateWithLifecycle().value) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        abrir(intent)
    }

    private fun abrir(i: Intent) {
        // En el coche sale sobre la pantalla de bloqueo
        val coche = app.enCoche.value
        setShowWhenLocked(coche)
        setTurnScreenOn(coche)
        var accion = i.action ?: ACCION_HABLAR
        anuncio = null
        compartido = null
        when (accion) {
            ACCION_VER -> {}
            ACCION_MENSAJE -> anuncio = Anuncio(
                i.getIntExtra("numero", 0), i.getStringExtra("app").orEmpty(),
                i.getStringExtra("quien").orEmpty(), i.getStringExtra("texto").orEmpty(),
            )
            Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE -> {
                adjuntos.lista.clear()
                compartido = listOfNotNull(i.getStringExtra(Intent.EXTRA_SUBJECT), i.getStringExtra(Intent.EXTRA_TEXT))
                    .filter { it.isNotBlank() }.distinct().joinToString("\n").ifBlank { null }
                if (i.type?.startsWith("image/") == true) fotos(i).forEach(adjuntos::anadir)
                accion = ACCION_ESCRIBIR
            }
            else -> adjuntos.lista.clear()
        }
        apertura.value = apertura.value.first + 1 to accion
    }

    @Suppress("DEPRECATION")
    private fun fotos(i: Intent): List<Uri> = when {
        i.action == Intent.ACTION_SEND_MULTIPLE && Build.VERSION.SDK_INT >= 33 -> i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        i.action == Intent.ACTION_SEND_MULTIPLE -> i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
        Build.VERSION.SDK_INT >= 33 -> listOfNotNull(i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
        else -> listOfNotNull(i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
    }.take(4)

    fun ampliar() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }
}

@Composable
private fun PanelRapido(act: AsistenteRapido, apertura: Pair<Int, String>) {
    val c = LocalColores.current
    val vm = act.vm
    val estado by vm.estado.collectAsStateWithLifecycle()
    val conv by vm.conv.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val voz = rememberVoz(act, conv)
    var texto by remember { mutableStateOf("") }
    // Mensajes de esta apertura: desde el primero que se manda en ella
    var desde by remember { mutableStateOf<Int?>(null) }
    val foco = remember { FocusRequester() }
    val teclado = LocalSoftwareKeyboardController.current
    val lista = rememberLazyListState()

    LifecycleStartEffect(vm) {
        vm.reanudar()
        onStopOrDispose { vm.pausar() }
    }

    LaunchedEffect(apertura.first) {
        if (apertura.second == ACCION_VER) {
            if (desde == null) desde = conv.mensajes.indexOfLast { it.rol == "user" }.takeIf { it >= 0 }
            return@LaunchedEffect
        }
        desde = null
        voz.ultimoEnvio = null
        texto = ""
        voz.callar()
        voz.prefijo = null
        vm.borrarError()
        if (!act.ajustes.listo) return@LaunchedEffect
        // Mensaje en el coche: se lee y se escucha la respuesta, que va a Claude con el mensaje delante
        act.anuncio?.let { a ->
            voz.prefijo = a.prefijo
            act.lector.leer(a.leer) { if (!act.isDestroyed) voz.escuchar() }
            return@LaunchedEffect
        }
        act.compartido?.let { texto = it }
        if (apertura.second != ACCION_ESCRIBIR) voz.escuchar() else { foco.requestFocus(); teclado?.show() }
    }

    fun mandar(t: String) = voz.mandar(t)
    LaunchedEffect(voz.ultimoEnvio) { if (desde == null) desde = voz.ultimoEnvio }

    val mensajes = desde?.let { conv.mensajes.drop(it) }.orEmpty()
    LaunchedEffect(mensajes.size, mensajes.lastOrNull()?.bloques?.size, conv.ocupado, conv.avisos.size) {
        val total = lista.layoutInfo.totalItemsCount
        if (total > 0) lista.animateScrollToItem(total - 1)
    }

    Box(
        Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, null) { voz.callar(); act.finish() },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(8.dp)
                .background(c.fondo, RoundedCornerShape(28.dp))
                .clickable(remember { MutableInteractionSource() }, null) {}
                .padding(top = 8.dp, bottom = 8.dp),
        ) {
            if (!act.ajustes.listo) {
                Text("Empareja primero la app con el servidor.", color = c.texto2, fontSize = 15.sp, modifier = Modifier.padding(18.dp))
                Row(Modifier.fillMaxWidth().padding(end = 8.dp), horizontalArrangement = Arrangement.End) {
                    IconButton(act::ampliar) { Icon(Icons.Filled.OpenInFull, "Abrir la app", tint = c.texto2) }
                }
                return@Column
            }

            if (mensajes.isNotEmpty() || conv.avisos.isNotEmpty()) LazyColumn(
                Modifier.fillMaxWidth().heightIn(max = 460.dp), state = lista,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(mensajes, key = { it.id }) { m -> if (m.rol == "user") MensajeUsuario(m.texto) else MensajeAsistente(m) }
                if (desde != null) items(conv.cola) { MensajeUsuario(it, enCola = true) }
                if (desde != null && conv.ocupado && conv.avisos.isEmpty()) item(key = "trabajando") { Trabajando(conv.tareas) }
                items(conv.avisos, key = { it.id }) { a -> TarjetaAviso(a) { vm.responder(a.id, it) } }
            }

            // El mensaje que se está anunciando, hasta que se contesta
            act.anuncio?.takeIf { mensajes.isEmpty() }?.let { a ->
                Text(
                    "${a.app}${if (a.quien.isNotBlank()) " · ${a.quien}" else ""}: ${a.texto}", color = c.texto2, fontSize = 15.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }

            FilaAdjuntos(act.adjuntos, Modifier.padding(horizontal = 18.dp, vertical = 6.dp))

            (voz.aviso ?: act.adjuntos.aviso ?: error)?.let {
                Text(it, color = c.error, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            }

            Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_chispa), null, tint = c.acento, modifier = Modifier.size(30.dp))
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f).padding(vertical = 12.dp)) {
                    if (voz.escuchando) Text(voz.parcial.ifEmpty { "Te escucho…" }, color = if (voz.parcial.isEmpty()) c.apagado else c.texto, fontSize = 16.sp)
                    else {
                        if (texto.isEmpty()) Text(
                            when { estado != Estado.CONECTADO -> "Conectando…"; conv.ocupado -> "Añadir a la cola…"; else -> "Pregunta a Claude" },
                            color = c.apagado, fontSize = 16.sp,
                        )
                        BasicTextField(
                            texto, { texto = it }, Modifier.fillMaxWidth().focusRequester(foco), maxLines = 4,
                            textStyle = TextStyle(color = c.texto, fontSize = 16.sp, lineHeight = 22.sp),
                            cursorBrush = SolidColor(c.acento),
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { if (texto.isNotBlank() || act.adjuntos.lista.isNotEmpty()) { mandar(texto.trim()); texto = "" } }),
                        )
                    }
                }
                Spacer(Modifier.width(6.dp))
                if (!voz.escuchando) BotonAdjuntar(act.adjuntos)
                when {
                    (texto.isNotBlank() || act.adjuntos.lista.isNotEmpty()) && !voz.escuchando -> IconButton(
                        { mandar(texto.trim()); texto = "" },
                        Modifier.size(40.dp).background(c.acento, CircleShape),
                    ) { Icon(Icons.AutoMirrored.Filled.Send, "Enviar", tint = Color.White, modifier = Modifier.size(20.dp)) }
                    conv.ocupado && !voz.escuchando && !voz.hablando -> IconButton(vm::interrumpir, Modifier.size(40.dp).background(c.elevado, CircleShape)) {
                        Icon(Icons.Filled.Stop, "Interrumpir", tint = c.texto)
                    }
                    else -> BotonMicro(voz)
                }
                IconButton(act::ampliar) { Icon(Icons.Filled.OpenInFull, "Abrir en la app", tint = c.apagado, modifier = Modifier.size(20.dp)) }
            }
        }
    }
}
