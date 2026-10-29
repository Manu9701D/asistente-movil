package com.mnldz.asistente

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.AlarmClock
import android.provider.CalendarContract
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.math.roundToInt

// ---------- Ubicación sin pedir permisos (desde receptores y servicios) ----------

object Sitio {
    fun permiso(ctx: Context) = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        .any { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }

    // «Permitir todo el tiempo»: sin él, Android no da la ubicación con la app cerrada
    fun siempre(ctx: Context) = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun ultima(ctx: Context): Location? = try {
        val lm = ctx.getSystemService(LocationManager::class.java)
        lm.allProviders.mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
    } catch (_: Exception) { null }

    @SuppressLint("MissingPermission")
    suspend fun actual(ctx: Context, espera: Long = 15_000): Location? {
        if (!permiso(ctx)) return null
        val lm = ctx.getSystemService(LocationManager::class.java)
        if (!lm.isLocationEnabled) return null
        val proveedor = when {
            Build.VERSION.SDK_INT >= 31 && lm.hasProvider(LocationManager.FUSED_PROVIDER) -> LocationManager.FUSED_PROVIDER
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) && ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED -> LocationManager.GPS_PROVIDER
            else -> LocationManager.NETWORK_PROVIDER
        }
        val fresca = if (Build.VERSION.SDK_INT >= 30) try {
            withTimeoutOrNull(espera) {
                suspendCancellableCoroutine<Location?> { c -> lm.getCurrentLocation(proveedor, null, ctx.mainExecutor) { if (c.isActive) c.resume(it) } }
            }
        } catch (_: Exception) { null } else null
        return fresca ?: ultima(ctx)
    }

    @Suppress("DEPRECATION")
    suspend fun direccion(ctx: Context, lat: Double, lon: Double, corta: Boolean = false): String? = withContext(Dispatchers.IO) {
        try {
            val d = Geocoder(ctx, Locale("es", "ES")).getFromLocation(lat, lon, 1)?.firstOrNull() ?: return@withContext null
            if (corta) listOfNotNull(d.thoroughfare, d.locality ?: d.subAdminArea).joinToString(", ").ifBlank { null }
            else d.getAddressLine(0)
        } catch (_: Exception) { null }
    }

    @Suppress("DEPRECATION")
    suspend fun buscar(ctx: Context, texto: String): Pair<Double, Double>? = withContext(Dispatchers.IO) {
        try {
            Geocoder(ctx, Locale("es", "ES")).getFromLocationName(texto, 1)?.firstOrNull()?.let { it.latitude to it.longitude }
        } catch (_: Exception) { null }
    }

    fun metros(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val r = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, r)
        return r[0]
    }
}

// ---------- Contexto de cada mensaje ----------

// Lo que se añade solo a lo que se manda a Claude: fecha y hora, sitio aproximado, batería y si va en el
// coche. El sitio sale de la última ubicación conocida (sin encender el GPS) y se guarda unos minutos.
class Contexto(private val app: AppAsistente) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lugar: String? = null
    private var cuando = 0L
    private var donde: Location? = null

    suspend fun texto(): String {
        val ahora = java.time.ZonedDateTime.now()
        val partes = mutableListOf(ahora.format(DateTimeFormatter.ofPattern("EEEE d 'de' MMMM 'de' yyyy, H:mm", Locale("es", "ES"))) + " (${ahora.zone.id})")
        // El geocodificador va por red: no se espera más de 1,5 s (si no, queda para el siguiente)
        val trabajo = scope.async { refrescar() }
        withTimeoutOrNull(1500) { trabajo.await() }
        lugar?.let { partes += "cerca de $it" }
        val bm = app.getSystemService(android.os.BatteryManager::class.java)
        partes += "batería ${bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)} %" + if (bm.isCharging) " (cargando)" else ""
        if (app.enCoche.value || app.auto.conectado.value) partes += "en el coche"
        return partes.joinToString(" · ")
    }

    private suspend fun refrescar() {
        val l = Sitio.ultima(app) ?: return
        if (lugar != null && System.currentTimeMillis() - cuando < 5 * 60_000 && (donde?.distanceTo(l) ?: Float.MAX_VALUE) < 300) return
        Sitio.direccion(app, l.latitude, l.longitude, corta = true)?.let { lugar = it; cuando = System.currentTimeMillis(); donde = l }
    }
}

