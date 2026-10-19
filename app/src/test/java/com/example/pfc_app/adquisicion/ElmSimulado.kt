package com.example.pfc_app.adquisicion

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.yield

/**
 * Un ELM327 simulado con respuestas como las de los registros de campo. [modoUno]
 * da los bytes de datos (después de 41 y el PID) de cada PID que el vehículo expone;
 * los demás contestan NO DATA. [respuestas] fija la respuesta cruda de un comando,
 * o una secuencia de respuestas para comandos que se repiten (0100 con SEARCHING).
 */
class ElmSimulado(
    val numeroProtocolo: String = "6",
    val nombreProtocolo: String = "ISO 15765-4 (CAN 11/500)",
    val modoUno: MutableMap<Int, String> = mutableMapOf(),
    val respuestas: MutableMap<String, ArrayDeque<String>> = mutableMapOf(),
    /** Emisor de las respuestas de modo 01: "7E8" en CAN 11 bits, "48 6B 11" en línea K. */
    val cabecera: String = "7E8",
) {
    val esCan: Boolean get() = numeroProtocolo !in listOf("1", "2", "3", "4", "5")
    var protocoloPedido: String? = null
        private set

    /** Respuestas de 0100 según el protocolo pedido con ATSP (la línea K tarda en despertar). */
    private val respuestas0100 = mutableMapOf<String, ArrayDeque<String>>()

    fun responderSecuencia(comando: String, vararg r: String) {
        respuestas[comando] = ArrayDeque(r.toList())
    }

    fun responder0100(protocolo: String, vararg r: String) {
        respuestas0100[protocolo] = ArrayDeque(r.toList())
    }

    fun responder(comando: String): String? {
        if (comando == "0100") {
            respuestas0100[protocoloPedido]?.let { cola -> return if (cola.size > 1) cola.removeFirst() else cola.first() }
        }
        respuestas[comando]?.let { cola ->
            val r = if (cola.size > 1) cola.removeFirst() else cola.firstOrNull()
            if (r != null) return r
        }
        return when {
            comando == "ATZ" -> "ELM327 v1.5"
            comando == "ATE0" -> "ATE0\rOK"
            comando.startsWith("ATSP") -> {
                protocoloPedido = comando.removePrefix("ATSP")
                "OK"
            }
            comando == "ATDPN" -> if (protocoloPedido == "0") "A$numeroProtocolo" else numeroProtocolo
            comando == "ATDP" -> if (protocoloPedido == "0") "AUTO, $nombreProtocolo" else nombreProtocolo
            comando.startsWith("AT") -> "OK"
            comando.startsWith("01") -> {
                // modo rápido de python-OBD: "010C1" pide una sola trama
                val pid = comando.substring(2, 4).toInt(16)
                val datos = modoUno[pid] ?: return "NO DATA"
                trama("41 %02X $datos".format(pid))
            }
            comando == "03" || comando == "07" -> trama(if (comando == "03") "43 00" else "47 00")
            else -> "NO DATA"
        }
    }

    /** Una trama simple con la cabecera del simulador (PCI en CAN, suma de control en línea K). */
    fun trama(datos: String): String {
        val b = datos.trim().split(" ")
        return if (esCan) "$cabecera %02X %s".format(b.size, datos.trim())
        else "$cabecera ${datos.trim()} %02X".format(sumaControl(cabecera, b))
    }

    private fun sumaControl(cab: String, datos: List<String>): Int =
        (cab.split(" ") + datos).sumOf { it.toInt(16) } and 0xFF
}

/** Transporte falso: entrega la respuesta del simulador seguida del prompt '>'. */
class TransporteFalso(
    private val elm: ElmSimulado,
    /** Tras este número de comandos el enlace se cae: leer() informa read() == −1. */
    private val caerTras: Int = Int.MAX_VALUE,
    private val fallaAlAbrir: Boolean = false,
) : TransporteSerie {
    override val descripcion = "transporte falso"
    val comandos = ArrayList<String>()
    private val pendientes = ArrayDeque<ByteArray>()
    private var abierto = false
    private var caido = false

    override suspend fun abrir() {
        if (fallaAlAbrir) throw AdaptadorNoEncontradoException("No se encuentra el adaptador ELM327 (simulado)")
        abierto = true
    }

    override suspend fun escribir(texto: String) {
        if (!abierto || caido) throw EnlaceCaidoException("El enlace no está abierto")
        val comando = texto.trimEnd('\r')
        comandos += comando
        if (comandos.size > caerTras) {
            caido = true
            return
        }
        val r = elm.responder(comando) ?: return
        pendientes += "$r\r\r>".replace("\r", "\r").toByteArray(Charsets.US_ASCII)
    }

    override suspend fun leer(): ByteArray {
        // cede el turno como lo haría una lectura real, para que los observadores vean cada estado
        yield()
        if (caido) throw EnlaceCaidoException("El adaptador cerró el enlace (read() devolvió -1)")
        pendientes.removeFirstOrNull()?.let { return it }
        // sin respuesta: se queda esperando hasta el tiempo límite de quien lee
        awaitCancellation()
    }

    override suspend fun descartarEntrada() {
        pendientes.clear()
    }

    override suspend fun cerrar() {
        abierto = false
    }
}

/** Un vehículo CAN de gestión MAP como el Tucson de VEH-010, en ralentí caliente. */
fun elmTucson(): ElmSimulado = ElmSimulado(
    modoUno = mutableMapOf(
        0x00 to "BE 3F A8 13",
        0x0C to "0A 6B", // 666,75 rpm
        0x04 to "68", // 40,784 %
        0x43 to "00 2C", // 17,255 %
        0x0B to "1E", // 30 kPa
        0x0F to "6A", // 66 °C
        0x05 to "85", // 93 °C
        0x06 to "8E", // 10,938 %
        0x07 to "80", // 0 %
        0x44 to "7F BE",
        0x14 to "19 FF",
        0x15 to "9F FF",
        0x03 to "02 00",
        0x0E to "92", // 9 °
        0x11 to "1F",
        0x33 to "4A", // 74 kPa
        0x42 to "36 2A",
        0x0D to "00",
        0x2E to "10",
        0x46 to "37",
    ),
).apply {
    responderSecuencia("0100", "SEARCHING...\r7E8 06 41 00 BE 3F A8 13 00", trama("41 00 BE 3F A8 13"))
}
