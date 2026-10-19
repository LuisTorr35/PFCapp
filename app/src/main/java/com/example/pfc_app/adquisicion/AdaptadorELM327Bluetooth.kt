package com.example.pfc_app.adquisicion

import android.content.Context
import com.example.pfc_app.Registro
import com.example.pfc_app.modelos.CodigoDTC
import com.example.pfc_app.modelos.ContadorModo06
import com.example.pfc_app.modelos.FamiliaGestion
import com.example.pfc_app.modelos.Lectura
import com.example.pfc_app.modelos.TipoDTC
import com.example.pfc_app.modelos.TipoRegimen
import com.example.pfc_app.procesamiento.Redondeo
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Ajustes del adaptador real, con los valores de registrar_obd.py v4. */
data class AjustesAdaptador(
    /** reconectar(): hasta 8 intentos con medio segundo entre ellos. */
    val intentosReconexion: Int = 8,
    val pausaReconexionMs: Long = 500,
    /** Periodo objetivo de una muestra (--hz 1.0); si la consulta tarda menos, se espera el resto. */
    val periodoMs: Long = 1_000,
    /** Lecturas seguidas sin rpm que fuerzan la reconexión. */
    val lecturasSinRpm: Int = 2,
    /** Consultar el anillo lento cada [PidOBD.CADA_LENTOS] muestras, para conservar la tasa del entrenamiento. */
    val anilloLento: Boolean = true,
    val tiempos: TiemposELM327 = TiemposELM327()
)

/** Lo que el sondeo inicial averiguó del vehículo. */
private class Sondeo(
    /** Anillo rápido: el PID elegido de cada parámetro que contestó, en el orden de registrar_obd.py. */
    val rapidos: MutableList<PidOBD> = mutableListOf(),
    /** Parámetros del anillo rápido que no contestaron, para el re-sondeo con el motor encendido. */
    val faltantes: MutableList<List<PidOBD>> = mutableListOf(),
    val lentos: MutableList<PidOBD> = mutableListOf(),
    val lentosFaltantes: MutableList<PidOBD> = mutableListOf(),
    var baro: Double? = null,
    var velocidad: Double? = null,
    /** MAP leída con el motor apagado: respaldo de la barométrica. */
    var mapMotorApagado: Double? = null,
    var resondeado: Boolean = false
)

/**
 * Adaptador ELM327 real por Bluetooth clásico (SPP), con el protocolo de captura de
 * registrar_obd.py v4: sondeo de cada PID con una consulta real (sin creerle a la
 * máscara), re-sondeo con el motor encendido, la misma lista rápida por muestra,
 * barométrica y velocidad una sola vez por sesión, anillo lento cada 10 muestras y
 * reconexión inmediata ante excepción, read() == −1, tiempo agotado o dos lecturas
 * seguidas sin rpm (RNF-01).
 *
 * El transporte se crea con [fabricaTransporte] en cada conexión; en el teléfono es
 * [TransporteBluetooth], en las pruebas de JVM un transporte falso.
 */
