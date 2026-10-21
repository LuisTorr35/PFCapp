package com.example.pfc_app.cuenta

import android.app.Activity
import kotlinx.coroutines.flow.StateFlow

/** La cuenta de Google con sesión abierta. [uid] es el de Firebase Authentication. */
data class Cuenta(val uid: String, val correo: String?, val nombre: String?) {
    /** El correo sin mostrarlo completo, para el menú, los registros y las capturas. */
    val correoOculto: String get() = ocultarCorreo(correo)

    companion object {
        /** "luis.torres@gmail.com" → "lu•••@gmail.com". */
        fun ocultarCorreo(correo: String?): String {
            if (correo.isNullOrBlank()) return "sin correo"
            val arroba = correo.indexOf('@')
            if (arroba <= 0) return "•••"
            return correo.take(minOf(2, arroba)) + "•••" + correo.substring(arroba)
        }
    }
}

/** Cómo terminó el intento de iniciar sesión. */
sealed interface ResultadoInicioSesion {
    data class Exito(val cuenta: Cuenta) : ResultadoInicioSesion

    /** El mecánico cerró la ventana de cuentas sin elegir una. */
    data object Cancelado : ResultadoInicioSesion

    /** El primer inicio de sesión necesita internet. */
    data object SinConexion : ResultadoInicioSesion

    data class Error(val mensaje: String) : ResultadoInicioSesion
}

/**
 * Sesión de la aplicación. La sesión queda guardada en el teléfono: [actual] se conoce al
 * abrir la app aunque no haya internet, y solo el primer inicio de sesión necesita red.
 */
interface ServicioCuenta {
    /** La cuenta con sesión abierta, o null si hay que iniciar sesión. */
    val actual: StateFlow<Cuenta?>

    /** Abre la ventana de cuentas de Google del teléfono; [actividad] es la que la muestra. */
    suspend fun iniciarConGoogle(actividad: Activity): ResultadoInicioSesion

    suspend fun cerrarSesion()
}
