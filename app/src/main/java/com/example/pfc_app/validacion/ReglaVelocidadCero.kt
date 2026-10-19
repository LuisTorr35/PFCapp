package com.example.pfc_app.validacion

import com.example.pfc_app.modelos.Atencion
import com.example.pfc_app.modelos.EfectoRegla
import com.example.pfc_app.modelos.NivelRegla
import com.example.pfc_app.modelos.ResultadoRegla
import com.example.pfc_app.modelos.TipoRegimen

/**
 * Regla 7 (obligatoria): la velocidad del vehículo permanece en cero durante toda
 * la muestra, en todas las filas que traen velocidad. El protocolo es estático:
 * una captura en movimiento introduce carga y deja de ser comparable. Rechaza
 * cada régimen en el que el vehículo se movió.
 */
class ReglaVelocidadCero : ReglaBase() {
    override val numero = 7
    override val nivel = NivelRegla.OBLIGATORIA
    override val descripcion = "Velocidad del vehículo en cero durante toda la muestra"

    override fun evaluar(atencion: Atencion): ResultadoRegla {
        val conMovimiento = regimenesConMovimiento(atencion)
        if (conMovimiento.isEmpty()) return cumple()
        val detalle = conMovimiento.entries.joinToString { (tipo, v) -> "$tipo hasta ${texto(v)} km/h" }
        return rechaza(
            "El vehículo se movió durante la captura ($detalle): el protocolo es con el vehículo detenido y en neutro",
            conMovimiento.keys.first()
        )
    }

    override fun regimenesARepetir(atencion: Atencion, resultado: ResultadoRegla): List<TipoRegimen> =
        if (resultado.efecto == EfectoRegla.RECHAZA) regimenesConMovimiento(atencion).keys.toList() else emptyList()

    /** Régimen → velocidad máxima registrada, solo los que tienen alguna fila con velocidad distinta de cero. */
    private fun regimenesConMovimiento(atencion: Atencion): Map<TipoRegimen, Double> {
        val salida = LinkedHashMap<TipoRegimen, Double>()
        for (tipo in TipoRegimen.entries) {
            val r = atencion.regimen(tipo) ?: continue
            val vel = r.lecturas.mapNotNull { it.velocidad }.filter { it != 0.0 }
            if (vel.isNotEmpty()) salida[tipo] = vel.maxOf { kotlin.math.abs(it) }
        }
        return salida
    }
}
