package com.example.pfc_app.depuracion

import android.content.Context
import androidx.core.content.edit

/**
 * Solo en debug: opciones que se activan por adb (ReceptorDepuracion). La variante release
 * tiene su propia ConfigDepuracion, sin opciones.
 */
object ConfigDepuracion {
    private const val ARCHIVO = "pfc_depuracion"
    private const val CLAVE_EMULADOR = "emulador_firestore"

    /**
     * "host:puerto" del emulador de Cloud Firestore, o null para usar el proyecto real. Con
     * adb reverse tcp:8080 tcp:8080, el teléfono llega al emulador de la PC en 127.0.0.1:8080.
     */
    fun emuladorFirestore(contexto: Context): String? = preferencias(contexto).getString(CLAVE_EMULADOR, null)

    /** null o vacío vuelve al proyecto real. */
    fun usarEmuladorFirestore(contexto: Context, hostPuerto: String?) = preferencias(contexto).edit(commit = true) {
        if (hostPuerto.isNullOrBlank()) remove(CLAVE_EMULADOR) else putString(CLAVE_EMULADOR, hostPuerto)
    }

    private fun preferencias(contexto: Context) = contexto.applicationContext.getSharedPreferences(ARCHIVO, Context.MODE_PRIVATE)
}
