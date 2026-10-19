package com.example.pfc_app.validacion

import com.example.pfc_app.Recursos
import com.example.pfc_app.inferencia.LectorContrato
import com.example.pfc_app.modelos.ResultadoDictamen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Valida desde los CSV reales las 25 atenciones del corte e informa el dictamen de
 * cada una. Las reglas no se ajustan para que pasen: el dictamen se imprime tal
 * cual. Lo que sí se exige es que ninguna del corte quede sin diagnóstico por la
 * regla 8, porque el modelo se entrenó con su fila completa.
 */
class ValidacionCorteTest {
    private val contrato = LectorContrato.desdeJson(File(Recursos.MODELO, "contrato_modelo.json").readText())
    private val validador = ValidadorAtencion(contrato)

    @Test
    fun dictamenDeLasVeinticincoDelCorte() {
        val conteo = sortedMapOf<String, Int>()
        val informe = StringBuilder("Dictamen de las 25 atenciones del corte:\n")
        for (id in Recursos.CORTE) {
            val at = Recursos.lector.leer(id)
            val d = validador.validar(at)
            conteo.merge(d.resultado.texto, 1, Int::plus)
            val utiles = at.regimenes.values.joinToString(" ") { "${it.tipo}=${it.muestrasUtiles}" }
            informe.append("  $id ${d.resultado.texto.uppercase()} [$utiles, baro ${at.baroKpa}]")
            if (d.regimenesARepetir.isNotEmpty()) informe.append(" repetir ${d.regimenesARepetir}")
            if (d.faltanParametrosModelo) informe.append(" SIN DIAGNÓSTICO")
            informe.append(" :: reglas ${d.reglasIncumplidas.joinToString { "${it.numero}" }} :: ${d.observacion}\n")
            assertFalse("$id quedó sin diagnóstico por la regla 8: ${d.observacion}", d.faltanParametrosModelo)
        }
        informe.append("Resumen: $conteo")
        println(informe)
    }

    @Test
    fun atn036SeGuardaSinDiagnostico() {
        val d = validador.validar(Recursos.lector.leer("ATN-036"))
        println("ATN-036: ${d.resultado.texto}, sin diagnóstico=${d.faltanParametrosModelo} :: ${d.observacion}")
        assertTrue(d.faltanParametrosModelo)
        assertTrue(d.permiteGuardar())
        assertFalse(d.permiteInferencia())
        assertEquals(8, d.reglas.single { it.numero == 8 && it.efecto.name == "SIN_DIAGNOSTICO" }.numero)
    }

    @Test
    fun atn033TraeR1EnFrioYNoSeDegradaPorR1() {
        val d = validador.validar(Recursos.lector.leer("ATN-033"))
        val incumplidas = d.reglasIncumplidas.map { it.numero }
        assertFalse(3 in incumplidas)
        assertFalse(5 in incumplidas)
        assertTrue(d.resultado != ResultadoDictamen.RECHAZADA)
    }
}
