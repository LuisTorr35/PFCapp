package com.example.pfc_app.modelos

/**
 * Contrato del modelo incluido en la aplicación (contrato_modelo.json, junto al .onnx).
 * La aplicación lee de aquí las clases que declara el modelo, el orden de las
 * características y sus valores de referencia sin falla.
 */
data class ContratoModelo(
    val versionModelo: String,
    val provisional: Boolean,
    val archivoOnnx: String,
    val nombreEntrada: String,
    val nombreSalidaProbabilidades: String,
    val clases: List<ClaseModelo>,
    val caracteristicas: List<CaracteristicaModelo>,
    val regimenesRequeridos: List<TipoRegimen>,
    /** Columnas de la tabla lectura que el modelo necesita: rpm, carga_calculada, stft, ltft, avance_encendido. */
    val columnasLecturaRequeridas: List<String>,
) {
    val nombresCaracteristicas: List<String> get() = caracteristicas.map { it.nombre }

    fun clase(codigo: String): ClaseModelo? = clases.firstOrNull { it.codigo == codigo }

    fun caracteristica(nombre: String): CaracteristicaModelo? = caracteristicas.firstOrNull { it.nombre == nombre }
}

data class ClaseModelo(val indice: Int, val codigo: String, val nombre: String)

data class CaracteristicaModelo(
    val indice: Int,
    val nombre: String,
    val etiqueta: String,
    val unidad: String,
    val regimen: TipoRegimen,
    val columnaLectura: String,
    val estadistico: String,
    val referencia: ReferenciaSinFalla,
)

/** Valores de referencia sin falla, calculados con las atenciones C0 del entrenamiento. */
data class ReferenciaSinFalla(
    val mediana: Double,
    val q1: Double,
    val q3: Double,
    val desv: Double,
    val limiteInferior: Double,
    val limiteSuperior: Double,
)