// ---------- Avisos por iniciativa propia ----------

// Lo que el móvil hace solo, sin que se pida: resumen de la mañana, «sal ya» para los eventos con
// sitio, repaso de la noche (¿hay alarma para lo de mañana?), recordatorios (a una hora, al subir o
// bajar del coche, al llegar o salir de un sitio) y dónde se ha aparcado.
object Proactivo {
    val HERRAMIENTAS = setOf("recordatorio", "recordatorios", "aparcamiento")

    const val RESUMEN = "com.mnldz.asistente.RESUMEN"
    const val NOCHE = "com.mnldz.asistente.NOCHE"
    const val REPASO = "com.mnldz.asistente.REPASO"
    const val SALIDA = "com.mnldz.asistente.SALIDA"
    const val RECORDATORIO = "com.mnldz.asistente.RECORDATORIO"
    const val LUGAR = "com.mnldz.asistente.LUGAR"

    private const val CANAL = "avisos"
    private const val CANAL_SILENCIO = "avisos_silencio"
    private const val ANTELACION_MIN = 10 // margen sobre el tiempo de viaje
    private val hora = DateTimeFormatter.ofPattern("H:mm")
    private val http by lazy { OkHttpClient.Builder().callTimeout(12, TimeUnit.SECONDS).build() }

    private class Fallo(msg: String) : Exception(msg)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("proactivo", Context.MODE_PRIVATE)

    // ---------- Planificar ----------

    private fun pendiente(ctx: Context, accion: String, codigo: Int, mutable: Boolean = false, extras: Intent.() -> Unit = {}): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, codigo, Intent(ctx, Despertador::class.java).setAction(accion).apply(extras),
            PendingIntent.FLAG_UPDATE_CURRENT or (if (mutable && Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE),
        )

