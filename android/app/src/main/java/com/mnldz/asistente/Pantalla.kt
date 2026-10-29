package com.mnldz.asistente

import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.speech.RecognizerIntent
import androidx.annotation.RequiresApi
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.media.CarAudioRecord
import androidx.car.app.model.Action
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.OutputStream
import kotlin.concurrent.thread

// Android Auto: pantalla «Claude» en el menú de apps del coche (Car App Library). Al abrirla escucha
// con el micrófono del coche, enseña en pequeño lo que dice Claude y lo lee; si acaba en pregunta,
// vuelve a escuchar. El micro de Android Auto es siempre de Gemini: esta pantalla es la forma de hablarle.
class ClaudeCoche : CarAppService() {
    // App propia instalada a mano: no hace falta comprobar quién la muestra
    override fun createHostValidator() = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
    override fun onCreateSession() = object : Session() {
        override fun onCreateScreen(intent: Intent): Screen = PantallaCoche(carContext)
    }
}

private class PantallaCoche(ctx: CarContext) : Screen(ctx), DefaultLifecycleObserver {
    private enum class Fase { LISTO, ESCUCHANDO, PENSANDO, LEYENDO }

    private val app = ctx.app
    private val vm = app.asistente
    private val dictado = Dictado(ctx, if (Build.VERSION.SDK_INT >= 33) MicroCoche(ctx) else null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val am = ctx.getSystemService(AudioManager::class.java)
    // Mientras lee, la música baja de volumen
    private val foco = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .build()

    private var fase = Fase.LISTO
    private var texto = "Pulsa Hablar y pregúntame."
    // Mensajes que había al mandar lo último desde aquí: su respuesta es para esta pantalla
    private var desde: Int? = null
    private var avisado = false
    private var abierta = false

    init { lifecycle.addObserver(this) }

    override fun onCreate(owner: LifecycleOwner) {
        scope.launch { vm.conv.collect(::revisar) }
        // Lo que va oyendo, para ver que lo ha entendido bien
        scope.launch { dictado.parcial.collect { if (fase == Fase.ESCUCHANDO && it.isNotBlank()) poner(Fase.ESCUCHANDO, "«$it»") } }
        scope.launch {
            app.lector.hablando.collect { if (!it && fase == Fase.LEYENDO) { am.abandonAudioFocusRequest(foco); poner(Fase.LISTO) } }
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        vm.reanudar()
        // Abrirla es para hablar: la primera vez escucha directamente
        if (!abierta) { abierta = true; escuchar() }
    }

    override fun onStop(owner: LifecycleOwner) {
        if (fase == Fase.ESCUCHANDO) { dictado.cancelar(); poner(Fase.LISTO) }
        vm.pausar()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        scope.cancel()
        dictado.liberar()
    }

    private fun poner(f: Fase, t: String? = null) {
        fase = f
        t?.let { texto = it }
        invalidate()
    }

    private fun escuchar() {
        app.lector.callar()
        am.abandonAudioFocusRequest(foco)
        poner(Fase.ESCUCHANDO, "Te escucho…")
        dictado.escuchar(::mandar) { poner(Fase.LISTO, it) }
    }

    private fun mandar(t: String) {
        desde = vm.conv.value.mensajes.size
        avisado = false
        vm.mandar(t)
        poner(Fase.PENSANDO, "«$t»\nPensando…")
    }

    private fun revisar(conv: Conversacion) {
        val d = desde ?: return
        if (conv.avisos.isNotEmpty()) {
            if (!avisado) { avisado = true; decir("Necesito que me respondas en el móvil.") }
            return
        }
        if (!turnoTerminado(conv, d)) return
        desde = null
        // Que no la lean también el panel o el aviso flotante
        app.leido = conv.mensajes.lastOrNull()?.id
        decir(respuestaDesde(conv, d)?.let(::paraLeer) ?: "Hecho.")
    }

    private fun decir(r: String) {
        poner(Fase.LEYENDO, r)
        am.requestAudioFocus(foco)
        app.lector.leer(r) { if (r.trimEnd().endsWith("?") && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) escuchar() }
    }

    private fun accion(titulo: String, f: () -> Unit) = Action.Builder().setTitle(titulo).setOnClickListener(f)

    override fun onGetTemplate(): Template {
        val principal = when (fase) {
            Fase.ESCUCHANDO -> accion("Enviar") { dictado.parar() }
            Fase.PENSANDO -> accion("Parar") { vm.interrumpir() }
            else -> accion("Hablar") { escuchar() } // mientras lee, corta la lectura
        }.setFlags(Action.FLAG_PRIMARY).build()
        val nueva = accion("Nueva") {
            app.lector.callar()
            dictado.cancelar()
            desde = null
            vm.nueva()
            poner(Fase.LISTO, "Conversación nueva. Pulsa Hablar.")
        }.build()
        return MessageTemplate.Builder(texto.take(400))
            .setTitle("Claude")
            .setHeaderAction(Action.APP_ICON)
            .addAction(principal)
            .addAction(nueva)
            .build()
    }
}

// Micrófono del coche (CarAudioRecord: PCM de 16 bits, mono, 16 kHz) para el reconocedor de voz:
// cada trozo del dictado lee de una tubería nueva que se llena con lo que se graba
@RequiresApi(33)
private class MicroCoche(private val ctx: CarContext) : FuenteAudio {
    private val am = ctx.getSystemService(AudioManager::class.java)
    private val foco = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .build()
    private var grabacion: CarAudioRecord? = null
    @Volatile private var salida: OutputStream? = null
    @Volatile private var ultimo = 0L
    private var entrada: ParcelFileDescriptor? = null

    // startRecording espera al coche (que enseña su aviso de micrófono): en el hilo principal lo bloquea
    override fun iniciar(listo: () -> Unit, fallo: (String) -> Unit) {
        if (grabacion != null) { listo(); return }
        am.requestAudioFocus(foco)
        val g = try { CarAudioRecord.create(ctx) } catch (e: Exception) { fallo(e.message.orEmpty()); return }
        grabacion = g
        val principal = Handler(Looper.getMainLooper())
        thread(name = "micro-coche") {
            try { g.startRecording() } catch (e: Exception) {
                principal.post { if (grabacion === g) { parar(); fallo(e.message.orEmpty()) } }
                return@thread
            }
            principal.post { if (grabacion === g) listo() }
            // Si el coche deja de mandar audio (el emulador lo hace al acabar), el reconocedor esperaría
            // para siempre el silencio del final: los huecos se rellenan con silencio
            thread(name = "micro-coche-silencio") {
                val nada = ByteArray(CarAudioRecord.AUDIO_CONTENT_SAMPLING_RATE / 10 * 2) // 100 ms
                while (grabacion === g) {
                    Thread.sleep(100)
                    if (SystemClock.elapsedRealtime() - ultimo > 200) escribir(nada, nada.size)
                }
            }
            val buf = ByteArray(CarAudioRecord.AUDIO_CONTENT_BUFFER_SIZE)
            while (grabacion === g) {
                val n = g.read(buf, 0, buf.size)
                if (n < 0) break
                escribir(buf, n)
            }
        }
    }

    private fun escribir(buf: ByteArray, n: Int) = synchronized(this) {
        ultimo = SystemClock.elapsedRealtime()
        try { salida?.write(buf, 0, n) } catch (_: Exception) {}
    }

    override fun trozo(intent: Intent) {
        val (lee, escribe) = ParcelFileDescriptor.createPipe()
        cerrarTubo()
        salida = ParcelFileDescriptor.AutoCloseOutputStream(escribe)
        entrada = lee
        intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, lee)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, CarAudioRecord.AUDIO_CONTENT_SAMPLING_RATE)
    }

    // Sin el cerrojo de escribir: si está bloqueado en una tubería llena, cerrarla lo despierta
    override fun cerrar() {
        try { salida?.close() } catch (_: Exception) {}
        salida = null
    }

    private fun cerrarTubo() {
        try { salida?.close() } catch (_: Exception) {}
        try { entrada?.close() } catch (_: Exception) {}
        salida = null
        entrada = null
    }

    override fun parar() {
        val g = grabacion ?: return
        grabacion = null
        try { g.stopRecording() } catch (_: Exception) {}
        cerrarTubo()
        am.abandonAudioFocusRequest(foco)
    }
}
