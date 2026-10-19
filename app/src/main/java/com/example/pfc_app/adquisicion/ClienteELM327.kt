package com.example.pfc_app.adquisicion

import com.example.pfc_app.Registro
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** Tiempos del diálogo con el ELM327, en milisegundos. */
data class TiemposELM327(
    /** ATZ reinicia el adaptador y tarda alrededor de un segundo. */
    val reinicio: Long = 5_000,
    val comandoAt: Long = 3_000,
    /** La primera 0100 incluye SEARCHING... o BUS INIT de la línea K, que tarda varios segundos. */
    val busqueda: Long = 15_000,
    /** Una consulta OBD normal; el ELM327 contesta NO DATA solo mucho antes. */
    val consulta: Long = 5_000,
    /** Pausa entre intentos de 0100 en línea K, como rescatar_linea_k(). */
    val pausaLineaK: Long = 2_000,
    val pausaCan: Long = 500
)

/** Resultado del inicio del adaptador y de la detección del protocolo. */
data class InicioELM327(
    val versionElm: String?,
    /** Número de ATDPN sin la "A" de automático: 6 es CAN 11/500, 3 es ISO 9141-2. */
    val numeroProtocolo: String,
    /** Descripción de ATDP, por ejemplo "ISO 15765-4 (CAN 11/500)". */
    val nombreProtocolo: String,
    val formato: FormatoTrama,
    /** Emisor del motor aprendido de la primera respuesta válida a 0100. */
    val direccionMotor: Int
)

/**
 * Diálogo con el ELM327 sobre un [TransporteSerie]: comandos AT y OBD, lectura de
 * la respuesta hasta el prompt '>', detección del protocolo y elección de la
 * respuesta del motor entre los módulos que contestan. Cada comando y su respuesta
 * cruda quedan en el registro PFC.BT, que es lo que se mira en el debug del teléfono.
 *
 * Inicio como python-OBD: ATZ, ATE0, ATL0, ATS1 (con espacios, para que el registro
 * crudo se lea byte a byte), ATH1 (cabeceras, para saber qué computadora responde),
 * ATSP del protocolo memorizado o ATSP0 (automático) y 0100 con reintentos. Si no
 * engancha, prueba la línea K como diagnostico_vehiculo.py: ATSP3, ATSP4 y ATSP5.
 */
class ClienteELM327(private val transporte: TransporteSerie, private val tiempos: TiemposELM327 = TiemposELM327()) {
    var formato: FormatoTrama = FormatoTrama.CAN_11
        private set

    /** Emisor del motor: 0x7E8 en CAN 11 bits; 0x10 en CAN 29 bits y línea K, salvo vehículos como VEH-008 (0x11). */
    var direccionMotor: Int? = null
        private set

    /**
     * Modo rápido de python-OBD: tras la primera respuesta de un PID se agrega al
     * comando el número de tramas que contestaron ("010C1"), y el ELM327 vuelve en
     * cuanto las recibe en vez de esperar su tiempo límite. Es lo que daba la tasa
     * de muestreo del entrenamiento en CAN; en línea K se capturó con --lento, sin él.
     */
    var modoRapido: Boolean = true

    private val tramasPorComando = HashMap<String, Int>()

