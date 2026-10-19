package com.example.pfc_app.procesamiento

import com.example.pfc_app.Registro
import com.example.pfc_app.modelos.Atencion
import com.example.pfc_app.modelos.FilaCaracteristicas
import com.example.pfc_app.modelos.Regimen
import com.example.pfc_app.modelos.TipoRegimen

/**
 * Convierte una atención en su fila de características, con el mismo cálculo que
 * consolidar_lecturas.py (núcleo universal, RF-05):
 *
 *  1. eje de tiempo t − min(t) de cada régimen (ver [ReglaMuestras.ejeTiempo]);
 *  2. se descartan las filas con t < 15 s y, en R1 y R2, las de rpm > 1100
 *     (las filas sin rpm se conservan);
 *  3. cada estadístico se calcula sobre los valores no vacíos del parámetro, y si
 *     no hay ninguno la característica queda vacía (null);
 *  4. media, desviación muestral, mínimo y máximo redondeados a 4 decimales con
 *     round() de Python; R3_rpm_pendiente es la pendiente de rpm contra t con más
 *     de 5 filas de rpm; R2_trim_total suma las dos medias ya redondeadas.
 */
class ProcesadorCaracteristicas {

    fun prepararFila(atencion: Atencion): FilaCaracteristicas = prepararFila(atencion, NUCLEO_UNIVERSAL)

    /** La fila con las características [nombres], en ese orden (por ejemplo, el orden del contrato). */
    fun prepararFila(atencion: Atencion, nombres: List<String>): FilaCaracteristicas {
        val todas = calcularCaracteristicas(atencion)
        val valores = LinkedHashMap<String, Double?>()
        for (n in nombres) valores[n] = todas[n]
        val faltan = valores.filterValues { it == null }.keys
        Registro.i(
            Registro.PROCESAMIENTO,
            "Fila de la atención ${atencion.id}: ${valores.entries.joinToString { "${it.key}=${it.value}" }}" +
                if (faltan.isEmpty()) "" else ". Sin calcular: ${faltan.joinToString()}",
        )
        return FilaCaracteristicas(valores)
    }

    /**
     * Todas las características agregadas que consolidar_lecturas.py calcula con los
     * parámetros que guarda una [com.example.pfc_app.modelos.Lectura], con sus nombres
     * (R2_rpm_media, R3_carga_calc_pct_max, R2_trim_total, R3_rpm_pendiente, R2_n_muestras...).
     */
    fun calcularCaracteristicas(atencion: Atencion): Map<String, Double?> {
        val fila = LinkedHashMap<String, Double?>()
        for (tipo in TipoRegimen.entries) {
            val regimen = atencion.regimen(tipo) ?: continue
            agregarRegimen(fila, ReglaMuestras.clasificar(regimen))
        }
        return fila
    }

    /** Devuelve el régimen con muestras útiles, descartadas y frecuencia calculadas. */
    fun contarMuestras(regimen: Regimen): Regimen {
        val m = ReglaMuestras.clasificar(regimen)
        Registro.d(
            Registro.PROCESAMIENTO,
            "${regimen.tipo}: ${m.filasBrutas} filas, ${m.utiles.size} útiles, " +
                "${m.descartadasTransitorio} por transitorio, ${m.descartadasTecho} por techo, " +
                "${"%.2f".format(m.frecuenciaHz)} Hz",
        )
        return regimen.copy(
            muestrasUtiles = m.utiles.size,
            descartadasTransitorio = m.descartadasTransitorio,
            descartadasTecho = m.descartadasTecho,
            frecuenciaHz = m.frecuenciaHz,
        )
    }

    private fun agregarRegimen(fila: MutableMap<String, Double?>, m: MuestrasRegimen) {
        // Un régimen sin filas útiles no llega al formato largo del consolidador:
        // no aporta ninguna característica.
        if (m.utiles.isEmpty()) return
        val r = m.tipo.name
        for (p in ParametroLectura.AGREGADOS) {
            val serie = m.utiles.mapNotNull { p.valor(it.lectura) }.toDoubleArray()
            if (serie.isEmpty()) continue
            fila["${r}_${p.canonico}_media"] = Redondeo.caracteristica(Estadisticos.media(serie))
            fila["${r}_${p.canonico}_desv"] = Estadisticos.desviacionMuestral(serie)?.let(Redondeo::caracteristica)
            fila["${r}_${p.canonico}_min"] = Estadisticos.minimo(serie)?.let(Redondeo::caracteristica)
            fila["${r}_${p.canonico}_max"] = Estadisticos.maximo(serie)?.let(Redondeo::caracteristica)
        }
        // pendiente de las revoluciones en la aceleración controlada
        if (m.tipo == TipoRegimen.R3) {
            val conRpm = m.utiles.filter { it.lectura.rpm != null }
            if (conRpm.size > 5) {
                val x = DoubleArray(conRpm.size) { conRpm[it].t }
                val y = DoubleArray(conRpm.size) { conRpm[it].lectura.rpm!! }
                Estadisticos.pendiente(x, y)?.let { fila["R3_rpm_pendiente"] = Redondeo.caracteristica(it) }
            }
        }
        // ajuste de combustible total: suma de las medias de corto y largo plazo ya redondeadas
        val ms = fila["${r}_stft_b1_pct_media"]
        val ml = fila["${r}_ltft_b1_pct_media"]
        if (ms != null && ml != null) fila["${r}_trim_total"] = Redondeo.caracteristica(ms + ml)
        fila["${r}_n_muestras"] = m.utiles.size.toDouble()
    }

    companion object {
        /** NUCLEO_UNIVERSAL de consolidar_lecturas.py: las 14 del modelo principal, en su orden. */
        val NUCLEO_UNIVERSAL: List<String> = listOf(
            "R2_rpm_media", "R2_rpm_desv", "R2_carga_calc_pct_media",
            "R2_stft_b1_pct_media", "R2_stft_b1_pct_desv", "R2_ltft_b1_pct_media",
            "R2_avance_grados_media", "R2_avance_grados_desv", "R2_trim_total",
            "R3_rpm_pendiente", "R3_carga_calc_pct_max",
            "R3_stft_b1_pct_media", "R3_ltft_b1_pct_media", "R3_avance_grados_media",
        )
    }
}
