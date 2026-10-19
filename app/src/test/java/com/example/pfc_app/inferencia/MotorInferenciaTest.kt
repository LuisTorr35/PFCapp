package com.example.pfc_app.inferencia

import com.example.pfc_app.Recursos
import com.example.pfc_app.modelos.CodigoDTC
import com.example.pfc_app.modelos.Direccion
import com.example.pfc_app.modelos.FilaCaracteristicas
import com.example.pfc_app.modelos.TipoDTC
import com.example.pfc_app.modelos.TipoRegimen
import com.example.pfc_app.procesamiento.Csv
import com.example.pfc_app.procesamiento.MapaColumnas
import com.example.pfc_app.procesamiento.ProcesadorCaracteristicas
import com.example.pfc_app.validacion.ValidadorAtencion
import java.io.File
import kotlin.math.abs
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * El modelo y el contrato se leen de src/main/assets/modelo/ (los mismos que van en
 * el APK) y los casos de casos_prueba_inferencia.csv. Al reemplazar el provisional
 * por el definitivo se reemplazan esos archivos y estas pruebas no cambian.
 */
class MotorInferenciaTest {

    private data class Caso(
        val id: String,
        val clase: String,
        val fila: FilaCaracteristicas,
        val prob: Map<String, Double>,
        val claseOnnx: String
    )

    private fun casos(): List<Caso> {
        val t = Csv.leer(Recursos.CASOS_INFERENCIA.readText())
        val nombres = contrato.nombresCaracteristicas
        val clases = contrato.clases.map { it.codigo }
        return t.comoMapas().map { f ->
            val valores = LinkedHashMap<String, Double?>()
            for (n in nombres) valores[n] = MapaColumnas.aNumero(f[n])
            Caso(
                id = f.getValue("id_atencion"),
                clase = f.getValue("clase_real"),
                fila = FilaCaracteristicas(valores),
                prob = clases.associateWith { MapaColumnas.aNumero(f["prob_$it"])!! },
                claseOnnx = f.getValue("clase_onnx")
            )
        }
    }

    @Test
    fun elContratoSeLeeCompleto() {
        assertTrue(contrato.versionModelo.isNotBlank())
        assertEquals(File(Recursos.MODELO, contrato.archivoOnnx).isFile, true)
        assertEquals("caracteristicas", contrato.nombreEntrada)
        assertEquals("probabilities", contrato.nombreSalidaProbabilidades)
        assertEquals((0 until contrato.clases.size).toList(), contrato.clases.map { it.indice })
        assertEquals(listOf(TipoRegimen.R2, TipoRegimen.R3), contrato.regimenesRequeridos)
        assertEquals(listOf("rpm", "carga_calculada", "stft", "ltft", "avance_encendido"), contrato.columnasLecturaRequeridas)
        // el modelo del núcleo universal pide las mismas 14 que calcula el procesador
        assertEquals(ProcesadorCaracteristicas.NUCLEO_UNIVERSAL, contrato.nombresCaracteristicas)
        val ref = contrato.caracteristica("R2_rpm_media")!!.referencia
        assertTrue(ref.limiteInferior < ref.q1 && ref.q1 <= ref.mediana && ref.mediana <= ref.q3 && ref.q3 < ref.limiteSuperior)
    }

    @Test
    fun lasProbabilidadesCoincidenConLasDelOnnxEnPython() {
        val lista = casos()
        assertTrue(lista.isNotEmpty())
        var difMax = 0.0
        for (c in lista) {
            val d = motor.predecir(c.fila, emptyList())
            assertNotNull(c.id, d)
            for ((clase, p) in c.prob) {
                val dif = abs(d!!.probabilidades.getValue(clase) - p)
                difMax = maxOf(difMax, dif)
                assertEquals("${c.id} $clase", p, d.probabilidades.getValue(clase), 1e-5)
            }
            assertEquals(c.id, c.claseOnnx, d!!.clase)
            assertEquals(contrato.clase(d.clase)!!.nombre, d.nombreClase)
            assertEquals(d.probabilidades.getValue(d.clase), d.probabilidad, 0.0)
        }
        println("Inferencia: ${lista.size} casos, diferencia máxima de probabilidad $difMax")
    }

