package com.example.pfc_app

import android.util.Log

/**
 * Registros (Log) de la aplicación, con una etiqueta por módulo para filtrar en
 * Logcat durante el debug en el teléfono, por ejemplo: adb logcat -s PFC.BT PFC.Captura
 */
object Registro {
    const val BT = "PFC.BT"
    const val CAPTURA = "PFC.Captura"
    const val VALIDACION = "PFC.Validacion"
    const val PROCESAMIENTO = "PFC.Procesamiento"
    const val INFERENCIA = "PFC.Inferencia"
    const val DATOS = "PFC.Datos"
    const val UI = "PFC.UI"

    /** Inicio de sesión con Google y respaldo de las atenciones en Firestore. */
    const val NUBE = "PFC.Nube"

    fun d(etiqueta: String, mensaje: String) {
        Log.d(etiqueta, mensaje)
    }

    fun i(etiqueta: String, mensaje: String) {
        Log.i(etiqueta, mensaje)
    }

    fun w(etiqueta: String, mensaje: String, error: Throwable? = null) {
        Log.w(etiqueta, mensaje, error)
    }

    fun e(etiqueta: String, mensaje: String, error: Throwable? = null) {
        Log.e(etiqueta, mensaje, error)
    }
}
