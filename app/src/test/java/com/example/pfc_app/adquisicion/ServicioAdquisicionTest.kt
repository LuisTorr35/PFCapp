package com.example.pfc_app.adquisicion

import com.example.pfc_app.Recursos
import com.example.pfc_app.inferencia.LectorContrato
import com.example.pfc_app.modelos.CodigoDTC
import com.example.pfc_app.modelos.ContadorModo06
import com.example.pfc_app.modelos.Lectura
import com.example.pfc_app.modelos.ResultadoDictamen
import com.example.pfc_app.modelos.TipoDTC
import com.example.pfc_app.modelos.TipoRegimen
import com.example.pfc_app.modelos.Vehiculo
import com.example.pfc_app.procesamiento.FuenteArchivosCarpeta
import com.example.pfc_app.procesamiento.ProcesadorCaracteristicas
import com.example.pfc_app.validacion.ValidadorAtencion
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** CU-03, CU-08 y RNF-01 con tiempo virtual: la demostración desde las grabaciones y el modo real con un adaptador falso. */
@OptIn(ExperimentalCoroutinesApi::class)
class ServicioAdquisicionTest {
    private val contrato = LectorContrato.desdeJson(File(Recursos.MODELO, "contrato_modelo.json").readText())
    private val validador = ValidadorAtencion(contrato)
    private val procesador = ProcesadorCaracteristicas()
    private val catalogo = CatalogoDemoArchivos(FuenteArchivosCarpeta(Recursos.DEMO))
    private val vehiculo = Vehiculo(id = 7, codigo = "VEH-010", marca = "Hyundai", modelo = "Tucson", anio = 2011, cilindros = 4)

    private fun TestScope.servicio() = ServicioAdquisicionImpl(reloj = { testScheduler.currentTime })

    /** Confirma cada instrucción que espera al mecánico, como si pulsara "Continuar". */
    private fun CoroutineScope.mecanico(s: ServicioAdquisicionImpl, instrucciones: MutableList<String>) = launch {
        s.estado.collect { e ->
            if (e.esperaConfirmacion) {
                instrucciones += e.instruccion.orEmpty()
                s.confirmar()
            }
        }
    }

    // Demostración --------------------------------------------------------------

    @Test
    fun demostracionConRechazoSimuladoYRepeticionDeR2() = runTest {
        val g = catalogo.grabaciones().single { it.id == "ATN-037" }
        val s = servicio()
        val a = catalogo.adaptador(g, simularRechazo = true)
        val atencion = s.capturarAtencion(vehiculo, a)
        val e = s.estado.value
        assertTrue(e.modoDemostracion)
        assertTrue(e.terminada)
        assertTrue(e.lecturaInicialHecha)
        assertEquals("ISO 15765-4 (CAN 11/500)", e.protocolo)
        assertEquals(74.0, e.baroKpa!!, 0.0)
        assertEquals(EstadoRegimen.OMITIDO, e.regimenes.getValue(TipoRegimen.R1).estado)
        assertTrue(e.regimenes.getValue(TipoRegimen.R1).motivoOmision!!.contains("caliente"))
        assertEquals(31, atencion.regimen(TipoRegimen.R2)!!.muestrasUtiles)
        assertEquals(31, e.regimenes.getValue(TipoRegimen.R2).muestrasUtiles)

        val d1 = validador.validar(atencion)
        assertEquals(ResultadoDictamen.RECHAZADA, d1.resultado)
        assertEquals(listOf(TipoRegimen.R2), d1.regimenesARepetir)
        assertTrue(d1.observacion, d1.observacion.contains("R2 no reunió las 45 muestras útiles (31 de 45)"))

        // CU-08: se repite R2 y esta vez la grabación se reproduce completa
        val repetida = s.repetirRegimen(TipoRegimen.R2)
        val d2 = validador.validar(repetida)
        assertEquals(d2.observacion, ResultadoDictamen.PARCIAL, d2.resultado)
        assertTrue(d2.permiteInferencia())
        assertEquals(64, repetida.regimen(TipoRegimen.R2)!!.muestrasUtiles)
        // R3 no se tocó
        assertEquals(atencion.regimen(TipoRegimen.R3), repetida.regimen(TipoRegimen.R3))
        // y la fila es exactamente la de la grabación
        assertEquals(
            Recursos.tablaConsolidador().getValue("ATN-037"),
            procesador.prepararFila(repetida).valores.toMap(),
        )
        s.finalizar()
        assertEquals(EstadoEnlace.DESCONECTADO, s.estado.value.enlace)
    }

