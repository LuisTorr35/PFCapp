package com.example.pfc_app.respaldo

import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La subida que corre el trabajo de respaldo, con un RespaldoNube falso y una base falsa:
 * sube las pendientes de la cuenta con sesión, no duplica, reintenta y no toca las de otra cuenta.
 */
class SubidaRespaldoTest {
    /** Atención local: id → (uid con que se guardó, respaldada). */
    private class BaseFalsa(vararg atenciones: Pair<Long, String?>) : AtencionesPorRespaldar {
        val uid = linkedMapOf(*atenciones)
        val respaldadas = mutableSetOf<Long>()
        var campoGrande: String? = null

        /** Se ejecuta al leer el documento de una atención (por ejemplo, para guardar otra en medio). */
        var alLeer: (Long) -> Unit = {}

        fun guardar(id: Long, cuenta: String) {
            uid[id] = cuenta
        }

        override fun pendientes(uid: String): List<Long> = this.uid.filter { it.value == uid && it.key !in respaldadas }.keys.sorted()

        override fun documento(idAtencion: Long, uid: String): DocumentoRespaldo? {
            alLeer(idAtencion)
            if (idAtencion !in this.uid) return null
            val campos = mutableMapOf<String, Any?>("id_atencion" to idAtencion, "uid" to uid)
            campoGrande?.let { campos["relleno"] = it }
            return DocumentoRespaldo("inst-$idAtencion", campos)
        }

        override fun marcarRespaldada(idAtencion: Long) {
            respaldadas += idAtencion
        }
    }

    /** Nube en memoria con la semántica de set: el mismo id reemplaza el documento. */
    private class NubeFalsa : RespaldoNube {
        val documentos = linkedMapOf<String, Map<String, Any?>>()
        var escrituras = 0
        var fallosPendientes = 0

        override suspend fun subir(uid: String, idDocumento: String, documento: Map<String, Any?>) {
            if (fallosPendientes > 0) {
                fallosPendientes--
                throw IllegalStateException("UNAVAILABLE: sin respuesta del servidor")
            }
            escrituras++
            documentos[DocumentoAtencion.ruta(uid, idDocumento)] = documento
        }

        override suspend fun listarIds(uid: String): List<String> =
            documentos.keys.filter { it.startsWith("usuarios/$uid/") }.map { it.substringAfterLast('/') }
    }

    private fun subida(base: BaseFalsa, nube: NubeFalsa, uid: () -> String?) = SubidaRespaldo(base, nube, uid, EmptyCoroutineContext)

    @Test
    fun subeLasPendientesDeLaCuentaYLasMarca() = runTest {
        val base = BaseFalsa(1L to "A", 2L to "A", 3L to "A")
        val nube = NubeFalsa()

        val r = subida(base, nube) { "A" }.ejecutar()

        assertEquals(ResultadoRespaldo.Completo(3), r)
        assertEquals(setOf(1L, 2L, 3L), base.respaldadas)
        assertEquals(listOf("inst-1", "inst-2", "inst-3"), nube.listarIds("A"))
        assertEquals("A", nube.documentos.getValue("usuarios/A/atenciones/inst-2")["uid"])
    }

    @Test
    fun noDuplica() = runTest {
        val base = BaseFalsa(1L to "A", 2L to "A")
        val nube = NubeFalsa()
        val s = subida(base, nube) { "A" }

        s.ejecutar()
        val segunda = s.ejecutar()

        assertEquals("Lo respaldado no se vuelve a subir", ResultadoRespaldo.Completo(0), segunda)
        assertEquals(2, nube.escrituras)

        // Aunque una atención se suba de nuevo (marca perdida), el id fijo reemplaza el documento.
        base.respaldadas.remove(1L)
        s.ejecutar()
        assertEquals(3, nube.escrituras)
        assertEquals(2, nube.documentos.size)
    }

