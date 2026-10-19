package com.example.pfc_app.procesamiento

import com.example.pfc_app.modelos.Atencion
import com.example.pfc_app.modelos.Lectura
import com.example.pfc_app.modelos.Regimen
import com.example.pfc_app.modelos.TipoRegimen
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Los casos borde de consolidar_lecturas.py, uno por uno. */
class ProcesadorCaracteristicasTest {
    private val procesador = ProcesadorCaracteristicas()

    private fun regimen(tipo: TipoRegimen, vararg filas: Lectura) = Regimen(tipo = tipo, lecturas = filas.toList())

    @Test
    fun redondeoComoRoundDePython() {
        // mitad al par sobre el binario exacto: 2.675 es 2.67499999... en binario
        assertEquals(2.67, Redondeo.python(2.675, 2), 0.0)
        assertEquals(10.938, Redondeo.python(10.9375, 3), 0.0)
        assertEquals(0.125, Redondeo.python(0.125, 3), 0.0)
        assertEquals(0.12, Redondeo.python(0.125, 2), 0.0)
        assertEquals(-15.624, Redondeo.caracteristica(-20.312 + 4.688), 0.0)
        assertEquals(651.9441, Redondeo.caracteristica(651.94405194805), 0.0)
    }

    @Test
    fun sumaPorParesComoNumpy() {
        // con más de 8 valores el orden de suma no es el lineal
        val v = DoubleArray(130) { 0.1 * (it + 1) }
        var lineal = 0.0
        for (x in v) lineal += x
        val pares = Estadisticos.sumaPareada(v)
        assertEquals(lineal, pares, 1e-9)
        // pocos valores: suma en orden
        assertEquals(0.1 + 0.2 + 0.3, Estadisticos.sumaPareada(doubleArrayOf(0.1, 0.2, 0.3)), 0.0)
        assertNull(Estadisticos.desviacionMuestral(doubleArrayOf(5.0)))
        assertEquals(1.0, Estadisticos.desviacionMuestral(doubleArrayOf(1.0, 2.0, 3.0))!!, 0.0)
        assertEquals(2.0, Estadisticos.pendiente(doubleArrayOf(0.0, 1.0, 2.0, 3.0), doubleArrayOf(1.0, 3.0, 5.0, 7.0))!!, 1e-12)
    }

    @Test
    fun ejeDeTiempoRelativoMilisegundosEIndice() {
        val relativo = ReglaMuestras.ejeTiempo(listOf(Lectura(100.0), Lectura(101.5), Lectura(103.0)))
        assertArrayEquals(doubleArrayOf(0.0, 1.5, 3.0), relativo, 0.0)
        val ms = ReglaMuestras.ejeTiempo(listOf(Lectura(0.0), Lectura(20_000.0)))
        assertArrayEquals(doubleArrayOf(0.0, 20.0), ms, 0.0)
        // más de la mitad sin instante: un registro por segundo
        val indice = ReglaMuestras.ejeTiempo(listOf(Lectura(Double.NaN), Lectura(Double.NaN), Lectura(7.0)))
        assertArrayEquals(doubleArrayOf(0.0, 1.0, 2.0), indice, 0.0)
    }

    @Test
    fun descartesDeTransitorioYTechoConservandoRpmVacia() {
        val r2 = regimen(
            TipoRegimen.R2,
            Lectura(0.0, rpm = 700.0, stft = 50.0), // transitorio
            Lectura(14.99, rpm = 700.0, stft = 50.0), // transitorio
            Lectura(15.0, rpm = 700.0, stft = 1.0),
            Lectura(16.0, rpm = 1500.0, stft = 50.0), // acelerón: techo de ralentí
            Lectura(17.0, rpm = null, stft = 3.0), // sin rpm: se conserva
            Lectura(18.0, rpm = 1100.0, stft = 2.0),
        )
        val c = procesador.contarMuestras(r2)
        assertEquals(3, c.muestrasUtiles)
        assertEquals(2, c.descartadasTransitorio)
        assertEquals(1, c.descartadasTecho)
        assertEquals(5.0 / 18.0, c.frecuenciaHz, 1e-12)
        val f = procesador.calcularCaracteristicas(Atencion(idVehiculo = 0, fecha = "", regimenes = mapOf(TipoRegimen.R2 to r2)))
        assertEquals(2.0, f["R2_stft_b1_pct_media"]!!, 0.0)
        assertEquals(900.0, f["R2_rpm_media"]!!, 0.0)
        assertEquals(1100.0, f["R2_rpm_max"]!!, 0.0)
        // sin ajuste de largo plazo no hay trim_total
        assertNull(f["R2_trim_total"])
        assertNull(f["R2_ltft_b1_pct_media"])
    }

