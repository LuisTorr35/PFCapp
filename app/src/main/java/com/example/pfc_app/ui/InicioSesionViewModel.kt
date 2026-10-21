package com.example.pfc_app.ui

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.pfc_app.AppContenedor
import com.example.pfc_app.Registro
import com.example.pfc_app.cuenta.ResultadoInicioSesion
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class EstadoInicioSesion(
    val iniciando: Boolean = false,
    /** Sin internet en el primer inicio de sesión. */
    val sinConexion: Boolean = false,
    val error: String? = null
)

/** Pantalla de inicio de sesión: solo aparece si no hay sesión guardada. */
class InicioSesionViewModel(private val contenedor: AppContenedor) : ViewModel() {
    private val _estado = MutableStateFlow(EstadoInicioSesion())
    val estado: StateFlow<EstadoInicioSesion> = _estado.asStateFlow()

    /** "Continuar con Google". Al iniciar sesión, la navegación pasa sola a Vehículos. */
    fun continuarConGoogle(actividad: Activity) {
        if (_estado.value.iniciando) return
        _estado.update { EstadoInicioSesion(iniciando = true) }
        viewModelScope.launch {
            intentar({ t ->
                Registro.e(Registro.NUBE, "Falló el inicio de sesión", t)
                _estado.update { EstadoInicioSesion(error = "No se pudo iniciar sesión: ${t.mensajeLegible()}") }
            }) {
                val r = contenedor.cuenta.iniciarConGoogle(actividad)
                _estado.update {
                    when (r) {
                        is ResultadoInicioSesion.Exito -> EstadoInicioSesion()
                        ResultadoInicioSesion.Cancelado -> EstadoInicioSesion()
                        ResultadoInicioSesion.SinConexion -> EstadoInicioSesion(sinConexion = true)
                        is ResultadoInicioSesion.Error -> EstadoInicioSesion(error = r.mensaje)
                    }
                }
                if (r is ResultadoInicioSesion.Exito) contenedor.alIniciarSesion()
            }
        }
    }
}
