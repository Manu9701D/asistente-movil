package com.mnldz.asistente

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Settings
import android.telephony.SmsManager
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.roundToInt

// Lo que Claude puede hacer en el móvil (herramientas mcp__movil__*, definidas en servidor/movil-mcp.mjs).
// Cada acción devuelve un texto para Claude; un fallo lanza Fallo con la explicación.
class Acciones(private val act: ComponentActivity) {
    private class Fallo(msg: String) : Exception(msg)

    private var permisosPedidos: CompletableDeferred<Map<String, Boolean>>? = null
    private val lanzador = act.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permisosPedidos?.complete(it)
    }

    suspend fun ejecutar(nombre: String, a: JSONObject): Pair<Boolean, String> = withContext(Dispatchers.Main) {
        try {
            true to when (nombre) {
                "info" -> info()
                "alarma" -> alarma(a)
                "temporizador" -> temporizador(a)
                "ver_alarmas" -> abrir(Intent(AlarmClock.ACTION_SHOW_ALARMS), "Alarmas abiertas.")
                "apps" -> apps(a.optString("filtro"))
                "abrir_app" -> abrirApp(a.getString("nombre"))
                "abrir_url" -> abrir(Intent(Intent.ACTION_VIEW, Uri.parse(a.getString("url"))), "Abierto.")
                "mapa" -> mapa(a)
                "llamar" -> if (a.optBoolean("directo")) {
                    pedir(Manifest.permission.CALL_PHONE)
                    abrir(Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(a.getString("numero")))), "Llamando a ${a.getString("numero")}.")
                } else abrir(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(a.getString("numero")))), "Marcador abierto con el número; falta que el usuario pulse llamar.")
                "sms" -> if (a.optBoolean("enviar")) enviarSms(a.getString("numero"), a.getString("texto"))
                else abrir(
                    Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(a.getString("numero")))).putExtra("sms_body", a.optString("texto")),
                    "SMS preparado; falta que el usuario lo envíe.",
                )
                "compartir" -> abrir(
                    Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, a.getString("texto")), null),
                    "Menú de compartir abierto.",
                )
                "ajustes" -> ajustes(a.getString("pantalla"))
                "volumen" -> volumen(a.getString("tipo"), a.getInt("nivel"))
                "modo_sonido" -> modoSonido(a.getString("modo"))
                "no_molestar" -> noMolestar(a.getBoolean("activar"))
                "brillo" -> brillo(a)
                "linterna" -> linterna(a.getBoolean("encender"))
                "portapapeles" -> portapapeles(a.optString("copiar").takeIf { a.has("copiar") })
                "notificacion" -> notificacion(a.getString("titulo"), a.optString("texto"))
                "calendario" -> calendario(a)
                "crear_evento" -> crearEvento(a)
                "contactos" -> contactos(a.getString("nombre"))
                "ubicacion" -> ubicacion()
                else -> throw Fallo("Herramienta desconocida: $nombre")
            }
        } catch (e: Fallo) {
            false to (e.message ?: "Error")
        } catch (e: SecurityException) {
            false to "Android no lo permite: ${e.message}"
        } catch (e: Exception) {
            false to "${e.javaClass.simpleName}: ${e.message}"
        }
    }

    // ---------- Utilidades ----------
    private val enPrimerPlano get() = act.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    private fun abrir(intent: Intent, ok: String): String {
        // Android no deja abrir pantallas desde segundo plano
        // (con el control de pantalla activo, Android sí lo deja)
        if (!enPrimerPlano && Control.activo == null) throw Fallo("El usuario ha salido de la app Asistente y Android no deja abrir pantallas desde segundo plano: pídele que vuelva a la app.")
        try {
            act.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            throw Fallo("No hay ninguna app que pueda abrir esto.")
        }
        return ok
    }

    private suspend fun enviarSms(numero: String, texto: String): String {
        if (texto.isBlank()) throw Fallo("Falta el texto del SMS.")
        pedir(Manifest.permission.SEND_SMS)
        val sm = if (Build.VERSION.SDK_INT >= 31) act.getSystemService(SmsManager::class.java)
            else @Suppress("DEPRECATION") SmsManager.getDefault()
        sm.sendMultipartTextMessage(numero, null, sm.divideMessage(texto), null, null)
        return "SMS enviado a $numero: «$texto»."
    }

    private fun concedido(p: String) = ContextCompat.checkSelfPermission(act, p) == PackageManager.PERMISSION_GRANTED

    // Pide permisos de Android si faltan; con `alguno`, basta con uno (p. ej. ubicación aproximada)
    private suspend fun pedir(vararg permisos: String, alguno: Boolean = false) {
        val faltan = permisos.filterNot(::concedido)
        if (faltan.isEmpty() || (alguno && faltan.size < permisos.size)) return
        if (!act.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
            throw Fallo("Falta un permiso de Android y la app no está en primer plano para pedirlo: pide al usuario que la abra.")
        val d = CompletableDeferred<Map<String, Boolean>>()
        permisosPedidos = d
        lanzador.launch(faltan.toTypedArray())
        val r = d.await()
        val ok = if (alguno) permisos.any { concedido(it) } else faltan.all { r[it] == true }
        if (!ok) throw Fallo("El usuario no ha dado el permiso de Android necesario (${faltan.joinToString { it.substringAfterLast('.') }}).")
    }

    private fun sinAcentos(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}"), "").lowercase(Locale.ROOT)

    private fun accesoNoMolestar(): NotificationManager {
        val nm = act.getSystemService(NotificationManager::class.java)
        if (!nm.isNotificationPolicyAccessGranted) {
            if (enPrimerPlano) act.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            throw Fallo("Hace falta que el usuario dé a «Asistente» acceso a No molestar en la pantalla que se ha abierto; después, vuelve a intentarlo.")
        }
        return nm
    }

    // ---------- Acciones ----------
    private fun info(): String {
        val bm = act.getSystemService(BatteryManager::class.java)
        val cm = act.getSystemService(ConnectivityManager::class.java)
        val am = act.getSystemService(AudioManager::class.java)
        val nm = act.getSystemService(NotificationManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val red = when {
            caps == null -> "sin conexión"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "datos móviles"
            else -> "otra"
        }
        fun pct(s: Int) = (am.getStreamVolume(s) * 100f / am.getStreamMaxVolume(s)).roundToInt()
        val cr = act.contentResolver
        val brillo = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, -1)
        val auto = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, 0) == 1
        val st = StatFs(Environment.getDataDirectory().path)
        val gb = { b: Long -> "%.1f GB".format(b / 1e9) }
        return buildString {
            appendLine("Hora: ${ZonedDateTime.now().format(DateTimeFormatter.ofPattern("EEEE d 'de' MMMM yyyy, HH:mm (VV)", Locale("es", "ES")))}")
            appendLine("Batería: ${bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)} %${if (bm.isCharging) ", cargando" else ""}")
            appendLine("Red: $red")
            appendLine("Volumen: multimedia ${pct(AudioManager.STREAM_MUSIC)} %, timbre ${pct(AudioManager.STREAM_RING)} %, notificaciones ${pct(AudioManager.STREAM_NOTIFICATION)} %, alarma ${pct(AudioManager.STREAM_ALARM)} %")
            appendLine("Modo de sonido: ${when (am.ringerMode) { AudioManager.RINGER_MODE_SILENT -> "silencio"; AudioManager.RINGER_MODE_VIBRATE -> "vibración"; else -> "sonido" }}")
            appendLine("No molestar: ${if (nm.currentInterruptionFilter > NotificationManager.INTERRUPTION_FILTER_ALL) "activado" else "desactivado"}")
            appendLine("Brillo: ${if (brillo >= 0) "${brillo * 100 / 255} %" else "?"}${if (auto) " (automático)" else ""}")
            appendLine("Almacenamiento: ${gb(st.availableBytes)} libres de ${gb(st.totalBytes)}")
            append("Móvil: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}")
        }
    }

    private fun alarma(a: JSONObject): String {
        val h = a.getInt("hora")
        val m = a.getInt("minuto")
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, h).putExtra(AlarmClock.EXTRA_MINUTES, m)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        a.optString("mensaje").takeIf { it.isNotBlank() }?.let { i.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        val dias = a.optJSONArray("dias")
        if (dias != null && dias.length() > 0) {
            // 1 lunes … 7 domingo → Calendar.MONDAY (2) … Calendar.SUNDAY (1)
            i.putExtra(AlarmClock.EXTRA_DAYS, ArrayList((0 until dias.length()).map { dias.getInt(it) % 7 + 1 }))
        }
        return abrir(i, "Alarma puesta a las %02d:%02d.".format(h, m))
    }

    private fun temporizador(a: JSONObject): String {
        val s = a.getInt("segundos")
        val i = Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, s).putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        a.optString("mensaje").takeIf { it.isNotBlank() }?.let { i.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        return abrir(i, "Temporizador de ${s / 60} min ${s % 60} s en marcha.")
    }

    private fun listaApps(): List<Pair<String, String>> {
        val pm = act.packageManager
        return pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
            .distinctBy { it.second }
            .sortedBy { sinAcentos(it.first) }
    }

    private suspend fun apps(filtro: String): String = withContext(Dispatchers.IO) {
        val f = sinAcentos(filtro)
        val l = listaApps().filter { f.isBlank() || sinAcentos(it.first).contains(f) || it.second.contains(f) }
        if (l.isEmpty()) "No hay apps que coincidan." else l.joinToString("\n") { "${it.first} (${it.second})" }
    }

    private suspend fun abrirApp(nombre: String): String {
        val n = sinAcentos(nombre.trim())
        val l = withContext(Dispatchers.IO) { listaApps() }
        val app = l.firstOrNull { it.second == nombre.trim() }
            ?: l.firstOrNull { sinAcentos(it.first) == n }
            ?: l.filter { sinAcentos(it.first).contains(n) }.minByOrNull { it.first.length }
            ?: throw Fallo("No encuentro ninguna app llamada «$nombre». Usa la herramienta apps para ver las que hay.")
        val i = act.packageManager.getLaunchIntentForPackage(app.second) ?: throw Fallo("«${app.first}» no se puede abrir.")
        return abrir(i, "Abierta: ${app.first}.")
    }

    private fun mapa(a: JSONObject): String {
        val destino = a.getString("destino")
        val uri = if (a.optBoolean("navegar")) {
            val modo = when (a.optString("modo")) { "andando" -> "walking"; "bici" -> "bicycling"; "transporte" -> "transit"; else -> "driving" }
            Uri.parse("https://www.google.com/maps/dir/?api=1&destination=${Uri.encode(destino)}&travelmode=$modo&dir_action=navigate")
        } else Uri.parse("geo:0,0?q=${Uri.encode(destino)}")
        return abrir(Intent(Intent.ACTION_VIEW, uri), if (a.optBoolean("navegar")) "Navegación hacia «$destino» iniciada." else "Mapa abierto con «$destino».")
    }

    private fun ajustes(pantalla: String): String {
        val accion = when (pantalla) {
            "wifi" -> Settings.ACTION_WIFI_SETTINGS
            "internet" -> Settings.Panel.ACTION_INTERNET_CONNECTIVITY
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "datos" -> Settings.ACTION_DATA_ROAMING_SETTINGS
            "volumen" -> Settings.Panel.ACTION_VOLUME
            "sonido" -> Settings.ACTION_SOUND_SETTINGS
            "pantalla" -> Settings.ACTION_DISPLAY_SETTINGS
            "bateria" -> Intent.ACTION_POWER_USAGE_SUMMARY
            "notificaciones" -> "android.settings.NOTIFICATION_SETTINGS"
            "no_molestar" -> "android.settings.ZEN_MODE_SETTINGS"
            "ubicacion" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            "apps" -> Settings.ACTION_APPLICATION_SETTINGS
            "almacenamiento" -> Settings.ACTION_INTERNAL_STORAGE_SETTINGS
            "accesibilidad" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
            "nfc" -> Settings.Panel.ACTION_NFC
            "fecha" -> Settings.ACTION_DATE_SETTINGS
            "idioma" -> Settings.ACTION_LOCALE_SETTINGS
            "seguridad" -> Settings.ACTION_SECURITY_SETTINGS
            "esta_app" -> Settings.ACTION_APPLICATION_DETAILS_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        val i = Intent(accion)
        if (pantalla == "esta_app") i.data = Uri.parse("package:${act.packageName}")
        return try {
            abrir(i, "Ajustes abiertos: $pantalla.")
        } catch (_: Fallo) {
            abrir(Intent(Settings.ACTION_SETTINGS), "Esa pantalla no existe en este móvil; he abierto los Ajustes generales.")
        }
    }

    private fun volumen(tipo: String, nivel: Int): String {
        val am = act.getSystemService(AudioManager::class.java)
        val s = when (tipo) {
            "timbre" -> AudioManager.STREAM_RING
            "notificaciones" -> AudioManager.STREAM_NOTIFICATION
            "alarma" -> AudioManager.STREAM_ALARM
            "llamada" -> AudioManager.STREAM_VOICE_CALL
            else -> AudioManager.STREAM_MUSIC
        }
        val max = am.getStreamMaxVolume(s)
        val v = (nivel.coerceIn(0, 100) * max / 100f).roundToInt().coerceAtLeast(am.getStreamMinVolume(s))
        try {
            am.setStreamVolume(s, v, AudioManager.FLAG_SHOW_UI)
        } catch (_: SecurityException) {
            accesoNoMolestar()
            am.setStreamVolume(s, v, AudioManager.FLAG_SHOW_UI)
        }
        return "Volumen de $tipo al ${v * 100 / max} %."
    }

    private fun modoSonido(modo: String): String {
        val am = act.getSystemService(AudioManager::class.java)
        val m = when (modo) { "silencio" -> AudioManager.RINGER_MODE_SILENT; "vibracion" -> AudioManager.RINGER_MODE_VIBRATE; else -> AudioManager.RINGER_MODE_NORMAL }
        try {
            am.ringerMode = m
        } catch (_: SecurityException) {
            accesoNoMolestar()
            am.ringerMode = m
        }
        return "Modo de sonido: $modo."
    }

    private fun noMolestar(activar: Boolean): String {
        accesoNoMolestar().setInterruptionFilter(
            if (activar) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL,
        )
        return if (activar) "No molestar activado." else "No molestar desactivado."
    }

    private fun brillo(a: JSONObject): String {
        if (!Settings.System.canWrite(act)) {
            if (enPrimerPlano) act.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${act.packageName}")))
            throw Fallo("Hace falta que el usuario permita a «Asistente» modificar los ajustes del sistema en la pantalla que se ha abierto; después, vuelve a intentarlo.")
        }
        val cr = act.contentResolver
        val partes = mutableListOf<String>()
        if (a.has("automatico")) {
            val auto = a.getBoolean("automatico")
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, if (auto) 1 else 0)
            partes += if (auto) "brillo automático" else "brillo manual"
        }
        if (a.has("nivel")) {
            val n = a.getInt("nivel").coerceIn(0, 100)
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, 0)
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, (n * 255 / 100).coerceAtLeast(1))
            partes += "brillo al $n %"
        }
        if (partes.isEmpty()) throw Fallo("Indica nivel o automatico.")
        return partes.joinToString(", ").replaceFirstChar { it.uppercase() } + "."
    }

    private fun linterna(encender: Boolean): String {
        val cm = act.getSystemService(CameraManager::class.java)
        val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
            ?: throw Fallo("Este móvil no tiene linterna.")
        cm.setTorchMode(id, encender)
        return if (encender) "Linterna encendida." else "Linterna apagada."
    }

    private fun portapapeles(copiar: String?): String {
        val cb = act.getSystemService(ClipboardManager::class.java)
        if (copiar != null) {
            cb.setPrimaryClip(ClipData.newPlainText("Asistente", copiar))
            return "Copiado al portapapeles."
        }
        if (!enPrimerPlano) throw Fallo("Android solo deja leer el portapapeles con la app en primer plano.")
        val t = cb.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(act)?.toString()
        return if (t.isNullOrEmpty()) "El portapapeles está vacío." else "Portapapeles:\n${t.take(5000)}"
    }

    private suspend fun notificacion(titulo: String, texto: String): String {
        if (Build.VERSION.SDK_INT >= 33) pedir(Manifest.permission.POST_NOTIFICATIONS)
        val nm = act.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("asistente", "Asistente", NotificationManager.IMPORTANCE_HIGH))
        val abrirApp = PendingIntent.getActivity(
            act, 0, Intent(act, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(act, "asistente")
            .setSmallIcon(R.drawable.ic_chispa).setContentTitle(titulo).setContentText(texto)
            .setStyle(NotificationCompat.BigTextStyle().bigText(texto))
            .setContentIntent(abrirApp).setAutoCancel(true).build()
        nm.notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), n)
        return "Notificación mostrada."
    }

    // Fecha u hora ISO → instante; una fecha sola es el inicio del día (o el final, con `fin`)
    private fun instante(s: String, fin: Boolean = false): Long {
        val zona = ZoneId.systemDefault()
        return try {
            if (s.length == 10) LocalDate.parse(s).let { if (fin) it.plusDays(1) else it }.atStartOfDay(zona).toInstant().toEpochMilli()
            else try { OffsetDateTime.parse(s).toInstant().toEpochMilli() } catch (_: Exception) { LocalDateTime.parse(s).atZone(zona).toInstant().toEpochMilli() }
        } catch (_: Exception) {
            throw Fallo("Fecha no válida: «$s». Usa ISO 8601, p. ej. 2026-10-08 o 2026-10-08T09:30.")
        }
    }

    private suspend fun calendario(a: JSONObject): String {
        pedir(Manifest.permission.READ_CALENDAR)
        val desde = instante(a.getString("desde"))
        val hasta = instante(a.getString("hasta"), fin = true)
        val buscar = sinAcentos(a.optString("buscar"))
        return withContext(Dispatchers.IO) {
            val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
                ContentUris.appendId(it, desde); ContentUris.appendId(it, hasta)
            }.build()
            val cols = arrayOf(
                CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
                CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
            )
            val fecha = DateTimeFormatter.ofPattern("EEE d MMM", Locale("es", "ES"))
            val hora = DateTimeFormatter.ofPattern("HH:mm")
            val out = mutableListOf<String>()
            act.contentResolver.query(uri, cols, null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
                while (c.moveToNext() && out.size < 150) {
                    val titulo = c.getString(0) ?: "(sin título)"
                    if (buscar.isNotBlank() && !sinAcentos(titulo).contains(buscar)) continue
                    val todoDia = c.getInt(3) == 1
                    val zona = if (todoDia) ZoneOffset.UTC else ZoneId.systemDefault()
                    val ini = Instant.ofEpochMilli(c.getLong(1)).atZone(zona)
                    val fin = Instant.ofEpochMilli(c.getLong(2)).atZone(zona)
                    val cuando = if (todoDia) "${ini.format(fecha)}, todo el día" else "${ini.format(fecha)} ${ini.format(hora)}–${fin.format(hora)}"
                    val lugar = c.getString(4)?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""
                    out += "$cuando · $titulo$lugar [${c.getString(5)}]"
                }
            }
            if (out.isEmpty()) "No hay eventos en esas fechas." else out.joinToString("\n")
        }
    }

    private suspend fun crearEvento(a: JSONObject): String {
        pedir(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        val todoDia = a.optBoolean("todo_el_dia")
        val inicioTxt = a.getString("inicio")
        val (ini, fin) = if (todoDia) {
            val d = LocalDate.parse(inicioTxt.take(10))
            val f = a.optString("fin").takeIf { it.isNotBlank() }?.let { LocalDate.parse(it.take(10)).plusDays(1) } ?: d.plusDays(1)
            d.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() to f.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        } else {
            val i = instante(inicioTxt)
            i to (a.optString("fin").takeIf { it.isNotBlank() }?.let { instante(it) } ?: (i + 3_600_000))
        }
        return withContext(Dispatchers.IO) {
            val cr = act.contentResolver
            // Calendario: el principal de Google si lo hay; si no, el primero editable
            var cal: Pair<Long, String>? = null
            cr.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, CalendarContract.Calendars.IS_PRIMARY, CalendarContract.Calendars.ACCOUNT_TYPE),
                "${CalendarContract.Calendars.VISIBLE} = 1 AND ${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR}",
                null, null,
            )?.use { c ->
                var mejor = -1
                while (c.moveToNext()) {
                    val nota = (if (c.getInt(2) == 1) 2 else 0) + (if (c.getString(3) == "com.google") 1 else 0)
                    if (nota > mejor) { mejor = nota; cal = c.getLong(0) to (c.getString(1) ?: "") }
                }
            }
            val (calId, calNombre) = cal ?: throw Fallo("No hay ningún calendario editable en el móvil.")
            val v = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calId)
                put(CalendarContract.Events.TITLE, a.getString("titulo"))
                put(CalendarContract.Events.DTSTART, ini)
                put(CalendarContract.Events.DTEND, fin)
                put(CalendarContract.Events.ALL_DAY, if (todoDia) 1 else 0)
                put(CalendarContract.Events.EVENT_TIMEZONE, if (todoDia) "UTC" else ZoneId.systemDefault().id)
                a.optString("lugar").takeIf { it.isNotBlank() }?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
                a.optString("descripcion").takeIf { it.isNotBlank() }?.let { put(CalendarContract.Events.DESCRIPTION, it) }
            }
            val uri = cr.insert(CalendarContract.Events.CONTENT_URI, v) ?: throw Fallo("No se ha podido crear el evento.")
            if (a.has("aviso_minutos")) {
                cr.insert(CalendarContract.Reminders.CONTENT_URI, ContentValues().apply {
                    put(CalendarContract.Reminders.EVENT_ID, ContentUris.parseId(uri))
                    put(CalendarContract.Reminders.MINUTES, a.getInt("aviso_minutos"))
                    put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
                })
            }
            "Evento «${a.getString("titulo")}» creado en el calendario «$calNombre»."
        }
    }

    private suspend fun contactos(nombre: String): String {
        pedir(Manifest.permission.READ_CONTACTS)
        return withContext(Dispatchers.IO) {
            val porContacto = linkedMapOf<String, MutableList<String>>()
            act.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.Data.DISPLAY_NAME, ContactsContract.Data.MIMETYPE, ContactsContract.Data.DATA1),
                "${ContactsContract.Data.DISPLAY_NAME} LIKE ? AND ${ContactsContract.Data.MIMETYPE} IN (?, ?)",
                arrayOf("%$nombre%", ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE),
                "${ContactsContract.Data.DISPLAY_NAME} ASC",
            )?.use { c ->
                while (c.moveToNext() && porContacto.size <= 30) {
                    val n = c.getString(0) ?: continue
                    val dato = c.getString(2) ?: continue
                    val l = porContacto.getOrPut(n) { mutableListOf() }
                    val limpio = dato.replace(" ", "")
                    if (l.none { it.replace(" ", "") == limpio }) l += dato
                }
            }
            if (porContacto.isEmpty()) "No hay contactos con «$nombre»."
            else porContacto.entries.joinToString("\n") { "${it.key}: ${it.value.joinToString(", ")}" }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun ubicacion(): String {
        pedir(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, alguno = true)
        val lm = act.getSystemService(LocationManager::class.java)
        if (!lm.isLocationEnabled) throw Fallo("La ubicación del móvil está desactivada.")
        val proveedor = when {
            Build.VERSION.SDK_INT >= 31 && lm.hasProvider(LocationManager.FUSED_PROVIDER) -> LocationManager.FUSED_PROVIDER
            concedido(Manifest.permission.ACCESS_FINE_LOCATION) && lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            else -> LocationManager.NETWORK_PROVIDER
        }
        val loc: Location = (if (Build.VERSION.SDK_INT >= 30) withTimeoutOrNull(20_000) {
            suspendCancellableCoroutine<Location?> { cont ->
                lm.getCurrentLocation(proveedor, null, act.mainExecutor) { cont.resume(it) }
            }
        } else null) ?: lm.getLastKnownLocation(proveedor) ?: throw Fallo("No se ha podido obtener la ubicación.")
        val direccion = withContext(Dispatchers.IO) {
            try {
                @Suppress("DEPRECATION")
                Geocoder(act, Locale("es", "ES")).getFromLocation(loc.latitude, loc.longitude, 1)?.firstOrNull()?.getAddressLine(0)
            } catch (_: Exception) { null }
        }
        return buildString {
            append("Lat ${"%.5f".format(Locale.ROOT, loc.latitude)}, lon ${"%.5f".format(Locale.ROOT, loc.longitude)} (±${loc.accuracy.roundToInt()} m)")
            direccion?.let { append("\nDirección aproximada: $it") }
        }
    }
}
