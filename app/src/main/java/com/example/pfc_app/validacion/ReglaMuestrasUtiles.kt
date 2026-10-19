package com.example.pfc_app.validacion

import com.example.pfc_app.modelos.Atencion
import com.example.pfc_app.modelos.NivelRegla
import com.example.pfc_app.modelos.ResultadoRegla
import com.example.pfc_app.modelos.TipoRegimen
import com.example.pfc_app.procesamiento.ReglaMuestras

/**
 * Reglas 1 y 2 (obligatorias): el régimen está presente y deja al menos 45
 * muestras útiles con la regla del consolidador (descarte de 15 s y, en R2, de
 * las muestras sobre 1100 rpm). El conteo se rehace desde las lecturas, no se
 * toma de los campos precalculados del régimen.
 */
class ReglaMuestrasUtiles(override val numero: Int, private val tipo: TipoRegimen, private val minimo: Int = ReglaMuestras.MINIMO_UTILES) :
    ReglaBase() {
    override val nivel = NivelRegla.OBLIGATORIA

    override val descripcion: String =
        if (ReglaMuestras.aplicaTecho(tipo)) {
            "$tipo presente con al menos $minimo muestras útiles tras descartar el transitorio de 15 s " +
                "y las muestras sobre 1100 rpm"
        } else {
            "$tipo presente con al menos $minimo muestras útiles tras descartar el transitorio de 15 s"
        }

    override fun evaluar(atencion: Atencion): ResultadoRegla {
        val r = atencion.regimen(tipo)
            ?: return rechaza("$tipo no capturado: hay que capturar ${tipo.nombre.lowercase()}", tipo)
        val utiles = ReglaMuestras.contarUtiles(tipo, r.lecturas)
        return if (utiles >= minimo) {
            cumple(regimen = tipo)
        } else {
            rechaza("$tipo no reunió las $minimo muestras útiles ($utiles de $minimo)", tipo)
        }
    }

    companion object {
        /** Regla 1: R2 con 45 muestras útiles. */
        fun regla1() = ReglaMuestrasUtiles(1, TipoRegimen.R2)

        /** Regla 2: R3 con 45 muestras útiles. */
        fun regla2() = ReglaMuestrasUtiles(2, TipoRegimen.R3)
    }
}
