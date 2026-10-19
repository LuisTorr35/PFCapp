package com.example.pfc_app.validacion

import com.example.pfc_app.modelos.Atencion
import com.example.pfc_app.modelos.Lectura
import com.example.pfc_app.modelos.NivelRegla
import com.example.pfc_app.modelos.ResultadoRegla
import com.example.pfc_app.modelos.TipoRegimen

/**
 * Regla 6 (graduada): en R3 hay al menos tres rampas que superan 2800 rpm y
 * retornan por debajo de 1200. Máquina de estados sobre todas las filas de R3 con
 * rpm: una rampa cuenta al volver bajo 1200 después de haber superado 2800. Si no
 * se alcanzan, la atención se degrada y se registra el máximo de rpm alcanzado.
 */
class ReglaRampasR3(
    private val minimoRampas: Int = MINIMO_RAMPAS,
    private val umbralSubida: Double = SUBIDA_RPM,
    private val umbralRetorno: Double = RETORNO_RPM
) : ReglaBase() {
    override val numero = 6
    override val nivel = NivelRegla.GRADUADA
    override val descripcion =
        "En R3, al menos $minimoRampas rampas que superan ${texto(umbralSubida)} rpm y vuelven bajo ${texto(umbralRetorno)}"

    override fun evaluar(atencion: Atencion): ResultadoRegla {
        val r3 = atencion.regimen(TipoRegimen.R3) ?: return cumple("No aplica: falta R3 (ver regla 2)")
        val (rampas, maximo) = contarRampas(r3.lecturas)
        return if (rampas >= minimoRampas) {
            cumple(regimen = TipoRegimen.R3)
        } else {
            degrada(
                "R3 con $rampas de $minimoRampas rampas sobre ${texto(umbralSubida)} rpm con retorno bajo " +
                    "${texto(umbralRetorno)} (máximo alcanzado ${maximo?.let { texto(it) } ?: "sin rpm"} rpm)",
                TipoRegimen.R3
            )
        }
    }

    /** Número de rampas completas y máximo de rpm del régimen. */
    fun contarRampas(lecturas: List<Lectura>): Pair<Int, Double?> {
        var arriba = false
        var rampas = 0
        var maximo: Double? = null
        for (l in lecturas) {
            val rpm = l.rpm ?: continue
            if (maximo == null || rpm > maximo) maximo = rpm
            if (rpm > umbralSubida) {
                arriba = true
            } else if (arriba && rpm < umbralRetorno) {
                rampas++
                arriba = false
            }
        }
        return rampas to maximo
    }

    companion object {
        const val MINIMO_RAMPAS = 3
        const val SUBIDA_RPM = 2800.0
        const val RETORNO_RPM = 1200.0
    }
}
