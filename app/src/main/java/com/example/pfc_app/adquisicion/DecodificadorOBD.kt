package com.example.pfc_app.adquisicion

import com.example.pfc_app.modelos.CodigoDTC
import com.example.pfc_app.modelos.ContadorModo06
import com.example.pfc_app.modelos.TipoDTC
import com.example.pfc_app.procesamiento.Redondeo

/** Campo de [com.example.pfc_app.modelos.Lectura] que llena un PID. */
enum class CampoLectura { RPM, CARGA, MAP, MAF, REFRIGERANTE, STFT, LTFT, AVANCE, VELOCIDAD }

/**
 * PID del modo 01 que consulta registrar_obd.py, con la decodificación SAE J1979
 * de python-OBD (decoders.py) en el mismo orden de operaciones, y el redondeo a
 * 3 decimales de valor(). [nombre] es el del comando en python-OBD.
 * [bytes] son los bytes de datos que espera (A, B, ...).
 */
enum class PidOBD(
    val codigo: Int,
    val nombre: String,
    val canonico: String,
    val bytes: Int,
    val campo: CampoLectura? = null,
    private val formula: (IntArray) -> Double?
) {
    // anillo rápido, en el orden de PARAMETROS de registrar_obd.py
    RPM(0x0C, "RPM", "rpm", 2, CampoLectura.RPM, { d -> ((d[0] shl 8) or d[1]) * 0.25 }),
    ENGINE_LOAD(0x04, "ENGINE_LOAD", "carga_calc_pct", 1, CampoLectura.CARGA, { d -> d[0] * 100.0 / 255.0 }),
    ABSOLUTE_LOAD(0x43, "ABSOLUTE_LOAD", "carga_abs_pct", 2, null, { d -> ((d[0] shl 8) or d[1]) * (100.0 / 255.0) }),
    INTAKE_PRESSURE(0x0B, "INTAKE_PRESSURE", "map_kpa", 1, CampoLectura.MAP, { d -> d[0].toDouble() }),
    MAF(0x10, "MAF", "maf_gps", 2, CampoLectura.MAF, { d -> ((d[0] shl 8) or d[1]) * 0.01 }),
    INTAKE_TEMP(0x0F, "INTAKE_TEMP", "iat_c", 1, null, { d -> (d[0] - 40).toDouble() }),
    COOLANT_TEMP(0x05, "COOLANT_TEMP", "ect_c", 1, CampoLectura.REFRIGERANTE, { d -> (d[0] - 40).toDouble() }),
    SHORT_FUEL_TRIM_1(0x06, "SHORT_FUEL_TRIM_1", "stft_b1_pct", 1, CampoLectura.STFT, { d -> (d[0] - 128) * 100.0 / 128.0 }),
    LONG_FUEL_TRIM_1(0x07, "LONG_FUEL_TRIM_1", "ltft_b1_pct", 1, CampoLectura.LTFT, { d -> (d[0] - 128) * 100.0 / 128.0 }),
    COMMANDED_EQUIV_RATIO(0x44, "COMMANDED_EQUIV_RATIO", "lambda_eq", 2, null, { d -> ((d[0] shl 8) or d[1]) * 0.0000305 }),
    SHORT_FUEL_TRIM_2(0x08, "SHORT_FUEL_TRIM_2", "stft_b2_pct", 1, null, { d -> (d[0] - 128) * 100.0 / 128.0 }),
    LONG_FUEL_TRIM_2(0x09, "LONG_FUEL_TRIM_2", "ltft_b2_pct", 1, null, { d -> (d[0] - 128) * 100.0 / 128.0 }),
    O2_S1_WR_VOLTAGE(0x24, "O2_S1_WR_VOLTAGE", "o2_arriba_v", 4, null, { d -> ((d[2] shl 8) or d[3]) * 8.0 / 65535 }),
    O2_B1S1(0x14, "O2_B1S1", "o2_arriba_v", 2, null, { d -> d[0] / 200.0 }),
    O2_S2_WR_VOLTAGE(0x25, "O2_S2_WR_VOLTAGE", "o2_abajo_v", 4, null, { d -> ((d[2] shl 8) or d[3]) * 8.0 / 65535 }),
    O2_B1S2(0x15, "O2_B1S2", "o2_abajo_v", 2, null, { d -> d[0] / 200.0 }),

    // estado del sistema de combustible: categórico, se consulta pero no se guarda en Lectura
    FUEL_STATUS(0x03, "FUEL_STATUS", "estado_comb", 2, null, { _ -> null }),
    TIMING_ADVANCE(0x0E, "TIMING_ADVANCE", "avance_grados", 1, CampoLectura.AVANCE, { d -> (d[0] - 128) / 2.0 }),
    THROTTLE_POS(0x11, "THROTTLE_POS", "tps_pct", 1, null, { d -> d[0] * 100.0 / 255.0 }),
    BAROMETRIC_PRESSURE(0x33, "BAROMETRIC_PRESSURE", "baro_kpa", 1, null, { d -> d[0].toDouble() }),
    CONTROL_MODULE_VOLTAGE(0x42, "CONTROL_MODULE_VOLTAGE", "volt_modulo", 2, null, { d -> ((d[0] shl 8) or d[1]) * 0.001 }),
    SPEED(0x0D, "SPEED", "vel_kmh", 1, CampoLectura.VELOCIDAD, { d -> d[0].toDouble() }),

    // anillo lento (LENTOS de registrar_obd.py): se consultan cada 10 muestras para
    // conservar la tasa de muestreo del entrenamiento, pero sus valores no se guardan
    FUEL_PRESSURE(0x0A, "FUEL_PRESSURE", "presion_comb_kpa", 1, null, { d -> d[0] * 3.0 }),
    FUEL_RAIL_PRESSURE_ABS(0x59, "FUEL_RAIL_PRESSURE_ABS", "presion_riel_kpa", 2, null, { d -> ((d[0] shl 8) or d[1]) * 10.0 }),
    FUEL_RAIL_PRESSURE_DIRECT(0x23, "FUEL_RAIL_PRESSURE_DIRECT", "presion_riel_dir_kpa", 2, null, { d -> ((d[0] shl 8) or d[1]) * 10.0 }),
    FUEL_INJECT_TIMING(0x5D, "FUEL_INJECT_TIMING", "inyeccion_grados", 2, null, { d -> (((d[0] shl 8) or d[1]) - 26880) / 128.0 }),
    FUEL_RATE(0x5E, "FUEL_RATE", "consumo_lph", 2, null, { d -> ((d[0] shl 8) or d[1]) * 0.05 }),
    COMMANDED_EGR(0x2C, "COMMANDED_EGR", "egr_cmd_pct", 1, null, { d -> d[0] * 100.0 / 255.0 }),
    EGR_ERROR(0x2D, "EGR_ERROR", "egr_error_pct", 1, null, { d -> (d[0] - 128) * 100.0 / 128.0 }),
    EVAPORATIVE_PURGE(0x2E, "EVAPORATIVE_PURGE", "purga_evap_pct", 1, null, { d -> d[0] * 100.0 / 255.0 }),
    CATALYST_TEMP_B1S1(0x3C, "CATALYST_TEMP_B1S1", "cat_b1s1_c", 2, null, { d -> ((d[0] shl 8) or d[1]) / 10.0 - 40 }),
    CATALYST_TEMP_B1S2(0x3E, "CATALYST_TEMP_B1S2", "cat_b1s2_c", 2, null, { d -> ((d[0] shl 8) or d[1]) / 10.0 - 40 }),
    AMBIANT_AIR_TEMP(0x46, "AMBIANT_AIR_TEMP", "amb_c", 1, null, { d -> (d[0] - 40).toDouble() }),
    OIL_TEMP(0x5C, "OIL_TEMP", "aceite_c", 1, null, { d -> (d[0] - 40).toDouble() }),
    RUN_TIME(0x1F, "RUN_TIME", "tiempo_marcha_s", 2, null, { d -> ((d[0] shl 8) or d[1]).toDouble() }),
    FUEL_TYPE(0x51, "FUEL_TYPE", "tipo_comb", 1, null, { _ -> null }),
    FUEL_RAIL_PRESSURE_VAC(0x22, "FUEL_RAIL_PRESSURE_VAC", "presion_riel_vac_kpa", 2, null, { d -> ((d[0] shl 8) or d[1]) * 0.079 });

    /** Comando del modo 01, por ejemplo "010C". */
    val comando: String get() = "01%02X".format(codigo)

    /** Valor ya redondeado a 3 decimales, o null si faltan bytes o el dato es categórico. */
    fun decodificar(datos: List<Int>): Double? {
        if (datos.size < bytes) return null
        val v = formula(datos.toIntArray()) ?: return null
        return Redondeo.lectura(v)
    }

    companion object {
        /**
         * Anillo rápido de registrar_obd.py v4 (PARAMETROS sin CONSTANTES_SESION).
         * Cada entrada es la lista de candidatos de un parámetro: el vehículo expone
         * la sonda de banda ancha o la de banda estrecha, nunca las dos.
         */
        val ANILLO_RAPIDO: List<List<PidOBD>> = listOf(
            listOf(RPM), listOf(ENGINE_LOAD), listOf(ABSOLUTE_LOAD), listOf(INTAKE_PRESSURE), listOf(MAF),
            listOf(INTAKE_TEMP), listOf(COOLANT_TEMP), listOf(SHORT_FUEL_TRIM_1), listOf(LONG_FUEL_TRIM_1),
            listOf(COMMANDED_EQUIV_RATIO), listOf(SHORT_FUEL_TRIM_2), listOf(LONG_FUEL_TRIM_2),
            listOf(O2_S1_WR_VOLTAGE, O2_B1S1), listOf(O2_S2_WR_VOLTAGE, O2_B1S2), listOf(FUEL_STATUS),
            listOf(TIMING_ADVANCE), listOf(THROTTLE_POS), listOf(CONTROL_MODULE_VOLTAGE)
        )

        /** Constantes de sesión: se leen una sola vez (CONSTANTES_SESION). */
        val CONSTANTES_SESION: List<PidOBD> = listOf(BAROMETRIC_PRESSURE, SPEED)

        /** Anillo lento de la versión 3 y 4 del protocolo de captura. */
        val ANILLO_LENTO: List<PidOBD> = listOf(
            FUEL_PRESSURE, FUEL_RAIL_PRESSURE_ABS, FUEL_RAIL_PRESSURE_DIRECT, FUEL_INJECT_TIMING, FUEL_RATE,
            COMMANDED_EGR, EGR_ERROR, EVAPORATIVE_PURGE, CATALYST_TEMP_B1S1, CATALYST_TEMP_B1S2,
            AMBIANT_AIR_TEMP, OIL_TEMP, RUN_TIME, FUEL_TYPE, FUEL_RAIL_PRESSURE_VAC
        )

        /** Lentos que se consultan cada tantas muestras (CADA_LENTOS). */
        const val CADA_LENTOS = 10

        fun desdeCodigo(codigo: Int): PidOBD? = entries.firstOrNull { it.codigo == codigo }
    }
}

