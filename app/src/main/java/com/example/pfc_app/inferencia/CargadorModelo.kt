package com.example.pfc_app.inferencia

import android.content.Context
import com.example.pfc_app.Registro
import com.example.pfc_app.procesamiento.FuenteArchivos
import com.example.pfc_app.procesamiento.FuenteArchivosCarpeta
import java.io.File
import java.io.FileNotFoundException

/**
 * Carga el modelo y su contrato. El contrato se llama contrato_modelo.json y el
 * .onnx es el que nombra su campo archivo_onnx: cambiar el modelo provisional por
 * el definitivo es reemplazar los dos archivos de assets/modelo/, sin tocar código.
 */
object CargadorModelo {
    const val ARCHIVO_CONTRATO = "contrato_modelo.json"

    fun desdeAssets(contexto: Context, carpeta: String = "modelo"): ModeloClasificacion {
        val assets = contexto.applicationContext.assets
        val nombres = assets.list(carpeta)?.toList().orEmpty()
        val fuente = object : FuenteArchivos {
            override fun leerBytes(nombre: String): ByteArray? = try {
                assets.open("$carpeta/$nombre").use { it.readBytes() }
            } catch (_: FileNotFoundException) {
                null
            }

            override fun lista(): List<String> = nombres

            override fun subcarpeta(nombre: String): FuenteArchivos = throw UnsupportedOperationException()
        }
        return desdeFuente(fuente, "assets/$carpeta")
    }

    /** Para las pruebas de JVM: lee la carpeta src/main/assets/modelo del disco. */
    fun desdeCarpeta(carpeta: File): ModeloClasificacion = desdeFuente(FuenteArchivosCarpeta(carpeta), carpeta.path)

    fun desdeFuente(fuente: FuenteArchivos, descripcion: String = fuente.toString()): ModeloClasificacion {
        val inicio = System.nanoTime()
        val textoContrato = fuente.leerTexto(ARCHIVO_CONTRATO)
            ?: buscarContrato(fuente)
            ?: throw FileNotFoundException("No hay $ARCHIVO_CONTRATO en $descripcion")
        val contrato = LectorContrato.desdeJson(textoContrato)
        val bytes = fuente.leerBytes(contrato.archivoOnnx)
            ?: throw FileNotFoundException("El contrato nombra ${contrato.archivoOnnx} y no está en $descripcion")
        val modelo = ModeloClasificacionOnnx(bytes, contrato)
        Registro.i(
            Registro.INFERENCIA,
            "Modelo ${contrato.versionModelo}${if (contrato.provisional) " (PROVISIONAL)" else ""} desde $descripcion/" +
                "${contrato.archivoOnnx}: ${contrato.clases.size} clases, ${contrato.caracteristicas.size} características, " +
                "${(System.nanoTime() - inicio) / 1_000_000} ms"
        )
        return modelo
    }

    /** Respaldo: el primer .json de la carpeta que declare archivo_onnx. */
    private fun buscarContrato(fuente: FuenteArchivos): String? =
        fuente.lista().filter { it.endsWith(".json") }.firstNotNullOfOrNull { nombre ->
            fuente.leerTexto(nombre)?.takeIf { "\"archivo_onnx\"" in it }
        }
}
