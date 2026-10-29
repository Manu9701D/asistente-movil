package com.mnldz.asistente

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

// De dónde sale el audio del dictado si no es el micrófono del móvil (en Android Auto, el del coche)
interface FuenteAudio {
    // Empieza a grabar; listo/fallo se llaman en el hilo principal
    fun iniciar(listo: () -> Unit, fallo: (String) -> Unit)
    // Añade al intent del reconocedor el audio de un trozo nuevo
    fun trozo(intent: Intent)
    // Acaba el audio del trozo: con audio propio, el reconocedor no da el resultado hasta que se acaba
    fun cerrar()
    fun parar()
}

// Dictado con el reconocedor de voz de Android (el de Google), con resultados parciales.
// Solo termina tras SILENCIO ms callado: si el reconocedor corta antes, sigue escuchando y junta lo oído.
// Sin actividad (Android Auto) no puede pedir el permiso del micrófono: tiene que estar ya concedido.
class Dictado(
    private val ctx: Context,
    private val fuente: FuenteAudio? = null,
    act: ComponentActivity? = ctx as? ComponentActivity,
) {
    private companion object { const val SILENCIO = 2000L }

    private val _escuchando = MutableStateFlow(false)
    val escuchando: StateFlow<Boolean> = _escuchando
    private val _parcial = MutableStateFlow("")
    val parcial: StateFlow<String> = _parcial
    private val _nivel = MutableStateFlow(0f)
    val nivel: StateFlow<Float> = _nivel

    private var rec: SpeechRecognizer? = null
    private var alTerminar: (String) -> Unit = {}
    private var alFallar: (String) -> Unit = {}

    private val manejador = Handler(Looper.getMainLooper())
    private var acumulado = ""
    private var ultimoParcial = "" // del trozo actual: al cerrar el audio propio, el resultado llega vacío
    private var parando = false
    private var hablando = false
    private var suelo = Float.MAX_VALUE // ruido de fondo (dB), para saber cuándo se calla
    private var ultimaVoz = 0L
    private val cortar = Runnable { rec?.cancel(); entregar() }
    // Con audio propio: SILENCIO ms sin texto nuevo cierran el trozo
    private val cerrarTrozo = Runnable { fuente?.cerrar() }
    private fun oido() { if (fuente != null) { manejador.removeCallbacks(cerrarTrozo); manejador.postDelayed(cerrarTrozo, SILENCIO) } }

    private val permiso = act?.registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) empezar() else alFallar("Sin permiso para usar el micrófono.")
    }

    fun escuchar(terminar: (String) -> Unit, fallar: (String) -> Unit) {
        alTerminar = terminar
        alFallar = fallar
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) empezar()
        else permiso?.launch(Manifest.permission.RECORD_AUDIO) ?: alFallar("Falta el permiso del micrófono: dalo en la app del móvil.")
    }

    private fun empezar() {
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) { alFallar("Este móvil no tiene reconocimiento de voz."); return }
        manejador.removeCallbacks(cortar)
        acumulado = ""
        parando = false
        suelo = Float.MAX_VALUE
        ultimaVoz = 0L
        _parcial.value = ""
        _escuchando.value = true
        val f = fuente ?: return arrancar()
        f.iniciar(::arrancar) { fin(); alFallar("No se puede usar el micrófono: $it") }
    }

    private fun arrancar() {
        if (!_escuchando.value) return // cancelado mientras arrancaba el micrófono
        val r = rec ?: SpeechRecognizer.createSpeechRecognizer(ctx).also { rec = it }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { _escuchando.value = true }
            override fun onBeginningOfSpeech() { hablando = true; ultimaVoz = SystemClock.elapsedRealtime(); manejador.removeCallbacks(cortar); oido() }
            override fun onRmsChanged(rmsdB: Float) {
                _nivel.value = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                suelo = minOf(suelo, rmsdB)
                if (rmsdB > suelo + 4f) ultimaVoz = SystemClock.elapsedRealtime()
            }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { _nivel.value = 0f; hablando = false }
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let {
                    ultimoParcial = it
                    val nuevo = juntar(acumulado, it)
                    if (nuevo != _parcial.value) { _parcial.value = nuevo; oido() }
                }
            }
            override fun onResults(results: Bundle?) {
                val texto = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty().ifBlank { ultimoParcial }
                acumulado = juntar(acumulado, texto)
                _parcial.value = acumulado
                seguirOEntregar()
            }
            override fun onError(error: Int) {
                // Tras un trozo ya oído, no oír nada más es el final normal
                if (acumulado.isNotBlank() && error in setOf(SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT)) { seguirOEntregar(); return }
                if (acumulado.isNotBlank()) { entregar(); return }
                fin()
                alFallar(when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No te he oído."
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "El reconocimiento de voz no tiene conexión."
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Sin permiso para usar el micrófono."
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "El micrófono está ocupado."
                    else -> "Error del reconocimiento de voz ($error)."
                })
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        escucharTrozo()
    }

    private fun escucharTrozo() {
        hablando = false
        ultimoParcial = ""
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES")
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, SILENCIO)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, SILENCIO)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
        fuente?.trozo(intent)
        rec?.startListening(intent)
    }

    // El reconocedor ha cortado: si aún no van SILENCIO ms callado, vuelve a escuchar hasta cumplirlos
    private fun seguirOEntregar() {
        val quedan = (if (ultimaVoz == 0L) 0L else ultimaVoz + SILENCIO - SystemClock.elapsedRealtime())
        if (parando || acumulado.isBlank() || quedan <= 150) { entregar(); return }
        manejador.postDelayed(cortar, quedan)
        manejador.post { if (_escuchando.value) escucharTrozo() }
    }

    private fun entregar() {
        manejador.removeCallbacks(cortar)
        val texto = acumulado
        acumulado = ""
        fin()
        if (texto.isNotBlank()) alTerminar(texto.replaceFirstChar { it.uppercase() }) else alFallar("No te he oído.")
    }

    private fun juntar(a: String, b: String) = listOf(a, b).filter { it.isNotBlank() }.joinToString(" ")

    private fun fin() {
        manejador.removeCallbacks(cerrarTrozo)
        fuente?.parar()
        _escuchando.value = false
        _nivel.value = 0f
    }

    // Deja de escuchar y se queda con lo oído hasta ahora
    fun parar() {
        parando = true
        if (manejador.hasCallbacks(cortar) && !hablando) { rec?.cancel(); entregar() } else { rec?.stopListening(); fuente?.cerrar() }
    }

    fun cancelar() {
        manejador.removeCallbacks(cortar)
        acumulado = ""
        rec?.cancel()
        fin()
        _parcial.value = ""
    }

    fun liberar() {
        manejador.removeCallbacks(cortar)
        rec?.destroy()
        rec = null
        fuente?.parar()
    }
}