    @Test
    fun elDiagnosticoLlevaVersionCodigosYDesviados() {
        val c = casos().first { it.id == "ATN-004" }
        val codigos = listOf(CodigoDTC("P0101", TipoDTC.ALMACENADO))
        val d = motor.predecir(c.fila, codigos)!!
        assertEquals(contrato.versionModelo, d.versionModelo)
        assertEquals(contrato.provisional, d.modeloProvisional)
        assertEquals(codigos, d.codigosLeidos)
        // ATN-004 (caudalímetro desconectado): ajuste de corto plazo de -20 % en R2, bajo el límite de los sanos
        val stft = d.parametrosDesviados.firstOrNull { it.parametro == "R2_stft_b1_pct_media" }
        assertNotNull(d.parametrosDesviados.toString(), stft)
        assertEquals(Direccion.MENOR, stft!!.direccion)
        assertEquals(contrato.caracteristica("R2_stft_b1_pct_media")!!.referencia.mediana, stft.referencia, 0.0)
        assertEquals(contrato.caracteristica("R2_stft_b1_pct_media")!!.etiqueta, stft.etiqueta)
        // ordenados de mayor a menor distancia relativa al límite superado
        val distancias = d.parametrosDesviados.map { p ->
            val r = contrato.caracteristica(p.parametro)!!.referencia
            val dist = if (p.direccion == Direccion.MAYOR) p.valor - r.limiteSuperior else r.limiteInferior - p.valor
            val escala = if (r.q3 - r.q1 > 0) r.q3 - r.q1 else r.desv
            dist / escala
        }
        assertEquals(distancias.sortedDescending(), distancias)
        println(
            "ATN-004: ${d.clase} ${d.probabilidad}; desviados ${d.parametrosDesviados.map {
                "${it.parametro} ${it.direccion.flecha}"
            }}"
        )
    }

    @Test
    fun unaFilaIncompletaNoSeDiagnostica() {
        val valores = LinkedHashMap<String, Double?>()
        contrato.nombresCaracteristicas.forEach { valores[it] = 1.0 }
        valores["R3_ltft_b1_pct_media"] = null
        assertNull(motor.predecir(FilaCaracteristicas(valores), emptyList()))
    }

    /** CSV → Atencion → ValidadorAtencion → ProcesadorCaracteristicas → MotorInferencia, para el corte. */
    @Test
    fun deExtremoAExtremoDesdeLosCsvDelCorte() {
        val porId = casos().associateBy { it.id }
        val validador = ValidadorAtencion(contrato)
        val procesador = ProcesadorCaracteristicas()
        var n = 0
        var difMax = 0.0
        val rechazadas = ArrayList<String>()
        for (id in Recursos.CORTE) {
            val caso = porId[id] ?: continue
            val atencion = Recursos.lector.leer(id)
            // el dictamen se informa tal cual; la fila y la inferencia se comprueban igual
            val dictamen = validador.validar(atencion)
            assertTrue("$id: ${dictamen.observacion}", !dictamen.faltanParametrosModelo)
            if (!dictamen.permiteInferencia()) rechazadas += "$id (${dictamen.observacion})"
            val fila = procesador.prepararFila(atencion, contrato.nombresCaracteristicas)
            val d = motor.predecir(fila, atencion.codigos)
            assertNotNull(id, d)
            for ((clase, p) in caso.prob) {
                difMax = maxOf(difMax, abs(d!!.probabilidades.getValue(clase) - p))
                assertEquals("$id $clase", p, d.probabilidades.getValue(clase), 1e-4)
            }
            assertEquals(id, caso.claseOnnx, d!!.clase)
            assertEquals(atencion.codigos, d.codigosLeidos)
            n++
        }
        println("Extremo a extremo: $n atenciones del corte, diferencia máxima de probabilidad $difMax")
        println("Rechazadas por la validación (la app no las diagnosticaría): $rechazadas")
        assertEquals(25, n)
    }

    companion object {
        private lateinit var modelo: ModeloClasificacion
        private lateinit var motor: MotorInferencia
        private val contrato get() = modelo.contrato

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
