package com.example.pfc_app

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/** Comprueba que las pruebas de JVM pueden ejecutar el .onnx con ONNX Runtime de escritorio. */
class OnnxJvmTest {
    @Test
    fun cargaYEjecutaElModeloIncluido() {
        val contrato = org.json.JSONObject(File("src/main/assets/modelo/contrato_modelo.json").readText())
        val bytes = File("src/main/assets/modelo/" + contrato.getString("archivo_onnx")).readBytes()
        val env = OrtEnvironment.getEnvironment()
        env.createSession(bytes).use { sesion ->
            val fila = arrayOf(FloatArray(14) { 1f })
            OnnxTensor.createTensor(env, fila).use { tensor ->
                sesion.run(mapOf("caracteristicas" to tensor)).use { r ->
                    @Suppress("UNCHECKED_CAST")
                    val prob = (r.get("probabilities").get().value as Array<FloatArray>)[0]
                    assertEquals(1.0, prob.sum().toDouble(), 1e-4)
                }
            }
        }
    }
}