// Lectura en voz alta de las respuestas (una para toda la app: AppAsistente.lector)
class Lector(context: Context) {
    // Lo de después de leer espera a que deje de sonar: por Bluetooth el audio va con retraso
    // y, si se vuelve a escuchar enseguida, el micrófono oye el final de la respuesta
    private companion object { const val COLA = 800L }

    private val _hablando = MutableStateFlow(false)
    val hablando: StateFlow<Boolean> = _hablando
    private var listo = false
    private var pendiente: String? = null
    private var alAcabar: (() -> Unit)? = null
    private var despues: Runnable? = null
    private val principal = Handler(Looper.getMainLooper())
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { estado -> iniciado(estado) }

    private fun iniciado(estado: Int) {
        listo = estado == TextToSpeech.SUCCESS
        if (listo) {
            tts.language = Locale("es", "ES")
            pendiente?.let { leer(it, alAcabar) }
        }
        pendiente = null
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { _hablando.value = true }
            override fun onDone(utteranceId: String?) {
                _hablando.value = false
                principal.post {
                    val f = alAcabar ?: return@post
                    alAcabar = null
                    despues = Runnable { despues = null; f() }.also { principal.postDelayed(it, COLA) }
                }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { _hablando.value = false }
            override fun onStop(utteranceId: String?, interrupted: Boolean) { _hablando.value = false }
        })
    }

    // alAcabar: lo que se hace al terminar de leerlo (p. ej. volver a escuchar)
    fun leer(md: String, alAcabar: (() -> Unit)? = null) {
        val texto = paraLeer(md)
        if (texto.isBlank()) return
        this.alAcabar = alAcabar
        if (!listo) { pendiente = md; return }
        tts.speak(texto, TextToSpeech.QUEUE_FLUSH, null, "respuesta")
    }

    fun callar() {
        pendiente = null
        alAcabar = null
        despues?.let { principal.removeCallbacks(it) }
        despues = null
        if (listo) tts.stop()
        _hablando.value = false
    }
}

// Quita el Markdown para leerlo: sin código, sin símbolos de formato y los enlaces solo con su texto
fun paraLeer(md: String): String = md
    .replace(Regex("```[\\s\\S]*?```"), " ")
    .replace(Regex("`([^`]*)`"), "$1")
    .replace(Regex("!?\\[([^\\]]*)]\\([^)]*\\)"), "$1")
    .replace(Regex("(?m)^\\s{0,3}(#{1,6}|>|[-*+]|\\d+[.)])\\s+"), "")
    .replace(Regex("(?m)^\\s*\\|?[-:| ]+\\|?\\s*$"), "")
    .replace("|", ", ")
    .replace(Regex("[*_~]{1,3}"), "")
    .replace(Regex("\\s*\\n\\s*"), "\n")
    .trim()
