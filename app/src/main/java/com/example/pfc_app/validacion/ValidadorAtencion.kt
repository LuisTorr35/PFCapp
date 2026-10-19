package com.example.pfc_app.validacion

import com.example.pfc_app.Registro
import com.example.pfc_app.modelos.Atencion
import com.example.pfc_app.modelos.ContratoModelo
import com.example.pfc_app.modelos.Dictamen
import com.example.pfc_app.modelos.EfectoRegla
import com.example.pfc_app.modelos.ResultadoDictamen
import com.example.pfc_app.modelos.TipoRegimen

/**
 * Aplica las nueve reglas y emite el dictamen completa, parcial o rechazada (CU-04).
 * Alguna regla rechaza → rechazada, con los regímenes a repetir; si no, alguna
 * degrada → parcial; si no, completa. La regla 8 no cambia el dictamen: marca
 * que la atención se guarda sin diagnóstico ([Dictamen.faltanParametrosModelo]).
 */
class ValidadorAtencion(private val contrato: ContratoModelo) {

    val reglas: List<ReglaValidacion> = listOf(
        ReglaMuestrasUtiles.regla1(),
        ReglaMuestrasUtiles.regla2(),
        ReglaPresenciaR1(),
        ReglaTemperaturaR2(),
        ReglaR1EnFrio(),
        ReglaRampasR3(),
        ReglaVelocidadCero(),
        ReglaParametrosModelo(contrato),
        ReglaBarometrica()
    )

    fun validar(atencion: Atencion): Dictamen {
        val resultados = reglas.map { regla ->
            regla.evaluar(atencion).also { r ->
                Registro.d(
                    Registro.VALIDACION,
                    "Regla ${r.numero} (${r.nivel.texto}): ${r.efecto}" + if (r.motivo.isEmpty()) "" else ". ${r.motivo}"
                )
            }
        }
        val aRepetir = reglas.zip(resultados)
            .flatMap { (regla, r) -> regla.regimenesARepetir(atencion, r) }
            .distinct()
            .sortedBy(TipoRegimen::ordinal)
        val resultado = when {
            resultados.any { it.efecto == EfectoRegla.RECHAZA } -> ResultadoDictamen.RECHAZADA
            resultados.any { it.efecto == EfectoRegla.DEGRADA } -> ResultadoDictamen.PARCIAL
            else -> ResultadoDictamen.COMPLETA
        }
        val sinDiagnostico = resultados.any { it.efecto == EfectoRegla.SIN_DIAGNOSTICO }
        val dictamen = Dictamen(
            resultado = resultado,
            reglas = resultados,
            regimenesARepetir = aRepetir,
            observacion = observacion(resultado, resultados.filter { it.efecto != EfectoRegla.CUMPLE }, aRepetir),
            faltanParametrosModelo = sinDiagnostico
        )
        Registro.i(
            Registro.VALIDACION,
            "Dictamen de la atención ${atencion.id}: ${resultado.texto}" +
                (if (aRepetir.isEmpty()) "" else ", repetir ${aRepetir.joinToString()}") +
                (if (sinDiagnostico) ", sin diagnóstico (regla 8)" else "") +
                (if (dictamen.observacion.isEmpty()) "" else ". ${dictamen.observacion}")
        )
        return dictamen
    }

    /** Motivos legibles: primero los rechazos, luego las degradaciones y al final la regla 8. */
    private fun observacion(
        resultado: ResultadoDictamen,
        incumplidas: List<com.example.pfc_app.modelos.ResultadoRegla>,
        aRepetir: List<TipoRegimen>
    ): String {
        val orden = listOf(EfectoRegla.RECHAZA, EfectoRegla.DEGRADA, EfectoRegla.SIN_DIAGNOSTICO)
        val motivos = orden.flatMap { e -> incumplidas.filter { it.efecto == e }.map { it.motivo } }
        val partes = ArrayList<String>(motivos)
        if (resultado == ResultadoDictamen.RECHAZADA && aRepetir.isNotEmpty()) {
            partes += "Repetir ${aRepetir.joinToString(" y ")}"
        }
        return partes.joinToString(". ")
    }
}