    @Test
    fun siFallaUnaSubidaReintentaYLuegoCompletaSinDuplicar() = runTest {
        val base = BaseFalsa(1L to "A", 2L to "A")
        val nube = NubeFalsa().apply { fallosPendientes = 1 }
        val s = subida(base, nube) { "A" }

        val primera = s.ejecutar()
        assertTrue(primera is ResultadoRespaldo.Reintentar)
        assertEquals(0, (primera as ResultadoRespaldo.Reintentar).subidas)
        assertTrue("Nada queda marcado si la nube no confirmó", base.respaldadas.isEmpty())

        assertEquals(ResultadoRespaldo.Completo(2), s.ejecutar())
        assertEquals(setOf(1L, 2L), base.respaldadas)
        assertEquals(2, nube.documentos.size)
    }

    @Test
    fun loSubidoAntesDelFalloQuedaMarcado() = runTest {
        val base = BaseFalsa(1L to "A", 2L to "A")
        val nube = object : RespaldoNube by NubeFalsa() {
            var n = 0

            override suspend fun subir(uid: String, idDocumento: String, documento: Map<String, Any?>) {
                if (++n == 2) throw IllegalStateException("UNAVAILABLE")
            }
        }

        val r = SubidaRespaldo(base, nube, { "A" }, EmptyCoroutineContext).ejecutar()

        assertEquals(1, (r as ResultadoRespaldo.Reintentar).subidas)
        assertEquals(setOf(1L), base.respaldadas)
    }

    @Test
    fun lasPendientesDeOtraCuentaNoSeSubenALaActual() = runTest {
        val base = BaseFalsa(1L to "A", 2L to "B", 3L to "A", 4L to null)
        val nube = NubeFalsa()

        assertEquals(ResultadoRespaldo.Completo(1), subida(base, nube) { "B" }.ejecutar())

        assertEquals(listOf("inst-2"), nube.listarIds("B"))
        assertTrue(nube.listarIds("A").isEmpty())
        assertEquals(setOf(2L), base.respaldadas)

        // Al volver a entrar con A, suben las suyas y solo a su nube.
        assertEquals(ResultadoRespaldo.Completo(2), subida(base, nube) { "A" }.ejecutar())
        assertEquals(listOf("inst-1", "inst-3"), nube.listarIds("A"))
        assertEquals(listOf("inst-2"), nube.listarIds("B"))
    }

    @Test
    fun sinSesionNoSubeNada() = runTest {
        val base = BaseFalsa(1L to "A")
        val nube = NubeFalsa()

        assertEquals(ResultadoRespaldo.SinSesion, subida(base, nube) { null }.ejecutar())
        assertEquals(0, nube.escrituras)
    }

    @Test
    fun siLaSesionCambiaSeDetiene() = runTest {
        val base = BaseFalsa(1L to "A", 2L to "A", 3L to "A")
        val nube = NubeFalsa()
        var sesion: String? = "A"
        base.alLeer = { id -> if (id == 2L) sesion = "B" }

        val r = subida(base, nube) { sesion }.ejecutar()

        // La sesión cambió mientras se armaba la 2: no se sube con la cuenta nueva; 2 y 3 siguen pendientes.
        assertEquals(ResultadoRespaldo.Completo(1), r)
        assertEquals(listOf("inst-1"), nube.listarIds("A"))
        assertTrue(nube.listarIds("B").isEmpty())
        assertEquals(setOf(1L), base.respaldadas)
    }

    @Test
    fun subeTambienLasGuardadasMientrasSubia() = runTest {
        val base = BaseFalsa(1L to "A")
        val nube = NubeFalsa()
        base.alLeer = { id -> if (id == 1L) base.guardar(2L, "A") }

        assertEquals(ResultadoRespaldo.Completo(2), subida(base, nube) { "A" }.ejecutar())
        assertEquals(setOf(1L, 2L), base.respaldadas)
    }

    @Test
    fun unDocumentoDemasiadoGrandeQuedaPendiente() = runTest {
        val base = BaseFalsa(1L to "A").apply { campoGrande = "x".repeat(DocumentoAtencion.LIMITE_BYTES) }
        val nube = NubeFalsa()

        assertEquals(ResultadoRespaldo.Completo(0, omitidas = 1), subida(base, nube) { "A" }.ejecutar())
        assertEquals(0, nube.escrituras)
        assertTrue(base.respaldadas.isEmpty())
    }
}
