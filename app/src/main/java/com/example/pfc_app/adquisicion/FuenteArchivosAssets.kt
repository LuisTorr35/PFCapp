package com.example.pfc_app.adquisicion

import android.content.Context
import com.example.pfc_app.procesamiento.FuenteArchivos
import java.io.FileNotFoundException

/** Archivos de una carpeta de assets, por ejemplo "demo/ATN-037". */
class FuenteArchivosAssets(private val contexto: Context, private val carpeta: String) : FuenteArchivos {
    private val ruta = carpeta.trim('/')

    override fun leerBytes(nombre: String): ByteArray? = try {
        contexto.assets.open(if (ruta.isEmpty()) nombre else "$ruta/$nombre").use { it.readBytes() }
    } catch (_: FileNotFoundException) {
        null
    }

    override fun lista(): List<String> = contexto.assets.list(ruta)?.sorted().orEmpty()

    override fun subcarpeta(nombre: String): FuenteArchivos =
        FuenteArchivosAssets(contexto, if (ruta.isEmpty()) nombre else "$ruta/$nombre")

    override fun toString(): String = "assets/$ruta"
}
