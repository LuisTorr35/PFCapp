package com.example.pfc_app.validacion

import com.example.pfc_app.modelos.Atencion
import com.example.pfc_app.modelos.NivelRegla
import com.example.pfc_app.modelos.ResultadoRegla
import com.example.pfc_app.modelos.TipoRegimen

/**
 * Regla 3 (graduada): R1 se captura cuando el vehículo llega frío y se registra su
 * ausencia cuando no. Su falta degrada la atención a parcial, nunca la rechaza,
 * porque el vehículo que llega caliente es el caso normal del taller.
 */
class ReglaPresenciaR1 : ReglaBase() {
    override val numero = 3
    override val nivel = NivelRegla.GRADUADA
    override val descripcion = "R1 capturado cuando el vehículo llega frío; su ausencia degrada a parcial"

    override fun evaluar(atencion: Atencion): ResultadoRegla {
        val r1 = atencion.regimen(TipoRegimen.R1)
        return if (r1 != null && r1.lecturas.isNotEmpty()) {
            cumple(regimen = TipoRegimen.R1)
        } else {
            degrada("R1 no capturado: el motor llegó caliente", TipoRegimen.R1)
        }
    }
}
