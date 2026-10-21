package com.example.pfc_app.ui

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.pfc_app.ui.theme.AmarilloAviso

/**
 * Inicio de sesión con Google. Solo aparece si no hay sesión guardada; después la app abre
 * directo en Vehículos, también sin internet.
 */
@Composable
fun PantallaInicioSesion(vm: InicioSesionViewModel) {
    val estado by vm.estado.collectAsStateWithLifecycle()
    val actividad = LocalContext.current as? Activity

    Column(Modifier.fillMaxSize()) {
        BarraSuperior("Iniciar sesión")
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("PFC-app", fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 24.dp))
            TextoSecundario("Diagnóstico asistido de fallas de motor con OBD-II")
            Tarjeta {
                TextoTarjeta(
                    "Inicie sesión con su cuenta de Google para respaldar las atenciones en la nube. " +
                        "La captura, el diagnóstico y el historial funcionan sin internet; " +
                        "las atenciones se suben solas cuando hay conexión."
                )
            }
            BotonPrincipal(
                texto = if (estado.iniciando) "Conectando con Google…" else "Continuar con Google",
                alPulsar = { actividad?.let(vm::continuarConGoogle) },
                modifier = Modifier.fillMaxWidth().testTag(EtiquetasPrueba.BOTON_CONTINUAR_GOOGLE),
                habilitado = !estado.iniciando && actividad != null
            )
            if (estado.sinConexion) {
                Tarjeta(Modifier.testTag(EtiquetasPrueba.AVISO_SIN_CONEXION), fondo = AmarilloAviso.copy(alpha = 0.35f)) {
                    TituloTarjeta("Sin conexión a internet")
                    TextoTarjeta(
                        "El primer inicio de sesión necesita internet. Conecte el teléfono a una red Wi-Fi " +
                            "o a los datos móviles y vuelva a intentarlo. Después, la app funciona sin conexión."
                    )
                }
            }
            estado.error?.let { TarjetaError("No se pudo iniciar sesión", it) }
        }
    }
}