class AdaptadorELM327Bluetooth(
    private val fabricaTransporte: () -> TransporteSerie,
    val direccionMac: String,
    private val despachador: CoroutineDispatcher = Dispatchers.IO,
    private val ajustes: AjustesAdaptador = AjustesAdaptador(),
    private val reloj: () -> Long = { System.currentTimeMillis() }
) : AdaptadorELM327 {

    constructor(contexto: Context, direccionMac: String) : this(
        fabricaTransporte = { TransporteBluetooth(contexto.applicationContext, direccionMac) },
        direccionMac = direccionMac
    )

    override val esDemostracion: Boolean = false

    private val _estadoEnlace = MutableStateFlow(EstadoEnlace.DESCONECTADO)
    override val estadoEnlace: StateFlow<EstadoEnlace> = _estadoEnlace.asStateFlow()

    private val candado = Mutex()
    private var transporte: TransporteSerie? = null
    private var cliente: ClienteELM327? = null
    private var sondeo = Sondeo()

    /** Protocolo de la última conexión buena; la reconexión lo prueba primero. */
    var protocoloMemorizado: String? = null
        private set
    var nombreProtocolo: String? = null
        private set
    var versionElm: String? = null
        private set

    /** Familia según contesten MAF (0x10) y MAP (0x0B) en el sondeo. */
    var familiaGestion: FamiliaGestion? = null
        private set

    /** Dirección del motor aprendida (VEH-008 responde desde la 11). */
    val direccionMotor: Int? get() = cliente?.direccionMotor

    override suspend fun conectar(): ResultadoConexion = withContext(despachador) {
        _estadoEnlace.value = EstadoEnlace.CONECTANDO
        Registro.i(Registro.BT, "CU-02: conectando con el adaptador $direccionMac")
        try {
            candado.withLock {
                abrirEIniciar(protocoloMemorizado)
                try {
                    sondear("motor apagado")
                } catch (e: IOException) {
                    // el enlace cayó a mitad del sondeo: se reconecta y se sondea de nuevo
                    Registro.w(Registro.BT, "El enlace cayó durante el sondeo: ${e.message}")
                    if (!reconectarSinCandado()) throw e
                    sondear("motor apagado")
                }
            }
            _estadoEnlace.value = EstadoEnlace.CONECTADO
            Registro.i(Registro.BT, "Conectado: $nombreProtocolo, familia ${familiaGestion?.texto}")
            ResultadoConexion(exito = true, protocolo = nombreProtocolo, familiaGestion = familiaGestion)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Registro.e(Registro.BT, "No se pudo conectar: ${e.message}", e)
            cerrarTransporte()
            _estadoEnlace.value = EstadoEnlace.DESCONECTADO
            ResultadoConexion(exito = false, mensajeError = mensajeLegible(e))
        }
    }

    override suspend fun leerCodigos(): List<CodigoDTC> = conReconexion("leer códigos") { c ->
        // sin esta verificación, una computadora muda se leería como "sin códigos" (ATN-021)
        if (!c.vivo()) throw EnlaceCaidoException("La unidad de control no contesta 0100")
        val almacenados = DecodificadorDTC.codigos(c.consultar("03", rapido = false), 0x43, c.formato.esCan, TipoDTC.ALMACENADO)
        val pendientes = DecodificadorDTC.codigos(c.consultar("07", rapido = false), 0x47, c.formato.esCan, TipoDTC.PENDIENTE)
        (almacenados + pendientes).also { l ->
            Registro.i(Registro.BT, "Códigos: ${if (l.isEmpty()) "ninguno" else l.joinToString { "${it.codigo} (${it.tipo.texto})" }}")
        }
    }

    /** Modo 06, MID A2 a A9 (cilindros 1 a 8). Solo en CAN; en línea K el formato es otro y se devuelve vacío. */
    override suspend fun leerContadoresModo06(): List<ContadorModo06> = conReconexion("leer modo 06") { c ->
        if (!c.formato.esCan) {
            Registro.i(Registro.BT, "Modo 06 no se lee en línea K ($nombreProtocolo)")
            return@conReconexion emptyList()
        }
        if (!c.vivo()) throw EnlaceCaidoException("La unidad de control no contesta 0100")
        val salida = ArrayList<ContadorModo06>()
        for (cil in 1..8) {
            val mid = DecodificadorDTC.midCilindro(cil)
            val m = c.delMotor(c.consultar("06%02X".format(mid), rapido = false)) {
                it.datos.size >= 2 && it.datos[0] == 0x46 && it.datos[1] == mid
            } ?: continue
            DecodificadorDTC.contador(cil, m.datos)?.let { salida += it }
        }
        Registro.i(
            Registro.BT,
            "Modo 06: ${if (salida.isEmpty()) {
                "sin contadores"
            } else {
                salida.joinToString {
                    "cil ${it.cilindro} actual ${it.conteoActual} promedio ${it.conteoPromedio}"
                }
            }}"
        )
        salida
    }

    /** PID 0x33 leído en el sondeo, o null si el vehículo no lo expone. */
    override suspend fun leerBarometrica(): Double? {
        if (sondeo.baro == null && cliente != null) {
            sondeo.baro = conReconexion("leer barométrica") { c ->
                c.consultarPid(PidOBD.BAROMETRIC_PRESSURE.codigo)?.let { PidOBD.BAROMETRIC_PRESSURE.decodificar(it) }
            }
        }
        Registro.i(Registro.BT, "Barométrica (PID 0x33): ${sondeo.baro ?: "no la expone el vehículo"}")
        return sondeo.baro
    }

    /** MAP (PID 0x0B) leída en el sondeo con el motor apagado. */
    override suspend fun leerMapContacto(): Double? {
        Registro.i(Registro.BT, "MAP con el motor apagado: ${sondeo.mapMotorApagado ?: "no disponible"}")
        return sondeo.mapMotorApagado
    }

    override suspend fun leerTemperaturaRefrigerante(): Double? = conReconexion("leer refrigerante") { c ->
        val datos = c.consultarPid(PidOBD.COOLANT_TEMP.codigo)
        // sin respuesta: o el vehículo no lo expone, o la computadora quedó muda tras el arranque
        if (datos == null && !c.vivo()) throw EnlaceCaidoException("La unidad de control dejó de contestar")
        datos?.let { PidOBD.COOLANT_TEMP.decodificar(it) }
    }.also { Registro.d(Registro.BT, "Refrigerante: $it °C") }

    /**
     * Una fila por ciclo de consultas del anillo rápido, con [Lectura.instante] en
     * segundos desde el inicio del régimen (t_seg de registrar_obd.py, a 2 decimales).
     * Sigue hasta que se cancela; si el enlace cae y no se recupera, termina con
     * [EnlaceCaidoException].
     */
    override fun leerParametros(regimen: TipoRegimen): Flow<Lectura> = flow {
        candado.withLock {
            if (cliente == null && !reconectarSinCandado()) throw EnlaceCaidoException("Sin enlace con el adaptador")
        }
        try {
            candado.withLock { if (!sondeo.resondeado) resondear() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            // el arranque del motor suele tumbar el enlace justo aquí
            Registro.w(Registro.BT, "Falló el re-sondeo con el motor encendido: ${e.message}")
            if (!reconectar()) throw EnlaceCaidoException("Se perdió el enlace con el adaptador y no se pudo restablecer")
        }
        Registro.i(
            Registro.BT,
            "$regimen: ${sondeo.rapidos.size} PID por muestra (${sondeo.rapidos.joinToString { it.nombre }}), " +
                "${sondeo.lentos.size} lentos cada ${PidOBD.CADA_LENTOS}"
        )
        val t0 = reloj()
        var nCiclo = 0
        var sinRpm = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val inicioCiclo = reloj()
            val t = (inicioCiclo - t0) / 1000.0
            val valores = HashMap<CampoLectura, Double?>()
            val completo = try {
                candado.withLock {
                    val c = cliente ?: throw EnlaceCaidoException("Sin cliente")
                    for (pid in sondeo.rapidos) {
                        val v = c.consultarPid(pid.codigo)?.let { pid.decodificar(it) }
                        pid.campo?.let { valores[it] = v }
                    }
                    if (ajustes.anilloLento && sondeo.lentos.isNotEmpty() && nCiclo % PidOBD.CADA_LENTOS == 0) {
                        for (pid in sondeo.lentos) c.consultarPid(pid.codigo)
                    }
                }
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Registro.w(Registro.BT, "$regimen: falló la muestra en t=$t s: ${e.message}")
                false
            }
            if (!completo) {
                // el corte del enlace descarta la muestra a medias, reconecta y sigue
                if (!reconectar()) throw EnlaceCaidoException("Se perdió el enlace con el adaptador y no se pudo restablecer")
                continue
            }
            nCiclo++
            // el adaptador a veces sigue "conectado" pero ya no lee nada
            if (valores[CampoLectura.RPM] == null && PidOBD.RPM in sondeo.rapidos) sinRpm++ else sinRpm = 0
            if (sinRpm >= ajustes.lecturasSinRpm) {
                Registro.w(Registro.BT, "$regimen: $sinRpm lecturas seguidas sin rpm, reconectando")
                sinRpm = 0
                if (!reconectar()) throw EnlaceCaidoException("El adaptador dejó de entregar datos y no se pudo reconectar")
                continue
            }
            emit(
                Lectura(
                    instante = Redondeo.python(t, 2),
                    rpm = valores[CampoLectura.RPM],
                    tempRefrigerante = valores[CampoLectura.REFRIGERANTE],
                    stft = valores[CampoLectura.STFT],
                    ltft = valores[CampoLectura.LTFT],
                    cargaCalculada = valores[CampoLectura.CARGA],
                    avanceEncendido = valores[CampoLectura.AVANCE],
                    maf = valores[CampoLectura.MAF],
                    map = valores[CampoLectura.MAP],
                    velocidad = sondeo.velocidad
                )
            )
            val resto = ajustes.periodoMs - (reloj() - inicioCiclo)
            if (resto > 0) delay(resto)
        }
    }.flowOn(despachador)

    /** Reconexión inmediata (RNF-01): hasta 8 intentos con 0,5 s entre ellos. */
    override suspend fun reconectar(): Boolean = withContext(despachador) { candado.withLock { reconectarSinCandado() } }

    override suspend fun cerrar() {
        withContext(despachador) { candado.withLock { cerrarTransporte() } }
        _estadoEnlace.value = EstadoEnlace.DESCONECTADO
        Registro.i(Registro.BT, "Enlace cerrado")
    }

    // ---------------------------------------------------------------------------

    private suspend fun abrirEIniciar(protocolo: String?) {
        cerrarTransporte()
        val t = fabricaTransporte()
        transporte = t
        Registro.i(Registro.BT, "Abriendo ${t.descripcion}")
        t.abrir()
        val c = ClienteELM327(t, ajustes.tiempos)
        val inicio = c.iniciar(protocolo)
        cliente = c
        protocoloMemorizado = inicio.numeroProtocolo
        nombreProtocolo = inicio.nombreProtocolo
        versionElm = inicio.versionElm
    }

    private suspend fun reconectarSinCandado(): Boolean {
        _estadoEnlace.value = EstadoEnlace.RECONECTANDO
        Registro.w(Registro.BT, "Enlace caído. Reconexión inmediata (protocolo memorizado $protocoloMemorizado)")
        var protocolo = protocoloMemorizado
        for (i in 1..ajustes.intentosReconexion) {
            try {
                abrirEIniciar(protocolo)
                _estadoEnlace.value = EstadoEnlace.CONECTADO
                Registro.i(Registro.BT, "Reconectado en el intento $i de ${ajustes.intentosReconexion}")
                return true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Registro.w(Registro.BT, "Reconexión, intento $i de ${ajustes.intentosReconexion}: ${e.message}")
                cerrarTransporte()
                // si el protocolo memorizado no sirvió, el siguiente intento autodetecta
                protocolo = null
            }
            if (i < ajustes.intentosReconexion) delay(ajustes.pausaReconexionMs)
        }
        _estadoEnlace.value = EstadoEnlace.DESCONECTADO
        Registro.e(Registro.BT, "No se pudo reconectar tras ${ajustes.intentosReconexion} intentos")
        return false
    }

    private suspend fun cerrarTransporte() {
        val t = transporte ?: return
        transporte = null
        cliente = null
        try {
            t.cerrar()
        } catch (e: Exception) {
            Registro.w(Registro.BT, "Error al cerrar el transporte: ${e.message}")
        }
    }

    /**
     * Ejecuta una lectura suelta; ante excepción, -1 o tiempo agotado reconecta y la
     * repite (hasta 3 veces, como Enlace.query de registrar_obd.py).
     */
    private suspend fun <T> conReconexion(que: String, bloque: suspend (ClienteELM327) -> T): T = withContext(despachador) {
        var ultimo: Exception? = null
        for (intento in 1..3) {
            try {
                return@withContext candado.withLock {
                    val c = cliente ?: run {
                        if (!reconectarSinCandado()) throw EnlaceCaidoException("Sin enlace con el adaptador")
                        cliente!!
                    }
                    bloque(c)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                ultimo = e
                Registro.w(Registro.BT, "Falló $que (intento $intento): ${e.message}")
                if (!reconectar()) break
            }
        }
        throw EnlaceCaidoException("No se pudo $que: se perdió el enlace con el adaptador", ultimo)
    }

    /** Sondeo de cada PID con una consulta real, sin creerle a la máscara de soportados. */
    private suspend fun sondear(momento: String) {
        val c = cliente ?: return
        val s = Sondeo()
        for (candidatos in PidOBD.ANILLO_RAPIDO) {
            val elegido = probarCandidatos(c, candidatos, momento)
            if (elegido != null) s.rapidos += elegido.first else s.faltantes += candidatos
            if (elegido?.first == PidOBD.INTAKE_PRESSURE) s.mapMotorApagado = elegido.second
        }
        for (pid in PidOBD.CONSTANTES_SESION) {
            val datos = c.consultarPid(pid.codigo)
            Registro.d(Registro.BT, "Sondeo ($momento) ${pid.nombre}: ${if (datos != null) "contesta" else "no contesta"}")
            val v = datos?.let { pid.decodificar(it) }
            when (pid) {
                PidOBD.BAROMETRIC_PRESSURE -> s.baro = v
                PidOBD.SPEED -> s.velocidad = v
                else -> Unit
            }
        }
        for (pid in PidOBD.ANILLO_LENTO) {
            if (c.consultarPid(pid.codigo) != null) s.lentos += pid else s.lentosFaltantes += pid
        }
        sondeo = s
        familiaGestion = FamiliaGestion.desdeSensores(
            tieneMaf = PidOBD.MAF in s.rapidos,
            tieneMap = PidOBD.INTAKE_PRESSURE in s.rapidos
        )
        Registro.i(
            Registro.BT,
            "Sondeo ($momento): contestan ${s.rapidos.joinToString { it.nombre }}; " +
                "sin lectura ${s.faltantes.joinToString { it.first().canonico }}; lentos ${s.lentos.joinToString { it.nombre }}; " +
                "barométrica ${s.baro}, velocidad ${s.velocidad}, MAP en reposo ${s.mapMotorApagado}"
        )
    }

    /**
     * Versión 4: lo que no contestó con el motor apagado se vuelve a preguntar una vez
     * con el motor en marcha, porque hay parámetros que la computadora solo entrega encendida.
     */
    private suspend fun resondear() {
        val c = cliente ?: return
        sondeo.resondeado = true
        if (sondeo.faltantes.isEmpty() && sondeo.lentosFaltantes.isEmpty()) return
        val recuperados = ArrayList<PidOBD>()
        for (candidatos in sondeo.faltantes.toList()) {
            val elegido = probarCandidatos(c, candidatos, "motor encendido") ?: continue
            recuperados += elegido.first
            sondeo.faltantes.remove(candidatos)
        }
        // se respeta el orden de registrar_obd.py en la lista rápida
        val orden = PidOBD.ANILLO_RAPIDO.flatten()
        val nuevaLista = (sondeo.rapidos + recuperados).sortedBy { orden.indexOf(it) }
        sondeo.rapidos.clear()
        sondeo.rapidos += nuevaLista
        for (pid in sondeo.lentosFaltantes.toList()) {
            if (c.consultarPid(pid.codigo) != null) {
                sondeo.lentos += pid
                sondeo.lentosFaltantes.remove(pid)
                recuperados += pid
            }
        }
        Registro.i(
            Registro.BT,
            "Re-sondeo con el motor encendido: " +
                if (recuperados.isEmpty()) "ninguno más contestó" else "recuperados ${recuperados.joinToString { it.nombre }}"
        )
    }

    /** El primer candidato que contesta y su valor (puede ser null si el dato es categórico). */
    private suspend fun probarCandidatos(c: ClienteELM327, candidatos: List<PidOBD>, momento: String): Pair<PidOBD, Double?>? {
        for (pid in candidatos) {
            val datos = c.consultarPid(pid.codigo, rapido = false)
            Registro.d(Registro.BT, "Sondeo ($momento) ${pid.nombre}: ${if (datos != null) "contesta" else "no contesta"}")
            if (datos != null) return pid to pid.decodificar(datos)
        }
        return null
    }

    private fun mensajeLegible(e: Exception): String = when (e) {
        is PermisoBluetoothException, is AdaptadorNoEncontradoException, is VehiculoNoRespondeException ->
            e.message ?: "No se pudo conectar"

        is EnlaceCaidoException ->
            "Se perdió el enlace con el adaptador durante la conexión. Revise que esté bien enchufado y vuelva a intentar."

        else -> "No se pudo conectar con el adaptador: ${e.message}"
    }
}
