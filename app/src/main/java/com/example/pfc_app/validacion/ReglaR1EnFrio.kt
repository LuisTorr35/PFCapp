package com.example.pfc_app.validacion

import com.example.pfc_app.modelos.Atencion
import com.example.pfc_app.modelos.NivelRegla
import com.example.pfc_app.modelos.ResultadoRegla
import com.example.pfc_app.modelos.TipoRegimen

/**
 * Regla 5 (condicional): si R1 está presente, su primera temperatura de
 * refrigerante es inferior a 40 °C. Solo aplica cuando el régimen existe; si no
 * se cumple, R1 no fue en frío y la atención se degrada.
 */
class ReglaR1EnFrio(private val umbral: Double = UMBRAL_C) : ReglaBase() {
    override val numero = 5
    override val nivel = NivelRegla.CONDICIONAL
    override val descripcion = "Si hay R1, su refrigerante inicial es menor a ${texto(umbral)} °C"

    override fun evaluar(atencion: Atencion): ResultadoRegla {
        val r1 = atencion.regimen(TipoRegimen.R1)
        if (r1 == null || r1.lecturas.isEmpty()) return cumple("No aplica: no hay R1")
        val inicial = r1.lecturas.firstNotNullOfOrNull { it.tempRefrigerante }
            ?: return degrada(
                "R1 sin lectura de refrigerante: no se puede verificar que fuera en frío",
                TipoRegimen.R1,
            )
        return if (inicial < umbral) cumple(regimen = TipoRegimen.R1)
        else degrada(
            "R1 no fue en frío: el refrigerante inicial era ${texto(inicial)} °C (debe ser menor a ${texto(umbral)} °C)",
            TipoRegimen.R1,
        )
    }

    companion object {
        const val UMBRAL_C = 40.0
    }
}