/** Códigos de diagnóstico (modos 03 y 07) y contadores del modo 06. */
object DecodificadorDTC {

    private val LETRAS = charArrayOf('P', 'C', 'B', 'U')

    /** Dos bytes SAE J2012 → "P0133". null para 00 00 (relleno, sin código). */
    fun codigo(a: Int, b: Int): String? {
        if (a == 0 && b == 0) return null
        val letra = LETRAS[(a shr 6) and 0x03]
        val digito = (a shr 4) and 0x03
        return "$letra$digito%01X%02X".format(a and 0x0F, b)
    }

    /**
     * Los códigos de una respuesta de modo 03 o 07. En CAN el byte que sigue al
     * modo es el número de códigos; en línea K y J1850 no existe. Se reúnen los de
     * todos los módulos que contestan, sin repetir.
     */
    fun codigos(mensajes: List<MensajeOBD>, modoRespuesta: Int, esCan: Boolean, tipo: TipoDTC): List<CodigoDTC> {
        val salida = LinkedHashMap<String, CodigoDTC>()
        for (m in mensajes) {
            if (m.datos.isEmpty() || m.datos[0] != modoRespuesta) continue
            val inicio = if (esCan) 2 else 1
            var i = inicio
            while (i + 1 < m.datos.size) {
                val c = codigo(m.datos[i], m.datos[i + 1])
                if (c != null) salida.putIfAbsent(c, CodigoDTC(c, tipo, DescripcionesDTC.descripcion(c)))
                i += 2
            }
        }
        return salida.values.toList()
    }