    @Test
    fun demostracionConR1EnFrio() = runTest {
        val g = catalogo.grabaciones().single { it.id == "ATN-033" }
        val s = servicio()
        val atencion = s.capturarAtencion(vehiculo, catalogo.adaptador(g))
        assertNotNull(atencion.regimen(TipoRegimen.R1))
        assertEquals(EstadoRegimen.CAPTURADO, s.estado.value.regimenes.getValue(TipoRegimen.R1).estado)
        assertEquals(ResultadoDictamen.COMPLETA, validador.validar(atencion).resultado)
        assertEquals(Recursos.tablaConsolidador().getValue("ATN-033"), procesador.prepararFila(atencion).valores.toMap())
    }

    @Test
    fun cadaGrabacionDelCatalogoReproduceSuFila() = runTest {
        val tabla = Recursos.tablaConsolidador()
        val grabaciones = catalogo.grabaciones()
        assertTrue(grabaciones.size >= 5)
        val clases = grabaciones.mapNotNull { it.claseConfirmada }.toSet()
        assertEquals(setOf("C0", "C1", "C2", "C3"), clases)
        for (g in grabaciones) {
            assertTrue(g.descripcion, g.descripcion.startsWith("${g.id} · ${g.marca} ${g.modelo} ${g.anio}"))
            val atencion = servicio().capturarAtencion(vehiculo, catalogo.adaptador(g))
            assertEquals(g.id, g.tieneR1, atencion.regimen(TipoRegimen.R1) != null)
            assertEquals(g.id, tabla.getValue(g.id), procesador.prepararFila(atencion).valores.toMap())
            val d = validador.validar(atencion)
            assertEquals(g.id, g.id == "ATN-036", d.faltanParametrosModelo)
            println("${g.descripcion} (${g.claseConfirmada}): ${d.resultado.texto}" + if (d.faltanParametrosModelo) ", sin diagnóstico" else "")
        }
    }

    // Modo real -----------------------------------------------------------------

    @Test
    fun modoRealConConfirmacionesCalentamientoYCortePorUtiles() = runTest {
        val falso = AdaptadorFalso(ectInicial = 62.0, ectCalentamiento = listOf(70.0, 80.0, 86.0))
        val s = servicio()
        val instrucciones = ArrayList<String>()
        val m = mecanico(s, instrucciones)
        val atencion = s.capturarAtencion(vehiculo, falso)
        m.cancel()
        // R1 no se captura con el motor caliente
        assertEquals(null, atencion.regimen(TipoRegimen.R1))
        assertEquals("el motor llegó caliente (62.0 °C)", s.estado.value.regimenes.getValue(TipoRegimen.R1).motivoOmision)
        assertEquals(listOf(TipoRegimen.R2, TipoRegimen.R3), falso.pedidos)
        // se esperó a que el refrigerante superara 85 °C
        assertTrue(falso.consultasRefrigerante >= 4)
        // instrucciones confirmadas por el mecánico: arranque y aceleraciones
        assertEquals(2, instrucciones.size)
        assertTrue(instrucciones[0].startsWith("Arranque el motor"))
        assertTrue(instrucciones[1].startsWith("Haga tres subidas de 800 a 3000 rpm"))
        // corte de registrar_obd.py v4: piso de 120 s con 55 útiles juntados mucho antes
        val r2 = atencion.regimen(TipoRegimen.R2)!!
        assertEquals(121, r2.lecturas.size)
        assertEquals(120.0, r2.lecturas.last().instante, 0.0)
        assertEquals(106, r2.muestrasUtiles)
        // R3 no corta a mitad de una rampa: termina en ralentí
        val r3 = atencion.regimen(TipoRegimen.R3)!!
        assertTrue(r3.lecturas.last().instante >= 120.0)
        assertTrue(r3.lecturas.last().rpm!! <= 1100.0)
        assertEquals(ResultadoDictamen.PARCIAL, validador.validar(atencion).resultado)
    }

    @Test
    fun r1EnFrioCortaAlLlegarA40Grados() = runTest {
        val falso = AdaptadorFalso(ectInicial = 25.0, ectCalentamiento = listOf(90.0))
        val s = servicio()
        val m = mecanico(s, ArrayList())
        val atencion = s.capturarAtencion(vehiculo, falso)
        m.cancel()
        val r1 = atencion.regimen(TipoRegimen.R1)!!
        // el refrigerante sube 0,2 °C por segundo desde 25: llega a 40 a los 75 s, antes del piso de 90
        assertEquals(76, r1.lecturas.size)
        assertEquals(40.0, r1.lecturas.last().tempRefrigerante!!, 1e-9)
        assertEquals(listOf(TipoRegimen.R1, TipoRegimen.R2, TipoRegimen.R3), falso.pedidos)
        assertEquals(ResultadoDictamen.COMPLETA, validador.validar(atencion).resultado)
    }

