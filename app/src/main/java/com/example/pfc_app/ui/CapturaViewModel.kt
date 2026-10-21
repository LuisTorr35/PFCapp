package com.example.pfc_app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.pfc_app.AppContenedor
import com.example.pfc_app.AtencionEnCurso
import com.example.pfc_app.ConfiguracionAdaptador
import com.example.pfc_app.Registro
import com.example.pfc_app.adquisicion.AdaptadorELM327
import com.example.pfc_app.adquisicion.EstadoCaptura
import com.example.pfc_app.adquisicion.GrabacionDemo
import com.example.pfc_app.adquisicion.ServicioAdquisicion
import com.example.pfc_app.modelos.Atencion
import com.example.pfc_app.modelos.Dictamen
import com.example.pfc_app.modelos.ResultadoDictamen
import com.example.pfc_app.modelos.TipoRegimen
import com.example.pfc_app.modelos.Vehiculo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** En qué punto está la pantalla de Captura. */
enum class FaseCaptura {
    PREPARANDO,
    CAPTURANDO,
    VALIDANDO,

    /** Hay dictamen y la pantalla espera al mecánico (Diagnosticar, Repetir o Guardar sin diagnóstico). */
    LISTA,
    REPITIENDO,
    DIAGNOSTICANDO,
    GUARDANDO,
    GUARDADA,
    ERROR
}

data class EstadoPantallaCaptura(
    val vehiculo: Vehiculo? = null,
    val modoDemostracion: Boolean = false,
    val grabacion: GrabacionDemo? = null,
    /** Lo que publica el servicio de adquisición mientras avanza la atención. */
    val captura: EstadoCaptura = EstadoCaptura(),
    val fase: FaseCaptura = FaseCaptura.PREPARANDO,
    val dictamen: Dictamen? = null,
    /** Regímenes que tenía la atención al validarse (para "R1 y R3 se conservan"). */
    val regimenesCapturados: Set<TipoRegimen> = emptySet(),
    val regimenEnRepeticion: TipoRegimen? = null,
    /** El vehículo no expone los parámetros que pide el modelo (regla 8) o el modelo no pudo predecir. */
    val sinDiagnostico: Boolean = false,
    /** CU-05 E1 y E2: la atención ya se guardó sola sin diagnóstico y se muestra el aviso con "Aceptar". */
    val guardadaSinDiagnostico: Boolean = false,
    /** Error que detuvo la atención (no se pudo conectar, por ejemplo). */
    val error: String? = null,
    /** Aviso que no detiene la atención (falló una repetición o el diagnóstico). */
    val aviso: String? = null
) {
    /**
     * "Diagnosticar" se habilita con dictamen completa o parcial. Si el vehículo no expone
     * los parámetros del modelo, al pulsarlo la atención se guarda sola sin diagnóstico (CU-05 E1).
     */
    val puedeDiagnosticar: Boolean
        get() = fase == FaseCaptura.LISTA && dictamen?.permiteGuardar() == true

    val puedeRepetir: Boolean
        get() = fase == FaseCaptura.LISTA && dictamen?.resultado == ResultadoDictamen.RECHAZADA

    /** Salir ahora descartaría una atención en marcha o sin guardar (CU-08 E1). */
    val descartaAlSalir: Boolean
        get() = fase != FaseCaptura.ERROR && fase != FaseCaptura.GUARDADA && fase != FaseCaptura.PREPARANDO
}

sealed interface EventoCaptura {
    data object AbrirDiagnostico : EventoCaptura
    data class AbrirHistorial(val idVehiculo: Long, val aviso: String) : EventoCaptura
}

/**
 * Orquesta la atención (CU-02, CU-03, CU-04, CU-08): arma el adaptador, captura con un
 * servicio nuevo, valida, repite el régimen rechazado y prepara el diagnóstico (CU-05, CU-07).
 * [idGrabacion] no nulo indica el modo demostración.
 */
