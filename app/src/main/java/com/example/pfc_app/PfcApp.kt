package com.example.pfc_app

import android.app.Application

/** Aplicación: crea el contenedor de dependencias (todo perezoso, nada pesado al arrancar). */
class PfcApp : Application() {
    lateinit var contenedor: AppContenedor
        private set

    override fun onCreate() {
        super.onCreate()
        contenedor = AppContenedor(this)
        Registro.i(Registro.UI, "Aplicación iniciada")
    }
}
