package com.mnldz.asistente

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

// Widget «Pregunta a Claude»: la barra abre el asistente rápido con el teclado y el micrófono, escuchando
class Widget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val vista = RemoteViews(context.packageName, R.layout.widget).apply {
            setOnClickPendingIntent(R.id.barra, abrir(context, ACCION_ESCRIBIR, 1))
            setOnClickPendingIntent(R.id.micro, abrir(context, ACCION_HABLAR, 2))
        }
        manager.updateAppWidget(ids, vista)
    }

    private fun abrir(context: Context, accion: String, codigo: Int) = PendingIntent.getActivity(
        context, codigo,
        Intent(context, AsistenteRapido::class.java).setAction(accion).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
