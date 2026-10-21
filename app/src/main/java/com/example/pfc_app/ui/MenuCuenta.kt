package com.example.pfc_app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.pfc_app.cuenta.Cuenta

/** Menú "⋮" de la barra de Vehículos: la cuenta con sesión abierta y "Cerrar sesión". */
@Composable
fun MenuCuenta(cuenta: Cuenta?, alCerrarSesion: () -> Unit) {
    var abierto by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { abierto = true }, modifier = Modifier.testTag(EtiquetasPrueba.BOTON_MENU_CUENTA)) {
            Text("⋮", fontSize = 26.sp, color = MaterialTheme.colorScheme.onSurface)
        }
        DropdownMenu(expanded = abierto, onDismissRequest = { abierto = false }) {
            // El correo nunca se muestra completo.
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(cuenta?.nombre ?: "Cuenta de Google", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(cuenta?.correoOculto ?: "", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Cerrar sesión", fontSize = 16.sp) },
                onClick = {
                    abierto = false
                    alCerrarSesion()
                },
                modifier = Modifier.testTag(EtiquetasPrueba.MENU_CERRAR_SESION)
            )
        }
    }
}

/** Aviso antes de cerrar sesión cuando quedan atenciones de la cuenta sin respaldar. */
@Composable
fun DialogoCerrarSesion(pendientes: Int, alConfirmar: () -> Unit, alCancelar: () -> Unit) {
    val atenciones = if (pendientes == 1) "1 atención" else "$pendientes atenciones"
    AlertDialog(
        modifier = Modifier.testTag(EtiquetasPrueba.DIALOGO_CERRAR_SESION),
        onDismissRequest = alCancelar,
        title = { Text("Atenciones sin respaldar") },
        text = {
            Text(
                "Esta cuenta tiene $atenciones sin respaldar en la nube. Se quedan guardadas en el teléfono " +
                    "y se subirán cuando vuelva a iniciar sesión con esta cuenta y haya internet."
            )
        },
        confirmButton = {
            TextButton(onClick = alConfirmar, modifier = Modifier.testTag(EtiquetasPrueba.BOTON_CONFIRMAR_CERRAR_SESION)) {
                Text("Cerrar sesión")
            }
        },
        dismissButton = { TextButton(onClick = alCancelar) { Text("Cancelar") } }
    )
}
