package com.mnldz.asistente

import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Aviso arriba de la pantalla mientras Claude maneja otra app: qué hace y un botón para pararlo.
// Al terminar enseña la respuesta (o que espera al usuario) y al tocarlo vuelve al panel.
class Flotante(private val srv: Control, private val scope: CoroutineScope) {
    private val wm = srv.getSystemService(WindowManager::class.java)
    private var vista: LinearLayout? = null
    private lateinit var texto: TextView
    private lateinit var boton: TextView
    private var desde: Int? = null
    private var ocultar: Job? = null
    private val lector get() = srv.app.lector

    init {
        scope.launch { srv.app.asistente.conv.collect { actualizar(it) } }
    }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), srv.resources.displayMetrics).toInt()

    // Claude ha empezado a usar la pantalla en este turno
    fun controlando() {
        val conv = srv.app.asistente.conv.value
        if (desde == null) desde = conv.mensajes.indexOfLast { it.rol == "user" }.coerceAtLeast(0)
        actualizar(conv)
    }

    private fun actualizar(conv: Conversacion) {
        val d = desde ?: return
        if (srv.app.alFrente.isNotEmpty()) { desde = null; quitar(); return }
        when {
            conv.avisos.isNotEmpty() -> mostrar("Claude necesita tu respuesta", "Abrir", ::abrirPanel)
            turnoTerminado(conv, d) -> {
                desde = null
                val respuesta = respuestaDesde(conv, d)
                mostrar(respuesta?.let(::paraLeer) ?: "Hecho.", "Abrir", ::abrirPanel)
                // En el coche, la respuesta se oye; si pregunta algo, se abre el panel escuchando
                val id = conv.mensajes.lastOrNull()?.id
                if (srv.app.enCoche.value && respuesta != null && id != srv.app.leido) {
                    srv.app.leido = id
                    lector.leer(respuesta, if (pregunta(respuesta)) ({ hablar() }) else null)
                }
                ocultar = scope.launch { delay(12_000); quitar() }
            }
            else -> {
                val herramienta = conv.mensajes.lastOrNull()?.bloques?.lastOrNull { it.tipo == "tool" }
                val que = herramienta?.let { "Claude usa el móvil · " + resumenHerramienta(it).substringAfter("movil · ") } ?: "Claude está usando el móvil…"
                mostrar(que, "Parar") { srv.app.asistente.interrumpir() }
            }
        }
    }

    private fun hablar() {
        quitar()
        srv.startActivity(Intent(srv, AsistenteRapido::class.java).setAction(ACCION_HABLAR).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun abrirPanel() {
        quitar()
        srv.startActivity(Intent(srv, AsistenteRapido::class.java).setAction(ACCION_VER).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun mostrar(t: String, b: String, alPulsar: () -> Unit) {
        ocultar?.cancel()
        if (vista == null) crear()
        texto.text = t
        boton.text = b
        boton.setOnClickListener { alPulsar() }
        vista!!.setOnClickListener { if (b == "Abrir") abrirPanel() }
    }

    private fun crear() {
        val c = srv.getColor(R.color.acento)
        vista = LinearLayout(srv).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(8), dp(10))
            background = GradientDrawable().apply { setColor(0xF2262624.toInt()); cornerRadius = dp(22).toFloat(); setStroke(dp(1), 0x33FFFFFF) }
            elevation = dp(6).toFloat()
            addView(ImageView(srv).apply { setImageResource(R.drawable.ic_chispa) }, LinearLayout.LayoutParams(dp(26), dp(26)))
            texto = TextView(srv).apply {
                setTextColor(0xFFECEAE3.toInt()); textSize = 14f; maxLines = 3; ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(8), 0, dp(8), 0)
            }
            addView(texto, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            boton = TextView(srv).apply {
                setTextColor(c); textSize = 14f; setPadding(dp(12), dp(6), dp(12), dp(6))
                paint.isFakeBoldText = true
            }
            addView(boton)
            addView(TextView(srv).apply {
                text = "✕"; setTextColor(0xFF9A988F.toInt()); textSize = 15f; setPadding(dp(8), dp(6), dp(6), dp(6))
                setOnClickListener { desde = null; quitar() }
            })
        }
        val p = WindowManager.LayoutParams(
            srv.resources.displayMetrics.widthPixels - dp(24), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = dp(36) }
        wm.addView(vista, p)
    }

    fun quitar() {
        ocultar?.cancel()
        if (srv.app.alFrente.isNotEmpty()) lector.callar()
        vista?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        vista = null
    }

    // Se esconde mientras se toca o se captura la pantalla, para no tapar lo que hay debajo
    suspend fun <T> sinTapar(bloque: suspend () -> T): T {
        vista?.visibility = View.GONE
        try { if (vista != null) delay(80); return bloque() } finally { vista?.visibility = View.VISIBLE }
    }
}
