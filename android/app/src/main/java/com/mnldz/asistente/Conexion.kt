package com.mnldz.asistente

import android.app.Application
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

enum class Estado { SIN_CONFIGURAR, CONECTANDO, CONECTADO, DESCONECTADO }

// Ajustes de emparejamiento con el servidor (dirección de Tailscale y token)
class Ajustes(context: Context) {
    private val prefs = context.getSharedPreferences("ajustes", Context.MODE_PRIVATE)
    var servidor: String
        get() = prefs.getString("servidor", "100.109.14.13:8790")!!
        set(v) = prefs.edit().putString("servidor", v.trim()).apply()
    var token: String
        get() = prefs.getString("token", "")!!
        set(v) = prefs.edit().putString("token", v.trim()).apply()
    // Leer en voz alta las respuestas a lo que se pide por voz
    var leerRespuestas: Boolean
        get() = prefs.getBoolean("leer", true)
        set(v) = prefs.edit().putBoolean("leer", v).apply()
    val listo get() = servidor.isNotBlank() && token.isNotBlank()
    // Modo coche: nombre Bluetooth del coche (basta una parte), app de música y si está dentro
    var coche: String
        get() = prefs.getString("coche", "Volvo EX30")!!
        set(v) = prefs.edit().putString("coche", v.trim()).apply()
    var appMusica: String
        get() = prefs.getString("appMusica", "com.apple.android.music")!!
        set(v) = prefs.edit().putString("appMusica", v.trim()).apply()
    var enCoche: Boolean
        get() = prefs.getBoolean("enCoche", false)
        set(v) = prefs.edit().putBoolean("enCoche", v).apply()
    // Avisos (Proactivo.kt): horas «HH:MM» (vacío, desactivado), «sal ya» y leer los mensajes en el coche
    var horaResumen: String
        get() = prefs.getString("horaResumen", "08:00")!!
        set(v) = prefs.edit().putString("horaResumen", v.trim()).apply()
    var horaNoche: String
        get() = prefs.getString("horaNoche", "22:30")!!
        set(v) = prefs.edit().putString("horaNoche", v.trim()).apply()
    var salYa: Boolean
        get() = prefs.getBoolean("salYa", true)
        set(v) = prefs.edit().putBoolean("salYa", v).apply()
    var leerMensajes: Boolean
        get() = prefs.getBoolean("leerMensajes", true)
        set(v) = prefs.edit().putBoolean("leerMensajes", v).apply()
}

// Una sola conexión para toda la app: la usan el chat y el asistente rápido
class AppAsistente : Application() {
    val ajustes by lazy { Ajustes(this) }
    val asistente by lazy { Asistente(this) }
    // Pantallas de la app a la vista (el control de pantalla las aparta para actuar en la de debajo)
    val alFrente = mutableListOf<android.app.Activity>()
    val enCoche by lazy { MutableStateFlow(ajustes.enCoche) }
    fun ponerCoche(v: Boolean) { ajustes.enCoche = v; enCoche.value = v }
    // Último mensaje leído en voz alta (lo leen el panel o el aviso flotante, nunca los dos)
    var leido: String? = null
    // Una sola voz para toda la app: al pedir hablar desde cualquier sitio se calla lo que se esté leyendo
    val lector by lazy { Lector(this) }
    val auto by lazy { Auto(this) }

    override fun onCreate() {
        super.onCreate()
        auto.iniciar()
        Proactivo.planificar(this)
    }
}

val Context.app get() = applicationContext as AppAsistente

typealias Manejador = suspend (String, JSONObject) -> Pair<Boolean, String>

// Acciones que abren pantallas: con el móvil bloqueado, antes hay que quitar el bloqueo
private val QUE_ABREN = setOf("abrir_app", "abrir_url", "mapa", "ver_alarmas", "ajustes", "llamar", "sms", "compartir")

class Asistente(private val app: AppAsistente) {
    private val ajustes = app.ajustes
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val cliente = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private var ws: WebSocket? = null
    private var reintento: Job? = null
    private var espera = 1000L
    private var activo = false
    private var cierre: Job? = null
    private var visibles = 0
    private val porEnviar = mutableListOf<JSONObject>()
    private val contexto = Contexto(app)

    // Quien ejecuta las acciones en el móvil: la última actividad que se ha mostrado
    private val manejadores = mutableListOf<Manejador>()
    fun activar(m: Manejador) { manejadores.remove(m); manejadores.add(m) }
    fun quitar(m: Manejador) { manejadores.remove(m) }

    private val _estado = MutableStateFlow(if (ajustes.listo) Estado.CONECTANDO else Estado.SIN_CONFIGURAR)
    val estado: StateFlow<Estado> = _estado
    private val _conv = MutableStateFlow(Conversacion())
    val conv: StateFlow<Conversacion> = _conv
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    // Cada pantalla visible (chat o asistente rápido) llama a reanudar al mostrarse y a pausar al ocultarse
    fun reanudar() {
        visibles++
        activo = true
        cierre?.cancel()
        if (ws == null) conectar()
    }

    // Al salir de la app la conexión sigue un rato: Claude puede estar abriendo otra app
    // o terminando lo que pidió el usuario
    fun pausar() {
        visibles = (visibles - 1).coerceAtLeast(0)
        if (visibles > 0) return
        cierre?.cancel()
        cierre = scope.launch {
            delay(120_000)
            // Mientras Claude trabaja (p. ej. manejando otra app) no se corta
            while (_conv.value.ocupado || _conv.value.avisos.isNotEmpty()) delay(15_000)
            cerrar()
        }
    }

    private fun cerrar() {
        activo = false
        reintento?.cancel()
        ws?.close(1000, null)
        ws = null
    }

