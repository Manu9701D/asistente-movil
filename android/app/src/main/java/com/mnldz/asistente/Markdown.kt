package com.mnldz.asistente

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Markdown básico (lo que Claude usa casi siempre): títulos, listas, citas, bloques de código,
// negrita, cursiva, código en línea y enlaces (se muestra el texto).

private sealed interface Parte {
    data class Parrafo(val texto: String) : Parte
    data class Titulo(val nivel: Int, val texto: String) : Parte
    data class Lista(val marca: String, val texto: String, val sangria: Int) : Parte
    data class Cita(val texto: String) : Parte
    data class Codigo(val texto: String) : Parte
    data class Tabla(val filas: List<List<String>>) : Parte
    data object Separador : Parte
}

private fun partes(md: String): List<Parte> {
    val out = mutableListOf<Parte>()
    val lineas = md.replace("\r", "").split("\n")
    var i = 0
    val parrafo = StringBuilder()
    fun cerrar() { if (parrafo.isNotBlank()) out += Parte.Parrafo(parrafo.toString().trim()); parrafo.clear() }
    while (i < lineas.size) {
        val l = lineas[i]
        val t = l.trim()
        when {
            t.startsWith("```") -> {
                cerrar()
                val code = StringBuilder()
                i++
                while (i < lineas.size && !lineas[i].trim().startsWith("```")) { code.appendLine(lineas[i]); i++ }
                out += Parte.Codigo(code.toString().trimEnd())
            }
            t.isEmpty() -> cerrar()
            Regex("^#{1,6} ").containsMatchIn(t) -> { cerrar(); out += Parte.Titulo(t.takeWhile { it == '#' }.length, t.trimStart('#').trim()) }
            Regex("^(-{3,}|\\*{3,}|_{3,})$").matches(t) -> { cerrar(); out += Parte.Separador }
            t.startsWith("|") -> {
                cerrar()
                val filas = mutableListOf<List<String>>()
                while (i < lineas.size && lineas[i].trim().startsWith("|")) {
                    val celdas = lineas[i].trim().trim('|').split("|").map { it.trim() }
                    if (!celdas.all { Regex("^:?-{2,}:?$").matches(it) }) filas += celdas
                    i++
                }
                i--
                out += Parte.Tabla(filas)
            }
            Regex("^\\s*([-*+]|\\d+[.)]) ").containsMatchIn(l) -> {
                cerrar()
                val m = Regex("^(\\s*)([-*+]|\\d+[.)]) (.*)").find(l)!!
                val marca = m.groupValues[2].let { if (it in listOf("-", "*", "+")) "•" else it }
                out += Parte.Lista(marca, m.groupValues[3], m.groupValues[1].length / 2)
            }
            t.startsWith(">") -> { cerrar(); out += Parte.Cita(t.trimStart('>').trim()) }
            else -> parrafo.append(if (parrafo.isEmpty()) t else " $t")
        }
        i++
    }
    cerrar()
    return out
}

// Formato en línea: **negrita**, *cursiva*, `código`, [texto](url)
fun enLinea(s: String, codigo: androidx.compose.ui.graphics.Color): AnnotatedString = buildAnnotatedString {
    val re = Regex("\\*\\*(.+?)\\*\\*|__(.+?)__|`([^`]+)`|\\[([^\\]]+)]\\([^)]+\\)|(?<![*\\w])\\*(?!\\s)(.+?)(?<!\\s)\\*(?!\\*)")
    var pos = 0
    for (m in re.findAll(s)) {
        append(s.substring(pos, m.range.first))
        val g = m.groupValues
        when {
            g[1].isNotEmpty() || g[2].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(g[1].ifEmpty { g[2] }) }
            g[3].isNotEmpty() -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codigo, fontSize = 13.sp)) { append(" ${g[3]} ") }
            g[4].isNotEmpty() -> withStyle(SpanStyle(color = androidx.compose.ui.graphics.Color(0xFF6A9FD8))) { append(g[4]) }
            else -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(g[5]) }
        }
        pos = m.range.last + 1
    }
    append(s.substring(pos))
}

@Composable
fun Markdown(texto: String, modifier: Modifier = Modifier) {
    val c = LocalColores.current
    SelectionContainer {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (p in partes(texto)) when (p) {
                is Parte.Parrafo -> Text(enLinea(p.texto, c.codigo), color = c.texto, fontSize = 15.sp, lineHeight = 23.sp)
                is Parte.Titulo -> Text(
                    enLinea(p.texto, c.codigo), color = c.texto, fontWeight = FontWeight.SemiBold,
                    fontSize = when (p.nivel) { 1 -> 21.sp; 2 -> 18.sp; else -> 16.sp },
                    modifier = Modifier.padding(top = 4.dp),
                )
                is Parte.Lista -> Row(Modifier.padding(start = (p.sangria * 16).dp)) {
                    Text(p.marca, color = c.apagado, fontSize = 15.sp, modifier = Modifier.width(22.dp))
                    Text(enLinea(p.texto, c.codigo), color = c.texto, fontSize = 15.sp, lineHeight = 22.sp)
                }
                is Parte.Cita -> Text(
                    enLinea(p.texto, c.codigo), color = c.texto2, fontStyle = FontStyle.Italic, fontSize = 15.sp,
                    modifier = Modifier.padding(start = 10.dp),
                )
                is Parte.Codigo -> Text(
                    p.texto, fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = c.texto2, lineHeight = 19.sp,
                    modifier = Modifier.fillMaxWidth().background(c.codigo, RoundedCornerShape(10.dp))
                        .horizontalScroll(rememberScrollState()).padding(12.dp),
                )
                is Parte.Tabla -> Column(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                        .background(c.codigo, RoundedCornerShape(10.dp)).padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    p.filas.forEachIndexed { n, fila ->
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            fila.forEach { celda ->
                                Text(
                                    enLinea(celda, c.elevado), color = c.texto, fontSize = 13.sp,
                                    fontWeight = if (n == 0) FontWeight.SemiBold else FontWeight.Normal,
                                    modifier = Modifier.width(110.dp),
                                )
                            }
                        }
                    }
                }
                Parte.Separador -> Text("—", color = c.apagado)
            }
        }
    }
}
