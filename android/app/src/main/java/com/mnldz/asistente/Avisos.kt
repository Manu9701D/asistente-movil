package com.mnldz.asistente

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

// Tarjetas para lo que Claude pregunta: permisos, AskUserQuestion y el plan
@Composable
fun TarjetaAviso(a: Aviso, responder: (JSONObject) -> Unit) {
    val c = LocalColores.current
    Column(
        Modifier.fillMaxWidth()
            .border(BorderStroke(1.dp, c.acento.copy(alpha = .5f)), RoundedCornerShape(14.dp))
            .background(c.superficie, RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when (a.tipo) {
            "question" -> Preguntas(a, responder)
            "plan" -> Plan(a, responder)
            else -> Permiso(a, responder)
        }
    }
}

@Composable
private fun Permiso(a: Aviso, responder: (JSONObject) -> Unit) {
    val c = LocalColores.current
    var nota by remember(a.id) { mutableStateOf("") }
    val e = a.entrada
    val detalle = listOf("command", "file_path", "url", "pattern", "query").firstNotNullOfOrNull { k -> e.optString(k).takeIf { it.isNotBlank() } }
        ?: e.toString(2).take(800)
    Text("¿Permitir ${a.herramienta.removePrefix("mcp__").replace("__", " · ")}?", color = c.texto, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    e.optString("description").takeIf { it.isNotBlank() }?.let { Text(it, color = c.texto2, fontSize = 13.sp) }
    Text(
        detalle, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = c.texto2,
        modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp)
            .background(c.codigo, RoundedCornerShape(8.dp)).verticalScroll(rememberScrollState()).padding(10.dp),
    )
    OutlinedTextField(
        nota, { nota = it }, Modifier.fillMaxWidth(), singleLine = false, maxLines = 3,
        placeholder = { Text("Si no, dile qué hacer en su lugar", fontSize = 13.sp) },
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button({ responder(JSONObject().put("decision", "allow")) }, enabled = nota.isBlank(), colors = ButtonDefaults.buttonColors(containerColor = c.acento)) { Text("Permitir") }
        if (a.puedeSiempre) OutlinedButton({ responder(JSONObject().put("decision", "always")) }, enabled = nota.isBlank()) {
            Text(a.etiquetaSiempre ?: "Siempre", maxLines = 1)
        }
        TextButton({ responder(JSONObject().put("decision", "deny").put("feedback", nota)) }) {
            Text(if (nota.isBlank()) "Denegar" else "Enviar", color = c.error)
        }
    }
}

@Composable
private fun Preguntas(a: Aviso, responder: (JSONObject) -> Unit) {
    val c = LocalColores.current
    val preguntas = remember(a.id) { a.preguntas }
    // Por pregunta: etiquetas elegidas y texto de «Otro»
    val elegidas = remember(a.id) { preguntas.map { mutableStateListOf<String>() } }
    val otros = remember(a.id) { preguntas.map { mutableStateOf("") } }
    preguntas.forEachIndexed { i, p ->
        if (p.cabecera.isNotBlank()) Text(p.cabecera.uppercase(), color = c.acento, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Text(p.pregunta, color = c.texto, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            p.opciones.forEachIndexed { n, o ->
                val sel = o.etiqueta in elegidas[i]
                Row(
                    Modifier.fillMaxWidth()
                        .background(if (sel) c.acento.copy(alpha = .16f) else c.elevado.copy(alpha = .5f), RoundedCornerShape(10.dp))
                        .clickable {
                            if (p.multiple) { if (sel) elegidas[i].remove(o.etiqueta) else elegidas[i].add(o.etiqueta) }
                            else { elegidas[i].clear(); elegidas[i].add(o.etiqueta); otros[i].value = "" }
                        }
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                ) {
                    Text(if (p.multiple) (if (sel) "☒" else "☐") else "${n + 1}.", color = if (sel) c.acento else c.apagado, fontSize = 14.sp)
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(o.etiqueta, color = c.texto, fontSize = 14.sp)
                        if (o.descripcion.isNotBlank()) Text(o.descripcion, color = c.apagado, fontSize = 12.sp)
                    }
                }
            }
            OutlinedTextField(
                otros[i].value,
                { otros[i].value = it; if (!p.multiple && it.isNotBlank()) elegidas[i].clear() },
                Modifier.fillMaxWidth(), maxLines = 3, placeholder = { Text("Otra respuesta", fontSize = 13.sp) },
            )
        }
    }
    val respuestas = preguntas.indices.map { i -> (elegidas[i] + listOfNotNull(otros[i].value.trim().takeIf { it.isNotEmpty() })).joinToString(", ") }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(
            {
                val answers = JSONObject()
                preguntas.forEachIndexed { i, p -> answers.put(p.pregunta, respuestas[i]) }
                responder(JSONObject().put("answers", answers))
            },
            enabled = respuestas.all { it.isNotEmpty() },
            colors = ButtonDefaults.buttonColors(containerColor = c.acento),
        ) { Text("Responder") }
        TextButton({ responder(JSONObject().put("skip", true)) }) { Text("Omitir", color = c.apagado) }
    }
}

// El plan se contesta tecleando en el diálogo de la terminal: opción n (1, 2, 3…)
@Composable
private fun Plan(a: Aviso, responder: (JSONObject) -> Unit) {
    val c = LocalColores.current
    var nota by remember(a.id) { mutableStateOf("") }
    Text("Plan propuesto", color = c.texto, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    Column(Modifier.fillMaxWidth().heightIn(max = 340.dp).background(c.codigo, RoundedCornerShape(8.dp)).verticalScroll(rememberScrollState()).padding(12.dp)) {
        Markdown(a.entrada.optString("plan"))
    }
    Button({ responder(JSONObject().put("option", 1)) }, Modifier.fillMaxWidth(), enabled = nota.isBlank(), colors = ButtonDefaults.buttonColors(containerColor = c.acento)) {
        Text("Sí, y aceptar ediciones")
    }
    OutlinedButton({ responder(JSONObject().put("option", 2)) }, Modifier.fillMaxWidth(), enabled = nota.isBlank()) { Text("Sí, aprobando cada edición") }
    OutlinedTextField(nota, { nota = it }, Modifier.fillMaxWidth(), maxLines = 3, placeholder = { Text("Qué cambiar del plan", fontSize = 13.sp) })
    TextButton({ responder(JSONObject().put("option", 3).put("feedback", nota)) }, Modifier.fillMaxWidth()) {
        Text("No, sigue planificando", color = c.texto2)
    }
}
