package com.mnldz.asistente

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

// Herramientas de pantalla (mcp__movil__pantalla, captura, pulsar, escribir, deslizar, boton):
// un servicio de accesibilidad que lee lo que hay en pantalla y toca, escribe y desliza como el usuario.
// El usuario lo activa en Ajustes › Accesibilidad › Apps instaladas › Asistente.
class Control : AccessibilityService() {
    companion object {
        var activo: Control? = null
            private set
        val HERRAMIENTAS = setOf("pantalla", "captura", "pulsar", "escribir", "deslizar", "boton")
    }

    private class Fallo(msg: String) : Exception(msg)
    private class Elemento(val nodo: AccessibilityNodeInfo, val rect: Rect)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var elementos = listOf<Elemento>()
    lateinit var flotante: Flotante
        private set

    override fun onServiceConnected() {
        activo = this
        flotante = Flotante(this, scope)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        if (activo === this) activo = null
        flotante.quitar()
        scope.cancel()
        super.onDestroy()
    }

    suspend fun ejecutar(nombre: String, a: JSONObject): Triple<Boolean, String, String?> = try {
        apartarNuestra()
        flotante.controlando()
        when (nombre) {
            "pantalla" -> Triple(true, pantalla(), null)
            "captura" -> captura()
            "pulsar" -> { pulsar(a); Triple(true, despues("Pulsado."), null) }
            "escribir" -> { escribir(a); Triple(true, despues("Escrito."), null) }
            "deslizar" -> { deslizar(a); Triple(true, despues("Deslizado."), null) }
            "boton" -> { boton(a.getString("boton")); Triple(true, despues("Hecho."), null) }
            else -> Triple(false, "Herramienta desconocida: $nombre", null)
        }
    } catch (e: Fallo) {
        Triple(false, e.message ?: "Error.", null)
    } catch (e: Exception) {
        Triple(false, "Error: ${e.message ?: e.javaClass.simpleName}", null)
    }

    // Si delante está esta app (el chat o el panel), se aparta para actuar sobre la de debajo
    private suspend fun apartarNuestra() {
        if (rootInActiveWindow?.packageName != packageName) return
        app.alFrente.toList().forEach { it.moveTaskToBack(true) }
        delay(600)
    }

    // Tras actuar, espera a que la pantalla cambie y la devuelve para el siguiente paso
    private suspend fun despues(hecho: String): String {
        delay(900)
        return "$hecho\n\n${pantalla()}"
    }

    // ---------- Leer la pantalla ----------

