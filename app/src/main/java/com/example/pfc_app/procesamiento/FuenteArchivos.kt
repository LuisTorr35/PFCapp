package com.example.pfc_app.procesamiento

import java.io.File

/**
 * De dónde se leen los archivos de una atención grabada. En el teléfono son los
 * assets (FuenteArchivosAssets, paquete adquisicion); en las pruebas de JVM, una
 * carpeta del disco. Los nombres son relativos a la raíz de la fuente.
 */
interface FuenteArchivos {
    /** Contenido del archivo, o null si no existe. */
    fun leerBytes(nombre: String): ByteArray?

    /** Nombres de los archivos de la raíz de la fuente. */
    fun lista(): List<String>

    /** Fuente con raíz en la subcarpeta [nombre]. */
    fun subcarpeta(nombre: String): FuenteArchivos

    fun leerTexto(nombre: String): String? = leerBytes(nombre)?.toString(Charsets.UTF_8)

    fun existe(nombre: String): Boolean = nombre in lista()
}

/** Archivos de una carpeta del disco. */
class FuenteArchivosCarpeta(private val carpeta: File) : FuenteArchivos {
    override fun leerBytes(nombre: String): ByteArray? {
        val f = File(carpeta, nombre)
        return if (f.isFile) f.readBytes() else null
    }

    override fun lista(): List<String> = carpeta.list()?.sorted() ?: emptyList()

    override fun subcarpeta(nombre: String): FuenteArchivos = FuenteArchivosCarpeta(File(carpeta, nombre))

    override fun existe(nombre: String): Boolean = File(carpeta, nombre).isFile

    override fun toString(): String = carpeta.path
}
