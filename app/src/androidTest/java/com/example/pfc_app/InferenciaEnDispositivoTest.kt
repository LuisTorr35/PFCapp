package com.example.pfc_app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.pfc_app.inferencia.CargadorModelo
import com.example.pfc_app.inferencia.MotorInferencia
import com.example.pfc_app.modelos.FilaCaracteristicas
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Ejecuta el modelo incluido en la aplicación con ONNX Runtime para Android, en el
 * dispositivo o emulador, y compara cada caso de prueba con las probabilidades que dio
 * onnxruntime en Python al exportar el modelo (casos_prueba_inferencia.csv).
 */
@RunWith(AndroidJUnit4::class)
class InferenciaEnDispositivoTest {

    @Test
    fun elModeloIncluidoReproduceLosCasosDePrueba() {
        val instr = InstrumentationRegistry.getInstrumentation()
        val modelo = CargadorModelo.desdeAssets(instr.targetContext)
        val motor = MotorInferencia(modelo)
        val contrato = motor.contrato
        val lineas = instr.context.assets.open("casos_prueba_inferencia.csv")
            .bufferedReader().readLines().filter { it.isNotBlank() }
        val cabecera = lineas.first().split(",")
        val casos = lineas.drop(1).map { l -> cabecera.zip(l.split(",")).toMap() }

        for (caso in casos) {
            val valores = LinkedHashMap<String, Double?>()
            contrato.nombresCaracteristicas.forEach { valores[it] = caso.getValue(it).toDouble() }
            val diagnostico = motor.predecir(FilaCaracteristicas(valores), emptyList())
                ?: error("${caso["id_atencion"]}: el motor no devolvió diagnóstico")
            for (clase in contrato.clases) {
                assertEquals(
                    "${caso["id_atencion"]} probabilidad de ${clase.codigo}",
                    caso.getValue("prob_${clase.codigo}").toDouble(),
                    diagnostico.probabilidades.getValue(clase.codigo),
                    1e-5,
                )
            }
            assertEquals("${caso["id_atencion"]} clase", caso["clase_onnx"], diagnostico.clase)
        }
        modelo.close()
    }
}
