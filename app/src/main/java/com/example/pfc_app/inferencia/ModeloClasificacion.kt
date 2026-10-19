package com.example.pfc_app.inferencia

import com.example.pfc_app.modelos.ContratoModelo

/** El modelo de clasificación incluido en la aplicación (paquete datos, Capítulo 5). */
interface ModeloClasificacion : AutoCloseable {
    val contrato: ContratoModelo

    /** Probabilidad de cada clase, en el orden de [ContratoModelo.clases]. */
    fun ejecutar(fila: FloatArray): FloatArray
}
