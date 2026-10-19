package com.example.pfc_app.validacion

import com.example.pfc_app.modelos.Atencion
import com.example.pfc_app.modelos.NivelRegla
import com.example.pfc_app.modelos.ResultadoRegla
import com.example.pfc_app.modelos.TipoRegimen
import com.example.pfc_app.procesamiento.ReglaMuestras

/**
 * Regla 4 (obligatoria): el refrigerante en R2 se mantiene por encima de 84 °C en
 * todo el tramo evaluado, es decir, en las muestras útiles que traen refrigerante.
 * Garantiza lazo cerrado y ajustes de combustible interpretables. Si el vehículo
 * no entrega el refrigerante en R2 no se puede verificar y la atención se degrada.
 */
class ReglaTemperaturaR2(private val umbral: Double = UMBRAL_C) : ReglaBase() {
    override val numero = 4
    override val nivel = NivelRegla.OBLIGATORIA
    override val descripcion = "Refrigerante en R2 por encima de ${texto(umbral)} °C en todo el tramo evaluado"

    override fun evaluar(atencion: Atencion): ResultadoRegla {
        val r2 = atencion.regimen(TipoRegimen.R2) ?: return cumple("No aplica: falta R2 (ver regla 1)")
        val ect = ReglaMuestras.clasificar(r2).utiles.mapNotNull { it.lectura.tempRefrigerante }
        if (ect.isEmpty()) {
            return degrada(
                "R2 sin lectura de refrigerante: no se puede verificar la temperatura de trabajo",
                TipoRegimen.R2,
            )
        }
        val minimo = ect.min()
        return if (minimo > umbral) cumple(regimen = TipoRegimen.R2)
        else rechaza(
            "El refrigerante en R2 bajó a ${texto(minimo)} °C: el ralentí caliente exige más de " +
                "${texto(umbral)} °C en todo el tramo (motor sin calentar)",
            TipoRegimen.R2,
        )
    }

    companion object {
        const val UMBRAL_C = 84.0
    }
}
