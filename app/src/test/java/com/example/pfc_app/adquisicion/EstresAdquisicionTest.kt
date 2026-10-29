package com.example.pfc_app.adquisicion

import com.example.pfc_app.modelos.CodigoDTC
import com.example.pfc_app.modelos.ContadorModo06
import com.example.pfc_app.modelos.FamiliaGestion
import com.example.pfc_app.modelos.Lectura
import com.example.pfc_app.modelos.TipoDTC
import com.example.pfc_app.modelos.TipoRegimen
import com.example.pfc_app.modelos.Vehiculo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Prueba de estrés de la captura (RNF-01 llevado al límite): un adaptador que pierde el enlace
 * una y otra vez durante los regímenes. Con tiempo virtual, así que corre en segundos.
 * La configuración admite tres rondas de reconexión por régimen (ConfiguracionCaptura).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EstresAdquisicionTest {
    private val vehiculo = Vehiculo(id = 7, codigo = "VEH-010", marca = "Hyundai", modelo = "Tucson", anio = 2011, cilindros = 4)

    private fun TestScope.servicio() = ServicioAdquisicionImpl(reloj = { testScheduler.currentTime })

    private fun CoroutineScope.mecanico(s: ServicioAdquisicionImpl) = launch {
        s.estado.collect { e -> if (e.esperaConfirmacion) s.confirmar() }
    }

    @Test
    fun tresCaidasEnR2YTresEnR3SeRecuperanSinPerderMuestras() = runTest {
        val ad = AdaptadorInestable(caidas = mapOf(TipoRegimen.R2 to 3, TipoRegimen.R3 to 3))
        val s = servicio()
        val m = mecanico(s)
        val atencion = s.capturarAtencion(vehiculo, ad)
        m.cancel()

        assertEquals(6, ad.reconexiones)
        assertNull(s.estado.value.error)
        for (tipo in listOf(TipoRegimen.R2, TipoRegimen.R3)) {
            val r = atencion.regimen(tipo)
            assertNotNull("$tipo debía capturarse", r)
            val t = r!!.lecturas.map { it.instante }
            // el tiempo sigue creciendo después de cada corte, sin repetir instantes
            assertEquals("$tipo: instantes desordenados", t.sorted(), t)
            assertEquals("$tipo: instantes repetidos", t.distinct(), t)
            assertTrue("$tipo: ${r.muestrasUtiles} útiles", r.muestrasUtiles >= 45)
            println("$tipo con 3 caídas: ${r.lecturas.size} filas, ${r.muestrasUtiles} útiles, último instante ${t.last()} s")
        }
    }

    @Test
    fun unaCuartaCaidaEnElMismoRegimenTerminaConErrorYConservaLoCapturado() = runTest {
        val ad = AdaptadorInestable(caidas = mapOf(TipoRegimen.R3 to 4))
        val s = servicio()
        val m = mecanico(s)
        val error = runCatching { s.capturarAtencion(vehiculo, ad) }.exceptionOrNull()
        m.cancel()

        assertTrue("Debía terminar en ErrorCaptura: $error", error is ErrorCaptura)
        assertTrue(error!!.message!!, error.message!!.contains("Los regímenes ya capturados se conservan"))
        assertEquals(error.message, s.estado.value.error)
        // R2 terminó antes de las caídas y sigue capturado
        assertEquals(EstadoRegimen.CAPTURADO, s.estado.value.regimenes.getValue(TipoRegimen.R2).estado)
        assertEquals(3, ad.reconexiones)
    }

    @Test
    fun siElAdaptadorNoVuelveSeAvisaEnLaPrimeraCaida() = runTest {
        val ad = AdaptadorInestable(caidas = mapOf(TipoRegimen.R2 to 1), reconectaBien = false)
        val s = servicio()
        val m = mecanico(s)
        val error = runCatching { s.capturarAtencion(vehiculo, ad) }.exceptionOrNull()
        m.cancel()

        assertTrue(error is ErrorCaptura)
        assertEquals(1, ad.reconexiones)
        assertEquals(EstadoEnlace.DESCONECTADO, s.estado.value.enlace)
    }

    @Test
    fun capturasSeguidasConElMismoServicioNoArrastranDatos() = runTest {
        val s = servicio()
        val filas = ArrayList<Int>()
        repeat(SESIONES) { n ->
            val ad = AdaptadorInestable(caidas = mapOf(TipoRegimen.R2 to n % 4))
            val m = mecanico(s)
            val atencion = s.capturarAtencion(vehiculo, ad)
            m.cancel()
            s.finalizar()
            assertNull("Sesión $n: ${s.estado.value.error}", s.estado.value.error)
            filas += atencion.regimen(TipoRegimen.R2)!!.lecturas.size
            // cada atención empieza de cero: el primer instante de R2 es 0
            assertEquals(0.0, atencion.regimen(TipoRegimen.R2)!!.lecturas.first().instante, 0.0)
        }
        println("$SESIONES capturas seguidas, filas de R2: $filas")
        assertEquals(SESIONES, filas.size)
    }

    private companion object {
        const val SESIONES = 20
    }
}

