package com.mnldz.asistente

import android.app.Activity
import android.app.KeyguardManager
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

// Modo coche: al conectarse por Bluetooth al coche (Ajustes.coche) se activa y reanuda la música;
// al desconectarse se quita. Mientras está activo, el panel sale sobre la pantalla de bloqueo,
// las respuestas se leen siempre en voz alta y lo que se manda lleva «🚗» para que Claude lo sepa.
class Coche : BroadcastReceiver() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onReceive(ctx: Context, i: Intent) {
        val app = ctx.app
        val d = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            else @Suppress("DEPRECATION") i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        if (d == null || !esCoche(app, d)) return
        val fin = goAsync()
        scope.launch {
            try {
                when (i.action) {
                    BluetoothDevice.ACTION_ACL_CONNECTED -> if (!app.enCoche.value) {
                        app.ponerCoche(true)
                        // Espera a que el audio vaya por el coche (y por si el coche ya reanuda la música solo)
                        delay(4000)
                        // Los recordatorios de «al subir al coche» se leen antes de la música
                        if (Proactivo.alSubir(app)) delay(6000)
                        Musica(app).reanudar()
                    }
                    // El coche se conecta con dos direcciones: solo se sale al irse las dos
                    BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                        delay(3000)
                        if (app.enCoche.value && !cocheConectado(app)) {
                            app.ponerCoche(false)
                            // Guarda dónde se ha aparcado y avisa de lo de «al bajar del coche»
                            Proactivo.alBajar(app)
                        }
                    }
                }
            } finally { fin.finish() }
        }
    }

    companion object {
        fun esCoche(app: AppAsistente, d: BluetoothDevice): Boolean {
            val nombre = try { d.name } catch (_: SecurityException) { null } ?: return false
            return app.ajustes.coche.isNotBlank() && nombre.contains(app.ajustes.coche.trim(), ignoreCase = true)
        }

        fun cocheConectado(app: AppAsistente): Boolean = try {
            val bt = app.getSystemService(BluetoothManager::class.java).adapter
            bt.bondedDevices.any { esCoche(app, it) && (it.javaClass.getMethod("isConnected").invoke(it) as? Boolean == true) }
        } catch (_: Exception) { false }
    }
}

// Quita la pantalla de bloqueo antes de manejar el móvil. Sin código si el coche es dispositivo de
// confianza (Smart Lock); si no, Android pide la huella o el PIN.
class Desbloquear : Activity() {
    companion object {
        private var resultado: CompletableDeferred<Boolean>? = null

        // true si ya estaba desbloqueado o se ha desbloqueado
        suspend fun pedir(ctx: Context): Boolean {
            if (!ctx.getSystemService(KeyguardManager::class.java).isKeyguardLocked) return true
            val d = CompletableDeferred<Boolean>()
            resultado = d
            ctx.startActivity(Intent(ctx, Desbloquear::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val ok = withTimeoutOrNull(25_000) { d.await() } ?: false
            if (ok) delay(500)
            return ok
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        getSystemService(KeyguardManager::class.java).requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() = acabar(true)
            override fun onDismissCancelled() = acabar(false)
            override fun onDismissError() = acabar(false)
        })
    }

    private fun acabar(ok: Boolean) {
        resultado?.complete(ok)
        resultado = null
        finish()
    }
}