    /** Envía un comando y devuelve las líneas de la respuesta, sin eco, sin vacías y sin el prompt. */
    suspend fun enviar(comando: String, tiempoLimiteMs: Long = tiempos.consulta): List<String> {
        transporte.descartarEntrada()
        Registro.d(Registro.BT, "→ $comando")
        transporte.escribir("$comando\r")
        val texto = StringBuilder()
        val completo = withTimeoutOrNull(tiempoLimiteMs) {
            while (texto.indexOf(">") < 0) texto.append(String(transporte.leer(), Charsets.US_ASCII))
            true
        }
        val crudo = texto.toString()
        Registro.d(Registro.BT, "← $comando: ${crudo.replace("\r", "\\r").replace("\n", "\\n")}")
        if (completo == null) {
            throw TiempoAgotadoException("El adaptador no terminó de responder a $comando en $tiempoLimiteMs ms")
        }
        val lineas = crudo.substringBefore('>')
            .replace("\u0000", "")
            .split('\r', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filterNot { it.replace(" ", "").equals(comando.replace(" ", ""), ignoreCase = true) }
        // el bajón de voltaje del arranque reinicia el adaptador: vuelve a saludar y pierde
        // ATE0, ATH1 y el protocolo, así que hay que configurarlo de nuevo
        if (comando != "ATZ" && lineas.any { it.contains("ELM327", ignoreCase = true) || it.contains("LV RESET", ignoreCase = true) }) {
            throw EnlaceCaidoException("El adaptador se reinició durante $comando (probable bajón de voltaje)")
        }
        return lineas
    }

    /** Comando AT que debe contestar OK; un clon que contesta otra cosa solo deja un aviso. */
    private suspend fun at(comando: String, tiempo: Long = tiempos.comandoAt): List<String> {
        val r = enviar(comando, tiempo)
        if (r.none { it.contains("OK", ignoreCase = true) }) {
            Registro.w(Registro.BT, "$comando no contestó OK: ${r.joinToString(" | ")}")
        }
        return r
    }

    /**
     * Configura el adaptador y engancha con la unidad de control. [preferido] es el
     * protocolo memorizado de la conexión anterior: se prueba primero.
     */
    suspend fun iniciar(preferido: String? = null): InicioELM327 {
        Registro.i(Registro.BT, "Inicio del ELM327 por ${transporte.descripcion}")
        var reinicio = try {
            enviar("ATZ", tiempos.reinicio)
        } catch (e: TiempoAgotadoException) {
            Registro.w(Registro.BT, "ATZ sin respuesta, se reintenta: ${e.message}")
            null
        }
        if (reinicio == null || reinicio.none { it.contains("ELM", ignoreCase = true) }) {
            reinicio = try {
                enviar("ATZ", tiempos.reinicio)
            } catch (e: TiempoAgotadoException) {
                throw AdaptadorNoEncontradoException(
                    "El adaptador ELM327 no responde. Revise que esté enchufado al conector OBD-II y vinculado con el teléfono."
                )
            }
        }
        val version = reinicio.firstOrNull { it.contains("ELM", ignoreCase = true) }
        Registro.i(Registro.BT, "Adaptador: ${version ?: "sin versión"}")
        at("ATE0")
        at("ATL0")
        at("ATS1")
        at("ATH1")

        val candidatos = (listOfNotNull(preferido) + listOf("0") + PROTOCOLOS_LINEA_K).distinct()
        for (p in candidatos) {
            val inicio = engancharCon(p)
            if (inicio != null) return inicio.copy(versionElm = version)
        }
        throw VehiculoNoRespondeException(
            "El vehículo no responde. Revise que el contacto esté encendido y que el adaptador esté bien enchufado."
        )
    }

    /** ATSP[p] y 0100 con reintentos; null si la unidad de control no contestó. */
    private suspend fun engancharCon(p: String): InicioELM327? {
        Registro.i(Registro.BT, "Probando protocolo ${if (p == "0") "automático" else p}")
        at("ATSP$p")
        val lineaK = p in PROTOCOLOS_LINEA_K
        val intentos = if (lineaK) 4 else 3
        repeat(intentos) { i ->
            val r = try {
                enviar("0100", tiempos.busqueda)
            } catch (e: TiempoAgotadoException) {
                Registro.w(Registro.BT, "0100 sin terminar con ATSP$p: ${e.message}")
                emptyList()
            }
            // SEARCHING..., BUS INIT: ..., UNABLE TO CONNECT, NO DATA o STOPPED se toleran y se reintenta
            val datos = r.filter(MensajesOBD::esDato)
            if (datos.isNotEmpty()) {
                val numero = enviar("ATDPN", tiempos.comandoAt).firstOrNull()?.trim()?.uppercase()?.removePrefix("A")
                    ?: p
                val nombre = enviar("ATDP", tiempos.comandoAt).firstOrNull()?.trim()
                    ?.removePrefix("AUTO, ")?.removePrefix("AUTO,")?.trim()
                    ?: "protocolo $numero"
                formato = FormatoTrama.desdeProtocolo(numero) ?: MensajesOBD.deducirFormato(datos)
                val mensajes = MensajesOBD.mensajes(MensajesOBD.tramas(datos, formato), formato)
                val validos = mensajes.filter { it.datos.size >= 2 && it.datos[0] == 0x41 && it.datos[1] == 0x00 }
                if (validos.isNotEmpty()) {
                    val motor = validos.firstOrNull { it.origen == motorHabitual(formato) } ?: validos.first()
                    direccionMotor = motor.origen
                    modoRapido = formato.esCan
                    tramasPorComando.clear()
                    Registro.i(
                        Registro.BT,
                        "Enganchado: protocolo $numero ($nombre), motor en la dirección ${"%02X".format(motor.origen)}" +
                            (if (validos.size > 1) ", contestan ${validos.joinToString { "%02X".format(it.origen) }}" else "") +
                            (if (motor.origen != motorHabitual(formato)) " (no es la habitual)" else "")
                    )
                    return InicioELM327(null, numero, nombre, formato, motor.origen)
                }
                Registro.w(Registro.BT, "0100 con datos pero sin respuesta 41 00 válida: ${datos.joinToString(" | ")}")
            } else {
                Registro.w(Registro.BT, "0100 sin datos (intento ${i + 1} de $intentos): ${r.joinToString(" | ")}")
            }
            delay(if (lineaK) tiempos.pausaLineaK else tiempos.pausaCan)
        }
        return null
    }

    /**
     * Consulta un comando OBD y devuelve los mensajes de todos los módulos que
     * contestaron (vacío con NO DATA). En modo rápido agrega el número de tramas
     * conocido para ese comando.
     */
    suspend fun consultar(comando: String, rapido: Boolean = true): List<MensajeOBD> {
        val conteo = tramasPorComando[comando]
        val enviado = if (rapido && modoRapido && conteo != null && conteo in 1..15) comando + "%X".format(conteo) else comando
        val lineas = enviar(enviado)
        val tramas = MensajesOBD.tramas(lineas.filter(MensajesOBD::esDato), formato)
        val mensajes = MensajesOBD.mensajes(tramas, formato)
        if (mensajes.isNotEmpty()) tramasPorComando[comando] = mensajes.sumOf { it.tramas }
        return mensajes
    }

    /**
     * Elige la respuesta del motor entre las válidas: la de [direccionMotor] si está;
     * si no, la primera que haya, sin descartarla por venir de otra dirección.
     */
    fun delMotor(mensajes: List<MensajeOBD>, valido: (MensajeOBD) -> Boolean): MensajeOBD? {
        val validos = mensajes.filter(valido)
        val propio = validos.firstOrNull { it.origen == direccionMotor }
        if (propio != null) return propio
        val otro = validos.firstOrNull() ?: return null
        Registro.w(
            Registro.BT,
            "Respuesta de la dirección ${"%02X".format(otro.origen)} en vez de la del motor " +
                (direccionMotor?.let { "%02X".format(it) } ?: "(sin aprender)") + ": se acepta"
        )
        return otro
    }

    /**
     * Prueba de vida con la unidad de control, no solo con el adaptador: 0100 lo
     * contesta toda computadora con la llave en contacto (Enlace.verificar de registrar_obd.py).
     */
    suspend fun vivo(): Boolean = consultarPid(0x00, rapido = false) != null

    /** Bytes de datos de un PID del modo 01 (tras 41 y el PID), o null si no contestó. */
    suspend fun consultarPid(pid: Int, rapido: Boolean = true): List<Int>? {
        val comando = "01%02X".format(pid)
        val m = delMotor(consultar(comando, rapido)) { it.datos.size >= 2 && it.datos[0] == 0x41 && it.datos[1] == pid }
        return m?.datos?.drop(2)
    }

    companion object {
        /** ISO 9141-2, KWP con inicio de 5 baudios y KWP con inicio rápido. */
        val PROTOCOLOS_LINEA_K = listOf("3", "4", "5")

        fun motorHabitual(formato: FormatoTrama): Int = if (formato == FormatoTrama.CAN_11) 0x7E8 else 0x10

        /** Para que un catch genérico no se trague la cancelación de la corrutina. */
        internal fun relanzarSiCancelacion(e: Throwable) {
            if (e is CancellationException) throw e
        }
    }
}
