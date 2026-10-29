package com.mnldz.asistente

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Un mensaje del asistente se pinta como en la app de escritorio: el trabajo intermedio en
// fichas plegables, separadas por lo que Claude va contando, y la respuesta debajo.
private sealed interface Grupo {
    data class Texto(val texto: String) : Grupo
    data class Pasos(val bloques: List<Bloque>) : Grupo
    data class Nota(val texto: String, val esError: Boolean) : Grupo
}

private fun agrupar(bloques: List<Bloque>): List<Grupo> {
    val out = mutableListOf<Grupo>()
    val pasos = mutableListOf<Bloque>()
    fun cerrar() { if (pasos.isNotEmpty()) { out += Grupo.Pasos(pasos.toList()); pasos.clear() } }
    for (b in bloques) when (b.tipo) {
        "text" -> { cerrar(); out += Grupo.Texto(b.texto) }
        "error" -> { cerrar(); out += Grupo.Nota(b.texto, true) }
        "interrupted" -> { cerrar(); out += Grupo.Nota(b.texto.ifBlank { "Interrumpido" }, false) }
        else -> pasos += b
    }
    cerrar()
    return out
}

@Composable
fun MensajeUsuario(texto: String, enCola: Boolean = false) {
    val c = LocalColores.current
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        SelectionContainer {
            Text(
                texto, color = if (enCola) c.apagado else c.texto, fontSize = 15.sp, lineHeight = 22.sp,
                modifier = Modifier.widthIn(max = 320.dp)
                    .background(c.burbuja, RoundedCornerShape(18.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
fun MensajeAsistente(m: Mensaje) {
    val c = LocalColores.current
    val grupos = remember(m) { agrupar(m.bloques) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        grupos.forEachIndexed { i, g ->
            when (g) {
                is Grupo.Texto -> Markdown(g.texto)
                is Grupo.Pasos -> Ficha(g.bloques, enCurso = !m.terminado && i == grupos.lastIndex)
                is Grupo.Nota -> Text(
                    g.texto, fontSize = 14.sp, color = if (g.esError) c.error else c.apagado,
                    fontStyle = if (g.esError) FontStyle.Normal else FontStyle.Italic,
                )
            }
        }
    }
}

// Ficha plegable con los pasos (herramientas, razonamiento, notas)
@Composable
private fun Ficha(bloques: List<Bloque>, enCurso: Boolean) {
    val c = LocalColores.current
    var abierta by remember { mutableStateOf(false) }
    val ultimo = bloques.last()
    val hayError = bloques.any { it.esError }
    val titulo = if (bloques.size == 1) resumenPaso(ultimo) else "${bloques.size} pasos · ${resumenPaso(ultimo)}"
    Column(
        Modifier.fillMaxWidth()
            .border(BorderStroke(1.dp, c.borde), RoundedCornerShape(12.dp))
            .background(c.superficie.copy(alpha = .5f), RoundedCornerShape(12.dp)),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { abierta = !abierta }.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (abierta) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowRight, null,
                tint = c.apagado, modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                titulo, color = if (hayError) c.error else c.texto2, fontSize = 13.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            if (enCurso) { Spacer(Modifier.width(8.dp)); Chispa(14) }
        }
        AnimatedVisibility(abierta) {
            Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                bloques.forEach { Paso(it) }
            }
        }
    }
}

private fun resumenPaso(b: Bloque) = when (b.tipo) {
    "thinking" -> "Pensando"
    "note" -> b.texto.lineSequence().firstOrNull()?.take(80) ?: "Nota"
    else -> resumenHerramienta(b)
}

@Composable
private fun Paso(b: Bloque) {
    val c = LocalColores.current
    var abierto by remember { mutableStateOf(false) }
    val detalle = when (b.tipo) {
        "thinking", "note" -> b.texto
        else -> buildString {
            b.entrada?.let { e ->
                val cmd = e.optString("command")
                append(if (cmd.isNotBlank()) "$ $cmd" else e.toString(2).take(1500))
            }
            if (b.resultado.isNotBlank()) append("\n\n").append(b.resultado.take(3000))
        }
    }
    Column {
        Row(Modifier.fillMaxWidth().clickable { abierto = !abierto }.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).background(if (b.esError) c.error else if (b.tipo == "tool" && b.resultado.isEmpty()) c.acento else c.apagado, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(
                resumenPaso(b), color = if (b.esError) c.error else c.texto2, fontSize = 13.sp,
                maxLines = if (abierto) 4 else 1, overflow = TextOverflow.Ellipsis,
                fontStyle = if (b.tipo == "thinking") FontStyle.Italic else FontStyle.Normal,
            )
        }
        if (abierto && detalle.isNotBlank()) {
            SelectionContainer {
                Text(
                    detalle.trim(), fontFamily = if (b.tipo == "tool") FontFamily.Monospace else FontFamily.Default,
                    fontSize = 12.sp, lineHeight = 17.sp, color = c.texto2,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp)
                        .background(c.codigo, RoundedCornerShape(8.dp))
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState())
                        .padding(10.dp),
                )
            }
        }
    }
}

// La chispa girando, como el indicador de Claude Code
@Composable
fun Chispa(tam: Int, color: Color = LocalColores.current.acento) {
    val giro by rememberInfiniteTransition(label = "chispa").animateFloat(
        0f, 360f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "giro",
    )
    Icon(painterResource(R.drawable.ic_chispa), null, tint = color, modifier = Modifier.size(tam.dp).rotate(giro))
}

@Composable
fun Trabajando(tareas: List<Tarea>) {
    val c = LocalColores.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Chispa(16)
            Spacer(Modifier.width(8.dp))
            Text("Trabajando…", color = c.apagado, fontSize = 14.sp)
        }
        if (tareas.isNotEmpty()) Column(Modifier.padding(start = 24.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            tareas.forEach { t ->
                val marca = when (t.estado) { "completed" -> "☒"; "in_progress" -> "◼"; else -> "☐" }
                Text(
                    "$marca ${t.asunto}", fontSize = 13.sp,
                    color = when (t.estado) { "in_progress" -> c.texto; "completed" -> c.apagado; else -> c.texto2 },
                )
            }
        }
    }
}