/**
 * Adaptador que entrega una fila por segundo con el motor caliente (sin R1) y pierde el
 * enlace [caidas] veces por régimen, cada vez tras [filasEntreCaidas] filas.
 */
private class AdaptadorInestable(
    private val caidas: Map<TipoRegimen, Int>,
    private val filasEntreCaidas: Int = 20,
    private val reconectaBien: Boolean = true
) : AdaptadorELM327 {
    override val esDemostracion = false
    private val _estadoEnlace = MutableStateFlow(EstadoEnlace.DESCONECTADO)
    override val estadoEnlace: StateFlow<EstadoEnlace> = _estadoEnlace
    private val restantes = caidas.toMutableMap()
    var reconexiones = 0

    override suspend fun conectar(): ResultadoConexion {
        _estadoEnlace.value = EstadoEnlace.CONECTADO
        return ResultadoConexion(true, "ISO 15765-4 (CAN 11/500)", FamiliaGestion.MAP)
    }

    override suspend fun leerCodigos() = listOf(CodigoDTC("P0302", TipoDTC.ALMACENADO))
    override suspend fun leerContadoresModo06() = listOf(ContadorModo06(2, 40, 12))
    override suspend fun leerBarometrica() = 74.0
    override suspend fun leerTemperaturaRefrigerante(): Double = 90.0

    override fun leerParametros(regimen: TipoRegimen): Flow<Lectura> = flow {
        var k = 0
        while (true) {
            if (k > 0) delay(1000)
            val quedan = restantes[regimen] ?: 0
            if (quedan > 0 && k == filasEntreCaidas) {
                restantes[regimen] = quedan - 1
                _estadoEnlace.value = EstadoEnlace.RECONECTANDO
                throw EnlaceCaidoException("read() devolvió -1")
            }
            val rpm = if (regimen == TipoRegimen.R3) {
                val fase = k % 20
                if (fase < 10) 800.0 + 2200.0 * fase / 9 else 3000.0 - 2200.0 * (fase - 10) / 9
            } else {
                780.0 + k % 3
            }
            emit(
                Lectura(
                    instante = k.toDouble(), rpm = rpm, tempRefrigerante = 90.0, stft = 0.781, ltft = 3.125,
                    cargaCalculada = 24.0, avanceEncendido = 9.0, map = 31.0, velocidad = 0.0
                )
            )
            k++
        }
    }

    override suspend fun reconectar(): Boolean {
        reconexiones++
        if (!reconectaBien) return false
        _estadoEnlace.value = EstadoEnlace.CONECTADO
        return true
    }

    override suspend fun cerrar() {
        _estadoEnlace.value = EstadoEnlace.DESCONECTADO
    }
}
