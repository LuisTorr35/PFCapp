package com.example.pfc_app.datos

import android.content.Context
import androidx.core.content.edit
import java.util.UUID

/** Preferencias del mecánico (modo demostración y adaptador ELM327) y el id de esta instalación. */
class PreferenciasApp(contexto: Context) {
    private val prefs = contexto.applicationContext.getSharedPreferences(ARCHIVO, Context.MODE_PRIVATE)

    /** Activado por defecto: la demostración reproduce atenciones grabadas sin adaptador. */
    var modoDemostracion: Boolean
        get() = prefs.getBoolean(CLAVE_DEMO, true)
        set(valor) = prefs.edit { putBoolean(CLAVE_DEMO, valor) }

    val macAdaptador: String? get() = prefs.getString(CLAVE_MAC, null)

    val nombreAdaptador: String? get() = prefs.getString(CLAVE_NOMBRE, null)

    /**
     * Identifica esta instalación de la app. Forma parte del id de cada documento respaldado,
     * para que dos teléfonos (o una reinstalación) no pisen las atenciones del otro en la nube:
     * los id_atencion de Room vuelven a empezar en 1 con cada base nueva. Dura lo que la base.
     */
    val idInstalacion: String
        @Synchronized get() = prefs.getString(CLAVE_INSTALACION, null) ?: UUID.randomUUID().toString()
            .replace("-", "")
            .take(LARGO_INSTALACION)
            .also { nuevo -> prefs.edit(commit = true) { putString(CLAVE_INSTALACION, nuevo) } }

    fun guardarAdaptador(nombre: String, direccionMac: String) = prefs.edit {
        putString(CLAVE_NOMBRE, nombre)
        putString(CLAVE_MAC, direccionMac)
    }

    private companion object {
        const val ARCHIVO = "pfc_preferencias"
        const val CLAVE_DEMO = "modo_demostracion"
        const val CLAVE_MAC = "mac_adaptador"
        const val CLAVE_NOMBRE = "nombre_adaptador"
        const val CLAVE_INSTALACION = "id_instalacion"
        const val LARGO_INSTALACION = 12
    }
}
