package com.example.pfc_app.inferencia

import com.example.pfc_app.Recursos
import com.example.pfc_app.modelos.Atencion
import com.example.pfc_app.modelos.Diagnostico
import com.example.pfc_app.procesamiento.ProcesadorCaracteristicas
import com.example.pfc_app.validacion.ValidadorAtencion
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * Prueba de carga del diagnóstico (RNF-05): repite muchas veces la validación, el cálculo de la
 * fila y la inferencia sobre las 25 atenciones del corte y mide cuánto tarda cada una. Comprueba
 * que ninguna pase de cinco segundos y que el resultado no cambie de una repetición a otra
 * (el motor no acumula estado ni se degrada con el uso).
 */
class CargaInferenciaTest {

    @Test
    fun cargaSostenidaSobreElCorte() {
        val validador = ValidadorAtencion(motor.contrato)
        val procesador = ProcesadorCaracteristicas()
        val atenciones: List<Pair<String, Atencion>> = Recursos.CORTE.map { it to Recursos.lector.leer(it) }
        val primeros = HashMap<String, Diagnostico>()
        val tiemposMs = ArrayList<Double>()

        repeat(REPETICIONES) { ronda ->
            for ((id, atencion) in atenciones) {
                val t0 = System.nanoTime()
                validador.validar(atencion)
                val fila = procesador.prepararFila(atencion, motor.contrato.nombresCaracteristicas)
                val d = motor.predecir(fila, atencion.codigos)
                val ms = (System.nanoTime() - t0) / 1e6
                assertNotNull(id, d)
                // la primera ronda calienta la JVM: no se mide, pero fija el resultado de referencia
                if (ronda == 0) {
                    primeros[id] = d!!
                } else {
                    tiemposMs += ms
                    val ref = primeros.getValue(id)
                    assertEquals("$id ronda $ronda: la clase cambió", ref.clase, d!!.clase)
                    assertEquals("$id ronda $ronda: las probabilidades cambiaron", ref.probabilidades, d.probabilidades)
                }
            }
        }

        val ordenados = tiemposMs.sorted()
        val media = tiemposMs.average()
        val p95 = ordenados[(ordenados.size * 0.95).toInt().coerceAtMost(ordenados.size - 1)]
        val maximo = ordenados.last()
        println(
            "Carga: ${tiemposMs.size} diagnósticos (${atenciones.size} atenciones x ${REPETICIONES - 1} rondas medidas); " +
                "media %.2f ms, p95 %.2f ms, máximo %.2f ms".format(media, p95, maximo)
        )
        assertEquals(atenciones.size * (REPETICIONES - 1), tiemposMs.size)
        assertTrue("El diagnóstico más lento tardó $maximo ms (RNF-05: menos de 5000 ms)", maximo < LIMITE_MS)
    }

    /** Ráfaga de inferencias sobre una misma fila: el tiempo total y el máximo quedan acotados. */
    @Test
    fun milInferenciasSeguidasSobreLaMismaFila() {
        val atencion = Recursos.lector.leer("ATN-004")
        val fila = ProcesadorCaracteristicas().prepararFila(atencion, motor.contrato.nombresCaracteristicas)
        val referencia = motor.predecir(fila, atencion.codigos)!!
        var maximoMs = 0.0
        val inicio = System.nanoTime()
        repeat(RAFAGA) {
            val t0 = System.nanoTime()
            val d = motor.predecir(fila, atencion.codigos)!!
            maximoMs = maxOf(maximoMs, (System.nanoTime() - t0) / 1e6)
            assertEquals(referencia.clase, d.clase)
        }
        val totalMs = (System.nanoTime() - inicio) / 1e6
        println("Ráfaga: $RAFAGA inferencias en %.1f ms (%.3f ms cada una, máximo %.2f ms)".format(totalMs, totalMs / RAFAGA, maximoMs))
        assertTrue("Una inferencia tardó $maximoMs ms", maximoMs < LIMITE_MS)
    }

    companion object {
        private const val REPETICIONES = 21
        private const val LIMITE_MS = 5000.0
        private const val RAFAGA = 1000

        private lateinit var modelo: ModeloClasificacion
        private lateinit var motor: MotorInferencia

        @BeforeClass
        @JvmStatic
        fun cargar() {
            modelo = CargadorModelo.desdeCarpeta(Recursos.MODELO)
            motor = MotorInferencia(modelo)
        }

        @AfterClass
        @JvmStatic
        fun cerrar() {
            modelo.close()
        }
    }
}
