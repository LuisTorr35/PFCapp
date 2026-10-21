package com.example.pfc_app.respaldo

import com.example.pfc_app.Recursos
import com.example.pfc_app.datos.AtencionGuardada
import com.example.pfc_app.inferencia.CargadorModelo
import com.example.pfc_app.inferencia.ModeloClasificacion
import com.example.pfc_app.inferencia.MotorInferencia
import com.example.pfc_app.modelos.Atencion
import com.example.pfc_app.modelos.FamiliaGestion
import com.example.pfc_app.modelos.TipoRegimen
import com.example.pfc_app.modelos.Vehiculo
import com.example.pfc_app.procesamiento.ProcesadorCaracteristicas
import com.example.pfc_app.validacion.ValidadorAtencion
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * Armado del documento de Firestore desde las atenciones reales del corte, con su dictamen y el
 * diagnóstico del modelo ONNX, como las guardaría la app. Cada documento debe ocupar menos de
 * 900 KB, también el de la atención más grande.
 */
class DocumentoAtencionTest {
    private val vehiculo = Vehiculo(1, "VEH-001", "Hyundai", "Tucson", 2011, 4, FamiliaGestion.MAF)

    /** La atención del corte como queda en la base: la barométrica de la sesión en baro_kpa. */
    private fun guardada(idTexto: String, id: Long): AtencionGuardada {
        val leida = Recursos.lector.leer(idTexto, idVehiculo = vehiculo.id, id = id)
        val atencion = leida.copy(baroKpa = leida.barometricaSesion)
        val dictamen = validador.validar(leida)
        val diagnostico = motor.predecir(procesador.prepararFila(leida), leida.codigos)
        return AtencionGuardada(atencion, dictamen, diagnostico)
    }

    private fun lecturas(a: Atencion): Int = a.regimenes.values.sumOf { it.lecturas.size }

    @Test
    fun tamanoSegunLasReglasDeFirestore() {
        // Ejemplo de la documentación de Firestore: users/jeff/tasks/my_task_id ocupa 147 bytes.
        val campos = mapOf("type" to "Personal", "done" to false, "priority" to 1, "description" to "Learn Cloud Firestore")
        assertEquals(44, TamanoFirestore.nombre("users/jeff/tasks/my_task_id"))
        assertEquals(147, TamanoFirestore.documento("users/jeff/tasks/my_task_id", campos))
        assertEquals(1 + 1 + 8, TamanoFirestore.valor(listOf(null, true, 2.5)))
        assertEquals(TamanoFirestore.texto("a") + 8, TamanoFirestore.valor(mapOf("a" to 1L)))
    }

    @Test
    fun cadaAtencionDelCorteCabeEnUnDocumentoDeMenosDe900KB() {
        val uid = "KYdezGLDb2eqpzLNqflxyYFcGEm2"
        val tamanos = Recursos.CORTE.mapIndexed { i, idTexto ->
            val g = guardada(idTexto, (i + 1).toLong())
            val id = DocumentoAtencion.idDocumento("a1b2c3d4e5f6", g.atencion.id)
            val campos = DocumentoAtencion.armar(g, vehiculo, uid, id, "2026-10-21T10:00:00")
            Triple(idTexto, lecturas(g.atencion), DocumentoAtencion.bytes(uid, id, campos))
        }
        val mayor = tamanos.maxBy { it.third }
        val masLecturas = tamanos.maxBy { it.second }
        println(
            "Documentos de las 25 atenciones del corte: el más grande es ${mayor.first} " +
                "(${mayor.second} lecturas, ${mayor.third} bytes = ${"%.1f".format(mayor.third / 1024.0)} KB); " +
                "la de más lecturas es ${masLecturas.first} (${masLecturas.second}). " +
                "Promedio ${tamanos.sumOf { it.third } / tamanos.size} bytes."
        )
        assertEquals(25, tamanos.size)
        assertEquals("La de más lecturas es también la de documento más grande", masLecturas.first, mayor.first)
        for ((idTexto, _, bytes) in tamanos) {
            assertTrue("$idTexto ocupa $bytes bytes", bytes < DocumentoAtencion.LIMITE_BYTES)
            assertTrue(bytes < TamanoFirestore.LIMITE_FIRESTORE)
        }
    }

