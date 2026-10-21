package com.example.pfc_app.ui

import android.app.Activity
import com.example.pfc_app.cuenta.Cuenta
import com.example.pfc_app.cuenta.ResultadoInicioSesion
import com.example.pfc_app.cuenta.ServicioCuenta
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Sesión falsa para las pruebas de interfaz: no abre la ventana de cuentas de Google ni
 * usa Firebase. [resultado] es lo que devuelve "Continuar con Google".
 */
class CuentaFalsa(inicial: Cuenta? = CUENTA_A, var resultado: ResultadoInicioSesion = ResultadoInicioSesion.Exito(CUENTA_A)) :
    ServicioCuenta {
    private val _actual = MutableStateFlow(inicial)
    override val actual: StateFlow<Cuenta?> = _actual.asStateFlow()

    var inicios = 0
        private set
    var cierres = 0
        private set

    override suspend fun iniciarConGoogle(actividad: Activity): ResultadoInicioSesion {
        inicios++
        (resultado as? ResultadoInicioSesion.Exito)?.let { _actual.value = it.cuenta }
        return resultado
    }

    override suspend fun cerrarSesion() {
        cierres++
        _actual.value = null
    }

    companion object {
        val CUENTA_A = Cuenta("uid-prueba-a", "mecanico.a@example.com", "Mecánico A")
        val CUENTA_B = Cuenta("uid-prueba-b", "mecanico.b@example.com", "Mecánico B")
    }
}
