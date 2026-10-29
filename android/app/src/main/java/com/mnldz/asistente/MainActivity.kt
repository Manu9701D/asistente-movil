package com.mnldz.asistente

import android.app.role.RoleManager
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle

// Lo común al chat y al asistente rápido: la conexión compartida, las acciones del móvil y la voz
abstract class ActividadAsistente : ComponentActivity() {
    val vm get() = app.asistente
    val ajustes get() = app.ajustes
    lateinit var dictado: Dictado
    val lector get() = app.lector
    lateinit var adjuntos: Adjuntos
    private lateinit var manejador: Manejador

    // Lo que escribe o dice el usuario, con las fotos adjuntas (sin texto, se pregunta qué es)
    fun mandar(texto: String) {
        val fotos = adjuntos.sacar()
        vm.mandar(texto.ifBlank { if (fotos.isNotEmpty()) "¿Qué es esto?" else texto }, fotos)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        adjuntos = Adjuntos(this)
        val acciones = Acciones(this)
        manejador = acciones::ejecutar
        vm.activar(manejador)
        dictado = Dictado(this)
    }

    override fun onResume() {
        super.onResume()
        vm.activar(manejador)
    }

    override fun onStart() {
        super.onStart()
        app.alFrente += this
    }

    override fun onStop() {
        app.alFrente -= this
        dictado.cancelar()
        super.onStop()
    }

    override fun onDestroy() {
        vm.quitar(manejador)
        dictado.liberar()
        super.onDestroy()
    }
}

class MainActivity : ActividadAsistente() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) atajo(intent)
        setContent { TemaAsistente { App(this) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        atajo(intent)
    }

    private fun atajo(i: Intent) {
        if (i.action == ACCION_NUEVA) vm.nueva()
    }
}

const val ACCION_NUEVA = "com.mnldz.asistente.NUEVA"

@Composable
private fun App(act: ActividadAsistente) {
    val vm = act.vm
    val estado by vm.estado.collectAsStateWithLifecycle()
    var verAjustes by rememberSaveable { mutableStateOf(false) }

    // Conectado mientras la app está a la vista (y un rato después, ver pausar)
    LifecycleStartEffect(vm) {
        vm.reanudar()
        onStopOrDispose { vm.pausar() }
    }

    val c = LocalColores.current
    Box(Modifier.fillMaxSize().background(c.fondo).systemBarsPadding().imePadding()) {
        if (verAjustes || estado == Estado.SIN_CONFIGURAR) {
            PantallaAjustes(act.ajustes, puedeVolver = act.ajustes.listo, volver = { verAjustes = false }) {
                verAjustes = false
                vm.reconectar()
            }
        } else PantallaChat(act, estado, abrirAjustes = { verAjustes = true })
    }
}

@Composable
private fun PantallaChat(act: ActividadAsistente, estado: Estado, abrirAjustes: () -> Unit) {
    val vm = act.vm
    val c = LocalColores.current
    val conv by vm.conv.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    var texto by rememberSaveable { mutableStateOf("") }
    val lista = rememberLazyListState()
    val voz = rememberVoz(act, conv)

    // Bajar al final con cada novedad
    val ultimo = conv.mensajes.lastOrNull()
    LaunchedEffect(conv.mensajes.size, ultimo?.bloques?.size, conv.cola.size, conv.ocupado, conv.avisos.size) {
        val total = lista.layoutInfo.totalItemsCount
        if (total > 0) lista.animateScrollToItem(total - 1)
    }

    Column(Modifier.fillMaxSize()) {
        Cabecera(estado, nueva = { vm.nueva() }, abrirAjustes = abrirAjustes)
        (voz.aviso ?: act.adjuntos.aviso ?: error)?.let { e ->
            Row(
                Modifier.fillMaxWidth().background(c.error.copy(alpha = .14f)).padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(e, color = c.error, fontSize = 13.sp, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
                IconButton({ voz.aviso = null; act.adjuntos.aviso = null; vm.borrarError() }) { Icon(Icons.Filled.Close, "Cerrar", tint = c.error) }
            }
        }

        if (conv.mensajes.isEmpty() && conv.cola.isEmpty()) {
            Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(painterResource(R.drawable.ic_chispa), null, tint = c.acento, modifier = Modifier.size(44.dp))
                Spacer(Modifier.height(14.dp))
                Text("¿En qué te ayudo?", color = c.texto, fontSize = 22.sp)
            }
        } else LazyColumn(
            Modifier.weight(1f).fillMaxWidth(), state = lista,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(conv.mensajes, key = { it.id }) { m ->
                if (m.rol == "user") MensajeUsuario(m.texto) else MensajeAsistente(m)
            }
            items(conv.cola) { MensajeUsuario(it, enCola = true) }
            if (conv.ocupado && conv.avisos.isEmpty()) item(key = "trabajando") { Trabajando(conv.tareas) }
            items(conv.avisos, key = { it.id }) { a -> TarjetaAviso(a) { vm.responder(a.id, it) } }
        }

        CuadroTexto(
            texto, { texto = it }, ocupado = conv.ocupado, conectado = estado == Estado.CONECTADO,
            enviar = { voz.callar(); act.mandar(texto.trim()); texto = "" },
            parar = { vm.interrumpir() },
            voz = voz, adjuntos = act.adjuntos,
        )
    }
}