    private fun exacta(ctx: Context, cuando: Long, pi: PendingIntent) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        try {
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cuando, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cuando, pi)
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cuando, pi)
        }
    }

    // «HH:MM» → la próxima vez que lo sea; vacío o mal escrito, nunca
    private fun proxima(hhmm: String): Long? {
        val t = try { LocalTime.parse(hhmm.trim().padStart(5, '0')) } catch (_: Exception) { return null }
        val zona = ZoneId.systemDefault()
        var d = LocalDate.now().atTime(t).atZone(zona)
        if (!d.toInstant().isAfter(Instant.now())) d = d.plusDays(1)
        return d.toInstant().toEpochMilli()
    }

    // Programa todo de nuevo (al arrancar la app o el móvil, al cambiar ajustes y tras cada aviso diario)
    fun planificar(ctx: Context) {
        val app = ctx.app
        val am = ctx.getSystemService(AlarmManager::class.java)
        for ((accion, hhmm, codigo) in listOf(Triple(RESUMEN, app.ajustes.horaResumen, 1), Triple(NOCHE, app.ajustes.horaNoche, 2))) {
            val pi = pendiente(ctx, accion, codigo)
            val t = proxima(hhmm)
            if (t != null) exacta(ctx, t, pi) else am.cancel(pi)
        }
        val repaso = pendiente(ctx, REPASO, 3)
        if (app.ajustes.salYa) am.setInexactRepeating(
            AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 60_000, AlarmManager.INTERVAL_HALF_HOUR, repaso,
        ) else am.cancel(repaso)
        recordatorios(ctx).forEach { programar(ctx, it) }
    }

    // ---------- Notificaciones ----------

    private fun canales(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CANAL, "Avisos del asistente", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CANAL_SILENCIO, "Avisos sin sonido (aparcamiento)", NotificationManager.IMPORTANCE_LOW))
    }

    private fun abrirPanel(ctx: Context, codigo: Int) = PendingIntent.getActivity(
        ctx, codigo, Intent(ctx, AsistenteRapido::class.java).setAction(ACCION_HABLAR).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun abrirIntent(ctx: Context, codigo: Int, i: Intent) =
        PendingIntent.getActivity(ctx, codigo, i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun notificar(
        ctx: Context, id: Int, titulo: String, texto: String, silencio: Boolean = false,
        acciones: List<Pair<String, PendingIntent>> = emptyList(), alPulsar: PendingIntent? = null,
    ) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        canales(ctx)
        val n = NotificationCompat.Builder(ctx, if (silencio) CANAL_SILENCIO else CANAL)
            .setSmallIcon(R.drawable.ic_chispa).setContentTitle(titulo).setContentText(texto)
            .setStyle(NotificationCompat.BigTextStyle().bigText(texto))
            .setContentIntent(alPulsar ?: abrirPanel(ctx, id)).setAutoCancel(true)
            .apply { acciones.forEach { (t, pi) -> addAction(0, t, pi) } }
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(id, n)
    }

    // En el coche, además se dice en voz alta
    private fun decir(ctx: Context, texto: String) {
        val app = ctx.app
        if (app.enCoche.value) app.lector.leer(texto)
    }

    // ---------- Calendario ----------

    private class Evento(val id: Long, val titulo: String, val inicio: Long, val fin: Long, val todoElDia: Boolean, val lugar: String)

    private fun eventos(ctx: Context, desde: Long, hasta: Long): List<Evento> {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return emptyList()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, desde); ContentUris.appendId(it, hasta)
        }.build()
        val cols = arrayOf(
            CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION,
        )
        val l = mutableListOf<Evento>()
        try {
            ctx.contentResolver.query(
                uri, cols,
                "${CalendarContract.Instances.VISIBLE} = 1 AND ${CalendarContract.Instances.SELF_ATTENDEE_STATUS} != ${CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED}",
                null, "${CalendarContract.Instances.BEGIN} ASC",
            )?.use { c ->
                while (c.moveToNext()) l += Evento(c.getLong(0), c.getString(1) ?: "(sin título)", c.getLong(2), c.getLong(3), c.getInt(4) == 1, c.getString(5).orEmpty())
            }
        } catch (e: Exception) { android.util.Log.w("Proactivo", "Calendario", e) }
        return l
    }

    private fun aHora(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(hora)

    // ---------- Servicios web gratuitos ----------

    private suspend fun json(url: String): JSONObject? = withContext(Dispatchers.IO) {
        try {
            http.newCall(Request.Builder().url(url).header("User-Agent", "AsistenteMovil/1.0").build()).execute().use { r ->
                if (r.isSuccessful) JSONObject(r.body!!.string()) else null
            }
        } catch (_: Exception) { null }
    }

    // Minutos en coche (OSRM, sin tráfico)
    private suspend fun minutosEnCoche(oLat: Double, oLon: Double, dLat: Double, dLon: Double): Int? {
        val j = json("https://router.project-osrm.org/route/v1/driving/$oLon,$oLat;$dLon,$dLat?overview=false") ?: return null
        val s = j.optJSONArray("routes")?.optJSONObject(0)?.optDouble("duration") ?: return null
        return if (s.isNaN()) null else (s / 60).roundToInt()
    }

    private fun cielo(codigo: Int) = when (codigo) {
        0 -> "despejado"; 1, 2 -> "poco nuboso"; 3 -> "nublado"; 45, 48 -> "niebla"
        in 51..57 -> "llovizna"; in 61..67 -> "lluvia"; in 71..77 -> "nieve"; in 80..82 -> "chubascos"
        85, 86 -> "chubascos de nieve"; in 95..99 -> "tormenta"; else -> "variable"
    }

    private suspend fun tiempoHoy(lat: Double, lon: Double): String? {
        val j = json("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max&timezone=auto&forecast_days=1")
            ?.optJSONObject("daily") ?: return null
        val max = j.optJSONArray("temperature_2m_max")?.optDouble(0)?.roundToInt() ?: return null
        val min = j.optJSONArray("temperature_2m_min")?.optDouble(0)?.roundToInt() ?: return null
        val codigo = j.optJSONArray("weather_code")?.optInt(0) ?: 0
        val lluvia = j.optJSONArray("precipitation_probability_max")?.optInt(0) ?: 0
        return "$min–$max °C, ${cielo(codigo)}" + if (lluvia >= 20) ", lluvia $lluvia %" else ""
    }

    // ---------- Resumen de la mañana ----------

    suspend fun resumen(ctx: Context) {
        val zona = ZoneId.systemDefault()
        val hoy = LocalDate.now()
        val desde = hoy.atStartOfDay(zona).toInstant().toEpochMilli()
        val hasta = hoy.plusDays(1).atStartOfDay(zona).toInstant().toEpochMilli()
        val evs = eventos(ctx, desde, hasta)
        val lineas = mutableListOf<String>()
        lineas += if (evs.isEmpty()) "Hoy no tienes nada en el calendario."
            else evs.joinToString("\n") { e -> (if (e.todoElDia) "Todo el día" else aHora(e.inicio)) + ": " + e.titulo + if (e.lugar.isNotBlank()) " (${e.lugar})" else "" }
        Sitio.actual(ctx, 10_000)?.let { l -> tiempoHoy(l.latitude, l.longitude)?.let { lineas += "Tiempo: $it." } }
        val recs = recordatorios(ctx).filter { it.optString("cuando") == "hora" && it.optLong("t") in desde until hasta }
        if (recs.isNotEmpty()) lineas += "Recordatorios: " + recs.joinToString(" · ") { "${aHora(it.optLong("t"))} ${it.optString("texto")}" }
        notificar(ctx, 101, "Buenos días", lineas.joinToString("\n"), acciones = listOf("Preguntar" to abrirPanel(ctx, 1101)))
    }

    // ---------- Repaso de la noche: ¿hay alarma para lo primero de mañana? ----------

    suspend fun noche(ctx: Context) {
        val zona = ZoneId.systemDefault()
        val manana = LocalDate.now().plusDays(1)
        val desde = manana.atStartOfDay(zona).toInstant().toEpochMilli()
        val primero = eventos(ctx, desde, manana.atTime(12, 0).atZone(zona).toInstant().toEpochMilli())
            .firstOrNull { !it.todoElDia && it.inicio >= desde } ?: return
        val alarma = ctx.getSystemService(AlarmManager::class.java).nextAlarmClock?.triggerTime
        if (alarma != null && alarma < primero.inicio) return
        // Propone despertarse hora y media antes, en múltiplos de 5 min
        val despertar = Instant.ofEpochMilli(primero.inicio - 90 * 60_000).atZone(zona).toLocalTime().let { it.withMinute(it.minute / 5 * 5) }
        val poner = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, despertar.hour).putExtra(AlarmClock.EXTRA_MINUTES, despertar.minute)
            .putExtra(AlarmClock.EXTRA_MESSAGE, primero.titulo).putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        notificar(
            ctx, 102, "Mañana a las ${aHora(primero.inicio)}: ${primero.titulo}",
            "No tienes ninguna alarma antes." + if (primero.lugar.isNotBlank()) " Es en ${primero.lugar}." else "",
            acciones = listOf("Alarma a las ${despertar.format(hora)}" to abrirIntent(ctx, 1102, poner)),
        )
    }

    // ---------- «Sal ya» ----------

    suspend fun salidas(ctx: Context) {
        if (!ctx.app.ajustes.salYa) return
        val ahora = System.currentTimeMillis()
        val evs = eventos(ctx, ahora, ahora + 4 * 3600_000).filter { !it.todoElDia && it.lugar.isNotBlank() && it.inicio > ahora }
        if (evs.isEmpty()) return
        val p = prefs(ctx)
        val avisadas = p.getStringSet("avisadas", emptySet())!!.toMutableSet()
        val origen = Sitio.actual(ctx, 10_000) ?: return
        var siguiente: Long? = null
        for (e in evs) {
            val clave = "${e.id}:${e.inicio}"
            if (clave in avisadas) continue
            val (lat, lon) = Sitio.buscar(ctx, e.lugar) ?: continue
            val distancia = Sitio.metros(origen.latitude, origen.longitude, lat, lon)
            if (distancia < 400 || distancia > 400_000) { avisadas += clave; continue } // ya está allí, o no es un viaje
            val min = minutosEnCoche(origen.latitude, origen.longitude, lat, lon) ?: (distancia / 1000 * 1.4 / 40 * 60).roundToInt()
            val salir = e.inicio - ((min * 1.15).roundToInt() + ANTELACION_MIN) * 60_000L
            if (salir - ahora <= 5 * 60_000) {
                avisadas += clave
                val texto = "Sal ya hacia ${e.titulo}: a las ${aHora(e.inicio)} en ${e.lugar}, unos $min min en coche."
                val ir = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${Uri.encode(e.lugar)}"))
                notificar(ctx, 200 + (e.id % 500).toInt(), "Sal ya: ${e.titulo}", texto, acciones = listOf("Ir" to abrirIntent(ctx, 1200 + (e.id % 500).toInt(), ir)))
                decir(ctx, texto)
            } else siguiente = minOf(siguiente ?: salir, salir)
        }
        p.edit().putStringSet("avisadas", avisadas.toList().takeLast(100).toSet()).apply()
        // El repaso es cada media hora y Android lo retrasa: la salida que toca antes va con alarma exacta
        siguiente?.let { if (it - ahora < 45 * 60_000) exacta(ctx, it, pendiente(ctx, SALIDA, 4)) }
    }

    // ---------- Recordatorios ----------

    private fun recordatorios(ctx: Context): List<JSONObject> = try {
        JSONArray(prefs(ctx).getString("recordatorios", "[]")).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
    } catch (_: Exception) { emptyList() }

    private fun guardar(ctx: Context, l: List<JSONObject>) = prefs(ctx).edit().putString("recordatorios", JSONArray(l).toString()).apply()

    private fun pendienteLugar(ctx: Context, n: Int) = pendiente(ctx, LUGAR, 5000 + n, mutable = true) { putExtra("n", n) }

    @SuppressLint("MissingPermission")
    private fun programar(ctx: Context, r: JSONObject) {
        val n = r.optInt("n")
        when (r.optString("cuando")) {
            "hora" -> exacta(ctx, r.optLong("t"), pendiente(ctx, RECORDATORIO, 1000 + n) { putExtra("n", n) })
            "llegar", "salir" -> try {
                ctx.getSystemService(LocationManager::class.java).addProximityAlert(r.optDouble("lat"), r.optDouble("lon"), r.optInt("radio", 150).toFloat(), -1, pendienteLugar(ctx, n))
            } catch (e: SecurityException) { android.util.Log.w("Proactivo", "Sin permiso de ubicación para el recordatorio $n", e) }
        }
    }

    private fun quitar(ctx: Context, n: Int): JSONObject? {
        val l = recordatorios(ctx).toMutableList()
        val r = l.firstOrNull { it.optInt("n") == n } ?: return null
        l.remove(r)
        guardar(ctx, l)
        ctx.getSystemService(AlarmManager::class.java).cancel(pendiente(ctx, RECORDATORIO, 1000 + n) { putExtra("n", n) })
        if (r.optString("cuando") in setOf("llegar", "salir")) try {
            ctx.getSystemService(LocationManager::class.java).removeProximityAlert(pendienteLugar(ctx, n))
        } catch (_: Exception) {}
        return r
    }

    private fun disparar(ctx: Context, r: JSONObject) {
        val n = r.optInt("n")
        quitar(ctx, n)
        val texto = r.optString("texto")
        notificar(ctx, 1000 + n, "Recordatorio", texto)
        decir(ctx, "Recordatorio: $texto")
    }

    private fun describir(r: JSONObject): String {
        val cuando = when (r.optString("cuando")) {
            "hora" -> Instant.ofEpochMilli(r.optLong("t")).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("EEEE d 'de' MMMM, H:mm", Locale("es", "ES")))
            "subir_coche" -> "al subir al coche"
            "bajar_coche" -> "al bajar del coche"
            "llegar" -> "al llegar a ${r.optString("lugar")}"
            else -> "al salir de ${r.optString("lugar")}"
        }
        return "[${r.optInt("n")}] ${r.optString("texto")} — $cuando"
    }

    private fun instante(s: String): Long {
        val zona = ZoneId.systemDefault()
        return try {
            try { OffsetDateTime.parse(s).toInstant().toEpochMilli() } catch (_: Exception) { LocalDateTime.parse(s).atZone(zona).toInstant().toEpochMilli() }
        } catch (_: Exception) {
            throw Fallo("Hora no válida: «$s». Usa ISO 8601 en hora local, p. ej. 2026-10-09T09:00.")
        }
    }

    // ---------- Herramientas ----------

    suspend fun ejecutar(ctx: Context, nombre: String, a: JSONObject): Pair<Boolean, String> = try {
        true to when (nombre) {
            "recordatorio" -> crear(ctx, a)
            "recordatorios" -> if (a.has("borrar")) {
                quitar(ctx, a.getInt("borrar"))?.let { "Borrado: ${it.optString("texto")}" } ?: throw Fallo("No hay recordatorio ${a.getInt("borrar")}.")
            } else recordatorios(ctx).joinToString("\n", transform = ::describir).ifBlank { "No hay recordatorios pendientes." }
            "aparcamiento" -> aparcamiento(ctx)
            else -> throw Fallo("Herramienta desconocida: $nombre")
        }
    } catch (e: Fallo) {
        false to (e.message ?: "Error.")
    } catch (e: Exception) {
        false to "${e.javaClass.simpleName}: ${e.message}"
    }

    private suspend fun crear(ctx: Context, a: JSONObject): String {
        val texto = a.optString("texto").trim().ifBlank { throw Fallo("Falta qué recordar.") }
        val cuando = a.optString("cuando")
        val l = recordatorios(ctx).toMutableList()
        val n = (l.maxOfOrNull { it.optInt("n") } ?: 0) + 1
        val r = JSONObject().put("n", n).put("texto", texto).put("cuando", cuando).put("creado", System.currentTimeMillis())
        var nota = ""
        when (cuando) {
            "hora" -> {
                val t = instante(a.optString("hora").ifBlank { throw Fallo("Falta la hora.") })
                if (t <= System.currentTimeMillis()) throw Fallo("Esa hora ya ha pasado.")
                r.put("t", t)
            }
            "subir_coche", "bajar_coche" -> {}
            "llegar", "salir" -> {
                val lugar = a.optString("lugar").trim().ifBlank { throw Fallo("Falta el sitio.") }
                if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
                    throw Fallo("Falta el permiso de ubicación precisa de «Asistente»: pide al usuario que lo dé en Ajustes de la app (sección Avisos).")
                val (lat, lon) = Sitio.buscar(ctx, lugar) ?: throw Fallo("No encuentro «$lugar» en el mapa: pide una dirección más concreta.")
                r.put("lugar", lugar).put("lat", lat).put("lon", lon).put("radio", a.optInt("radio", 150).coerceAtLeast(50))
                if (!Sitio.siempre(ctx)) nota = "\nOjo: la ubicación de «Asistente» no está en «Permitir todo el tiempo»; sin eso Android no avisará con la app cerrada (díselo al usuario: Ajustes de la app › Avisos)."
            }
            else -> throw Fallo("«cuando» no válido: $cuando")
        }
        l += r
        guardar(ctx, l)
        programar(ctx, r)
        return "Recordatorio creado: ${describir(r)}$nota"
    }

    // ---------- Coche: recordatorios y aparcamiento ----------

    // true si había alguno (y se está leyendo)
    fun alSubir(ctx: Context): Boolean {
        val l = recordatorios(ctx).filter { it.optString("cuando") == "subir_coche" }
        l.forEach { disparar(ctx, it) }
        return l.isNotEmpty()
    }

    suspend fun alBajar(ctx: Context) {
        recordatorios(ctx).filter { it.optString("cuando") == "bajar_coche" }.forEach { disparar(ctx, it) }
        val l = Sitio.actual(ctx, 15_000) ?: return
        val dir = Sitio.direccion(ctx, l.latitude, l.longitude)
        prefs(ctx).edit().putString(
            "aparcamiento",
            JSONObject().put("lat", l.latitude).put("lon", l.longitude).put("precision", l.accuracy.roundToInt())
                .put("direccion", dir).put("t", System.currentTimeMillis()).toString(),
        ).apply()
        val ir = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${l.latitude},${l.longitude}&mode=w"))
        notificar(
            ctx, 103, "Coche aparcado", dir ?: "%.5f, %.5f".format(Locale.ROOT, l.latitude, l.longitude), silencio = true,
            acciones = listOf("Llévame" to abrirIntent(ctx, 1103, ir)),
        )
    }

    private fun aparcamiento(ctx: Context): String {
        val j = prefs(ctx).getString("aparcamiento", null)?.let { JSONObject(it) }
            ?: return "No hay ningún aparcamiento guardado (se guarda al desconectarse el coche, si la ubicación está en «Permitir todo el tiempo»)."
        val min = (System.currentTimeMillis() - j.optLong("t")) / 60_000
        val hace = when { min < 60 -> "hace $min min"; min < 48 * 60 -> "hace ${min / 60} h"; else -> "hace ${min / 1440} días" }
        return "Aparcado $hace (${Instant.ofEpochMilli(j.optLong("t")).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d/M H:mm"))}) en " +
            "${j.optString("direccion").ifBlank { "dirección desconocida" }}: lat ${"%.6f".format(Locale.ROOT, j.optDouble("lat"))}, " +
            "lon ${"%.6f".format(Locale.ROOT, j.optDouble("lon"))} (±${j.optInt("precision")} m)."
    }

    // ---------- Al saltar una alarma ----------

    suspend fun atender(ctx: Context, i: Intent) {
        when (i.action) {
            RESUMEN -> { planificar(ctx); resumen(ctx) }
            NOCHE -> { planificar(ctx); noche(ctx) }
            REPASO, SALIDA -> salidas(ctx)
            RECORDATORIO -> recordatorios(ctx).firstOrNull { it.optInt("n") == i.getIntExtra("n", -1) }?.let { disparar(ctx, it) }
            LUGAR -> {
                val r = recordatorios(ctx).firstOrNull { it.optInt("n") == i.getIntExtra("n", -1) } ?: return
                val entrando = i.getBooleanExtra(LocationManager.KEY_PROXIMITY_ENTERING, false)
                if (entrando == (r.optString("cuando") == "llegar")) disparar(ctx, r)
            }
        }
    }
}

// Alarmas de los avisos (solo las manda la propia app)
class Despertador : BroadcastReceiver() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onReceive(ctx: Context, i: Intent) {
        val fin = goAsync()
        scope.launch {
            try { Proactivo.atender(ctx.applicationContext, i) }
            catch (e: Exception) { android.util.Log.w("Despertador", "Aviso ${i.action}", e) }
            finally { fin.finish() }
        }
    }
}

// Al encender el móvil o actualizar la app, Android borra las alarmas: se vuelven a poner
class Arranque : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        if (i.action == Intent.ACTION_BOOT_COMPLETED || i.action == Intent.ACTION_MY_PACKAGE_REPLACED) Proactivo.planificar(ctx.applicationContext)
    }
}
