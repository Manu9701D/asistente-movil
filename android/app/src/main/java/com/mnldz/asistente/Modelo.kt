package com.mnldz.asistente

import org.json.JSONArray
import org.json.JSONObject

// La conversación tal como la manda el servidor (termchat.mjs): mensajes con bloques,
// preguntas o permisos pendientes, y si Claude está trabajando.

data class Bloque(
    val tipo: String,          // text, thinking, tool, note, error, interrupted
    val texto: String = "",
    val herramienta: String = "",
    val entrada: JSONObject? = null,
    val resultado: String = "",
    val esError: Boolean = false,
    val id: String = "",
)

data class Mensaje(
    val id: String,
    val rol: String,           // user o assistant
    val texto: String = "",
    val bloques: List<Bloque> = emptyList(),
    val terminado: Boolean = true,
)

data class Opcion(val etiqueta: String, val descripcion: String)
data class Pregunta(val pregunta: String, val cabecera: String, val opciones: List<Opcion>, val multiple: Boolean)

data class Aviso(
    val id: String,
    val tipo: String,          // permission, question, plan
    val herramienta: String,
    val entrada: JSONObject,
    val puedeSiempre: Boolean,
    val etiquetaSiempre: String?,
) {
    val preguntas: List<Pregunta>
        get() = entrada.optJSONArray("questions").objetos().map { q ->
            Pregunta(
                pregunta = q.optString("question"),
                cabecera = q.optString("header"),
                opciones = q.optJSONArray("options").objetos().map { Opcion(it.optString("label"), it.optString("description")) },
                multiple = q.optBoolean("multiSelect"),
            )
        }
}

data class Tarea(val asunto: String, val estado: String)

data class Conversacion(
    val id: String = "",
    val mensajes: List<Mensaje> = emptyList(),
    val avisos: List<Aviso> = emptyList(),
    val tareas: List<Tarea> = emptyList(),
    val cola: List<String> = emptyList(),
    val ocupado: Boolean = false,
)

fun JSONArray?.objetos(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

fun leerConversacion(c: JSONObject): Conversacion {
    var n = 0
    val mensajes = c.optJSONArray("messages").objetos().map { m ->
        val idx = n++
        Mensaje(
            id = m.optString("id").ifEmpty { "m$idx" },
            rol = m.optString("role"),
            texto = m.optString("text"),
            terminado = m.optBoolean("done", true),
            bloques = m.optJSONArray("blocks").objetos().map { b ->
                Bloque(
                    tipo = b.optString("type"),
                    texto = b.optString("text"),
                    herramienta = b.optString("name"),
                    entrada = b.optJSONObject("input"),
                    resultado = b.opt("result")?.let { if (it is String) it else it.toString() } ?: "",
                    esError = b.optBoolean("isError"),
                    id = b.optString("id"),
                )
            },
        )
    }
    return Conversacion(
        id = c.optString("id"),
        mensajes = mensajes,
        avisos = c.optJSONArray("prompts").objetos().map { p ->
            Aviso(
                id = p.optString("id"),
                tipo = p.optString("kind"),
                herramienta = p.optString("toolName"),
                entrada = p.optJSONObject("input") ?: JSONObject(),
                puedeSiempre = p.optBoolean("canAlways"),
                etiquetaSiempre = p.optString("alwaysLabel").takeIf { it.isNotEmpty() && it != "null" },
            )
        },
        tareas = c.optJSONArray("tasks").objetos().map { Tarea(it.optString("subject"), it.optString("status")) },
        cola = c.optJSONArray("queue").objetos().map { it.optString("text") },
        ocupado = c.optBoolean("busy"),
    )
}

// Resumen de una herramienta en una línea, como las fichas de la app de escritorio
fun resumenHerramienta(b: Bloque): String {
    val e = b.entrada ?: return b.herramienta
    val detalle = listOf("command", "description", "file_path", "pattern", "url", "query", "prompt", "nombre", "destino", "titulo", "pantalla", "texto")
        .firstNotNullOfOrNull { k -> e.optString(k).takeIf { it.isNotBlank() } }
    val nombre = b.herramienta.removePrefix("mcp__").replace("__", " · ")
    return if (detalle != null) "$nombre · ${detalle.lineSequence().first().take(80)}" else nombre
}
