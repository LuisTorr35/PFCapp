package com.example.pfc_app.modelos

/**
 * La fila de características de una atención, en el orden del núcleo universal.
 * Un valor null significa que no se pudo calcular (falta el régimen o el parámetro).
 */
data class FilaCaracteristicas(val valores: LinkedHashMap<String, Double?>) {
    operator fun get(nombre: String): Double? = valores[nombre]

    /** La fila en el orden que pide el modelo, o null si falta algún valor. */
    fun enOrden(nombres: List<String>): DoubleArray? {
        val salida = DoubleArray(nombres.size)
        for ((i, n) in nombres.withIndex()) salida[i] = valores[n] ?: return null
        return salida
    }

    fun faltantes(nombres: List<String>): List<String> = nombres.filter { valores[it] == null }
}

enum class Direccion(val texto: String, val flecha: String) {
    MAYOR("mayor", "↑"),
    MENOR("menor", "↓");

    companion object {
        fun desdeTexto(texto: String?): Direccion? =
            entries.firstOrNull { it.texto.equals(texto, ignoreCase = true) }
    }
}

/** Parámetro que se desvió de su referencia sin falla (tabla parametro_desviado). */
data class ParametroDesviado(
    val parametro: String,
    val valor: Double,
    val referencia: Double,
    val direccion: Direccion,
    /** Etiqueta para el mecánico y unidad, tomadas del contrato del modelo. */
    val etiqueta: String = parametro,
    val unidad: String = "",
)

/** Resultado del modelo sobre una atención (RF-07). */
data class Diagnostico(
    val id: Long = 0,
    val clase: String,
    val nombreClase: String,
    val probabilidad: Double,
    val versionModelo: String,
    val probabilidades: Map<String, Double> = emptyMap(),
    val parametrosDesviados: List<ParametroDesviado> = emptyList(),
    val codigosLeidos: List<CodigoDTC> = emptyList(),
    val modeloProvisional: Boolean = true,
)
