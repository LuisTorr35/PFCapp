package com.example.pfc_app.procesamiento

import com.example.pfc_app.modelos.Lectura

/**
 * Parámetros de una [Lectura] con sus tres nombres: el canónico de los CSV de
 * registrar_obd.py y consolidar_lecturas.py, el de la columna de la tabla lectura
 * (el que usa columnas_lectura_requeridas del contrato) y su etiqueta para el mecánico.
 * [agregado] marca los que consolidar_lecturas.py resume en estadísticos (PARAMS_AGREGADOS).
 */
enum class ParametroLectura(
    val canonico: String,
    val columnaLectura: String,
    val etiqueta: String,
    val agregado: Boolean,
    val valor: (Lectura) -> Double?,
) {
    RPM("rpm", "rpm", "revoluciones", true, { it.rpm }),
    CARGA("carga_calc_pct", "carga_calculada", "carga calculada", true, { it.cargaCalculada }),
    STFT("stft_b1_pct", "stft", "ajuste de corto plazo", true, { it.stft }),
    LTFT("ltft_b1_pct", "ltft", "ajuste de largo plazo", true, { it.ltft }),
    AVANCE("avance_grados", "avance_encendido", "avance de encendido", true, { it.avanceEncendido }),
    REFRIGERANTE("ect_c", "temp_refrigerante", "temperatura del refrigerante", false, { it.tempRefrigerante }),
    MAF("maf_gps", "maf", "caudal de aire (MAF)", false, { it.maf }),
    MAP("map_kpa", "map", "presión del múltiple (MAP)", false, { it.map }),
    VELOCIDAD("vel_kmh", "velocidad", "velocidad", false, { it.velocidad });

    companion object {
        /** Parámetros resumidos en estadísticos, en el orden de PARAMS_AGREGADOS. */
        val AGREGADOS: List<ParametroLectura> = entries.filter { it.agregado }

        /** Acepta el nombre de la columna de la tabla lectura o el canónico del CSV. */
        fun desdeColumna(nombre: String): ParametroLectura? {
            val n = nombre.trim().lowercase()
            return entries.firstOrNull { it.columnaLectura == n || it.canonico == n }
                ?: when (n) {
                    "tempRefrigerante".lowercase(), "ect", "refrigerante" -> REFRIGERANTE
                    "cargacalculada", "carga" -> CARGA
                    "avanceencendido", "avance" -> AVANCE
                    "vel" -> VELOCIDAD
                    else -> null
                }
        }
    }
}
