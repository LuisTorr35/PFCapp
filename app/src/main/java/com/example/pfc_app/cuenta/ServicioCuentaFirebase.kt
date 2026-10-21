package com.example.pfc_app.cuenta

import android.app.Activity
import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.ClearCredentialException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.example.pfc_app.R
import com.example.pfc_app.Registro
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await

/**
 * Inicio de sesión con Google por Credential Manager y Firebase Authentication. Firebase guarda
 * la sesión en el teléfono, así que [actual] está disponible al abrir la app sin internet.
 * El id de cliente web (default_web_client_id) lo genera el plugin google-services.
 */
class ServicioCuentaFirebase(private val contexto: Context) : ServicioCuenta {
    private val auth = FirebaseAuth.getInstance()
    private val _actual = MutableStateFlow(auth.currentUser?.aCuenta())
    override val actual: StateFlow<Cuenta?> = _actual.asStateFlow()

    init {
        _actual.value?.let { Registro.i(Registro.NUBE, "Sesión guardada: ${it.correoOculto} (uid ${it.uid})") }
        auth.addAuthStateListener { a -> _actual.value = a.currentUser?.aCuenta() }
    }

    override suspend fun iniciarConGoogle(actividad: Activity): ResultadoInicioSesion {
        if (!Conectividad.hayInternet(contexto)) {
            Registro.w(Registro.NUBE, "Inicio de sesión sin internet")
            return ResultadoInicioSesion.SinConexion
        }
        val opcion = GetSignInWithGoogleOption.Builder(contexto.getString(R.string.default_web_client_id)).build()
        val solicitud = GetCredentialRequest.Builder().addCredentialOption(opcion).build()
        return try {
            val credencial = CredentialManager.create(actividad).getCredential(actividad, solicitud).credential
            if (credencial !is CustomCredential || credencial.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                Registro.e(Registro.NUBE, "Credencial inesperada: ${credencial.type}")
                return ResultadoInicioSesion.Error("Google devolvió una credencial que la app no reconoce.")
            }
            val token = GoogleIdTokenCredential.createFrom(credencial.data).idToken
            val usuario = auth.signInWithCredential(GoogleAuthProvider.getCredential(token, null)).await().user
                ?: return ResultadoInicioSesion.Error("Firebase no devolvió la cuenta.")
            val cuenta = usuario.aCuenta()
            _actual.value = cuenta
            Registro.i(Registro.NUBE, "Sesión iniciada: ${cuenta.correoOculto} (uid ${cuenta.uid})")
            ResultadoInicioSesion.Exito(cuenta)
        } catch (e: CancellationException) {
            throw e
        } catch (e: GetCredentialCancellationException) {
            Registro.i(Registro.NUBE, "El mecánico cerró la ventana de cuentas")
            ResultadoInicioSesion.Cancelado
        } catch (e: NoCredentialException) {
            Registro.w(Registro.NUBE, "No hay cuentas de Google disponibles", e)
            ResultadoInicioSesion.Error("No hay cuentas de Google en el teléfono. Agregue una en Ajustes › Cuentas.")
        } catch (e: GetCredentialException) {
            fallo(e, "No se pudo abrir la ventana de cuentas de Google")
        } catch (e: FirebaseNetworkException) {
            Registro.w(Registro.NUBE, "Sin red al validar la cuenta en Firebase", e)
            ResultadoInicioSesion.SinConexion
        } catch (e: FirebaseAuthException) {
            fallo(e, "Firebase rechazó la cuenta")
        } catch (e: GoogleIdTokenParsingException) {
            fallo(e, "La respuesta de Google no se pudo leer")
        }
    }

    /** Un error con red caída se informa como falta de conexión, que es lo que el mecánico puede resolver. */
    private fun fallo(e: Exception, titulo: String): ResultadoInicioSesion {
        if (!Conectividad.hayInternet(contexto)) {
            Registro.w(Registro.NUBE, "$titulo: sin internet", e)
            return ResultadoInicioSesion.SinConexion
        }
        Registro.e(Registro.NUBE, titulo, e)
        return ResultadoInicioSesion.Error("$titulo: ${e.message ?: e.javaClass.simpleName}")
    }

    override suspend fun cerrarSesion() {
        val antes = _actual.value
        auth.signOut()
        _actual.value = null
        // Sin esto, el siguiente inicio podría reutilizar la cuenta anterior sin preguntar.
        try {
            CredentialManager.create(contexto).clearCredentialState(ClearCredentialStateRequest())
        } catch (e: ClearCredentialException) {
            Registro.w(Registro.NUBE, "No se pudo limpiar el estado de Credential Manager", e)
        }
        Registro.i(Registro.NUBE, "Sesión cerrada: ${antes?.correoOculto} (uid ${antes?.uid})")
    }

    private fun FirebaseUser.aCuenta() = Cuenta(uid = uid, correo = email, nombre = displayName)
}
