package com.example.pfc_app.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.pfc_app.Registro

/** Rutas de las cuatro pantallas (figura de navegación del Capítulo 6). */
object Rutas {
    const val VEHICULOS = "vehiculos"
    const val CAPTURA = "captura/{idVehiculo}?grabacion={grabacion}&rechazo={rechazo}"
    const val DIAGNOSTICO = "diagnostico?idAtencion={idAtencion}"
    const val HISTORIAL = "historial/{idVehiculo}?aviso={aviso}"

    fun captura(idVehiculo: Long, idGrabacion: String?, simularRechazo: Boolean): String =
        "captura/$idVehiculo?rechazo=$simularRechazo" + (idGrabacion?.let { "&grabacion=${Uri.encode(it)}" } ?: "")

    /** Diagnóstico de la atención en curso (todavía sin guardar). */
    fun diagnosticoEnCurso(): String = "diagnostico"

    fun diagnosticoGuardado(idAtencion: Long): String = "diagnostico?idAtencion=$idAtencion"

    fun historial(idVehiculo: Long, aviso: String? = null): String =
        "historial/$idVehiculo" + (aviso?.let { "?aviso=${Uri.encode(it)}" } ?: "")
}

/**
 * Navegación: Vehículos → Captura ("Atender") → Diagnóstico ("Diagnosticar") → Historial
 * ("Guardar atención") → Diagnóstico ("Abrir atención"); Vehículos → Historial.
 * Al guardar, la pila queda Vehículos → Historial, así que volver lleva a Vehículos.
 */
@Composable
fun NavegacionPfc(navController: NavHostController = rememberNavController()) {
    val contenedor = contenedorApp()

    fun volver() {
        if (!navController.popBackStack()) Registro.d(Registro.UI, "No hay pantalla anterior")
    }

    /** Abre el historial después de guardar, dejando solo Vehículos debajo. */
    fun historialTrasGuardar(idVehiculo: Long, aviso: String) {
        Registro.i(Registro.UI, "→ Historial de $idVehiculo ($aviso)")
        navController.navigate(Rutas.historial(idVehiculo, aviso)) {
            popUpTo(Rutas.VEHICULOS) { inclusive = false }
            launchSingleTop = true
        }
    }

    NavHost(navController = navController, startDestination = Rutas.VEHICULOS) {
        composable(Rutas.VEHICULOS) {
            val vm: VehiculoViewModel = viewModel(factory = fabricaDe { VehiculoViewModel(contenedor) })
            PantallaVehiculo(
                vm = vm,
                alAtender = { v, grabacion, rechazo ->
                    Registro.i(Registro.UI, "→ Captura de ${v.codigo} (grabación $grabacion, rechazo $rechazo)")
                    navController.navigate(Rutas.captura(v.id, grabacion, rechazo)) { launchSingleTop = true }
                },
                alAbrirHistorial = { v ->
                    Registro.i(Registro.UI, "→ Historial de ${v.codigo}")
                    navController.navigate(Rutas.historial(v.id)) { launchSingleTop = true }
                },
            )
        }

        composable(
            route = Rutas.CAPTURA,
            arguments = listOf(
                navArgument("idVehiculo") { type = NavType.LongType },
                navArgument("grabacion") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("rechazo") { type = NavType.BoolType; defaultValue = false },
            ),
        ) { entrada ->
            val args = entrada.arguments
            val idVehiculo = args?.getLong("idVehiculo") ?: 0L
            val grabacion = args?.getString("grabacion")?.takeIf { it.isNotBlank() }
            val rechazo = args?.getBoolean("rechazo") ?: false
            val vm: CapturaViewModel = viewModel(
                factory = fabricaDe { CapturaViewModel(contenedor, idVehiculo, grabacion, rechazo) },
            )
            PantallaCaptura(
                vm = vm,
                alVolver = ::volver,
                alAbrirDiagnostico = {
                    Registro.i(Registro.UI, "→ Diagnóstico de la atención en curso")
                    navController.navigate(Rutas.diagnosticoEnCurso()) { launchSingleTop = true }
                },
                alAbrirHistorial = ::historialTrasGuardar,
            )
        }

        composable(
            route = Rutas.DIAGNOSTICO,
            arguments = listOf(navArgument("idAtencion") { type = NavType.LongType; defaultValue = -1L }),
        ) { entrada ->
            val id = entrada.arguments?.getLong("idAtencion")?.takeIf { it > 0 }
            val vm: DiagnosticoViewModel = viewModel(factory = fabricaDe { DiagnosticoViewModel(contenedor, id) })
            PantallaDiagnostico(
                vm = vm,
                alVolver = ::volver,
                alAbrirHistorial = { idVehiculo -> historialTrasGuardar(idVehiculo, CapturaViewModel.AVISO_GUARDADA) },
            )
        }

        composable(
            route = Rutas.HISTORIAL,
            arguments = listOf(
                navArgument("idVehiculo") { type = NavType.LongType },
                navArgument("aviso") { type = NavType.StringType; nullable = true; defaultValue = null },
            ),
        ) { entrada ->
            val idVehiculo = entrada.arguments?.getLong("idVehiculo") ?: 0L
            val aviso = entrada.arguments?.getString("aviso")
            val vm: HistorialViewModel = viewModel(factory = fabricaDe { HistorialViewModel(contenedor, idVehiculo) })
            PantallaHistorial(
                vm = vm,
                aviso = aviso,
                alVolver = ::volver,
                alAbrirAtencion = { idAtencion ->
                    Registro.i(Registro.UI, "→ Diagnóstico de la atención guardada $idAtencion")
                    navController.navigate(Rutas.diagnosticoGuardado(idAtencion)) { launchSingleTop = true }
                },
            )
        }
    }
}
