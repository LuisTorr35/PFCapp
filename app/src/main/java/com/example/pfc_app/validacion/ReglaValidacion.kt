package com.example.pfc_app.validacion

import com.example.pfc_app.modelos.Atencion
import com.example.pfc_app.modelos.EfectoRegla
import com.example.pfc_app.modelos.NivelRegla
import com.example.pfc_app.modelos.ResultadoRegla
import com.example.pfc_app.modelos.TipoRegimen

/** Una regla de pfc_reglas_validacion_v2.md. */
interface ReglaValidacion {
    val numero: Int
    val nivel: NivelRegla
    val descripcion: String
    fun evaluar(atencion: Atencion): ResultadoRegla

    /** Regímenes que hay que repetir cuando la regla rechaza. Por omisión, el del resultado. */
    fun regimenesARepetir(atencion: Atencion, resultado: ResultadoRegla): List<TipoRegimen> =
        if (resultado.efecto == EfectoRegla.RECHAZA) listOfNotNull(resultado.regimen) else emptyList()
}

/** Base con los cuatro constructores de resultado, para que cada regla solo decida. */
abstract class ReglaBase : ReglaValidacion {
    protected fun cumple(motivo: String = "", regimen: TipoRegimen? = null) =
        ResultadoRegla(numero, nivel, descripcion, EfectoRegla.CUMPLE, regimen, motivo)

    protected fun degrada(motivo: String, regimen: TipoRegimen? = null) =
        ResultadoRegla(numero, nivel, descripcion, EfectoRegla.DEGRADA, regimen, motivo)

    protected fun rechaza(motivo: String, regimen: TipoRegimen?) =
        ResultadoRegla(numero, nivel, descripcion, EfectoRegla.RECHAZA, regimen, motivo)

    protected fun sinDiagnostico(motivo: String, regimen: TipoRegimen? = null) =
        ResultadoRegla(numero, nivel, descripcion, EfectoRegla.SIN_DIAGNOSTICO, regimen, motivo)
}

/** Formato de números para los motivos: sin ".0" sobrante y con coma decimal. */
internal fun texto(x: Double): String {
    val r = Math.round(x * 10) / 10.0
    return if (r == Math.floor(r)) r.toLong().toString() else r.toString().replace('.', ',')
}
