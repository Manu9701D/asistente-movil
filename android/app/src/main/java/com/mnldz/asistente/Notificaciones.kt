package com.mnldz.asistente

import android.app.Notification
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import org.json.JSONObject

// Acceso a notificaciones: deja ver y manejar lo que suena (Musica.kt) y leer y contestar los mensajes
// que llegan (herramientas notificaciones y responder_notificacion). En el coche, además, lee en voz alta
// los mensajes nuevos y pregunta si se quiere contestar (salvo con Android Auto, que ya lo hace).
class Oyente : NotificationListenerService() {
    companion object {
        var activo: Oyente? = null
            private set
    }

    override fun onListenerConnected() { activo = this }
    override fun onListenerDisconnected() { if (activo === this) activo = null }
    override fun onDestroy() { if (activo === this) activo = null; super.onDestroy() }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try { Mensajes.recibido(this, sbn) } catch (e: Exception) { android.util.Log.w("Oyente", "Notificación", e) }
    }
}

// Un mensaje que el móvil ha leído en voz alta en el coche y al que se puede contestar hablando
data class Anuncio(val numero: Int, val app: String, val quien: String, val texto: String) {
    val leer get() = "$app${if (quien.isNotBlank()) " de $quien" else ""}: $texto. ¿Quieres contestar?"
    // Lo que se manda a Claude junto con lo que conteste el usuario (ver NOTE en index.mjs)
    val prefijo get() = "[Mensaje recibido · notificación $numero · $app${if (quien.isNotBlank()) " · $quien" else ""}: «$texto»] Respuesta del usuario:"
}

object Mensajes {
    val HERRAMIENTAS = setOf("notificaciones", "responder_notificacion")

    private class Fallo(msg: String) : Exception(msg)

    // Números estables por notificación (clave de Android → número) para contestar luego a la que toca
    private val numeros = mutableMapOf<String, Int>()
    private var siguiente = 1
    private fun numero(key: String) = numeros.getOrPut(key) { siguiente++ }

    // Lo último anunciado por conversación, para no repetir al actualizarse la notificación
    private val anunciados = mutableMapOf<String, Pair<String, Long>>()

    private class Noti(
        val numero: Int, val app: String, val paquete: String, val quien: String, val texto: String,
        val cuando: Long, val respuesta: Notification.Action?, val esMensaje: Boolean,
    )

    private fun leer(o: Oyente, sbn: StatusBarNotification): Noti? {
        val n = sbn.notification
        if (sbn.packageName == o.packageName || n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        if (sbn.isOngoing && n.category != Notification.CATEGORY_CALL) return null
        val pm = o.packageManager
        val app = try { pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0)).toString() } catch (_: Exception) { sbn.packageName }
        val e = n.extras
        val estilo = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
        val titulo = e.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val (quien, texto) = if (estilo != null && estilo.messages.isNotEmpty()) {
            val ultimos = estilo.messages.takeLast(5)
            val grupo = estilo.conversationTitle?.toString()
            val texto = ultimos.joinToString(" · ") { m ->
                val de = m.person?.name?.toString()
                (if (grupo != null && de != null) "$de: " else "") + (m.text?.toString() ?: "(adjunto)")
            }
            (grupo ?: ultimos.last().person?.name?.toString() ?: titulo) to texto
        } else {
            val texto = (e.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: e.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
            titulo to texto
        }
        if (quien.isBlank() && texto.isBlank()) return null
        val respuesta = n.actions?.firstOrNull { it.semanticAction == Notification.Action.SEMANTIC_ACTION_REPLY && !it.remoteInputs.isNullOrEmpty() }
            ?: n.actions?.firstOrNull { !it.remoteInputs.isNullOrEmpty() }
        val esMensaje = estilo != null || n.category == Notification.CATEGORY_MESSAGE
        return Noti(numero(sbn.key), app, sbn.packageName, quien, texto.take(1500), sbn.postTime, respuesta, esMensaje)
    }

    private fun todas(o: Oyente): List<Pair<StatusBarNotification, Noti>> =
        (o.activeNotifications ?: emptyArray()).mapNotNull { s -> leer(o, s)?.let { s to it } }.sortedByDescending { it.second.cuando }

