package com.example.pfc_app.modelos

/**
 * Familia de gestión del motor, determinada en la primera conexión con el vehículo
 * a partir de los parámetros que expone (CU-02, paso 6).
 */
enum class FamiliaGestion(val texto: String, val descripcion: String) {
    MAF("MAF", "caudal de aire (MAF)"),
    MAP("MAP", "presión del múltiple (MAP)"),
    AMBOS("ambos", "MAF y MAP");

    companion object {
        fun desdeTexto(texto: String?): FamiliaGestion? =
            entries.firstOrNull { it.texto.equals(texto, ignoreCase = true) || it.name == texto }

        /** Decide la familia según los PID que contestan: 0x10 (MAF) y 0x0B (MAP). */
        fun desdeSensores(tieneMaf: Boolean, tieneMap: Boolean): FamiliaGestion? = when {
            tieneMaf && tieneMap -> AMBOS
            tieneMaf -> MAF
            tieneMap -> MAP
            else -> null
        }
    }
}

/**
 * Vehículo registrado bajo un código propio VEH-###, sin placa, sin número de
 * identificación vehicular y sin datos del propietario (RF-01).
 */
data class Vehiculo(
    val id: Long = 0,
    val codigo: String,
    val marca: String,
    val modelo: String,
    val anio: Int,
    val cilindros: Int,
    val familiaGestion: FamiliaGestion? = null,
)
