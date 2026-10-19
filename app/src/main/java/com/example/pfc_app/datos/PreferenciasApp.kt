package com.example.pfc_app.datos

import android.content.Context
import androidx.core.content.edit

/** Preferencias del mecánico: modo demostración y adaptador ELM327 elegido. */
class PreferenciasApp(contexto: Context) {
    private val prefs = contexto.applicationContext.getSharedPreferences(ARCHIVO, Context.MODE_PRIVATE)

    /** Activado por defecto: la demostración reproduce atenciones grabadas sin adaptador. */
    var modoDemostracion: Boolean
        get() = prefs.getBoolean(CLAVE_DEMO, true)
        set(valor) = prefs.edit { putBoolean(CLAVE_DEMO, valor) }

    val macAdaptador: String? get() = prefs.getString(CLAVE_MAC, null)

    val nombreAdaptador: String? get() = prefs.getString(CLAVE_NOMBRE, null)

    fun guardarAdaptador(nombre: String, direccionMac: String) = prefs.edit {
        putString(CLAVE_NOMBRE, nombre)
        putString(CLAVE_MAC, direccionMac)
    }

    private companion object {
        const val ARCHIVO = "pfc_preferencias"
        const val CLAVE_DEMO = "modo_demostracion"
        const val CLAVE_MAC = "mac_adaptador"
        const val CLAVE_NOMBRE = "nombre_adaptador"
    }
}
