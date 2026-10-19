package com.example.pfc_app.procesamiento

import com.example.pfc_app.Recursos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * RF-05: la fila que calcula la aplicación es la misma que calcula
 * consolidar_lecturas.py. Se compara característica por característica con
 * caracteristicas_nucleo.csv del consolidador, para las 25 atenciones del corte y
 * para las 28 válidas. Objetivo: igualdad exacta tras el redondeo; se tolera como
 * máximo 1e-4 (un dígito en el cuarto decimal) por el orden de suma.
 */
class EquivalenciaConsolidadorTest {
    private val procesador = ProcesadorCaracteristicas()

    private data class Comparacion(val celdas: Int, val noIdenticas: List<String>, val fueraDeTolerancia: List<String>)

    private fun comparar(ids: Collection<String>): Comparacion {
        val tabla = Recursos.tablaConsolidador()
        val columnas = Recursos.columnasConsolidador()
        assertEquals("el consolidador trae las 14 del núcleo universal en orden", ProcesadorCaracteristicas.NUCLEO_UNIVERSAL, columnas)
        var celdas = 0
        val noIdenticas = ArrayList<String>()
        val fuera = ArrayList<String>()
        for (id in ids) {
            val esperado = tabla[id] ?: error("$id no está en la tabla del consolidador")
            val fila = procesador.prepararFila(Recursos.lector.leer(id))
            assertEquals(columnas, fila.valores.keys.toList())
            for (c in columnas) {
                celdas++
                val e = esperado[c]
                val v = fila[c]
                when {
                    e == null && v == null -> Unit
                    e == null || v == null -> {
                        noIdenticas += "$id $c: app=$v consolidador=$e"
                        fuera += "$id $c: app=$v consolidador=$e (vacío en un lado)"
                    }
                    e.toDouble() == v.toDouble() -> Unit
                    else -> {
                        noIdenticas += "$id $c: app=$v consolidador=$e (dif ${abs(v - e)})"
                        if (abs(v - e) > 1e-4 + 1e-12) fuera += "$id $c: app=$v consolidador=$e"
                    }
                }
            }
        }
        return Comparacion(celdas, noIdenticas, fuera)
    }

    @Test
    fun lasVeinticincoDelCorteCoincidenConElConsolidador() {
        val r = comparar(Recursos.CORTE)
        println("RF-05 corte: ${Recursos.CORTE.size} atenciones, ${r.celdas} celdas, ${r.noIdenticas.size} no idénticas")
        r.noIdenticas.forEach { println("  $it") }
        assertEquals(25 * 14, r.celdas)
        assertTrue("Fuera de tolerancia: ${r.fueraDeTolerancia}", r.fueraDeTolerancia.isEmpty())
    }

    @Test
    fun lasVeintiochoValidasCoincidenConElConsolidador() {
        val ids = Recursos.tablaConsolidador().keys
        assertEquals(28, ids.size)
        val r = comparar(ids)
        println("RF-05 válidas: ${ids.size} atenciones, ${r.celdas} celdas, ${r.noIdenticas.size} no idénticas")
        r.noIdenticas.forEach { println("  $it") }
        assertTrue("Fuera de tolerancia: ${r.fueraDeTolerancia}", r.fueraDeTolerancia.isEmpty())
    }

    @Test
    fun atn036NoTieneAjusteDeLargoPlazoEnR3() {
        val fila = procesador.prepararFila(Recursos.lector.leer("ATN-036"))
        assertEquals(null, fila["R3_ltft_b1_pct_media"])
        assertEquals(listOf("R3_ltft_b1_pct_media"), fila.faltantes(ProcesadorCaracteristicas.NUCLEO_UNIVERSAL))
    }
}