    fun reconectar() {
        ws?.close(1000, null)
        ws = null
        espera = 1000L
        conectar()
    }

    private fun conectar() {
        if (!ajustes.listo) { _estado.value = Estado.SIN_CONFIGURAR; return }
        reintento?.cancel()
        _estado.value = Estado.CONECTANDO
        val url = "ws://${ajustes.servidor}/?k=${ajustes.token}"
        val req = try { Request.Builder().url(url).build() } catch (e: IllegalArgumentException) {
            _error.value = "Dirección no válida: ${ajustes.servidor}"
            _estado.value = Estado.DESCONECTADO
            return
        }
        ws = cliente.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                espera = 1000L
                _estado.value = Estado.CONECTADO
                _error.value = null
                scope.launch {
                    val pendientes = porEnviar.toList(); porEnviar.clear()
                    pendientes.forEach { enviar(it) }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val m = try { JSONObject(text) } catch (_: Exception) { return }
                when (m.optString("type")) {
                    "conv" -> _conv.value = leerConversacion(m.getJSONObject("conv"))
                    "error" -> _error.value = m.optString("message")
                    "accion" -> scope.launch {
                        val nombre = m.optString("nombre")
                        val args = m.optJSONObject("args") ?: JSONObject()
                        val r = JSONObject().put("type", "resultado").put("id", m.optString("id"))
                        // Un SMS que se manda sin abrir la app de mensajes no necesita desbloquear
                        val abre = (nombre in QUE_ABREN && !(nombre == "sms" && args.optBoolean("enviar"))) || nombre in Control.HERRAMIENTAS
                        if (abre && !Desbloquear.pedir(app)) {
                            r.put("ok", false).put("texto", "El móvil está bloqueado y no se ha desbloqueado. Pide al usuario que lo desbloquee (en el coche se hace solo si el coche es dispositivo de confianza en Smart Lock).")
                        } else if (nombre in Musica.HERRAMIENTAS) {
                            val (ok, texto) = Musica(app).ejecutar(args)
                            r.put("ok", ok).put("texto", texto)
                        } else if (nombre in Mensajes.HERRAMIENTAS) {
                            val (ok, texto) = Mensajes.ejecutar(nombre, args)
                            r.put("ok", ok).put("texto", texto)
                        } else if (nombre in Proactivo.HERRAMIENTAS) {
                            val (ok, texto) = Proactivo.ejecutar(app, nombre, args)
                            r.put("ok", ok).put("texto", texto)
                        } else if (nombre in Control.HERRAMIENTAS) {
                            val (ok, texto, imagen) = Control.activo?.ejecutar(nombre, args)
                                ?: Triple(false, "El control de pantalla está desactivado: pide al usuario que active «Asistente» en Ajustes › Accesibilidad (o usa mcp__movil__ajustes con pantalla accesibilidad).", null)
                            r.put("ok", ok).put("texto", texto)
                            imagen?.let { r.put("imagen", it) }
                        } else {
                            val (ok, texto) = manejadores.lastOrNull()?.invoke(nombre, args)
                                ?: (false to "La app del móvil no está lista: pide al usuario que la abra.")
                            r.put("ok", ok).put("texto", texto)
                        }
                        enviar(r)
                        // Si Claude ha sacado al usuario de la app, el aviso flotante sigue lo que hace
                        if (app.alFrente.isEmpty()) Control.activo?.flotante?.controlando()
                    }
                    // Un diálogo de la terminal que el chat no sabe enseñar
                    "termchat:attention" -> _error.value = "Claude espera en la terminal: ${m.optString("message")}"
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (webSocket !== ws) return
                ws = null
                _error.value = if (response?.code == 401) "Token incorrecto: revísalo en Ajustes."
                    else "Sin conexión con el servidor (¿Tailscale activo?)"
                _estado.value = Estado.DESCONECTADO
                if (response?.code != 401) programarReintento()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (webSocket !== ws) return
                ws = null
                _estado.value = Estado.DESCONECTADO
                programarReintento()
            }
        })
    }

    private fun programarReintento() {
        if (!activo) return
        reintento?.cancel()
        reintento = scope.launch {
            delay(espera)
            espera = (espera * 2).coerceAtMost(30_000L)
            if (activo && ws == null) conectar()
        }
    }

    private fun enviar(obj: JSONObject) {
        if (ws?.send(obj.toString()) == true) return
        // Lo que escribe el usuario espera a la conexión (el asistente rápido se abre y se habla enseguida)
        if (obj.optString("type") in setOf("send", "new") && activo) { porEnviar += obj; if (ws == null) conectar(); return }
        _error.value = "No se ha podido enviar: sin conexión."
    }

    // Lo que escribe o dice el usuario, con las fotos adjuntas (JPEG en base64) y el contexto del momento
    fun mandar(texto: String, imagenes: List<String> = emptyList()) {
        val m = JSONObject().put("type", "send").put("text", if (app.enCoche.value || app.auto.conectado.value) "🚗 $texto" else texto)
        if (imagenes.isNotEmpty()) m.put("images", JSONArray(imagenes.map { JSONObject().put("mediaType", "image/jpeg").put("data", it) }))
        scope.launch {
            m.put("contexto", contexto.texto())
            enviar(m)
        }
    }
    fun interrumpir() = enviar(JSONObject().put("type", "interrupt"))
    fun nueva() = enviar(JSONObject().put("type", "new"))
    fun responder(avisoId: String, payload: JSONObject) =
        enviar(JSONObject().put("type", "respond").put("promptId", avisoId).put("payload", payload))

    fun borrarError() { _error.value = null }
}
