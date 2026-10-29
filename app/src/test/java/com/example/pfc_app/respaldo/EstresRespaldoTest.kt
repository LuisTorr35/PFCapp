package com.example.pfc_app.respaldo

import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Prueba de estrés del respaldo (RF-09): muchas atenciones pendientes acumuladas sin red y una
 * red que falla a ratos. Comprueba que al final todas quedan en la nube una sola vez, que nada se
 * marca sin confirmación y que las pendientes de otra cuenta no se tocan.
 */
class EstresRespaldoTest {

    /** Base local en memoria: id → cuenta con que se guardó. */
    private class BaseGrande(n: Int, cuentaB: Int) : AtencionesPorRespaldar {
        val cuenta = LinkedHashMap<Long, String>().apply {
            for (i in 1..n) put(i.toLong(), "A")
            for (i in 1..cuentaB) put((n + i).toLong(), "B")
        }
        val respaldadas = HashSet<Long>()
        var marcasRepetidas = 0

        override fun pendientes(uid: String): List<Long> = cuenta.filter { it.value == uid && it.key !in respaldadas }.keys.sorted()

        override fun documento(idAtencion: Long, uid: String): DocumentoRespaldo =
            DocumentoRespaldo("inst-$idAtencion", mapOf("id_atencion" to idAtencion, "uid" to uid))

        override fun marcarRespaldada(idAtencion: Long) {
            if (!respaldadas.add(idAtencion)) marcasRepetidas++
        }
    }

    /** Nube que rechaza una de cada [cadaCuantas] escrituras, como una señal inestable en el taller. */
    private class NubeInestable(private val cadaCuantas: Int) : RespaldoNube {
        val documentos = LinkedHashMap<String, Map<String, Any?>>()
        var intentos = 0
        var fallos = 0

        override suspend fun subir(uid: String, idDocumento: String, documento: Map<String, Any?>) {
            intentos++
            if (cadaCuantas > 0 && intentos % cadaCuantas == 0) {
                fallos++
                throw IllegalStateException("UNAVAILABLE: la red se cortó")
            }
            documentos[DocumentoAtencion.ruta(uid, idDocumento)] = documento
        }

        override suspend fun listarIds(uid: String): List<String> =
            documentos.keys.filter { it.startsWith("usuarios/$uid/") }.map { it.substringAfterLast('/') }
    }

    @Test
    fun milPendientesConRedInestableTerminanTodasRespaldadasUnaVez() = runTest {
        val base = BaseGrande(n = PENDIENTES, cuentaB = 50)
        val nube = NubeInestable(cadaCuantas = 7)
        val subida = SubidaRespaldo(base, nube, { "A" }, EmptyCoroutineContext)

        // WorkManager reintenta mientras el resultado sea Reintentar; aquí se simulan esos reintentos
        var ejecuciones = 0
        var subidasTotales = 0
        while (true) {
            ejecuciones++
            assertTrue("No terminó en $ejecuciones ejecuciones", ejecuciones <= PENDIENTES)
            when (val r = subida.ejecutar()) {
                is ResultadoRespaldo.Completo -> {
                    subidasTotales += r.subidas
                    break
                }
                is ResultadoRespaldo.Reintentar -> subidasTotales += r.subidas
                ResultadoRespaldo.SinSesion -> error("Había sesión")
            }
        }

        println(
            "Respaldo: $PENDIENTES atenciones, ${nube.intentos} intentos, ${nube.fallos} fallos de red, " +
                "$ejecuciones ejecuciones del trabajo"
        )
        assertEquals(PENDIENTES, subidasTotales)
        assertEquals(PENDIENTES, nube.listarIds("A").size)
        assertEquals(PENDIENTES, base.respaldadas.size)
        assertEquals("Ninguna atención se marcó dos veces", 0, base.marcasRepetidas)
        assertTrue("La cuenta B no se tocó", nube.listarIds("B").isEmpty())
        assertEquals(50, base.pendientes("B").size)
    }

    @Test
    fun sinRedNoSeMarcaNadaYLuegoSubeTodo() = runTest {
        val base = BaseGrande(n = 200, cuentaB = 0)
        val sinRed = NubeInestable(cadaCuantas = 1)
        val r = SubidaRespaldo(base, sinRed, { "A" }, EmptyCoroutineContext).ejecutar()
        assertTrue(r is ResultadoRespaldo.Reintentar)
        assertTrue("Sin confirmación de la nube no se marca nada", base.respaldadas.isEmpty())
        assertEquals(200, base.pendientes("A").size)

        val conRed = NubeInestable(cadaCuantas = 0)
        assertEquals(ResultadoRespaldo.Completo(200), SubidaRespaldo(base, conRed, { "A" }, EmptyCoroutineContext).ejecutar())
        assertEquals(200, conRed.documentos.size)
    }

    private companion object {
        const val PENDIENTES = 1000
    }
}