    @Test
    fun siElEnlaceCaeDuranteR2ReconectaYConservaLoCapturado() = runTest {
        val falso = AdaptadorFalso(ectInicial = 88.0, ectCalentamiento = listOf(88.0), caerEnR2TrasFilas = 30)
        val s = servicio()
        val mensajes = ArrayList<String>()
        val vigia = launch { s.estado.collect { e -> e.mensaje?.let { if (mensajes.lastOrNull() != it) mensajes += it } } }
        val m = mecanico(s, ArrayList())
        val atencion = s.capturarAtencion(vehiculo, falso)
        m.cancel()
        vigia.cancel()
        assertTrue(mensajes.toString(), ServicioAdquisicionImpl.MENSAJE_RECONECTANDO in mensajes)
        assertEquals(1, falso.reconexiones)
        val r2 = atencion.regimen(TipoRegimen.R2)!!
        // las 30 filas de antes del corte siguen ahí y el tiempo sigue creciendo después
        val t = r2.lecturas.map { it.instante }
        assertEquals(t.sorted(), t)
        assertEquals(t.distinct(), t)
        assertTrue(r2.lecturas.size > 100)
        assertNotNull(atencion.regimen(TipoRegimen.R3))
        assertFalse(s.estado.value.error != null)
    }

    @Test
    fun siNoConectaLanzaUnErrorLegible() = runTest {
        val falso = AdaptadorFalso(conecta = false)
        val s = servicio()
        val error = runCatching { s.capturarAtencion(vehiculo, falso) }.exceptionOrNull()
        assertTrue(error is ErrorCaptura)
        assertEquals("El vehículo no responde. Revise que el contacto esté encendido.", error!!.message)
        assertEquals(error.message, s.estado.value.error)
    }
}

/**
 * Adaptador falso del modo real: una fila por segundo; en R1 el refrigerante sube
 * 0,2 °C por segundo, en R2 queda caliente y en R3 hace rampas de 20 s hasta 3000 rpm.
 */
private class AdaptadorFalso(
    private val conecta: Boolean = true,
    private val ectInicial: Double = 62.0,
    private val ectCalentamiento: List<Double> = listOf(90.0),
    private val caerEnR2TrasFilas: Int? = null,
) : AdaptadorELM327 {
    override val esDemostracion = false
    private val _estado = MutableStateFlow(EstadoEnlace.DESCONECTADO)
    override val estadoEnlace: StateFlow<EstadoEnlace> = _estado
    val pedidos = ArrayList<TipoRegimen>()
    var consultasRefrigerante = 0
    var reconexiones = 0
    private var cayo = false

    override suspend fun conectar(): ResultadoConexion {
        if (!conecta) return ResultadoConexion(false, mensajeError = "El vehículo no responde. Revise que el contacto esté encendido.")
        _estado.value = EstadoEnlace.CONECTADO
        return ResultadoConexion(true, "ISO 15765-4 (CAN 11/500)", com.example.pfc_app.modelos.FamiliaGestion.MAP)
    }

    override suspend fun leerCodigos() = listOf(CodigoDTC("P0301", TipoDTC.ALMACENADO))
    override suspend fun leerContadoresModo06() = listOf(ContadorModo06(1, 12, 3))
    override suspend fun leerBarometrica() = 74.0

    override suspend fun leerTemperaturaRefrigerante(): Double? {
        consultasRefrigerante++
        // las dos primeras consultas son la lectura inicial y la del arranque
        return if (consultasRefrigerante <= 2) ectInicial
        else ectCalentamiento.getOrElse(consultasRefrigerante - 3) { ectCalentamiento.last() }
    }

    override fun leerParametros(regimen: TipoRegimen): Flow<Lectura> = flow {
        pedidos += regimen
        var k = 0
        while (true) {
            if (k > 0) delay(1000)
            if (regimen == TipoRegimen.R2 && caerEnR2TrasFilas != null && !cayo && k == caerEnR2TrasFilas) {
                cayo = true
                _estado.value = EstadoEnlace.RECONECTANDO
                throw EnlaceCaidoException("read() devolvió -1")
            }
            val rpm = when (regimen) {
                TipoRegimen.R3 -> {
                    val fase = k % 20
                    if (fase < 10) 800.0 + 2200.0 * fase / 9 else 3000.0 - 2200.0 * (fase - 10) / 9
                }
                else -> 760.0 + k % 3
            }
            val ect = when (regimen) {
                TipoRegimen.R1 -> 25.0 + 0.2 * k
                else -> 90.0
            }
            emit(
                Lectura(
                    instante = k.toDouble(), rpm = rpm, tempRefrigerante = ect, stft = 1.563, ltft = -2.344,
                    cargaCalculada = 25.0, avanceEncendido = 10.0, map = 30.0, velocidad = 0.0,
                ),
            )
            k++
        }
    }

    override suspend fun reconectar(): Boolean {
        reconexiones++
        _estado.value = EstadoEnlace.CONECTADO
        return true
    }

    override suspend fun cerrar() {
        _estado.value = EstadoEnlace.DESCONECTADO
    }
}