    /** MID del modo 06 para el cilindro 1 a 8 (MONITOR_MISFIRE_CYLINDER_n de python-OBD). */
    fun midCilindro(cilindro: Int): Int = 0xA1 + cilindro

    /** TID del promedio de los últimos diez ciclos (MISFIRE_AVERAGE) y del ciclo actual (MISFIRE_COUNT). */
    const val TID_PROMEDIO = 0x0B
    const val TID_ACTUAL = 0x0C

    /**
     * Respuesta del modo 06 en CAN: 46 seguido de bloques de 9 bytes
     * MID TID UAS valor(2) mínimo(2) máximo(2). Los contadores de fallo de
     * encendido van con UAS 0x24 (conteo), así que el valor es el entero tal cual.
     */
    fun contador(cilindro: Int, datos: List<Int>): ContadorModo06? {
        val mid = midCilindro(cilindro)
        if (datos.size < 10 || datos[0] != 0x46 || datos[1] != mid) return null
        var actual: Int? = null
        var promedio: Int? = null
        var i = 1
        while (i + 9 <= datos.size) {
            val b = datos.subList(i, i + 9)
            if (b[0] == mid) {
                val valor = (b[3] shl 8) or b[4]
                when (b[1]) {
                    TID_PROMEDIO -> promedio = valor
                    TID_ACTUAL -> actual = valor
                }
            }
            i += 9
        }
        if (actual == null && promedio == null) return null
        return ContadorModo06(cilindro, conteoActual = actual, conteoPromedio = promedio)
    }
}