    private fun pantalla(): String {
        val raiz = rootInActiveWindow ?: throw Fallo("No se puede leer la pantalla (¿bloqueada o cargando?).")
        val lista = mutableListOf<Elemento>()
        val lineas = mutableListOf<String>()
        fun visitar(n: AccessibilityNodeInfo, dentro: Boolean) {
            if (!n.isVisibleToUser) return
            val accionable = n.isClickable || n.isLongClickable || n.isEditable || n.isScrollable || n.isCheckable
            val propio = etiqueta(n)
            if (accionable || (!dentro && propio.isNotBlank())) {
                val texto = propio.ifBlank { if (accionable && !n.isScrollable) textosDentro(n) else "" }
                val r = Rect().also { n.getBoundsInScreen(it) }
                if (texto.isNotBlank() || accionable) {
                    lista += Elemento(n, r)
                    lineas += linea(lista.size, n, texto, r)
                }
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { visitar(it, dentro || (accionable && !n.isScrollable)) }
        }
        visitar(raiz, false)
        elementos = lista
        val paquete = raiz.packageName?.toString().orEmpty()
        val nombreApp = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(paquete, 0)).toString() } catch (_: Exception) { paquete }
        val m = resources.displayMetrics
        val cabecera = "App: $nombreApp ($paquete) · pantalla ${m.widthPixels}x${m.heightPixels}"
        val cuerpo = if (lineas.size > 250) lineas.take(250) + "… (${lineas.size - 250} elementos más)" else lineas
        return (listOf(cabecera) + cuerpo.ifEmpty { listOf("(sin elementos legibles: prueba con captura)") }).joinToString("\n")
    }

    private fun etiqueta(n: AccessibilityNodeInfo): String {
        val t = n.text?.toString().orEmpty()
        val d = n.contentDescription?.toString().orEmpty()
        val texto = if (t.isNotBlank() && d.isNotBlank() && d != t) "$t ($d)" else t.ifBlank { d }
        val pista = if (Build.VERSION.SDK_INT >= 26 && n.isShowingHintText) " [pista]" else ""
        return texto.replace(Regex("\\s+"), " ").trim().take(120) + if (texto.isNotBlank()) pista else ""
    }

    private fun textosDentro(n: AccessibilityNodeInfo): String {
        val partes = mutableListOf<String>()
        fun ir(x: AccessibilityNodeInfo) {
            if (partes.joinToString(" ").length > 120) return
            etiqueta(x).takeIf { it.isNotBlank() }?.let { partes += it }
            for (i in 0 until x.childCount) x.getChild(i)?.let(::ir)
        }
        for (i in 0 until n.childCount) n.getChild(i)?.let(::ir)
        return partes.joinToString(" · ").take(120)
    }

    private fun linea(num: Int, n: AccessibilityNodeInfo, texto: String, r: Rect): String {
        val clase = n.className?.toString()?.substringAfterLast('.').orEmpty()
        val tipo = when {
            n.isEditable -> "campo"
            clase.contains("Switch") -> "interruptor"
            clase.contains("CheckBox") || clase.contains("RadioButton") -> "casilla"
            clase.contains("Button") -> "botón"
            clase.contains("Image") -> "imagen"
            n.isScrollable -> "lista"
            n.isClickable -> "pulsable"
            else -> "texto"
        }
        val estado = buildList {
            if (n.isCheckable) add(if (n.isChecked) "activado" else "desactivado")
            if (n.isFocused) add("enfocado")
            if (n.isSelected) add("seleccionado")
            if (!n.isEnabled) add("inactivo")
            if (n.isLongClickable && !n.isClickable) add("pulsación larga")
            if (n.isScrollable) add("desplazable")
        }.joinToString(", ")
        return "[$num] $tipo" + (if (texto.isNotBlank()) " «$texto»" else "") + (if (estado.isNotEmpty()) " ($estado)" else "") + " @${r.centerX()},${r.centerY()}"
    }

    private fun elemento(a: JSONObject): Elemento? {
        if (!a.has("elemento")) return null
        val n = a.getInt("elemento")
        return elementos.getOrNull(n - 1) ?: throw Fallo("No hay elemento $n: vuelve a leer la pantalla.")
    }

    // ---------- Actuar ----------

    private suspend fun pulsar(a: JSONObject) {
        val larga = a.optBoolean("larga")
        val e = elemento(a)
        if (e != null) {
            e.nodo.refresh()
            val accion = if (larga) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK
            var n: AccessibilityNodeInfo? = e.nodo
            while (n != null) {
                if ((if (larga) n.isLongClickable else n.isClickable) && n.performAction(accion)) return
                n = n.parent
            }
            toque(e.rect.exactCenterX(), e.rect.exactCenterY(), larga)
            return
        }
        if (!a.has("x") || !a.has("y")) throw Fallo("Indica un elemento o unas coordenadas x, y.")
        toque(a.getDouble("x").toFloat(), a.getDouble("y").toFloat(), larga)
    }

    private suspend fun escribir(a: JSONObject) {
        val texto = a.getString("texto")
        val e = elemento(a)?.nodo
            ?: rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }
            ?: elementos.firstOrNull { it.nodo.isEditable }?.nodo
            ?: throw Fallo("No hay ningún campo de texto en pantalla.")
        e.refresh()
        if (!e.isFocused) { e.performAction(AccessibilityNodeInfo.ACTION_CLICK); e.performAction(AccessibilityNodeInfo.ACTION_FOCUS); delay(300); e.refresh() }
        val previo = if (a.optBoolean("anadir") && !(Build.VERSION.SDK_INT >= 26 && e.isShowingHintText)) e.text?.toString().orEmpty() else ""
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, previo + texto) }
        if (!e.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) throw Fallo("El campo no acepta texto.")
        if (a.optBoolean("enviar")) {
            delay(200)
            if (Build.VERSION.SDK_INT < 30 || !e.performAction(AccessibilityAction.ACTION_IME_ENTER.id))
                throw Fallo("Texto escrito, pero el teclado no permite enviar: pulsa el botón de enviar.")
        }
    }

    private suspend fun deslizar(a: JSONObject) {
        if (a.has("x1")) {
            gesto(a.getDouble("x1").toFloat(), a.getDouble("y1").toFloat(), a.getDouble("x2").toFloat(), a.getDouble("y2").toFloat(), a.optLong("ms", 300))
            return
        }
        val dir = a.optString("direccion", "abajo")
        elemento(a)?.let { e ->
            e.nodo.refresh()
            val accion = if (dir == "abajo" || dir == "derecha") AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            if (e.nodo.performAction(accion)) return
        }
        val m = resources.displayMetrics
        val (w, h) = m.widthPixels.toFloat() to m.heightPixels.toFloat()
        // «abajo» es ver lo que hay más abajo: el dedo sube
        when (dir) {
            "abajo" -> gesto(w / 2, h * .7f, w / 2, h * .3f)
            "arriba" -> gesto(w / 2, h * .3f, w / 2, h * .7f)
            "derecha" -> gesto(w * .8f, h / 2, w * .2f, h / 2)
            "izquierda" -> gesto(w * .2f, h / 2, w * .8f, h / 2)
            else -> throw Fallo("Dirección no válida: $dir")
        }
    }

    private fun boton(b: String) {
        val accion = when (b) {
            "atras" -> GLOBAL_ACTION_BACK
            "inicio" -> GLOBAL_ACTION_HOME
            "recientes" -> GLOBAL_ACTION_RECENTS
            "notificaciones" -> GLOBAL_ACTION_NOTIFICATIONS
            "ajustes_rapidos" -> GLOBAL_ACTION_QUICK_SETTINGS
            "bloquear" -> GLOBAL_ACTION_LOCK_SCREEN
            else -> throw Fallo("Botón no válido: $b")
        }
        if (!performGlobalAction(accion)) throw Fallo("El sistema no ha hecho «$b».")
    }

    private suspend fun toque(x: Float, y: Float, larga: Boolean) = gesto(x, y, x, y, if (larga) 800 else 60)

    private suspend fun gesto(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long = 300) {
        val camino = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(camino, 0, ms)).build()
        val ok = flotante.sinTapar { suspendCancellableCoroutine { c ->
            val hecho = dispatchGesture(g, object : GestureResultCallback() {
                override fun onCompleted(d: GestureDescription?) { if (c.isActive) c.resume(true) }
                override fun onCancelled(d: GestureDescription?) { if (c.isActive) c.resume(false) }
            }, null)
            if (!hecho && c.isActive) c.resume(false)
        } }
        if (!ok) throw Fallo("El sistema ha cancelado el gesto.")
    }

    // ---------- Captura ----------

    private suspend fun captura(): Triple<Boolean, String, String?> {
        if (Build.VERSION.SDK_INT < 30) throw Fallo("La captura necesita Android 11 o superior.")
        val bmp = flotante.sinTapar { suspendCancellableCoroutine<Bitmap?> { c ->
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(s: ScreenshotResult) {
                    val b = Bitmap.wrapHardwareBuffer(s.hardwareBuffer, s.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                    s.hardwareBuffer.close()
                    if (c.isActive) c.resume(b)
                }
                override fun onFailure(code: Int) { if (c.isActive) c.resume(null) }
            })
        } } ?: throw Fallo("No se ha podido capturar la pantalla (algunas apps lo impiden).")
        val escala = 2
        val peq = Bitmap.createScaledBitmap(bmp, bmp.width / escala, bmp.height / escala, true)
        val out = ByteArrayOutputStream()
        peq.compress(Bitmap.CompressFormat.JPEG, 70, out)
        val texto = "Captura a ${peq.width}x${peq.height} (la pantalla mide ${bmp.width}x${bmp.height}: multiplica por $escala las coordenadas de la imagen para pulsar)."
        return Triple(true, texto, Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP))
    }
}