    suspend fun ejecutar(nombre: String, a: JSONObject): Pair<Boolean, String> = try {
        val o = Oyente.activo ?: throw Fallo("Falta el acceso a notificaciones de «Asistente» (Ajustes › Notificaciones › Acceso a notificaciones): pídeselo al usuario o abre mcp__movil__ajustes con pantalla notificaciones.")
        true to when (nombre) {
            "notificaciones" -> lista(o, a.optString("app"))
            "responder_notificacion" -> responder(o, a.getInt("numero"), a.getString("texto"))
            else -> throw Fallo("Herramienta desconocida: $nombre")
        }
    } catch (e: Fallo) {
        false to (e.message ?: "Error.")
    } catch (e: Exception) {
        false to "${e.javaClass.simpleName}: ${e.message}"
    }

    private fun hace(ms: Long): String {
        val min = ((System.currentTimeMillis() - ms) / 60_000).toInt()
        return when {
            min < 1 -> "ahora"
            min < 60 -> "hace $min min"
            min < 24 * 60 -> "hace ${min / 60} h"
            else -> "hace ${min / 1440} d"
        }
    }

    private fun lista(o: Oyente, app: String): String {
        val filtro = app.trim().lowercase()
        val l = todas(o).map { it.second }
            .filter { filtro.isEmpty() || it.app.lowercase().contains(filtro) || it.paquete.contains(filtro) }
            .take(30)
        if (l.isEmpty()) return if (filtro.isEmpty()) "No hay notificaciones." else "No hay notificaciones de «$app»."
        return l.joinToString("\n") { n ->
            "[${n.numero}] ${n.app}${if (n.quien.isNotBlank()) " · ${n.quien}" else ""} (${hace(n.cuando)}): ${n.texto}" +
                if (n.respuesta != null) " (se puede contestar)" else ""
        }
    }

    private fun responder(o: Oyente, numero: Int, texto: String): String {
        val (_, n) = todas(o).firstOrNull { it.second.numero == numero }
            ?: throw Fallo("La notificación $numero ya no está (quizá se ha leído o borrado). Mira notificaciones otra vez.")
        val accion = n.respuesta ?: throw Fallo("La notificación de ${n.app} no deja contestar desde ella: hazlo abriendo la app.")
        val resultados = Bundle()
        accion.remoteInputs.forEach { resultados.putCharSequence(it.resultKey, texto) }
        val datos = Intent()
        RemoteInput.addResultsToIntent(accion.remoteInputs, datos, resultados)
        accion.actionIntent.send(o, 0, datos)
        return "Contestado a ${n.quien.ifBlank { n.app }} por ${n.app}: «$texto»."
    }

    // ---------- En el coche: leer los mensajes nuevos ----------

    fun recibido(o: Oyente, sbn: StatusBarNotification) {
        val app = o.app
        if (!app.enCoche.value || !app.ajustes.leerMensajes || app.auto.conectado.value) return
        val n = leer(o, sbn) ?: return
        if (!n.esMensaje || n.respuesta == null) return
        // Las apps repiten la notificación al actualizarla: solo lo nuevo y no más de uno cada 5 s por conversación
        val conv = "${n.paquete}/${n.quien}"
        val ahora = System.currentTimeMillis()
        val antes = anunciados[conv]
        if (antes != null && (antes.first == n.texto || ahora - antes.second < 5000)) return
        anunciados[conv] = n.texto to ahora
        val nuevo = antes?.first?.let { n.texto.removePrefix(it).removePrefix(" · ").ifBlank { null } } ?: n.texto
        val anuncio = Anuncio(n.numero, n.app, n.quien, nuevo.take(400))
        // El panel lo lee y escucha la respuesta; Android solo deja abrirlo desde segundo plano con el
        // control de pantalla activo (o con la app delante). Si no, al menos se lee.
        if (Control.activo != null || app.alFrente.isNotEmpty()) {
            o.startActivity(
                Intent(o, AsistenteRapido::class.java).setAction(ACCION_MENSAJE)
                    .putExtra("numero", anuncio.numero).putExtra("app", anuncio.app)
                    .putExtra("quien", anuncio.quien).putExtra("texto", anuncio.texto)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } else if (!app.lector.hablando.value) {
            app.lector.leer("${anuncio.app}${if (anuncio.quien.isNotBlank()) " de ${anuncio.quien}" else ""}: ${anuncio.texto}")
        }
    }
}
