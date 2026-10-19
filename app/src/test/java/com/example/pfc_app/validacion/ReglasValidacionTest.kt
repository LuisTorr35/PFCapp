package com.example.pfc_app.validacion

import com.example.pfc_app.Recursos
import com.example.pfc_app.inferencia.LectorContrato
import com.example.pfc_app.modelos.EfectoRegla
import com.example.pfc_app.modelos.NivelRegla
import com.example.pfc_app.modelos.ResultadoDictamen
import com.example.pfc_app.modelos.TipoRegimen
import com.example.pfc_app.validacion.AtencionesSinteticas.aceleraciones
import com.example.pfc_app.validacion.AtencionesSinteticas.buena
import com.example.pfc_app.validacion.AtencionesSinteticas.ralenti
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * RF-04: cada regla tiene pruebas para los resultados que su nivel admite.
 * Obligatorias: aceptación y rechazo. Graduadas y condicional: aceptación y
 * degradación. Regla 8: aceptación y sin diagnóstico. Regla 9: aceptación y degradación.
 */
class ReglasValidacionTest {
    private val contrato = LectorContrato.desdeJson(File(Recursos.MODELO, "contrato_modelo.json").readText())
    private val validador = ValidadorAtencion(contrato)

    private fun regla(n: Int) = validador.reglas.single { it.numero == n }

    @Test
    fun hayNueveReglasConSuNivel() {
        assertEquals((1..9).toList(), validador.reglas.map { it.numero })
        val niveles = validador.reglas.associate { it.numero to it.nivel }
        assertEquals(NivelRegla.OBLIGATORIA, niveles[1])
        assertEquals(NivelRegla.OBLIGATORIA, niveles[2])
        assertEquals(NivelRegla.GRADUADA, niveles[3])
        assertEquals(NivelRegla.OBLIGATORIA, niveles[4])
        assertEquals(NivelRegla.CONDICIONAL, niveles[5])
        assertEquals(NivelRegla.GRADUADA, niveles[6])
        assertEquals(NivelRegla.OBLIGATORIA, niveles[7])
        assertEquals(NivelRegla.OBLIGATORIA, niveles[9])
    }

    @Test
    fun laAtencionBuenaEsCompleta() {
        val d = validador.validar(buena())
        assertEquals(d.reglasIncumplidas.toString(), ResultadoDictamen.COMPLETA, d.resultado)
        assertTrue(d.reglas.all { it.efecto == EfectoRegla.CUMPLE })
        assertTrue(d.permiteInferencia())
        assertEquals("", d.observacion)
    }

    // Regla 1 -----------------------------------------------------------------

    @Test
    fun regla1AceptaR2Con45Utiles() {
        // 54 filas: las 9 primeras caen en el transitorio, quedan 45 útiles
        val r = regla(1).evaluar(buena(r2 = ralenti(TipoRegimen.R2, n = 54)))
        assertEquals(EfectoRegla.CUMPLE, r.efecto)
    }

    @Test
    fun regla1RechazaR2Con31UtilesYPideRepetirlo() {
        val at = buena(r2 = ralenti(TipoRegimen.R2, n = 40))
        val r = regla(1).evaluar(at)
        assertEquals(EfectoRegla.RECHAZA, r.efecto)
        assertEquals(TipoRegimen.R2, r.regimen)
        assertEquals("R2 no reunió las 45 muestras útiles (31 de 45)", r.motivo)
        val d = validador.validar(at)
        assertEquals(ResultadoDictamen.RECHAZADA, d.resultado)
        assertEquals(listOf(TipoRegimen.R2), d.regimenesARepetir)
        assertFalse(d.permiteInferencia())
        assertFalse(d.permiteGuardar())
        assertTrue(d.observacion, d.observacion.contains("31 de 45") && d.observacion.contains("Repetir R2"))
    }

    @Test
    fun regla1DescuentaLasMuestrasSobre1100Rpm() {
        // 60 filas con 51 útiles por tiempo, pero 10 de ellas sobre 1100 rpm: quedan 41
        val at = buena(r2 = ralenti(TipoRegimen.R2, n = 60, rpm = { if (it in 20 until 30) 1500.0 else 760.0 }))
        val r = regla(1).evaluar(at)
        assertEquals(EfectoRegla.RECHAZA, r.efecto)
        assertEquals("R2 no reunió las 45 muestras útiles (41 de 45)", r.motivo)
    }

    @Test
    fun regla1RechazaSinR2() {
        val r = regla(1).evaluar(buena(r2 = null))
        assertEquals(EfectoRegla.RECHAZA, r.efecto)
        assertEquals(TipoRegimen.R2, r.regimen)
    }

    // Regla 2 -----------------------------------------------------------------

    @Test
    fun regla2AceptaR3Con45Utiles() {
        assertEquals(EfectoRegla.CUMPLE, regla(2).evaluar(buena()).efecto)
    }