    @Test
    fun enR3NoHayTechoYLaPendienteNecesitaMasDeCincoFilas() {
        // la primera fila fija t = 0; las demás quedan después del transitorio
        val pocas = regimen(
            TipoRegimen.R3,
            Lectura(0.0, rpm = 800.0),
            *(0 until 5).map { Lectura(15.0 + it, rpm = 1000.0 + 500 * it) }.toTypedArray(),
        )
        val f1 = procesador.calcularCaracteristicas(Atencion(idVehiculo = 0, fecha = "", regimenes = mapOf(TipoRegimen.R3 to pocas)))
        assertNull(f1["R3_rpm_pendiente"])
        assertEquals(3000.0, f1["R3_rpm_max"]!!, 0.0)
        val seis = regimen(
            TipoRegimen.R3,
            Lectura(0.0, rpm = 800.0),
            *(0 until 6).map { Lectura(15.0 + it, rpm = 1000.0 + 500 * it) }.toTypedArray(),
        )
        val f2 = procesador.calcularCaracteristicas(Atencion(idVehiculo = 0, fecha = "", regimenes = mapOf(TipoRegimen.R3 to seis)))
        assertEquals(500.0, f2["R3_rpm_pendiente"]!!, 0.0)
        // con un solo valor la desviación queda vacía, como el NaN de pandas
        val uno = regimen(TipoRegimen.R2, Lectura(0.0, rpm = 700.0), Lectura(20.0, rpm = 710.0))
        val f3 = procesador.calcularCaracteristicas(Atencion(idVehiculo = 0, fecha = "", regimenes = mapOf(TipoRegimen.R2 to uno)))
        assertEquals(710.0, f3["R2_rpm_media"]!!, 0.0)
        assertNull(f3["R2_rpm_desv"])
    }

    @Test
    fun filaEnElOrdenDelNucleoConVaciosEnNull() {
        val fila = procesador.prepararFila(Atencion(idVehiculo = 0, fecha = ""))
        assertEquals(ProcesadorCaracteristicas.NUCLEO_UNIVERSAL, fila.valores.keys.toList())
        assertEquals(14, fila.faltantes(ProcesadorCaracteristicas.NUCLEO_UNIVERSAL).size)
        assertNull(fila.enOrden(ProcesadorCaracteristicas.NUCLEO_UNIVERSAL))
    }

    @Test
    fun csvConBomComillasYComasDentro() {
        val t = Csv.leer("﻿a,b,c\r\n1,\"x, y\",3\r\n\r\n4,\"dijo \"\"hola\"\"\",\r\n")
        assertEquals(listOf("a", "b", "c"), t.encabezados)
        assertEquals(listOf("1", "x, y", "3"), t.filas[0])
        assertEquals(listOf("4", "dijo \"hola\"", ""), t.filas[1])
        assertEquals(2, t.filas.size)
        assertEquals(12.5, MapaColumnas.aNumero("12,5")!!, 0.0)
        assertEquals(12.5, MapaColumnas.aNumero("12.5 %")!!, 0.0)
        assertNull(MapaColumnas.aNumero(""))
        assertNull(MapaColumnas.aNumero("NaN"))
    }

    @Test
    fun mapeoDeEncabezadosComoElConsolidador() {
        val m = MapaColumnas.mapear(listOf("Time (s)", "Engine RPM (rpm)", "Short Term Fuel Trim Bank 1 (%)", "Velocidad", "Timing Advance"))
        assertEquals(0, m["t_seg"])
        assertEquals(1, m["rpm"])
        assertEquals(2, m["stft_b1_pct"])
        assertEquals(3, m["vel_kmh"])
        assertEquals(4, m["avance_grados"])
        assertEquals("temperatura del refrigerante", MapaColumnas.normalizar("Temperatura del refrigerante (°C)"))
    }
}
