package com.example.pfc_app.inferencia

import com.example.pfc_app.Registro
import com.example.pfc_app.modelos.CodigoDTC
import com.example.pfc_app.modelos.ContratoModelo
import com.example.pfc_app.modelos.Diagnostico
import com.example.pfc_app.modelos.Direccion
import com.example.pfc_app.modelos.FilaCaracteristicas
import com.example.pfc_app.modelos.ParametroDesviado

/**
 * Aplica el modelo a la fila y arma el diagnóstico (CU-07): clase más probable con su
 * probabilidad, parámetros desviados frente a la referencia sin falla y códigos leídos.
 *
 * Un parámetro está desviado si queda fuera de los límites de Tukey de los sanos
 * del contrato: sobre limite_superior es MAYOR y bajo limite_inferior es MENOR. La
 * referencia que se muestra es la mediana de los sanos. Se ordenan de mayor a menor
 * distancia al límite superado, dividida por el RIC (q3 − q1) o por la desviación si
 * el RIC es cero, como sugiere regla_desviacion del contrato.
 */
class MotorInferencia(private val modelo: ModeloClasificacion) {
    val contrato: ContratoModelo get() = modelo.contrato

    /** Tiempo de la última inferencia, en milisegundos. */
    var ultimoTiempoMs: Double = 0.0
        private set

    /** null si a la fila le falta alguna característica que pide el modelo. */
    fun predecir(fila: FilaCaracteristicas, codigos: List<CodigoDTC>): Diagnostico? {
        val nombres = contrato.nombresCaracteristicas
        val valores = fila.enOrden(nombres)
        if (valores == null) {
            Registro.w(Registro.INFERENCIA, "Sin diagnóstico: faltan ${fila.faltantes(nombres).joinToString()}")
            return null
        }
        val entrada = FloatArray(valores.size) { valores[it].toFloat() }
        val inicio = System.nanoTime()
        val prob = modelo.ejecutar(entrada)
        ultimoTiempoMs = (System.nanoTime() - inicio) / 1e6

        val clases = contrato.clases.sortedBy { it.indice }
        require(prob.size == clases.size) { "El modelo devolvió ${prob.size} probabilidades para ${clases.size} clases" }
        var mejor = 0
        for (i in prob.indices) if (prob[i] > prob[mejor]) mejor = i
        val clase = clases[mejor]
        val probabilidades = LinkedHashMap<String, Double>()
        for ((i, c) in clases.withIndex()) probabilidades[c.codigo] = prob[i].toDouble()

        val desviados = desviados(nombres, valores)
        val diagnostico = Diagnostico(
            clase = clase.codigo,
            nombreClase = clase.nombre,
            probabilidad = prob[mejor].toDouble(),
            versionModelo = contrato.versionModelo,
            probabilidades = probabilidades,
            parametrosDesviados = desviados,
            codigosLeidos = codigos,
            modeloProvisional = contrato.provisional,
        )
        Registro.i(
            Registro.INFERENCIA,
            "Fila ${nombres.zip(valores.toList()).joinToString { "${it.first}=${it.second}" }}",
        )
        Registro.i(
            Registro.INFERENCIA,
            "Probabilidades ${probabilidades.entries.joinToString { "${it.key}=${"%.4f".format(it.value)}" }}; " +
                "clase ${clase.codigo} (${clase.nombre}); ${desviados.size} desviados; " +
                "${"%.1f".format(ultimoTiempoMs)} ms; modelo ${contrato.versionModelo}",
        )
        return diagnostico
    }

    private fun desviados(nombres: List<String>, valores: DoubleArray): List<ParametroDesviado> {
        val salida = ArrayList<Pair<Double, ParametroDesviado>>()
        for ((i, nombre) in nombres.withIndex()) {
            val c = contrato.caracteristica(nombre) ?: continue
            val ref = c.referencia
            val v = valores[i]
            val (direccion, distancia) = when {
                v > ref.limiteSuperior -> Direccion.MAYOR to (v - ref.limiteSuperior)
                v < ref.limiteInferior -> Direccion.MENOR to (ref.limiteInferior - v)
                else -> continue
            }
            val ric = ref.q3 - ref.q1
            val escala = when {
                ric > 0 -> ric
                ref.desv > 0 -> ref.desv
                else -> 1.0
            }
            salida += (distancia / escala) to ParametroDesviado(
                parametro = nombre,
                valor = v,
                referencia = ref.mediana,
                direccion = direccion,
                etiqueta = c.etiqueta,
                unidad = c.unidad,
            )
        }
        return salida.sortedByDescending { it.first }.map { it.second }
    }
}
