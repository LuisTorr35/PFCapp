package com.example.pfc_app.depuracion

import android.content.Context

/** En release no hay opciones de depuración: siempre el proyecto real de Firebase. */
object ConfigDepuracion {
    @Suppress("UNUSED_PARAMETER")
    fun emuladorFirestore(contexto: Context): String? = null
}
