package com.example.pfc_app.inferencia

import com.example.pfc_app.modelos.CaracteristicaModelo
import com.example.pfc_app.modelos.ClaseModelo
import com.example.pfc_app.modelos.ContratoModelo
import com.example.pfc_app.modelos.ReferenciaSinFalla
import com.example.pfc_app.modelos.TipoRegimen
import org.json.JSONArray
import org.json.JSONObject

/**
 * Lee contrato_modelo.json (el esquema que genera entrenar_modelo.py). El
 * contrato del modelo definitivo tendrá las mismas claves; nada de lo que la app
 * usa del modelo (archivo, entrada, salida, clases, orden de las características,
 * referencias) está escrito en el código.
 */
object LectorContrato {

    fun desdeJson(texto: String): ContratoModelo {
        val raiz = JSONObject(texto)
        val formato = raiz.optJSONObject("formato")
        val entrada = formato?.optJSONArray("entradas")?.optJSONObject(0)?.optString("nombre")
            ?.takeIf { it.isNotEmpty() } ?: "caracteristicas"
        val salida = nombreSalida(formato?.optJSONArray("salidas"))

        val clases = objetos(raiz.getJSONArray("clases")).map {
            ClaseModelo(it.getInt("indice"), it.getString("codigo"), it.getString("nombre"))
        }.sortedBy { it.indice }

        val caracteristicas = objetos(raiz.getJSONArray("caracteristicas")).map { c ->
            val ref = c.getJSONObject("referencia_sin_falla")
            CaracteristicaModelo(
                indice = c.getInt("indice"),
                nombre = c.getString("nombre"),
                etiqueta = c.optString("etiqueta", c.getString("nombre")),
                unidad = c.optString("unidad", ""),
                regimen = TipoRegimen.valueOf(c.getString("regimen")),
                columnaLectura = c.optString("columna_lectura", ""),
                estadistico = c.optString("estadistico", estadisticoDelNombre(c.getString("nombre"))),
                referencia = ReferenciaSinFalla(
                    mediana = ref.getDouble("mediana"),
                    q1 = ref.getDouble("q1"),
                    q3 = ref.getDouble("q3"),
                    desv = ref.optDouble("desv", 0.0),
                    limiteInferior = ref.getDouble("limite_inferior"),
                    limiteSuperior = ref.getDouble("limite_superior")
                )
            )
        }.sortedBy { it.indice }

        val regimenes = raiz.optJSONArray("regimenes_requeridos")?.let { textos(it) }
            ?.map { TipoRegimen.valueOf(it) }
            ?: caracteristicas.map { it.regimen }.distinct().sortedBy { it.ordinal }

        val columnas = raiz.optJSONArray("columnas_lectura_requeridas")?.let { textos(it) }
            ?: caracteristicas.flatMap { it.columnaLectura.split("+").map(String::trim) }.filter(String::isNotEmpty).distinct()

        return ContratoModelo(
            versionModelo = raiz.getString("version_modelo"),
            provisional = raiz.optBoolean("provisional", false),
            archivoOnnx = raiz.getString("archivo_onnx"),
            nombreEntrada = entrada,
            nombreSalidaProbabilidades = salida,
            clases = clases,
            caracteristicas = caracteristicas,
            regimenesRequeridos = regimenes,
            columnasLecturaRequeridas = columnas
        )
    }

    /** La salida de probabilidades: la llamada "probabilities" o, si no, la float32 de dos dimensiones. */
    private fun nombreSalida(salidas: JSONArray?): String {
        if (salidas == null) return "probabilities"
        val lista = objetos(salidas)
        lista.firstOrNull { it.optString("nombre") == "probabilities" }?.let { return "probabilities" }
        lista.firstOrNull { it.optString("tipo") == "float32" && (it.optJSONArray("forma")?.length() ?: 0) == 2 }
            ?.let { return it.getString("nombre") }
        return "probabilities"
    }

    private fun estadisticoDelNombre(nombre: String): String = nombre.substringAfterLast('_')

    private fun objetos(a: JSONArray): List<JSONObject> = (0 until a.length()).map { a.getJSONObject(it) }

    private fun textos(a: JSONArray): List<String> = (0 until a.length()).map { a.getString(it) }
}