    @Test
    fun regla2RechazaR3CortoYNoDescuentaRpmAltas() {
        // 1 rampa + 10 filas de ralentí = 30 filas cada 1,5 s: 20 útiles (en R3 no hay techo de rpm)
        val at = buena(r3 = aceleraciones(rampas = 1, filasExtra = 10))
        val r = regla(2).evaluar(at)
        assertEquals(EfectoRegla.RECHAZA, r.efecto)
        assertEquals(TipoRegimen.R3, r.regimen)
        assertEquals("R3 no reunió las 45 muestras útiles (20 de 45)", r.motivo)
        assertEquals(listOf(TipoRegimen.R3), validador.validar(at).regimenesARepetir)
    }

    // Regla 3 -----------------------------------------------------------------

    @Test
    fun regla3AceptaConR1() {
        assertEquals(EfectoRegla.CUMPLE, regla(3).evaluar(buena()).efecto)
    }

    @Test
    fun regla3DegradaSinR1() {
        val at = buena(r1 = null)
        val r = regla(3).evaluar(at)
        assertEquals(EfectoRegla.DEGRADA, r.efecto)
        assertEquals("R1 no capturado: el motor llegó caliente", r.motivo)
        val d = validador.validar(at)
        assertEquals(ResultadoDictamen.PARCIAL, d.resultado)
        assertTrue(d.permiteInferencia())
        assertEquals("R1 no capturado: el motor llegó caliente", d.observacion)
    }

    // Regla 4 -----------------------------------------------------------------

    @Test
    fun regla4AceptaRefrigeranteSobre84EnElTramoEvaluado() {
        // el transitorio (t < 15 s) puede estar más frío: no se evalúa
        val at = buena(r2 = ralenti(TipoRegimen.R2, ect = { if (it < 9) 80.0 else 86.0 }))
        assertEquals(EfectoRegla.CUMPLE, regla(4).evaluar(at).efecto)
    }

    @Test
    fun regla4Acepta84GradosExactos() {
        // límite inclusivo: el caso de ATN-015, con muestras útiles en 84,0 °C
        val at = buena(r2 = ralenti(TipoRegimen.R2, ect = { if (it in 25..26) 84.0 else 88.0 }))
        assertEquals(EfectoRegla.CUMPLE, regla(4).evaluar(at).efecto)
    }

    @Test
    fun regla4RechazaR2SinCalentar() {
        val at = buena(r2 = ralenti(TipoRegimen.R2, ect = { if (it == 40) 83.9 else 88.0 }))
        val r = regla(4).evaluar(at)
        assertEquals(EfectoRegla.RECHAZA, r.efecto)
        assertEquals(TipoRegimen.R2, r.regimen)
        assertTrue(r.motivo, r.motivo.contains("83.9 °C") || r.motivo.contains("83,9 °C"))
        assertEquals(listOf(TipoRegimen.R2), validador.validar(at).regimenesARepetir)
    }

    @Test
    fun regla4DegradaSiElVehiculoNoDaRefrigerante() {
        val at = buena(r2 = ralenti(TipoRegimen.R2, ect = { null }))
        val r = regla(4).evaluar(at)
        assertEquals(EfectoRegla.DEGRADA, r.efecto)
        assertEquals(ResultadoDictamen.PARCIAL, validador.validar(at).resultado)
    }

    // Regla 5 -----------------------------------------------------------------

    @Test
    fun regla5AceptaR1EnFrio() {
        assertEquals(EfectoRegla.CUMPLE, regla(5).evaluar(buena()).efecto)
    }

    @Test
    fun regla5DegradaR1QueNoFueEnFrio() {
        val at = buena(r1 = ralenti(TipoRegimen.R1, ect = { 62.0 }))
        val r = regla(5).evaluar(at)
        assertEquals(EfectoRegla.DEGRADA, r.efecto)
        assertTrue(r.motivo, r.motivo.startsWith("R1 no fue en frío") && r.motivo.contains("62 °C"))
        assertEquals(ResultadoDictamen.PARCIAL, validador.validar(at).resultado)
    }

    @Test
    fun regla5NoAplicaSinR1() {
        assertEquals(EfectoRegla.CUMPLE, regla(5).evaluar(buena(r1 = null)).efecto)
    }

    // Regla 6 -----------------------------------------------------------------

    @Test
    fun regla6AceptaTresRampas() {
        val (rampas, maximo) = ReglaRampasR3().contarRampas(aceleraciones().lecturas)
        assertEquals(3, rampas)
        assertEquals(3100.0, maximo!!, 0.0)
        assertEquals(EfectoRegla.CUMPLE, regla(6).evaluar(buena()).efecto)
    }

    @Test
    fun regla6DegradaYRegistraElMaximo() {
        // tres subidas que no pasan de 2663 rpm, como ATN-003 del piloto
        val at = buena(r3 = aceleraciones(pico = 2663.0))
        val r = regla(6).evaluar(at)
        assertEquals(EfectoRegla.DEGRADA, r.efecto)
        assertTrue(r.motivo, r.motivo.contains("0 de 3") && r.motivo.contains("2663 rpm"))
        // una sola rampa sobre 2800 (ATN-005 llegó a 2978) también degrada
        val una = regla(6).evaluar(buena(r3 = aceleraciones(rampas = 1, pico = 2978.0, filasExtra = 60)))
        assertEquals(EfectoRegla.DEGRADA, una.efecto)
        assertTrue(una.motivo, una.motivo.contains("1 de 3") && una.motivo.contains("2978 rpm"))
        assertEquals(ResultadoDictamen.PARCIAL, validador.validar(at).resultado)
    }

