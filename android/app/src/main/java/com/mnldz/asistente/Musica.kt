package com.mnldz.asistente

import android.app.SearchManager
import android.content.ComponentName
import android.content.Intent
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.provider.MediaStore
import android.view.KeyEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

// Herramienta mcp__movil__musica: qué suena, reproducir, pausar, saltar y buscar, en cualquier app de música
class Musica(private val app: AppAsistente) {
    companion object { val HERRAMIENTAS = setOf("musica") }

    private class Fallo(msg: String) : Exception(msg)

    private val preferida get() = app.ajustes.appMusica

    private fun sesiones(): List<MediaController> = try {
        app.getSystemService(MediaSessionManager::class.java).getActiveSessions(ComponentName(app, Oyente::class.java))
    } catch (_: SecurityException) {
        throw Fallo("Falta el acceso a notificaciones de «Asistente» (Ajustes › Notificaciones › Acceso a notificaciones); sin él no se ve qué suena.")
    }

    private fun sonando(c: MediaController) = c.playbackState?.state in setOf(PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING)

    private fun principal(l: List<MediaController> = sesiones()) =
        l.firstOrNull(::sonando) ?: l.firstOrNull { it.packageName == preferida } ?: l.firstOrNull()

    suspend fun ejecutar(a: JSONObject): Pair<Boolean, String> = try {
        true to when (val accion = a.optString("accion", "estado")) {
            "estado" -> estado()
            "reproducir" -> { reanudar(); estado() }
            "pausar" -> { principal()?.transportControls?.pause() ?: tecla(KeyEvent.KEYCODE_MEDIA_PAUSE); despues() }
            "siguiente" -> { principal()?.transportControls?.skipToNext() ?: tecla(KeyEvent.KEYCODE_MEDIA_NEXT); despues() }
            "anterior" -> { principal()?.transportControls?.skipToPrevious() ?: tecla(KeyEvent.KEYCODE_MEDIA_PREVIOUS); despues() }
            "buscar" -> buscar(a.optString("busqueda").ifBlank { throw Fallo("Falta qué buscar.") })
            else -> throw Fallo("Acción desconocida: $accion")
        }
    } catch (e: Fallo) {
        false to (e.message ?: "Error.")
    }

    private suspend fun despues(): String { delay(1200); return estado() }

    // Sigue con lo último que sonaba; si nada tiene sesión, despierta la app preferida
    suspend fun reanudar() {
        val l = sesiones()
        if (l.any(::sonando)) return
        val c = principal(l)
        if (c != null) { c.transportControls.play(); return }
        despertar()
    }

    // Apple Music (media3) no tiene receptor de botones ni deja conectarse a apps desconocidas: se le
    // manda el botón PLAY a su servicio de reproducción, como hace el sistema para retomar lo último
    private suspend fun despertar(): Boolean {
        val servicio = app.packageManager.queryIntentServices(Intent(Intent.ACTION_MEDIA_BUTTON).setPackage(preferida), 0)
            .firstOrNull()?.serviceInfo ?: return false
        try {
            app.startForegroundService(
                Intent(Intent.ACTION_MEDIA_BUTTON).setComponent(ComponentName(servicio.packageName, servicio.name))
                    .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY)),
            )
        } catch (e: Exception) {
            android.util.Log.w("Musica", "No se ha podido despertar $preferida", e)
            return false
        }
        // En frío tarda: arranca, se identifica y carga la canción
        return withTimeoutOrNull(25_000) { while (sesiones().none { it.packageName == preferida && sonando(it) }) delay(500) } != null
    }

    private fun tecla(codigo: Int) {
        val am = app.getSystemService(AudioManager::class.java)
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, codigo))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, codigo))
    }

    private suspend fun buscar(q: String): String {
        val c = sesiones().firstOrNull { it.packageName == preferida }
        if (c != null) c.transportControls.playFromSearch(q, Bundle())
        else app.startActivity(
            Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).setPackage(preferida)
                .putExtra(SearchManager.QUERY, q).putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        delay(3000)
        return "Buscado «$q».\n" + estado()
    }

    private fun estado(): String {
        val l = sesiones()
        if (l.isEmpty()) return "No suena nada y ninguna app de música está abierta."
        return l.joinToString("\n") { c ->
            val nombre = try { app.packageManager.getApplicationLabel(app.packageManager.getApplicationInfo(c.packageName, 0)).toString() } catch (_: Exception) { c.packageName }
            val m = c.metadata
            val titulo = m?.getString(MediaMetadata.METADATA_KEY_TITLE)
            val artista = m?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            val album = m?.getString(MediaMetadata.METADATA_KEY_ALBUM)
            val que = listOfNotNull(titulo?.let { "«$it»" }, artista, album?.let { "($it)" }).joinToString(" · ").ifBlank { "sin datos de la canción" }
            "$nombre: ${if (sonando(c)) "sonando" else "en pausa"} · $que"
        }
    }
}
