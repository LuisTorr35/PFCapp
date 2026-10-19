package com.example.pfc_app

import com.example.pfc_app.modelos.Atencion
import com.example.pfc_app.modelos.Diagnostico
import com.example.pfc_app.modelos.Dictamen
import com.example.pfc_app.modelos.Vehiculo

/** La atención capturada y diagnosticada que todavía no se guarda en el historial. */
data class AtencionEnCurso(val vehiculo: Vehiculo, val atencion: Atencion, val dictamen: Dictamen, val diagnostico: Diagnostico?)

/** Guarda en memoria la atención en curso para que la pantalla Diagnóstico la muestre y la guarde. */
class SesionAtencion {
    @Volatile
    var actual: AtencionEnCurso? = null
        private set

    fun iniciar(atencion: AtencionEnCurso) {
        actual = atencion
    }

    fun limpiar() {
        actual = null
    }
}
