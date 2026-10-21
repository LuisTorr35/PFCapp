package com.example.pfc_app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.pfc_app.ui.theme.AmarilloAviso

/**
 * Pantalla Historial (Figura 13, CU-06). [aviso] informa lo que acaba de pasar al llegar
 * desde Diagnóstico ("guardada") o desde Captura sin diagnóstico ("sin_diagnostico").
 */
@Composable
fun PantallaHistorial(vm: HistorialViewModel, aviso: String?, alVolver: () -> Unit, alAbrirAtencion: (idAtencion: Long) -> Unit) {
    val estado by vm.estado.collectAsStateWithLifecycle()

    // Se relee cada vez que la pantalla aparece.
    LaunchedEffect(Unit) { vm.recargar() }

    Column(Modifier.fillMaxSize()) {
        BarraSuperior("Historial · ${estado.vehiculo?.codigo.orEmpty()}", alVolver = alVolver)
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag(EtiquetasPrueba.LISTA_ATENCIONES),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val textoAviso = when (aviso) {
                CapturaViewModel.AVISO_GUARDADA -> "Atención guardada en el historial."

                CapturaViewModel.AVISO_SIN_DIAGNOSTICO ->
                    "La atención quedó en el historial sin diagnóstico: el vehículo no expone los parámetros que requiere el modelo."

                else -> null
            }
            if (textoAviso != null) {
                item(key = "aviso") {
                    Tarjeta(Modifier.testTag(EtiquetasPrueba.AVISO_HISTORIAL), fondo = AmarilloAviso.copy(alpha = 0.35f)) {
                        Text(textoAviso, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            estado.vehiculo?.let { v ->
                item(key = "vehiculo") {
                    Tarjeta(Modifier.testTag(EtiquetasPrueba.TARJETA_VEHICULO)) {
                        TituloTarjeta("${v.marca} ${v.modelo} ${v.anio}")
                        TextoTarjeta("${Formatos.cilindros(v.cilindros)} · ${Formatos.familiaLarga(v.familiaGestion)}")
                    }
                }
            }
            estado.error?.let { e ->
                item(key = "error") { TarjetaError("No se pudo leer el historial", e) }
            }
            item(key = "titulo") { TituloSeccion("Atenciones") }
            if (!estado.cargando && estado.filas.isEmpty() && estado.error == null) {
                item(key = "vacio") {
                    TextoSecundario(
                        "Este vehículo todavía no tiene atenciones guardadas.",
                        Modifier.testTag(EtiquetasPrueba.HISTORIAL_VACIO)
                    )
                }
            }
            items(estado.filas, key = { it.idAtencion }) { f ->
                Tarjeta(
                    modifier = Modifier.testTag(EtiquetasPrueba.FILA_ATENCION),
                    alPulsar = {
                        if (f.tieneDiagnostico) alAbrirAtencion(f.idAtencion) else vm.avisarSinDiagnostico(true)
                    }
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(f.fecha, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                            TextoTarjeta(f.resultado)
                            Text(
                                if (f.respaldada) "✓ Respaldada" else "Pendiente de respaldo",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.testTag(EtiquetasPrueba.MARCA_RESPALDO)
                            )
                        }
                        Text("›", fontSize = 28.sp)
                    }
                }
            }
        }
    }

    if (estado.avisoSinDiagnostico) {
        AlertDialog(
            modifier = Modifier.testTag(EtiquetasPrueba.DIALOGO_SIN_DIAGNOSTICO),
            onDismissRequest = { vm.avisarSinDiagnostico(false) },
            title = { Text("Atención sin diagnóstico") },
            text = { Text("Esta atención no tiene diagnóstico porque el vehículo no expone los parámetros que requiere el modelo.") },
            confirmButton = { TextButton(onClick = { vm.avisarSinDiagnostico(false) }) { Text("Aceptar") } }
        )
    }
}
