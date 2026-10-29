package com.mnldz.asistente

import androidx.car.app.connection.CarConnection
import kotlinx.coroutines.flow.MutableStateFlow

// Android Auto: si el móvil está proyectando en el coche (solo se sabe con el proceso vivo). Para hablarle
// a Claude en el coche está la pantalla «Claude» (Pantalla.kt). Al no venir de Play Store, Android Auto
// necesita «Fuentes desconocidas» en sus ajustes de desarrollador.
class Auto(private val app: AppAsistente) {
    val conectado = MutableStateFlow(false)

    fun iniciar() {
        CarConnection(app).type.observeForever { conectado.value = it == CarConnection.CONNECTION_TYPE_PROJECTION }
    }
}
