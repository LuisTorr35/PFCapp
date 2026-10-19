package com.example.pfc_app.procesamiento

import com.example.pfc_app.modelos.Lectura
import com.example.pfc_app.modelos.Regimen
import com.example.pfc_app.modelos.TipoRegimen

/** Una fila que sobrevive a los dos descartes, con su segundo relativo [t]. */
data class MuestraUtil(val t: Double, val lectura: Lectura)

/** Resultado de aplicar a un régimen la regla de muestras útiles del consolidador. */
class MuestrasRegimen(
    val tipo: TipoRegimen,
    /** Eje de tiempo de todas las filas, en el orden grabado. */
    val tiempos: DoubleArray,
    val utiles: List<MuestraUtil>,
    val descartadasTransitorio: Int,
    val descartadasTecho: Int,
    /** max(t) − min(t) de todas las filas, 0 con una sola fila. */
    val duracionBruta: Double,
    val filasBrutas: Int
) {
    /** (n − 1) / duración bruta, como tasa_hz de diagnostico_importacion.csv (sin redondear). */
    val frecuenciaHz: Double get() = if (duracionBruta > 0) (filasBrutas - 1) / duracionBruta else 0.0
}

/**
 * La regla con la que consolidar_lecturas.py decide qué filas de un régimen sirven
 * (procesar_archivo). Es la misma para contar muestras útiles en la captura, para
 * validar y para calcular las características, así no puede haber dos conteos.
 */
object ReglaMuestras {
    /** Transitorio inicial que se descarta de cada régimen. */
    const val SEGUNDOS_DESCARTE = 15.0

    /** En R1 y R2 toda muestra sobre este valor es un acelerón fuera de protocolo. */
    const val TECHO_RALENTI_RPM = 1100.0

    /** Mínimo de muestras útiles de las reglas 1 y 2. */
    const val MINIMO_UTILES = 45

    fun aplicaTecho(tipo: TipoRegimen): Boolean = tipo == TipoRegimen.R1 || tipo == TipoRegimen.R2

    /**
     * eje_tiempo(): segundo relativo desde el inicio del régimen, t − min(t). Si más
     * de la mitad de los instantes falta (NaN), se asume un registro por segundo.
     * Si el máximo supera 10 000 se asume que venía en milisegundos.
     */
    fun ejeTiempo(lecturas: List<Lectura>): DoubleArray {
        val n = lecturas.size
        val validos = lecturas.count { !it.instante.isNaN() }
        if (validos > n * 0.5) {
            val minimo = lecturas.filter { !it.instante.isNaN() }.minOf { it.instante }
            var t = DoubleArray(n) { lecturas[it].instante - minimo }
            val maximo = t.filter { !it.isNaN() }.max()
            if (maximo > 10_000) t = DoubleArray(n) { t[it] / 1000.0 }
            return t
        }
        return DoubleArray(n) { it.toDouble() }
    }

    fun clasificar(regimen: Regimen): MuestrasRegimen = clasificar(regimen.tipo, regimen.lecturas)

    fun clasificar(tipo: TipoRegimen, lecturas: List<Lectura>): MuestrasRegimen {
        val t = ejeTiempo(lecturas)
        val conTiempo = t.filter { !it.isNaN() }
        val duracion = if (lecturas.size > 1 && conTiempo.isNotEmpty()) conTiempo.max() - conTiempo.min() else 0.0
        val utiles = ArrayList<MuestraUtil>(lecturas.size)
        var transitorio = 0
        var techo = 0
        for ((i, l) in lecturas.withIndex()) {
            // NaN >= 15 es falso: una fila sin instante cae con el transitorio, como en pandas
            if (!(t[i] >= SEGUNDOS_DESCARTE)) {
                transitorio++
                continue
            }
            val rpm = l.rpm
            if (aplicaTecho(tipo) && rpm != null && rpm > TECHO_RALENTI_RPM) {
                techo++
                continue
            }
            utiles += MuestraUtil(t[i], l)
        }
        return MuestrasRegimen(tipo, t, utiles, transitorio, techo, duracion, lecturas.size)
    }

    /** Conteo de útiles de una lista de lecturas que todavía se está grabando. */
    fun contarUtiles(tipo: TipoRegimen, lecturas: List<Lectura>): Int = clasificar(tipo, lecturas).utiles.size
}