class CapturaViewModel(
    private val contenedor: AppContenedor,
    private val idVehiculo: Long,
    private val idGrabacion: String?,
    private val simularRechazo: Boolean
) : ViewModel() {

    private val _estado = MutableStateFlow(EstadoPantallaCaptura(modoDemostracion = idGrabacion != null))
    val estado: StateFlow<EstadoPantallaCaptura> = _estado.asStateFlow()

    private val canalEventos = Channel<EventoCaptura>(Channel.BUFFERED)
    val eventos: Flow<EventoCaptura> = canalEventos.receiveAsFlow()

    private var servicio: ServicioAdquisicion? = null
    private var adaptador: AdaptadorELM327? = null
    private var atencion: Atencion? = null
    private var familiaGuardada = false

    init {
        viewModelScope.launch { iniciar() }
    }

    private suspend fun iniciar() {
        intentar({ t -> fallar("No se pudo iniciar la atención", t) }) {
            val vehiculo = withContext(Dispatchers.IO) { contenedor.repositorioVehiculos.obtener(idVehiculo) }
                ?: error("No se encontró el vehículo.")
            _estado.update { it.copy(vehiculo = vehiculo) }

            val configuracion = if (idGrabacion != null) {
                val grabacion = withContext(Dispatchers.IO) {
                    contenedor.catalogoDemo.grabaciones().firstOrNull { it.id == idGrabacion }
                } ?: error("No se encontró la atención grabada $idGrabacion.")
                _estado.update { it.copy(grabacion = grabacion) }
                ConfiguracionAdaptador.Demostracion(grabacion, simularRechazo)
            } else {
                val mac = contenedor.preferencias.macAdaptador
                    ?: error("Elija primero el adaptador ELM327 en la pantalla Vehículos.")
                ConfiguracionAdaptador.Bluetooth(mac)
            }
            Registro.i(Registro.UI, "Captura de ${vehiculo.codigo}: $configuracion")

            val ad = contenedor.fabricaAdaptador(configuracion).also { adaptador = it }
            val serv = contenedor.fabricaServicio().also { servicio = it }
            _estado.update { it.copy(modoDemostracion = ad.esDemostracion, fase = FaseCaptura.CAPTURANDO) }
            viewModelScope.launch { observar(serv, vehiculo) }

            val capturada = withContext(Dispatchers.IO) { serv.capturarAtencion(vehiculo, ad) }
            Registro.i(Registro.UI, "Captura terminada: regímenes ${capturada.regimenes.keys}")
            guardarFamiliaSiFalta(vehiculo, serv.estado.value)
            atencion = capturada
            validar(capturada)
        }
    }

    /** Copia el estado del servicio en la pantalla y guarda la familia en la primera conexión. */
    private suspend fun observar(serv: ServicioAdquisicion, vehiculo: Vehiculo) {
        intentar({ t -> Registro.e(Registro.UI, "No se pudo observar el estado de la captura", t) }) {
            serv.estado.collect { e ->
                _estado.update { it.copy(captura = e) }
                guardarFamiliaSiFalta(vehiculo, e)
            }
        }
    }

    /** CU-02 paso 6: si el vehículo no tenía familia de gestión, se guarda la detectada. */
    private suspend fun guardarFamiliaSiFalta(vehiculo: Vehiculo, e: EstadoCaptura) {
        val familia = e.familiaGestion ?: return
        if (familiaGuardada || vehiculo.familiaGestion != null) return
        familiaGuardada = true
        intentar({ t -> Registro.e(Registro.UI, "No se pudo guardar la familia de gestión", t) }) {
            withContext(Dispatchers.IO) { contenedor.repositorioVehiculos.actualizarFamilia(vehiculo.id, familia) }
            _estado.update { it.copy(vehiculo = it.vehiculo?.copy(familiaGestion = familia)) }
            Registro.i(Registro.UI, "Familia de gestión de ${vehiculo.codigo}: ${familia.texto}")
        }
    }

    /** CU-04: aplica las reglas y muestra el dictamen. */
    private suspend fun validar(a: Atencion) {
        _estado.update { it.copy(fase = FaseCaptura.VALIDANDO, regimenEnRepeticion = null) }
        val dictamen = withContext(Dispatchers.Default) { contenedor.validarAtencion(a) }
        Registro.i(
            Registro.UI,
            "Dictamen ${dictamen.resultado.texto}; repetir ${dictamen.regimenesARepetir}; " +
                "faltan parámetros del modelo: ${dictamen.faltanParametrosModelo}"
        )
        _estado.update {
            it.copy(
                fase = FaseCaptura.LISTA,
                dictamen = dictamen,
                regimenesCapturados = a.regimenes.keys,
                sinDiagnostico = dictamen.faltanParametrosModelo
            )
        }
    }

    /** El mecánico confirma la instrucción en pantalla ("Continuar"). */
    fun confirmar() {
        intentar({ t -> Registro.e(Registro.UI, "No se pudo confirmar la instrucción", t) }) {
            Registro.d(Registro.UI, "Instrucción confirmada")
            servicio?.confirmar()
        }
    }

    /** CU-08: descarta el régimen rechazado, lo vuelve a capturar y valida otra vez. */
    fun repetirRegimen(tipo: TipoRegimen) {
        val serv = servicio ?: return
        val anterior = _estado.value
        if (!anterior.puedeRepetir) return
        Registro.i(Registro.UI, "Repetir $tipo")
        _estado.update { it.copy(fase = FaseCaptura.REPITIENDO, regimenEnRepeticion = tipo, dictamen = null, aviso = null) }
        viewModelScope.launch {
            intentar({ t ->
                Registro.e(Registro.UI, "Falló la repetición de $tipo", t)
                _estado.update {
                    it.copy(
                        fase = FaseCaptura.LISTA,
                        dictamen = anterior.dictamen,
                        regimenEnRepeticion = null,
                        aviso = "No se pudo repetir $tipo: ${t.mensajeLegible()}"
                    )
                }
            }) {
                val nueva = withContext(Dispatchers.IO) { serv.repetirRegimen(tipo) }
                atencion = nueva
                validar(nueva)
            }
        }
    }

    /** CU-07: prepara la fila (CU-05), ejecuta el modelo y abre Diagnóstico. */
    fun diagnosticar() {
        val e = _estado.value
        val a = atencion ?: return
        val d = e.dictamen ?: return
        val v = e.vehiculo ?: return
        if (!e.puedeDiagnosticar) return
        Registro.i(Registro.UI, "Diagnosticar ${v.codigo}")
        if (d.faltanParametrosModelo) {
            // CU-05 paso 1 y E1: el vehículo no expone los parámetros que requiere el modelo.
            guardarSinDiagnostico(a, d, v)
            return
        }
        _estado.update { it.copy(fase = FaseCaptura.DIAGNOSTICANDO, aviso = null) }
        viewModelScope.launch {
            intentar({ t ->
                Registro.e(Registro.UI, "No se pudo obtener el diagnóstico", t)
                _estado.update { it.copy(fase = FaseCaptura.LISTA, aviso = "No se pudo obtener el diagnóstico: ${t.mensajeLegible()}") }
            }) {
                val inicio = System.currentTimeMillis()
                // ProcesadorCaracteristicas().prepararFila + MotorInferencia(modelo).predecir
                val diagnostico = withContext(Dispatchers.Default) { contenedor.diagnosticarAtencion(a) }
                Registro.i(
                    Registro.UI,
                    "Inferencia en ${System.currentTimeMillis() - inicio} ms: ${diagnostico?.clase ?: "sin diagnóstico"}"
                )
                if (diagnostico == null) {
                    // A la fila le falta alguna característica: se trata como la regla 8 (CU-05 E1).
                    guardarSinDiagnostico(a, d, v)
                } else {
                    contenedor.sesion.iniciar(AtencionEnCurso(v, a, d, diagnostico))
                    _estado.update { it.copy(fase = FaseCaptura.LISTA) }
                    canalEventos.send(EventoCaptura.AbrirDiagnostico)
                }
            }
        }
    }

    /**
     * CU-05 E1: el sistema guarda la atención en el historial sin diagnóstico, por su cuenta.
     * Luego E2: la pantalla avisa que el vehículo no expone los parámetros que requiere el
     * modelo, con "Aceptar" ([aceptarSinDiagnostico]) que lleva al historial.
     */
    private fun guardarSinDiagnostico(a: Atencion, d: Dictamen, v: Vehiculo) {
        _estado.update { it.copy(fase = FaseCaptura.GUARDANDO, sinDiagnostico = true, aviso = null) }
        viewModelScope.launch {
            intentar({ t ->
                Registro.e(Registro.UI, "No se pudo guardar la atención sin diagnóstico", t)
                _estado.update { it.copy(fase = FaseCaptura.LISTA, aviso = "No se pudo guardar la atención: ${t.mensajeLegible()}") }
            }) {
                val id = withContext(Dispatchers.IO) { contenedor.guardarAtencion(a, d, null) }
                Registro.i(Registro.UI, "Atención $id de ${v.codigo} guardada sin diagnóstico (CU-05 E1)")
                contenedor.sesion.limpiar()
                _estado.update { it.copy(fase = FaseCaptura.GUARDADA, guardadaSinDiagnostico = true) }
            }
        }
    }

    /** CU-05 E2: el mecánico acepta el aviso y pasa al historial del vehículo. */
    fun aceptarSinDiagnostico() {
        val v = _estado.value.vehiculo ?: return
        if (!_estado.value.guardadaSinDiagnostico) return
        _estado.update { it.copy(guardadaSinDiagnostico = false) }
        viewModelScope.launch { canalEventos.send(EventoCaptura.AbrirHistorial(v.id, AVISO_SIN_DIAGNOSTICO)) }
    }

    private fun fallar(titulo: String, t: Throwable) {
        Registro.e(Registro.UI, titulo, t)
        _estado.update { it.copy(fase = FaseCaptura.ERROR, error = t.mensajeLegible()) }
    }

    /** Al salir de la pantalla se cierra el enlace con el adaptador. */
    override fun onCleared() {
        val serv = servicio
        val ad = adaptador
        Registro.i(Registro.UI, "Saliendo de Captura: se cierra el adaptador")
        contenedor.alcance.launch(Dispatchers.IO) {
            runCatching { serv?.finalizar() }.onFailure { Registro.w(Registro.UI, "finalizar() falló", it) }
            runCatching { ad?.cerrar() }.onFailure { Registro.w(Registro.UI, "cerrar() falló", it) }
        }
        super.onCleared()
    }

    companion object {
        const val AVISO_SIN_DIAGNOSTICO = "sin_diagnostico"
        const val AVISO_GUARDADA = "guardada"
    }
}
