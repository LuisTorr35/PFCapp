package com.example.pfc_app.adquisicion

/**
 * Formato de las líneas que devuelve el ELM327 con las cabeceras activas (ATH1).
 *  - CAN_11: "7E8 06 41 0C 0A 6B": 3 dígitos de identificador, byte PCI y datos.
 *  - CAN_29: "18 DA F1 10 06 41 0C ...": 4 bytes de cabecera (el último es el emisor).
 *  - LEGADO: línea K (ISO 9141-2, ISO 14230-4 KWP) y J1850: "48 6B 11 41 0C 0C 80 xx",
 *    3 bytes de cabecera (el tercero es el emisor), datos y un byte de suma de control.
 */
enum class FormatoTrama {
    CAN_11,
    CAN_29,
    LEGADO;

    val esCan: Boolean get() = this != LEGADO

    companion object {
        /** Según el número de protocolo de ATDPN (sin la "A" de automático). */
        fun desdeProtocolo(numero: String?): FormatoTrama? = when (numero?.trim()?.uppercase()?.removePrefix("A")) {
            "1", "2", "3", "4", "5" -> LEGADO
            "6", "8", "B" -> CAN_11
            "7", "9", "C" -> CAN_29
            else -> null
        }
    }
}

/** Una línea de datos ya separada en emisor y bytes (sin cabecera ni suma de control). */
data class TramaOBD(val origen: Int, val bytes: List<Int>, val crudo: String)

/** Un mensaje completo de un emisor: en CAN, las tramas ISO-TP ya reunidas. */
data class MensajeOBD(val origen: Int, val datos: List<Int>, val tramas: Int)

/**
 * Separa la respuesta cruda del ELM327 en tramas y reúne los mensajes de varias
 * tramas (ISO-TP en CAN: primera trama 1L LL, consecutivas 2N). No descarta
 * respuestas por dirección: devuelve las de todos los emisores y quien consulta
 * elige la del motor ([ClienteELM327.direccionMotor]).
 */
object MensajesOBD {

    private val HEX = Regex("^[0-9A-Fa-f]+$")

    /**
     * true si la línea son datos: solo dígitos hexadecimales (y espacios). Las de
     * estado del ELM327 (SEARCHING..., BUS INIT: ...OK, NO DATA, UNABLE TO CONNECT,
     * STOPPED, CAN ERROR, ?) siempre tienen alguna letra que no es hexadecimal.
     */
    fun esDato(linea: String): Boolean {
        val plano = linea.replace(" ", "")
        return plano.isNotEmpty() && HEX.matches(plano)
    }

    /** Deduce el formato de las propias líneas, para la primera respuesta antes de ATDPN. */
    fun deducirFormato(lineas: List<String>): FormatoTrama {
        for (l in lineas) {
            val tokens = l.trim().split(Regex("\\s+"))
            val plano = l.replace(" ", "")
            if (!HEX.matches(plano)) continue
            if (tokens.size > 1) {
                if (tokens[0].length == 3) return FormatoTrama.CAN_11
                if (tokens.size > 4 && tokens[0] == "18" && tokens[1].equals("DA", true)) return FormatoTrama.CAN_29
                return FormatoTrama.LEGADO
            }
            if (plano.length % 2 == 1) return FormatoTrama.CAN_11
            if (plano.startsWith("18DA", ignoreCase = true) && plano.length >= 12) return FormatoTrama.CAN_29
            return FormatoTrama.LEGADO
        }
        return FormatoTrama.CAN_11
    }

