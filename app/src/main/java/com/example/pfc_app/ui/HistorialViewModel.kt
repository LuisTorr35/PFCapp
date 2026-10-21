package com.example.pfc_app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.pfc_app.AppContenedor
import com.example.pfc_app.Registro
import com.example.pfc_app.datos.ResumenAtencion
import com.example.pfc_app.modelos.Vehiculo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Una fila del historial lista para mostrar. */
data class FilaHistorial(
    val idAtencion: Long,
    /** "28/09/2026 · 11:20" */
    val fecha: String,
    /** "Completa · Encendido y combustión, 72 %" o "Parcial · Sin diagnóstico" */
    val resultado: String,
    val tieneDiagnostico: Boolean,
    /** Marca discreta: respaldada en la nube o pendiente. */
    val respaldada: Boolean = false
)

data class EstadoPantallaHistorial(
    val cargando: Boolean = true,
    val vehiculo: Vehiculo? = null,
    val filas: List<FilaHistorial> = emptyList(),
    /** Atención elegida que no tiene diagnóstico (CU-06 E1 del paso 4). */
    val avisoSinDiagnostico: Boolean = false,
    val error: String? = null
)

/** Pantalla Historial (CU-06): datos del vehículo y sus atenciones, de la más reciente a la más antigua. */
class HistorialViewModel(private val contenedor: AppContenedor, private val idVehiculo: Long) : ViewModel() {
    private val _estado = MutableStateFlow(EstadoPantallaHistorial())
    val estado: StateFlow<EstadoPantallaHistorial> = _estado.asStateFlow()

    init {
        // La marca de respaldo cambia sola cuando el trabajo de respaldo sube una atención.
        viewModelScope.launch {
            intentar({ t -> Registro.w(Registro.UI, "No se pudo seguir el estado de respaldo", t) }) {
                contenedor.repositorioAtenciones.observarRespaldo(idVehiculo).collect { respaldo ->
                    _estado.update { e -> e.copy(filas = e.filas.map { f -> f.copy(respaldada = respaldo[f.idAtencion] ?: f.respaldada) }) }
                }
            }
        }
    }

    /** Se llama cada vez que la pantalla aparece. */
    fun recargar() {
        viewModelScope.launch {
            intentar({ t ->
                Registro.e(Registro.UI, "No se pudo leer el historial", t)
                _estado.update { it.copy(cargando = false, error = t.mensajeLegible()) }
            }) {
                val (vehiculo, filas) = withContext(Dispatchers.IO) {
                    val v = contenedor.repositorioVehiculos.obtener(idVehiculo)
                    val resumenes = contenedor.repositorioAtenciones.listarPorVehiculo(idVehiculo)
                    // El nombre de cada clase viene del contrato del modelo; sin contrato, el código.
                    val contrato = if (resumenes.any { it.tieneDiagnostico }) {
                        runCatching { contenedor.contrato }.getOrNull()
                    } else {
                        null
                    }
                    v to resumenes.map { r -> aFila(r) { codigo -> contrato?.clase(codigo)?.nombre ?: codigo } }
                }
                Registro.d(Registro.UI, "Historial de ${vehiculo?.codigo}: ${filas.size} atenciones")
                _estado.update { it.copy(cargando = false, vehiculo = vehiculo, filas = filas) }
            }
        }
    }

    fun avisarSinDiagnostico(mostrar: Boolean) = _estado.update { it.copy(avisoSinDiagnostico = mostrar) }

    companion object {
        fun aFila(r: ResumenAtencion, nombreClase: (String) -> String): FilaHistorial {
            val resultado = Formatos.resultadoTitulo(r.resultado.texto)
            val detalle = if (r.clase != null && r.probabilidad != null) {
                "${nombreClase(r.clase)}, ${Formatos.porcentaje(r.probabilidad)}"
            } else {
                "Sin diagnóstico"
            }
            return FilaHistorial(r.idAtencion, Formatos.fechaHora(r.fecha), "$resultado · $detalle", r.tieneDiagnostico, r.respaldada)
        }
    }
}
