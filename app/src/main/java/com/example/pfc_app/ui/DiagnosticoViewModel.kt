package com.example.pfc_app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.pfc_app.AppContenedor
import com.example.pfc_app.Registro
import com.example.pfc_app.modelos.CodigoDTC
import com.example.pfc_app.modelos.ContratoModelo
import com.example.pfc_app.modelos.Diagnostico
import com.example.pfc_app.modelos.Dictamen
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

/** Lo que muestra la pantalla Diagnóstico, venga de la atención en curso o de la base. */
data class VistaDiagnostico(
    val idVehiculo: Long,
    val codigoVehiculo: String,
    val diagnostico: Diagnostico,
    val dictamen: Dictamen,
    val codigos: List<CodigoDTC>
)

data class EstadoPantallaDiagnostico(
    val cargando: Boolean = true,
    val vista: VistaDiagnostico? = null,
    /** true si es la atención en curso (todavía sin guardar): se muestra "Guardar atención". */
    val enCurso: Boolean = false,
    val guardando: Boolean = false,
    /** La atención guardada no tiene diagnóstico (CU-06 E1 del paso 4). */
    val sinDiagnostico: Boolean = false,
    val codigoVehiculo: String = "",
    val error: String? = null
)

sealed interface EventoDiagnostico {
    data class AbrirHistorial(val idVehiculo: Long) : EventoDiagnostico
}

/**
 * Pantalla Diagnóstico (CU-07, RNF-02). Con [idAtencion] nulo muestra la atención en curso
 * de la [com.example.pfc_app.SesionAtencion]; con un id, la lee del historial.
 */
class DiagnosticoViewModel(private val contenedor: AppContenedor, private val idAtencion: Long?) : ViewModel() {
    private val _estado = MutableStateFlow(EstadoPantallaDiagnostico(enCurso = idAtencion == null))
    val estado: StateFlow<EstadoPantallaDiagnostico> = _estado.asStateFlow()

    private val canalEventos = Channel<EventoDiagnostico>(Channel.BUFFERED)
    val eventos: Flow<EventoDiagnostico> = canalEventos.receiveAsFlow()

    init {
        viewModelScope.launch { cargar() }
    }

    private suspend fun cargar() {
        intentar({ t ->
            Registro.e(Registro.UI, "No se pudo abrir el diagnóstico", t)
            _estado.update { it.copy(cargando = false, error = t.mensajeLegible()) }
        }) {
            if (idAtencion == null) {
                val actual = contenedor.sesion.actual
                if (actual?.diagnostico == null) {
                    _estado.update { it.copy(cargando = false, error = "No hay una atención diagnosticada en curso.") }
                    return
                }
                val contrato = withContext(Dispatchers.Default) { contratoOpcional() }
                val vista = VistaDiagnostico(
                    idVehiculo = actual.vehiculo.id,
                    codigoVehiculo = actual.vehiculo.codigo,
                    diagnostico = completarConContrato(actual.diagnostico, contrato),
                    dictamen = actual.dictamen,
                    codigos = actual.diagnostico.codigosLeidos.ifEmpty { actual.atencion.codigos }
                )
                _estado.update { it.copy(cargando = false, vista = vista, codigoVehiculo = vista.codigoVehiculo) }
            } else {
                val (guardada, vehiculo, contrato) = withContext(Dispatchers.IO) {
                    val g = contenedor.repositorioAtenciones.obtener(idAtencion)
                    val v = g?.let { contenedor.repositorioVehiculos.obtener(it.atencion.idVehiculo) }
                    Triple(g, v, if (g?.diagnostico != null) contratoOpcional() else null)
                }
                if (guardada == null) {
                    _estado.update { it.copy(cargando = false, error = "No se encontró la atención.") }
                    return
                }
                val codigo = vehiculo?.codigo.orEmpty()
                val diag = guardada.diagnostico
                if (diag == null) {
                    _estado.update { it.copy(cargando = false, sinDiagnostico = true, codigoVehiculo = codigo) }
                    return
                }
                val vista = VistaDiagnostico(
                    idVehiculo = guardada.atencion.idVehiculo,
                    codigoVehiculo = codigo,
                    diagnostico = completarConContrato(diag, contrato),
                    dictamen = guardada.dictamen,
                    codigos = guardada.atencion.codigos
                )
                _estado.update { it.copy(cargando = false, vista = vista, codigoVehiculo = codigo) }
            }
        }
    }

    /** El contrato del modelo, o null si no se pudo cargar (se muestran los códigos tal cual). */
    private fun contratoOpcional(): ContratoModelo? = runCatching { contenedor.contrato }
        .onFailure { Registro.w(Registro.UI, "Sin contrato del modelo: se muestran nombres internos", it) }
        .getOrNull()

    /** CU-07 paso 5: guarda la atención en una transacción y abre el historial. */
    fun guardar() {
        val e = _estado.value
        if (!e.enCurso || e.guardando) return
        val actual = contenedor.sesion.actual ?: return
        _estado.update { it.copy(guardando = true) }
        viewModelScope.launch {
            intentar({ t ->
                Registro.e(Registro.UI, "No se pudo guardar la atención", t)
                _estado.update { it.copy(guardando = false, error = "No se pudo guardar la atención: ${t.mensajeLegible()}") }
            }) {
                val id = withContext(Dispatchers.IO) {
                    contenedor.repositorioAtenciones.guardar(actual.atencion, actual.dictamen, actual.diagnostico)
                }
                Registro.i(Registro.UI, "Atención $id guardada con diagnóstico ${actual.diagnostico?.clase}")
                contenedor.sesion.limpiar()
                canalEventos.send(EventoDiagnostico.AbrirHistorial(actual.vehiculo.id))
            }
        }
    }

    fun cerrarError() = _estado.update { it.copy(error = null) }

    companion object {
        /**
         * Completa el nombre de la clase y la etiqueta y unidad de cada parámetro con el
         * contrato del modelo, buscándolos por nombre (la base guarda solo los nombres).
         */
        fun completarConContrato(d: Diagnostico, contrato: ContratoModelo?): Diagnostico {
            if (contrato == null) return d
            val nombre = if (d.nombreClase.isBlank() || d.nombreClase == d.clase) {
                contrato.clase(d.clase)?.nombre ?: d.clase
            } else {
                d.nombreClase
            }
            val parametros = d.parametrosDesviados.map { p ->
                val c = contrato.caracteristica(p.parametro) ?: return@map p
                p.copy(
                    etiqueta = if (p.etiqueta.isBlank() || p.etiqueta == p.parametro) c.etiqueta else p.etiqueta,
                    unidad = p.unidad.ifBlank { c.unidad }
                )
            }
            val provisional = d.modeloProvisional || (contrato.versionModelo == d.versionModelo && contrato.provisional)
            return d.copy(nombreClase = nombre, parametrosDesviados = parametros, modeloProvisional = provisional)
        }
    }
}