@Composable
private fun Cabecera(estado: Estado, nueva: () -> Unit, abrirAjustes: () -> Unit) {
    val c = LocalColores.current
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(R.drawable.ic_chispa), null, tint = c.acento, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("Asistente", color = c.texto, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            val (color, etiqueta) = when (estado) {
                Estado.CONECTADO -> androidx.compose.ui.graphics.Color(0xFF6BBF7A) to "Conectado"
                Estado.CONECTANDO -> c.apagado to "Conectando…"
                else -> c.error to "Sin conexión"
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).background(color, CircleShape))
                Spacer(Modifier.width(5.dp))
                Text(etiqueta, color = c.apagado, fontSize = 12.sp)
            }
        }
        Box {
            IconButton({ menu = true }) { Icon(Icons.Filled.MoreVert, "Menú", tint = c.texto2) }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem({ Text("Nueva conversación") }, { menu = false; nueva() })
                DropdownMenuItem({ Text("Ajustes") }, { menu = false; abrirAjustes() })
            }
        }
    }
}

@Composable
private fun CuadroTexto(
    texto: String, cambiar: (String) -> Unit, ocupado: Boolean, conectado: Boolean,
    enviar: () -> Unit, parar: () -> Unit, voz: Voz, adjuntos: Adjuntos,
) {
    val c = LocalColores.current
    val hayFotos = adjuntos.lista.isNotEmpty()
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)
            .background(c.superficie, RoundedCornerShape(24.dp)),
    ) {
    FilaAdjuntos(adjuntos, Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp))
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BotonAdjuntar(adjuntos)
        Box(Modifier.weight(1f).padding(vertical = 8.dp)) {
            if (voz.escuchando) Text(voz.parcial.ifEmpty { "Te escucho…" }, color = if (voz.parcial.isEmpty()) c.apagado else c.texto2, fontSize = 15.sp)
            else {
                if (texto.isEmpty()) Text(if (ocupado) "Añadir a la cola…" else "Escribe a Claude…", color = c.apagado, fontSize = 15.sp)
                BasicTextField(
                    texto, cambiar, Modifier.fillMaxWidth(), maxLines = 6,
                    textStyle = TextStyle(color = c.texto, fontSize = 15.sp, lineHeight = 21.sp),
                    cursorBrush = SolidColor(c.acento),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                )
            }
        }
        Spacer(Modifier.width(6.dp))
        if (voz.escuchando || voz.hablando || (texto.isBlank() && !hayFotos && !ocupado)) {
            BotonMicro(voz)
        } else if (ocupado && texto.isBlank() && !hayFotos) {
            IconButton(parar, Modifier.size(40.dp).background(c.elevado, CircleShape)) {
                Icon(Icons.Filled.Stop, "Interrumpir", tint = c.texto)
            }
        } else {
            val activo = (texto.isNotBlank() || hayFotos) && conectado
            IconButton(enviar, Modifier.size(40.dp).background(if (activo) c.acento else c.elevado, CircleShape), enabled = activo) {
                Icon(Icons.AutoMirrored.Filled.Send, "Enviar", tint = if (activo) androidx.compose.ui.graphics.Color.White else c.apagado, modifier = Modifier.size(20.dp))
            }
        }
    }
    }
}

