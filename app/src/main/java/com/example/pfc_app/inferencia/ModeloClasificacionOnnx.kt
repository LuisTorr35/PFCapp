package com.example.pfc_app.inferencia

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.example.pfc_app.Registro
import com.example.pfc_app.modelos.ContratoModelo

/**
 * Modelo ONNX ejecutado con ONNX Runtime. La sesión se crea una vez desde los
 * bytes del .onnx, con un solo hilo, que sobra para una fila de 14 valores. La
 * entrada y la salida se buscan por los nombres que declara el contrato: entrada
 * float32 [1, n] y salida "probabilities" float32 [1, clases].
 */
class ModeloClasificacionOnnx(private val bytesModelo: ByteArray, override val contrato: ContratoModelo) : ModeloClasificacion {

    private val entorno: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val opciones = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(1)
        setInterOpNumThreads(1)
    }
    private val sesion: OrtSession = entorno.createSession(bytesModelo, opciones)

    init {
        Registro.i(
            Registro.INFERENCIA,
            "Modelo ${contrato.versionModelo} cargado (${bytesModelo.size} bytes): entradas ${sesion.inputNames}, " +
                "salidas ${sesion.outputNames}"
        )
        require(contrato.nombreEntrada in sesion.inputNames) {
            "El modelo no tiene la entrada '${contrato.nombreEntrada}' que declara el contrato (tiene ${sesion.inputNames})"
        }
        require(contrato.nombreSalidaProbabilidades in sesion.outputNames) {
            "El modelo no tiene la salida '${contrato.nombreSalidaProbabilidades}' (tiene ${sesion.outputNames})"
        }
    }

    @Synchronized
    override fun ejecutar(fila: FloatArray): FloatArray {
        require(fila.size == contrato.caracteristicas.size) {
            "La fila tiene ${fila.size} valores y el modelo espera ${contrato.caracteristicas.size}"
        }
        OnnxTensor.createTensor(entorno, arrayOf(fila)).use { tensor ->
            sesion.run(mapOf(contrato.nombreEntrada to tensor)).use { resultado ->
                val valor = resultado.get(contrato.nombreSalidaProbabilidades)
                    .orElseThrow { IllegalStateException("El modelo no devolvió '${contrato.nombreSalidaProbabilidades}'") }
                    .value
                return probabilidades(valor)
            }
        }
    }

    /** La salida de skl2onnx sin zipmap es float[1][clases]; con zipmap sería una lista de mapas. */
    private fun probabilidades(valor: Any?): FloatArray {
        when (valor) {
            is Array<*> -> {
                val primera = valor.firstOrNull()
                if (primera is FloatArray) return primera.copyOf()
            }

            is FloatArray -> return valor.copyOf()

            is List<*> -> {
                val mapa = valor.firstOrNull() as? Map<*, *>
                if (mapa != null) {
                    return FloatArray(contrato.clases.size) { i ->
                        val clase = contrato.clases.sortedBy { it.indice }[i]
                        ((mapa[clase.codigo] ?: mapa[clase.indice.toLong()]) as? Number)?.toFloat() ?: 0f
                    }
                }
            }
        }
        throw IllegalStateException("Formato de salida del modelo no reconocido: ${valor?.javaClass}")
    }

    override fun close() {
        sesion.close()
        opciones.close()
    }
}