    /** Las líneas de datos de la respuesta, con su emisor. Las líneas de estado se ignoran. */
    fun tramas(lineas: List<String>, formato: FormatoTrama): List<TramaOBD> {
        val salida = ArrayList<TramaOBD>()
        for (linea in lineas) {
            val plano = linea.replace(" ", "").replace(":", "")
            if (plano.isEmpty() || !HEX.matches(plano)) continue
            when (formato) {
                FormatoTrama.CAN_11 -> {
                    if (plano.length < 5 || (plano.length - 3) % 2 != 0) continue
                    salida += TramaOBD(plano.substring(0, 3).toInt(16), bytes(plano.substring(3)), linea)
                }

                FormatoTrama.CAN_29 -> {
                    if (plano.length < 10 || plano.length % 2 != 0) continue
                    val cab = bytes(plano.substring(0, 8))
                    salida += TramaOBD(cab[3], bytes(plano.substring(8)), linea)
                }

                FormatoTrama.LEGADO -> {
                    if (plano.length % 2 != 0) continue
                    val b = bytes(plano)
                    if (b.size < 5) continue
                    // 3 bytes de cabecera (prioridad o formato, destino, emisor), datos y suma de control
                    salida += TramaOBD(b[2], b.subList(3, b.size - 1), linea)
                }
            }
        }
        return salida
    }

    /** Reúne las tramas en mensajes por emisor, en el orden en que llegaron. */
    fun mensajes(tramas: List<TramaOBD>, formato: FormatoTrama): List<MensajeOBD> =
        if (formato.esCan) mensajesCan(tramas) else mensajesLegado(tramas)

    private fun mensajesCan(tramas: List<TramaOBD>): List<MensajeOBD> {
        val salida = ArrayList<MensajeOBD>()
        val pendientes = LinkedHashMap<Int, Pendiente>()
        for (t in tramas) {
            if (t.bytes.isEmpty()) continue
            val pci = t.bytes[0]
            when (pci shr 4) {
                0x0 -> {
                    val largo = pci and 0x0F
                    if (largo == 0) continue
                    salida += MensajeOBD(t.origen, t.bytes.drop(1).take(largo), 1)
                }

                0x1 -> {
                    if (t.bytes.size < 2) continue
                    val largo = ((pci and 0x0F) shl 8) or t.bytes[1]
                    pendientes[t.origen] = Pendiente(largo, ArrayList(t.bytes.drop(2)), 1)
                }

                0x2 -> {
                    val p = pendientes[t.origen] ?: continue
                    p.datos += t.bytes.drop(1)
                    p.tramas++
                    if (p.datos.size >= p.largo) {
                        salida += MensajeOBD(t.origen, p.datos.take(p.largo), p.tramas)
                        pendientes.remove(t.origen)
                    }
                }

                else -> Unit // control de flujo (3x): lo maneja el ELM327
            }
        }
        // un mensaje largo al que le faltó alguna trama se entrega con lo que llegó
        for ((origen, p) in pendientes) salida += MensajeOBD(origen, p.datos.take(p.largo), p.tramas)
        return salida
    }

    /**
     * Línea K y J1850: cada línea es un mensaje. Los modos 03 y 07 llegan en varias
     * líneas, cada una con el byte de modo y tres códigos; se juntan en uno solo
     * conservando un único byte de modo, como hace python-OBD.
     */
    private fun mensajesLegado(tramas: List<TramaOBD>): List<MensajeOBD> {
        val salida = ArrayList<MensajeOBD>()
        val porOrigen = tramas.filter { it.bytes.isNotEmpty() }.groupBy { it.origen }
        for ((origen, lista) in porOrigen) {
            val modo = lista.first().bytes[0]
            if ((modo == 0x43 || modo == 0x47) && lista.all { it.bytes[0] == modo }) {
                val datos = arrayListOf(modo)
                for (t in lista) datos += t.bytes.drop(1)
                salida += MensajeOBD(origen, datos, lista.size)
            } else {
                for (t in lista) salida += MensajeOBD(origen, t.bytes, 1)
            }
        }
        return salida
    }

    private class Pendiente(val largo: Int, val datos: ArrayList<Int>, var tramas: Int)

    private fun bytes(hex: String): List<Int> = hex.chunked(2).map { it.toInt(16) }
}