    // Regla 7 -----------------------------------------------------------------

    @Test
    fun regla7AceptaVehiculoDetenido() {
        assertEquals(EfectoRegla.CUMPLE, regla(7).evaluar(buena()).efecto)
    }

    @Test
    fun regla7RechazaElRegimenEnQueSeMovio() {
        val at = buena(r3 = aceleraciones(velocidad = { if (it == 50) 12.0 else 0.0 }))
        val r = regla(7).evaluar(at)
        assertEquals(EfectoRegla.RECHAZA, r.efecto)
        assertEquals(TipoRegimen.R3, r.regimen)
        val d = validador.validar(at)
        assertEquals(ResultadoDictamen.RECHAZADA, d.resultado)
        assertEquals(listOf(TipoRegimen.R3), d.regimenesARepetir)
    }

    // Regla 8 -----------------------------------------------------------------

    @Test
    fun regla8AceptaConLosParametrosDelModelo() {
        val r = regla(8).evaluar(buena())
        assertEquals(EfectoRegla.CUMPLE, r.efecto)
        assertFalse(validador.validar(buena()).faltanParametrosModelo)
    }

    @Test
    fun regla8SinDiagnosticoSiFaltaUnParametro() {
        // como ATN-036: el ajuste de largo plazo no llega en R3
        val at = buena(r3 = aceleraciones(ltft = { null }))
        val r = regla(8).evaluar(at)
        assertEquals(EfectoRegla.SIN_DIAGNOSTICO, r.efecto)
        assertTrue(r.motivo, r.motivo.contains("ajuste de largo plazo en R3"))
        val d = validador.validar(at)
        // no rechaza ni degrada: se guarda sin diagnóstico
        assertEquals(ResultadoDictamen.COMPLETA, d.resultado)
        assertTrue(d.faltanParametrosModelo)
        assertTrue(d.permiteGuardar())
        assertFalse(d.permiteInferencia())
    }

    @Test
    fun regla8ExigeNoventaPorCiento() {
        // 61 útiles en R2: 7 vacíos dejan 88,5 % (< 90) y 6 vacíos dejan 90,2 % (cumple)
        val conSiete = buena(r2 = ralenti(TipoRegimen.R2, ltft = { if (it in 10 until 17) null else 1.0 }))
        val conSeis = buena(r2 = ralenti(TipoRegimen.R2, ltft = { if (it in 10 until 16) null else 1.0 }))
        assertEquals(EfectoRegla.SIN_DIAGNOSTICO, regla(8).evaluar(conSiete).efecto)
        assertEquals(EfectoRegla.CUMPLE, regla(8).evaluar(conSeis).efecto)
    }

    // Regla 9 -----------------------------------------------------------------

    @Test
    fun regla9AceptaBarometricaDeArequipa() {
        assertEquals(EfectoRegla.CUMPLE, regla(9).evaluar(buena(baro = 74.0)).efecto)
        assertEquals(EfectoRegla.CUMPLE, regla(9).evaluar(buena(baro = 70.0)).efecto)
        assertEquals(EfectoRegla.CUMPLE, regla(9).evaluar(buena(baro = 80.0)).efecto)
    }

    @Test
    fun regla9DegradaSustituidaAusenteOFueraDeRango() {
        val sustituida = regla(9).evaluar(buena(baro = 101.0))
        assertEquals(EfectoRegla.DEGRADA, sustituida.efecto)
        assertTrue(sustituida.motivo, sustituida.motivo.contains("sustituida por defecto"))
        val ausente = regla(9).evaluar(buena(baro = null))
        assertEquals(EfectoRegla.DEGRADA, ausente.efecto)
        assertTrue(ausente.motivo, ausente.motivo.contains("no registrada"))
        val fuera = regla(9).evaluar(buena(baro = 30.0))
        assertEquals(EfectoRegla.DEGRADA, fuera.efecto)
        assertTrue(fuera.motivo, fuera.motivo.contains("fuera de rango"))
        assertEquals(ResultadoDictamen.PARCIAL, validador.validar(buena(baro = 101.0)).resultado)
    }

    // Dictamen ----------------------------------------------------------------

    @Test
    fun elRechazoPrevaleceSobreLaDegradacionYLaObservacionLosJunta() {
        val at = buena(r1 = null, r2 = ralenti(TipoRegimen.R2, n = 40), baro = 101.0)
        val d = validador.validar(at)
        assertEquals(ResultadoDictamen.RECHAZADA, d.resultado)
        assertEquals(listOf(TipoRegimen.R2), d.regimenesARepetir)
        assertEquals(listOf(1, 3, 9), d.reglasIncumplidas.map { it.numero })
        // primero el rechazo, después las degradaciones
        assertTrue(d.observacion, d.observacion.indexOf("31 de 45") < d.observacion.indexOf("R1 no capturado"))
    }
}
