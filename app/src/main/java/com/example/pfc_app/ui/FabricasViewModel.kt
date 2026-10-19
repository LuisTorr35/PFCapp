package com.example.pfc_app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.pfc_app.AppContenedor
import com.example.pfc_app.PfcApp
import kotlinx.coroutines.CancellationException

/** El contenedor de dependencias de la aplicación. */
@Composable
fun contenedorApp(): AppContenedor = (LocalContext.current.applicationContext as PfcApp).contenedor

/** Fábrica de un ViewModel que recibe sus dependencias por constructor. */
inline fun <reified VM : ViewModel> fabricaDe(crossinline crear: () -> VM): ViewModelProvider.Factory =
    viewModelFactory { initializer { crear() } }

/** Mensaje legible de un error, sin el nombre de la clase de la excepción. */
fun Throwable.mensajeLegible(): String = when (this) {
    is NotImplementedError -> "Función todavía no disponible en esta versión (${message ?: "sin detalle"})."
    else -> message?.takeIf { it.isNotBlank() } ?: (this::class.simpleName ?: "Error desconocido")
}

/** Relanza la cancelación de corrutinas; cualquier otro error se entrega a [alFallar]. */
inline fun <T> intentar(alFallar: (Throwable) -> T, bloque: () -> T): T = try {
    bloque()
} catch (c: CancellationException) {
    throw c
} catch (t: Throwable) {
    alFallar(t)
}
