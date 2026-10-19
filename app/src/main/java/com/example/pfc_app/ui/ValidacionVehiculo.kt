package com.example.pfc_app.ui

import java.time.Year

/** Error de cada campo del formulario de registro; null si el campo está bien. */
data class ErroresVehiculo(val marca: String? = null, val modelo: String? = null, val anio: String? = null, val cilindros: String? = null) {
    val hayErrores: Boolean get() = listOfNotNull(marca, modelo, anio, cilindros).isNotEmpty()
}

/** Datos ya validados, listos para registrar. */
data class DatosVehiculo(val marca: String, val modelo: String, val anio: Int, val cilindros: Int)

/** Validación del formulario de CU-01 (paso 3 y excepción E.1). */
object ValidacionVehiculo {
    const val ANIO_MINIMO = 1980
    const val CILINDROS_MAXIMO = 16

    fun anioMaximo(): Int = Year.now().value + 1

    fun validar(
        marca: String,
        modelo: String,
        anio: String,
        cilindros: String,
        anioMaximo: Int = anioMaximo()
    ): Pair<DatosVehiculo?, ErroresVehiculo> {
        val anioNum = anio.trim().toIntOrNull()
        val cilNum = cilindros.trim().toIntOrNull()
        val errores = ErroresVehiculo(
            marca = if (marca.isBlank()) "Ingrese la marca" else null,
            modelo = if (modelo.isBlank()) "Ingrese el modelo" else null,
            anio = when {
                anio.isBlank() -> "Ingrese el año"

                anioNum == null || anioNum !in ANIO_MINIMO..anioMaximo ->
                    "El año debe ser un número entre $ANIO_MINIMO y $anioMaximo"

                else -> null
            },
            cilindros = when {
                cilindros.isBlank() -> "Ingrese los cilindros"

                cilNum == null || cilNum !in 1..CILINDROS_MAXIMO ->
                    "Debe ser un número entre 1 y $CILINDROS_MAXIMO"

                else -> null
            }
        )
        if (errores.hayErrores) return null to errores
        return DatosVehiculo(marca.trim(), modelo.trim(), anioNum!!, cilNum!!) to errores
    }
}