    @Test
    fun elDocumentoLlevaTodaLaAtencion() {
        val g = guardada("ATN-037", 7)
        val d = DocumentoAtencion.armar(g, vehiculo, "uid-a", "inst-7", "2026-10-21T10:00:00")

        assertEquals(DocumentoAtencion.FORMATO, d["formato"])
        assertEquals("inst-7", d["id_documento"])
        assertEquals(7L, d["id_atencion"])
        assertEquals("uid-a", d["uid"])
        assertEquals(g.atencion.fecha, d["fecha"])
        assertEquals("VEH-001", (d["vehiculo"] as Map<*, *>)["codigo"])
        assertEquals("MAF", (d["vehiculo"] as Map<*, *>)["familia_gestion"])
        assertEquals(g.dictamen.resultado.texto, (d["dictamen"] as Map<*, *>)["resultado"])

        val diagnostico = assertNotNullMapa(d["diagnostico"])
        assertEquals(g.diagnostico!!.clase, diagnostico["clase"])
        assertEquals(g.diagnostico!!.parametrosDesviados.size, (diagnostico["parametros_desviados"] as List<*>).size)

        assertEquals(g.atencion.codigos.map { it.codigo }, (d["codigos_dtc"] as List<*>).map { (it as Map<*, *>)["codigo"] })
        assertEquals(g.atencion.contadoresModo06.size, (d["modo06"] as List<*>).size)

        val regimenes = d["regimenes"] as List<*>
        val tipos = TipoRegimen.entries.filter { it in g.atencion.regimenes }.map { it.name }
        assertEquals(tipos, regimenes.map { (it as Map<*, *>)["tipo"] })
        for (r in regimenes) {
            r as Map<*, *>
            val original = g.atencion.regimenes.getValue(TipoRegimen.valueOf(r["tipo"] as String))
            val lecturas = r["lecturas"] as List<*>
            assertEquals(original.lecturas.size, lecturas.size)
            assertEquals(original.muestrasUtiles, r["muestras_utiles"])
            val primera = lecturas.first() as Map<*, *>
            assertEquals(original.lecturas.first().rpm, primera["rpm"])
            // Un parámetro no expuesto viaja como null; la velocidad, como entero.
            if (original.lecturas.first().map == null) assertNull(primera["map"])
            primera["velocidad"]?.let { assertTrue(it is Long) }
        }
    }

    @Test
    fun sinDiagnosticoElCampoQuedaNull() {
        val g = guardada("ATN-037", 1).copy(diagnostico = null)
        val d = DocumentoAtencion.armar(g, null, "uid-a", "x-1", "2026-10-21T10:00:00")
        assertTrue(d.containsKey("diagnostico"))
        assertNull(d["diagnostico"])
        assertNull(d["vehiculo"])
    }

    @Test
    fun elIdDelDocumentoEsFijo() {
        assertEquals("a1b2c3d4e5f6-12", DocumentoAtencion.idDocumento("a1b2c3d4e5f6", 12))
        assertEquals("usuarios/u/atenciones/a1b2c3d4e5f6-12", DocumentoAtencion.ruta("u", "a1b2c3d4e5f6-12"))
    }

    private fun assertNotNullMapa(valor: Any?): Map<*, *> {
        assertNotNull(valor)
        return valor as Map<*, *>
    }

    companion object {
        private lateinit var modelo: ModeloClasificacion
        private lateinit var motor: MotorInferencia
        private lateinit var validador: ValidadorAtencion
        private val procesador = ProcesadorCaracteristicas()

        @BeforeClass
        @JvmStatic
        fun cargar() {
            modelo = CargadorModelo.desdeCarpeta(Recursos.MODELO)
            motor = MotorInferencia(modelo)
            validador = ValidadorAtencion(modelo.contrato)
        }

        @AfterClass
        @JvmStatic
        fun cerrar() {
            modelo.close()
        }
    }
}
