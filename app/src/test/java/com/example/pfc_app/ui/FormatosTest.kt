package com.example.pfc_app.ui

import com.example.pfc_app.datos.FechasIso
import com.example.pfc_app.datos.ResumenAtencion
import com.example.pfc_app.modelos.CodigoDTC
import com.example.pfc_app.modelos.ContadorModo06
import com.example.pfc_app.modelos.Dictamen
import com.example.pfc_app.modelos.Direccion
import com.example.pfc_app.modelos.EfectoRegla
import com.example.pfc_app.modelos.FamiliaGestion
import com.example.pfc_app.modelos.NivelRegla
import com.example.pfc_app.modelos.ParametroDesviado
import com.example.pfc_app.modelos.ResultadoDictamen
import com.example.pfc_app.modelos.ResultadoRegla
import com.example.pfc_app.modelos.TipoDTC
import com.example.pfc_app.modelos.TipoRegimen
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FormatosTest {
    @Test
    fun numerosConComaDecimal() {
        assertEquals("14,9", Formatos.numero(14.94))
        assertEquals("3,5", Formatos.numero(3.5))
        assertEquals("-0,4", Formatos.numero(-0.4))
        assertEquals("812", Formatos.numeroLegible(812.25))
        assertEquals("9,4", Formatos.numeroLegible(9.43))
        assertEquals("72 %", Formatos.porcentaje(0.7234))
        assertEquals("100 %", Formatos.porcentaje(1.0))
    }

    @Test
    fun lineaDeParametroDesviado() {
        val p = ParametroDesviado("R2_rpm_desv", 14.9, 3.5, Direccion.MAYOR, "Variación de rpm en ralentí caliente", "rpm")
        assertEquals("↑ Mayor que sin falla (14,9 frente a 3,5 rpm)", Formatos.lineaParametro(p))
        val q = ParametroDesviado("R2_avance", 2.0, 8.25, Direccion.MENOR, unidad = "°")
        assertEquals("↓ Menor que sin falla (2,0 frente a 8,3 °)", Formatos.lineaParametro(q))
        val sinReferencia = p.copy(referencia = Double.NaN)
        assertEquals("↑ Mayor que sin falla (14,9 rpm)", Formatos.lineaParametro(sinReferencia))
    }

    @Test
    fun codigosYModo06() {
        val codigos = listOf(CodigoDTC("P0303", TipoDTC.ALMACENADO))
        assertEquals("P0303 almacenado", Formatos.codigos(codigos))
        assertEquals("ninguno", Formatos.codigos(emptyList()))
        assertEquals(
            "P0303 almacenado. Se muestra como contexto, no como fundamento de la clasificación.",
            Formatos.codigosDiagnostico(codigos),
        )
        assertEquals("Ninguno", Formatos.codigosDiagnostico(emptyList()))

        assertEquals("no disponible", Formatos.fallosModo06(emptyList()))
        assertEquals("no disponible", Formatos.fallosModo06(listOf(ContadorModo06(1))))
        assertEquals("sin fallos", Formatos.fallosModo06(listOf(ContadorModo06(1, 0, 0), ContadorModo06(2, 0, 3))))
        assertEquals(
            "cilindro 1, 2; cilindro 3, 12",
            Formatos.fallosModo06(listOf(ContadorModo06(3, 12, 0), ContadorModo06(2, 0, 0), ContadorModo06(1, 2, 1))),
        )
    }

    @Test
    fun familiaYCilindros() {
        assertEquals("MAP", Formatos.familiaCorta(FamiliaGestion.MAP))
        assertEquals("MAF y MAP", Formatos.familiaCorta(FamiliaGestion.AMBOS))
        assertEquals("familia pendiente", Formatos.familiaCorta(null))
        assertEquals("presión del múltiple (MAP)", Formatos.familiaLarga(FamiliaGestion.MAP))
        assertEquals("4 cilindros", Formatos.cilindros(4))
        assertEquals("1 cilindro", Formatos.cilindros(1))
    }

    @Test
    fun indicacionDeRepeticion() {
        val todos = TipoRegimen.entries
        assertEquals("Repita solo R2; R1 y R3 se conservan.", Formatos.indicacionRepeticion(listOf(TipoRegimen.R2), todos))
        assertEquals(
            "Repita solo R2; R3 se conserva.",
            Formatos.indicacionRepeticion(listOf(TipoRegimen.R2), listOf(TipoRegimen.R2, TipoRegimen.R3)),
        )
        assertEquals(
            "Repita solo R2 y R3.",
            Formatos.indicacionRepeticion(listOf(TipoRegimen.R3, TipoRegimen.R2), listOf(TipoRegimen.R2, TipoRegimen.R3)),
        )
    }

    @Test
    fun motivoDelDictamen() {
        val regla = ResultadoRegla(2, NivelRegla.OBLIGATORIA, "45 muestras útiles", EfectoRegla.RECHAZA, TipoRegimen.R2, "R2 no reunió las 45 muestras útiles.")
        val d = Dictamen(ResultadoDictamen.RECHAZADA, listOf(regla), listOf(TipoRegimen.R2))
        assertEquals("R2 no reunió las 45 muestras útiles.", Formatos.motivoDictamen(d, EfectoRegla.RECHAZA))
        assertEquals("Observación", Formatos.motivoDictamen(d.copy(observacion = "Observación")))
    }

    @Test
    fun fechasDelHistorial() {
        assertEquals("28/09/2026 · 11:20", Formatos.fechaHora("2026-09-28T11:20:00"))
        assertEquals("texto raro", Formatos.fechaHora("texto raro"))
        val r = ResumenAtencion(1, "2026-09-28T11:20:00", ResultadoDictamen.COMPLETA, "", "C1", 0.72, "0.1")
        val fila = HistorialViewModel.aFila(r) { if (it == "C1") "Encendido y combustión" else it }
        assertEquals("28/09/2026 · 11:20", fila.fecha)
        assertEquals("Completa · Encendido y combustión, 72 %", fila.resultado)
        val sin = HistorialViewModel.aFila(r.copy(resultado = ResultadoDictamen.PARCIAL, clase = null, probabilidad = null)) { it }
        assertEquals("Parcial · Sin diagnóstico", sin.resultado)
    }

    @Test
    fun fechasIso() {
        val lima = ZoneId.of("America/Lima")
        assertEquals(LocalDateTime.of(2026, 9, 28, 11, 20), FechasIso.interpretar("2026-09-28T11:20:00", lima))
        assertEquals(LocalDateTime.of(2026, 9, 28, 11, 20), FechasIso.interpretar("2026-09-28T16:20:00Z", lima))
        assertEquals(LocalDateTime.of(2026, 9, 28, 11, 20), FechasIso.interpretar("2026-09-28T11:20:00-05:00", lima))
        assertEquals(LocalDateTime.of(2026, 9, 28, 11, 20, 5), FechasIso.interpretar("2026-09-28 11:20:05", lima))
        assertNull(FechasIso.interpretar("", lima))
        assertEquals("2026-09-28T11:20:00", FechasIso.normalizar("2026-09-28T11:20:00.999"))
    }
}