@Composable
private fun PantallaAjustes(ajustes: Ajustes, puedeVolver: Boolean, volver: () -> Unit, guardar: () -> Unit) {
    val c = LocalColores.current
    var servidor by rememberSaveable { mutableStateOf(ajustes.servidor) }
    var token by rememberSaveable { mutableStateOf(ajustes.token) }
    if (puedeVolver) BackHandler(onBack = volver)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (puedeVolver) IconButton(volver) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver", tint = c.texto2) }
            Text("Ajustes", color = c.texto, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(
            "La app habla con Claude Code en tu servidor a través de Tailscale. Ten Tailscale activo en el móvil.",
            color = c.texto2, fontSize = 14.sp,
        )
        OutlinedTextField(servidor, { servidor = it }, Modifier.fillMaxWidth(), label = { Text("Servidor (IP:puerto)") }, singleLine = true)
        OutlinedTextField(
            token, { token = it }, Modifier.fillMaxWidth(), label = { Text("Token") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
        )
        Button(
            { ajustes.servidor = servidor; ajustes.token = token; guardar() },
            enabled = servidor.isNotBlank() && token.isNotBlank(),
            colors = ButtonDefaults.buttonColors(containerColor = c.acento),
        ) { Text("Guardar y conectar") }

        Spacer(Modifier.height(8.dp))
        Text("Voz", color = c.texto, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        var leer by remember { mutableStateOf(ajustes.leerRespuestas) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Leer en voz alta las respuestas a lo que pidas hablando", color = c.texto2, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Switch(leer, { leer = it; ajustes.leerRespuestas = it }, colors = SwitchDefaults.colors(checkedTrackColor = c.acento))
        }

        Avisos(ajustes)

        Spacer(Modifier.height(8.dp))
        Text("Asistente del móvil", color = c.texto, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        val ctx = LocalContext.current
        var esAsistente by remember { mutableStateOf(false) }
        LifecycleResumeEffect(Unit) {
            esAsistente = ctx.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true
            onPauseOrDispose {}
        }
        Text(
            if (esAsistente) "Es tu asistente digital: ábrelo manteniendo pulsado el botón lateral (si lo tienes así en Funciones avanzadas › Botón lateral)."
            else "Elígelo como «Aplicación de asistente digital» para abrirlo con el botón lateral.",
            color = c.texto2, fontSize = 14.sp,
        )
        if (!esAsistente) OutlinedButton({
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }) { Text("Abrir aplicaciones predeterminadas", color = c.texto) }
    }
}

// Lo que el móvil avisa solo (Proactivo.kt) y los permisos que necesita
@Composable
private fun Avisos(ajustes: Ajustes) {
    val c = LocalColores.current
    val ctx = LocalContext.current
    val replanificar = { Proactivo.planificar(ctx.applicationContext) }
    val hora = Regex("^([01]?\\d|2[0-3]):[0-5]\\d$")
    Spacer(Modifier.height(8.dp))
    Text("Avisos", color = c.texto, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    var resumen by remember { mutableStateOf(ajustes.horaResumen) }
    var noche by remember { mutableStateOf(ajustes.horaNoche) }
    Text("Resumen de la mañana (agenda y tiempo) y repaso de la noche (¿hay alarma para lo de mañana?). Vacío, no se avisa.", color = c.texto2, fontSize = 14.sp)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            resumen, { resumen = it; if (it.isBlank() || hora.matches(it.trim())) { ajustes.horaResumen = it.trim(); replanificar() } },
            Modifier.weight(1f), label = { Text("Mañana (H:MM)") }, singleLine = true,
            isError = resumen.isNotBlank() && !hora.matches(resumen.trim()),
        )
        OutlinedTextField(
            noche, { noche = it; if (it.isBlank() || hora.matches(it.trim())) { ajustes.horaNoche = it.trim(); replanificar() } },
            Modifier.weight(1f), label = { Text("Noche (H:MM)") }, singleLine = true,
            isError = noche.isNotBlank() && !hora.matches(noche.trim()),
        )
    }
    var salYa by remember { mutableStateOf(ajustes.salYa) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("«Sal ya»: avisar de cuándo salir hacia los eventos con sitio, según el tráfico", color = c.texto2, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Switch(salYa, { salYa = it; ajustes.salYa = it; replanificar() }, colors = SwitchDefaults.colors(checkedTrackColor = c.acento))
    }
    var leerMensajes by remember { mutableStateOf(ajustes.leerMensajes) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("En el coche, leer los mensajes que lleguen y contestarlos hablando", color = c.texto2, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Switch(leerMensajes, { leerMensajes = it; ajustes.leerMensajes = it }, colors = SwitchDefaults.colors(checkedTrackColor = c.acento))
    }
    var siempre by remember { mutableStateOf(false) }
    var oyente by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        siempre = Sitio.siempre(ctx)
        oyente = androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
        onPauseOrDispose {}
    }
    if (!siempre) {
        Text("Para guardar dónde aparcas y los recordatorios por sitio, la ubicación tiene que estar en «Permitir todo el tiempo» (Permisos › Ubicación).", color = c.texto2, fontSize = 14.sp)
        OutlinedButton({
            ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", ctx.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }) { Text("Abrir los permisos de la app", color = c.texto) }
    }
    if (!oyente) {
        Text("Para leer y contestar mensajes (y ver qué suena), activa el acceso a notificaciones.", color = c.texto2, fontSize = 14.sp)
        OutlinedButton({
            ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }) { Text("Abrir el acceso a notificaciones", color = c.texto) }
    }
}
