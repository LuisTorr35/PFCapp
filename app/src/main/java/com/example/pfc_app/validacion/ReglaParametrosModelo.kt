package com.example.pfc_app.validacion

import com.example.pfc_app.modelos.Atencion
import com.example.pfc_app.modelos.ContratoModelo
import com.example.pfc_app.modelos.NivelRegla
import com.example.pfc_app.modelos.ResultadoRegla
import com.example.pfc_app.modelos.TipoRegimen
import com.example.pfc_app.procesamiento.ParametroLectura
import com.example.pfc_app.procesamiento.ReglaMuestras

/**
 * Regla 8: están presentes, con al menos el 90 % de muestras válidas, los
 * parámetros que el modelo cargado declara como requeridos
 * (columnas_lectura_requeridas en los regimenes_requeridos del contrato).
 *
 * Decisión de Luis: no rechaza. Si un parámetro no alcanza el 90 % en las
 * muestras útiles, el efecto es SIN_DIAGNOSTICO y la atención se guarda en el
 * historial sin diagnóstico (CU-05, excepción E1). Un régimen ausente no se
 * evalúa aquí, porque ya lo rechazan las reglas 1 y 2.
 */
class ReglaParametrosModelo(
    private val contrato: ContratoModelo,
    private val coberturaMinima: Double = COBERTURA_MINIMA,
) : ReglaBase() {
    override val numero = 8
    override val nivel = NivelRegla.OBLIGATORIA
    override val descripcion =
        "Parámetros que pide el modelo (${contrato.columnasLecturaRequeridas.joinToString()}) con al menos " +
            "${(coberturaMinima * 100).toInt()} % de muestras válidas"

    override fun evaluar(atencion: Atencion): ResultadoRegla {
        val faltantes = ArrayList<String>()
        var primerRegimen: TipoRegimen? = null
        for (tipo in contrato.regimenesRequeridos) {
            val r = atencion.regimen(tipo) ?: continue
            val utiles = ReglaMuestras.clasificar(r).utiles
            for (columna in contrato.columnasLecturaRequeridas) {
                val p = ParametroLectura.desdeColumna(columna)
                val validas = if (p == null || utiles.isEmpty()) 0 else utiles.count { p.valor(it.lectura) != null }
                val cobertura = if (utiles.isEmpty()) 0.0 else validas.toDouble() / utiles.size
                if (cobertura < coberturaMinima) {
                    val nombre = p?.etiqueta ?: columna
                    faltantes += "$nombre en $tipo (${(cobertura * 100).toInt()} % de muestras válidas)"
                    if (primerRegimen == null) primerRegimen = tipo
                }
            }
        }
        if (faltantes.isEmpty()) return cumple()
        return sinDiagnostico(
            "El vehículo no expone los parámetros que requiere el modelo: ${faltantes.joinToString("; ")}. " +
                "Se exige ${(coberturaMinima * 100).toInt()} %; la atención se guarda sin diagnóstico",
            primerRegimen,
        )
    }

    companion object {
        const val COBERTURA_MINIMA = 0.9
    }
}
