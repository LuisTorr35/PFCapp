package com.example.pfc_app.adquisicion

import com.example.pfc_app.modelos.FamiliaGestion
import com.example.pfc_app.modelos.TipoDTC
import com.example.pfc_app.modelos.TipoRegimen
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El protocolo del adaptador real, probado en la JVM con un transporte falso que
 * devuelve respuestas grabadas: inicio, decodificación de PID, códigos, modo 06 de
 * varias tramas, línea K con cabecera 48 6B 11 y reconexión.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProtocoloELM327Test {

    /** Adaptador con tiempo virtual: los retardos y tiempos límite no esperan de verdad. */
    private fun TestScope.adaptador(fabrica: () -> TransporteSerie): AdaptadorELM327Bluetooth = AdaptadorELM327Bluetooth(
        fabricaTransporte = fabrica,
        direccionMac = "00:1D:A5:68:98:8B",
        despachador = StandardTestDispatcher(testScheduler),
        reloj = { testScheduler.currentTime }
    )

    // Inicio ------------------------------------------------------------------

    @Test
    fun inicioCanDetectaProtocoloYFamilia() = runTest {
        val elm = elmTucson()
        val t = TransporteFalso(elm)
        val a = adaptador { t }
        val r = a.conectar()
        assertTrue(r.mensajeError, r.exito)
        assertEquals("ISO 15765-4 (CAN 11/500)", r.protocolo)
        assertEquals(FamiliaGestion.MAP, r.familiaGestion)
        assertEquals(EstadoEnlace.CONECTADO, a.estadoEnlace.value)
        assertEquals(listOf("ATZ", "ATE0", "ATL0", "ATS1", "ATH1", "ATSP0", "0100", "ATDPN", "ATDP"), t.comandos.take(9))
        assertEquals(0x7E8, a.direccionMotor)
        assertEquals("6", a.protocoloMemorizado)
        assertEquals("ELM327 v1.5", a.versionElm)
        // el sondeo pregunta cada PID de verdad, también los que la máscara no declara
        assertTrue("0110" in t.comandos && "0124" in t.comandos && "0114" in t.comandos)
        assertEquals(74.0, a.leerBarometrica()!!, 0.0)
        assertEquals(93.0, a.leerTemperaturaRefrigerante()!!, 0.0)
    }

    @Test
    fun inicioToleraSearchingYUnableToConnect() = runTest {
        val elm = elmTucson()
        elm.responderSecuencia(
            "0100",
            "SEARCHING...\rUNABLE TO CONNECT",
            "STOPPED",
            "SEARCHING...\r7E8 06 41 00 BE 3F A8 13 00",
            elm.trama("41 00 BE 3F A8 13")
        )
        val t = TransporteFalso(elm)
        val r = adaptador { t }.conectar()
        assertTrue(r.mensajeError, r.exito)
        assertEquals(3, t.comandos.takeWhile { it != "ATDPN" }.count { it == "0100" })
    }

    @Test
    fun sinAdaptadorOSinVehiculoElMensajeEsLegible() = runTest {
        val sinAdaptador = adaptador { TransporteFalso(elmTucson(), fallaAlAbrir = true) }.conectar()
        assertFalse(sinAdaptador.exito)
        assertTrue(sinAdaptador.mensajeError!!, sinAdaptador.mensajeError!!.contains("adaptador"))

        val mudo = ElmSimulado().apply { responderSecuencia("0100", "SEARCHING...\rUNABLE TO CONNECT") }
        val t = TransporteFalso(mudo)
        val sinVehiculo = adaptador { t }.conectar()
        assertFalse(sinVehiculo.exito)
        assertTrue(sinVehiculo.mensajeError!!, sinVehiculo.mensajeError!!.contains("vehículo no responde"))
        // automático y después la línea K, como diagnostico_vehiculo.py
        assertEquals(listOf("ATSP0", "ATSP3", "ATSP4", "ATSP5"), t.comandos.filter { it.startsWith("ATSP") })
    }

    // Decodificación ----------------------------------------------------------

    @Test
    fun decodificacionSaeJ1979ComoPythonObd() {
        fun v(pid: PidOBD, hex: String) = pid.decodificar(hex.split(" ").map { it.toInt(16) })
        assertEquals(666.75, v(PidOBD.RPM, "0A 6B")!!, 0.0)
        assertEquals(40.784, v(PidOBD.ENGINE_LOAD, "68")!!, 0.0)
        assertEquals(17.255, v(PidOBD.ABSOLUTE_LOAD, "00 2C")!!, 0.0)
        assertEquals(30.0, v(PidOBD.INTAKE_PRESSURE, "1E")!!, 0.0)
        assertEquals(4.41, v(PidOBD.MAF, "01 B9")!!, 0.0)
        assertEquals(93.0, v(PidOBD.COOLANT_TEMP, "85")!!, 0.0)
        assertEquals(-40.0, v(PidOBD.INTAKE_TEMP, "00")!!, 0.0)
        // (142 − 128) · 100 / 128 = 10,9375, mitad al par sobre el binario exacto: 10,938
        assertEquals(10.938, v(PidOBD.SHORT_FUEL_TRIM_1, "8E")!!, 0.0)
        assertEquals(-1.562, v(PidOBD.LONG_FUEL_TRIM_1, "7E")!!, 0.0)
        assertEquals(9.0, v(PidOBD.TIMING_ADVANCE, "92")!!, 0.0)
        assertEquals(-4.5, v(PidOBD.TIMING_ADVANCE, "77")!!, 0.0)
        assertEquals(12.157, v(PidOBD.THROTTLE_POS, "1F")!!, 0.0)
        assertEquals(13.866, v(PidOBD.CONTROL_MODULE_VOLTAGE, "36 2A")!!, 0.0)
        assertEquals(0.125, v(PidOBD.O2_B1S1, "19 FF")!!, 0.0)
        // python-OBD usa 0,0000305 por bit (no 1/32768): 0x8000 da 0,999, como en las grabaciones
        assertEquals(0.999, v(PidOBD.COMMANDED_EQUIV_RATIO, "80 00")!!, 0.0)
        assertEquals(74.0, v(PidOBD.BAROMETRIC_PRESSURE, "4A")!!, 0.0)
        assertNull(v(PidOBD.RPM, "0A"))
        assertNull(v(PidOBD.FUEL_STATUS, "02 00"))
    }

    @Test
    fun laMuestraTraeLosCamposDeLecturaYUsaElModoRapido() = runTest {
        val elm = elmTucson()
        val t = TransporteFalso(elm)
        val a = adaptador { t }
        assertTrue(a.conectar().exito)
        val filas = a.leerParametros(TipoRegimen.R2).take(3).toList()
        val l = filas.first()
        assertEquals(0.0, l.instante, 0.0)
        assertEquals(666.75, l.rpm!!, 0.0)
        assertEquals(93.0, l.tempRefrigerante!!, 0.0)
        assertEquals(10.938, l.stft!!, 0.0)
        assertEquals(0.0, l.ltft!!, 0.0)
        assertEquals(40.784, l.cargaCalculada!!, 0.0)
        assertEquals(9.0, l.avanceEncendido!!, 0.0)
        assertEquals(30.0, l.map!!, 0.0)
        assertNull(l.maf)
        assertEquals(0.0, l.velocidad!!, 0.0)
        // periodo de 1 s cuando la consulta tarda menos
        assertEquals(listOf(0.0, 1.0, 2.0), filas.map { it.instante })
        // tras la primera respuesta se pide una sola trama, como python-OBD con fast=True
        assertTrue(t.comandos.toString(), "010C1" in t.comandos)
        // velocidad y barométrica son constantes de sesión: no se piden por muestra
        val porMuestra = t.comandos.dropWhile { it != "010C1" }
        assertFalse("010D" in porMuestra || "010D1" in porMuestra || "0133" in porMuestra || "01331" in porMuestra)
        // la sonda de banda estrecha contestó y la de banda ancha no: se usa 0x14
        assertTrue("01141" in porMuestra && "01241" !in porMuestra)
    }

    // Códigos -----------------------------------------------------------------

    @Test
    fun codigosAlmacenadosYPendientesEnCan() = runTest {
        val elm = elmTucson()
        elm.responderSecuencia("03", "7E8 06 43 02 01 33 03 00")
        // cuatro códigos pendientes: mensaje de varias tramas ISO-TP
        elm.responderSecuencia("07", "7E8 10 0A 47 04 01 01 01 02\r7E8 21 01 07 01 13 00 00 00")
        val a = adaptador { TransporteFalso(elm) }
        assertTrue(a.conectar().exito)
        val c = a.leerCodigos()
        assertEquals(listOf("P0133", "P0300", "P0101", "P0102", "P0107", "P0113"), c.map { it.codigo })
        assertEquals(listOf(TipoDTC.ALMACENADO, TipoDTC.ALMACENADO), c.take(2).map { it.tipo })
        assertTrue(c.drop(2).all { it.tipo == TipoDTC.PENDIENTE })
        assertEquals("Fallo de encendido aleatorio o en varios cilindros", c[1].descripcion)
    }

    @Test
    fun decodificacionDeCodigosPCBU() {
        assertEquals("P0133", DecodificadorDTC.codigo(0x01, 0x33))
        assertEquals("C0035", DecodificadorDTC.codigo(0x40, 0x35))
        assertEquals("B1234", DecodificadorDTC.codigo(0x92, 0x34))
        assertEquals("U0100", DecodificadorDTC.codigo(0xC1, 0x00))
        assertNull(DecodificadorDTC.codigo(0, 0))
    }

    @Test
    fun modo06DeVariasTramasPorCilindro() = runTest {
        val elm = elmTucson()
        fun respuesta(mid: Int, promedio: Int, actual: Int): String {
            val m = "%02X".format(mid)
            return "7E8 10 13 46 $m 0B 24 %02X %02X\r".format(promedio shr 8, promedio and 0xFF) +
                "7E8 21 00 00 FF FF $m 0C 24\r" +
                "7E8 22 %02X %02X 00 00 FF FF 00".format(actual shr 8, actual and 0xFF)
        }
        elm.responderSecuencia("06A2", respuesta(0xA2, 0, 0))
        elm.responderSecuencia("06A3", respuesta(0xA3, 2, 5))
        elm.responderSecuencia("06A4", respuesta(0xA4, 0, 151))
        elm.responderSecuencia("06A5", respuesta(0xA5, 0, 0))
        val a = adaptador { TransporteFalso(elm) }
        assertTrue(a.conectar().exito)
        val m = a.leerContadoresModo06()
        assertEquals(listOf(1, 2, 3, 4), m.map { it.cilindro })
        assertEquals(listOf(0, 5, 151, 0), m.map { it.conteoActual })
        assertEquals(listOf(0, 2, 0, 0), m.map { it.conteoPromedio })
    }

    // Línea K -----------------------------------------------------------------

    @Test
    fun lineaKConMotorEnLaDireccion11() = runTest {
        // Hyundai Accent 2000 (VEH-008): ISO 9141-2, el motor contesta desde la 11 (cabecera 48 6B 11)
        val elm = ElmSimulado(
            numeroProtocolo = "3",
            nombreProtocolo = "ISO 9141-2",
            cabecera = "48 6B 11",
            modoUno = mutableMapOf(0x0C to "0C 80", 0x0B to "4D", 0x05 to "5B", 0x06 to "80", 0x07 to "80", 0x0E to "98", 0x04 to "05")
        )
        // el automático no engancha; con ATSP3 la primera inicialización del bus falla y la segunda sí
        elm.responder0100("0", "SEARCHING...\rUNABLE TO CONNECT")
        elm.responder0100("3", "BUS INIT: ...ERROR", "BUS INIT: ...OK\r48 6B 11 41 00 BE 3E B8 11 C9", "48 6B 11 41 00 BE 3E B8 11 C9")
        val t = TransporteFalso(elm)
        val a = adaptador { t }
        val r = a.conectar()
        assertTrue(r.mensajeError, r.exito)
        assertEquals("ISO 9141-2", r.protocolo)
        assertEquals(0x11, a.direccionMotor)
        assertEquals("3", a.protocoloMemorizado)
        assertEquals(FamiliaGestion.MAP, r.familiaGestion)
        assertEquals(listOf("ATSP0", "ATSP3"), t.comandos.filter { it.startsWith("ATSP") })
        assertEquals(null, a.leerBarometrica()) // el vehículo no expone el PID 0x33
        assertEquals(77.0, a.leerMapContacto()!!, 0.0) // la MAP con el motor apagado hace de barométrica
        val l = a.leerParametros(TipoRegimen.R2).take(1).toList().single()
        assertEquals(800.0, l.rpm!!, 0.0)
        assertEquals(51.0, l.tempRefrigerante!!, 0.0)
        assertEquals(0.0, l.stft!!, 0.0)
        assertEquals(12.0, l.avanceEncendido!!, 0.0)
        assertEquals(77.0, l.map!!, 0.0)
        // en línea K no se usa el modo rápido (se capturó con --lento) y no hay modo 06
        assertFalse(t.comandos.any { it == "010C1" })
        assertTrue(a.leerContadoresModo06().isEmpty())
    }

    @Test
    fun conDosComputadorasSeEligeLaDelMotorAprendida() {
        val lineas = listOf("48 6B 10 41 0C 1F 40 00", "48 6B 11 41 0C 0C 80 00")
        val tramas = MensajesOBD.tramas(lineas, FormatoTrama.LEGADO)
        val mensajes = MensajesOBD.mensajes(tramas, FormatoTrama.LEGADO)
        assertEquals(listOf(0x10, 0x11), mensajes.map { it.origen })
        assertEquals(listOf(0x41, 0x0C, 0x0C, 0x80), mensajes[1].datos)
        // sin dirección aprendida no se descarta nada: se toma la primera válida
        val cliente = ClienteELM327(TransporteFalso(ElmSimulado()))
        assertEquals(0x10, cliente.delMotor(mensajes) { it.datos[0] == 0x41 }!!.origen)
    }

    @Test
    fun cabecerasCan29Bits() {
        val lineas = listOf("18 DA F1 10 06 41 00 BE 3F A8 13", "18 DA F1 18 06 41 00 80 00 00 01")
        val m = MensajesOBD.mensajes(MensajesOBD.tramas(lineas, FormatoTrama.CAN_29), FormatoTrama.CAN_29)
        assertEquals(listOf(0x10, 0x18), m.map { it.origen })
        assertEquals(listOf(0x41, 0x00, 0xBE, 0x3F, 0xA8, 0x13), m[0].datos)
        assertEquals(FormatoTrama.CAN_29, MensajesOBD.deducirFormato(lineas))
        assertEquals(FormatoTrama.CAN_11, MensajesOBD.deducirFormato(listOf("7E8 06 41 00 BE 3F A8 13")))
        assertEquals(FormatoTrama.LEGADO, MensajesOBD.deducirFormato(listOf("48 6B 11 41 00 BE 3E B8 11 C9")))
    }

    // Reconexión ----------------------------------------------------------------

    @Test
    fun reconectaSiElEnlaceCaeDuranteElRegimenYSigue() = runTest {
        val creados = ArrayList<TransporteFalso>()
        val a = adaptador {
            // el primer enlace se cae a los 80 comandos; los siguientes no
            TransporteFalso(elmTucson(), caerTras = if (creados.isEmpty()) 80 else Int.MAX_VALUE).also { creados += it }
        }
        val estados = ArrayList<EstadoEnlace>()
        val vigia = launch { a.estadoEnlace.collect { estados += it } }
        assertTrue(a.conectar().exito)
        val filas = a.leerParametros(TipoRegimen.R3).take(6).toList()
        runCurrent()
        vigia.cancel()
        assertEquals(6, filas.size)
        assertEquals(2, creados.size)
        assertTrue(estados.toString(), EstadoEnlace.RECONECTANDO in estados)
        assertEquals(EstadoEnlace.CONECTADO, a.estadoEnlace.value)
        // la base de tiempo del régimen se conserva: los instantes siguen creciendo
        assertEquals(filas.map { it.instante }.sorted(), filas.map { it.instante })
        // la reconexión prueba primero el protocolo memorizado
        assertTrue(creados[1].comandos.toString(), "ATSP6" in creados[1].comandos)
        assertTrue(filas.all { it.rpm == 666.75 })
    }

    @Test
    fun dosLecturasSeguidasSinRpmFuerzanLaReconexion() = runTest {
        val creados = ArrayList<TransporteFalso>()
        val a = adaptador {
            val elm = elmTucson()
            // en el primer enlace el adaptador "sigue conectado" pero deja de dar rpm tras el sondeo
            if (creados.isEmpty()) {
                elm.responderSecuencia("010C", elm.trama("41 0C 0A 6B"), "NO DATA")
                elm.responderSecuencia("010C1", "NO DATA")
            }
            TransporteFalso(elm).also { creados += it }
        }
        assertTrue(a.conectar().exito)
        val filas = a.leerParametros(TipoRegimen.R2).take(3).toList()
        assertEquals(2, creados.size)
        // la primera muestra sin rpm se conserva; la segunda dispara la reconexión y se descarta
        assertNull(filas[0].rpm)
        assertEquals(666.75, filas[1].rpm!!, 0.0)
    }

    @Test
    fun siElAdaptadorSeReiniciaAlArrancarSeVuelveAConfigurar() = runTest {
        val creados = ArrayList<TransporteFalso>()
        val a = adaptador {
            val elm = elmTucson()
            // tras el sondeo, el bajón de voltaje del arranque reinicia el ELM327: vuelve a saludar
            if (creados.isEmpty()) elm.responderSecuencia("01051", "\rELM327 v1.5")
            TransporteFalso(elm).also { creados += it }
        }
        assertTrue(a.conectar().exito)
        assertEquals(93.0, a.leerTemperaturaRefrigerante()!!, 0.0)
        assertEquals(2, creados.size)
        assertEquals(listOf("ATZ", "ATE0", "ATL0", "ATS1", "ATH1", "ATSP6"), creados[1].comandos.take(6))
    }

    @Test
    fun unaComputadoraMudaNoSeLeeComoSinCodigos() = runTest {
        val creados = ArrayList<TransporteFalso>()
        val a = adaptador {
            val elm = elmTucson()
            // el primer enlace pierde a la computadora después de conectar: 0100 deja de contestar
            if (creados.isEmpty()) elm.responderSecuencia("0100", "SEARCHING...\r7E8 06 41 00 BE 3F A8 13 00", "NO DATA")
            elm.responderSecuencia("03", "7E8 04 43 01 03 01")
            TransporteFalso(elm).also { creados += it }
        }
        assertTrue(a.conectar().exito)
        assertEquals(listOf("P0301"), a.leerCodigos().map { it.codigo })
        assertEquals(2, creados.size)
    }

    @Test
    fun siNoSePuedeReconectarElFlujoTerminaConEnlaceCaido() = runTest {
        var n = 0
        val a = adaptador {
            n++
            if (n == 1) TransporteFalso(elmTucson(), caerTras = 70) else TransporteFalso(elmTucson(), fallaAlAbrir = true)
        }
        assertTrue(a.conectar().exito)
        val error = runCatching { a.leerParametros(TipoRegimen.R2).toList() }.exceptionOrNull()
        assertTrue(error.toString(), error is EnlaceCaidoException)
        assertEquals(1 + 8, n)
        assertEquals(EstadoEnlace.DESCONECTADO, a.estadoEnlace.value)
    }
}
