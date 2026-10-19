package com.example.pfc_app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import com.example.pfc_app.modelos.ResultadoDictamen
import com.example.pfc_app.ui.theme.AmarilloAviso
import com.example.pfc_app.ui.theme.Negro

/**
 * Pantalla Diagnóstico (Figura 12, CU-07, RNF-02): sistema afectado más probable con su
 * probabilidad, parámetros desviados frente a la referencia sin falla y códigos como contexto.
 */
@Composable
fun PantallaDiagnostico(vm: DiagnosticoViewModel, alVolver: () -> Unit, alAbrirHistorial: (idVehiculo: Long) -> Unit) {
    val estado by vm.estado.collectAsStateWithLifecycle()

    LaunchedEffect(vm) {
        vm.eventos.collect { e ->
            when (e) {
                is EventoDiagnostico.AbrirHistorial -> alAbrirHistorial(e.idVehiculo)
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        BarraSuperior("Diagnóstico · ${estado.codigoVehiculo}", alVolver = alVolver)
        val vista = estado.vista
        when {
            estado.cargando -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.onSurface)
            }

            estado.sinDiagnostico -> Column(Modifier.weight(1f).padding(16.dp)) {
                Tarjeta {
                    TituloTarjeta("Atención sin diagnóstico")
                    TextoTarjeta("Esta atención no tiene diagnóstico porque el vehículo no expone los parámetros que requiere el modelo.")
                }
            }

            vista == null -> Column(Modifier.weight(1f).padding(16.dp)) {
                TarjetaError("No se puede mostrar el diagnóstico", estado.error ?: "Sin datos.", alVolver = alVolver)
            }

            else -> ContenidoDiagnostico(vista, Modifier.weight(1f))
        }
        if (estado.enCurso && vista != null) {
            Column(Modifier.padding(16.dp)) {
                BotonPrincipal(
                    if (estado.guardando) "Guardando…" else "Guardar atención",
                    vm::guardar,
                    Modifier.testTag(EtiquetasPrueba.BOTON_GUARDAR_ATENCION),
                    habilitado = !estado.guardando
                )
            }
        }
    }

    // Error al guardar: la atención sigue en pantalla para reintentar.
    if (estado.vista != null && estado.error != null) {
        AlertDialog(
            onDismissRequest = vm::cerrarError,
            title = { Text("Aviso") },
            text = { Text(estado.error.orEmpty()) },
            confirmButton = { TextButton(onClick = vm::cerrarError) { Text("Aceptar") } }
        )
    }
}

@Composable
private fun ContenidoDiagnostico(vista: VistaDiagnostico, modifier: Modifier = Modifier) {
    val d = vista.diagnostico
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Sin fusionar: el nombre de la clase y la probabilidad conservan sus etiquetas de prueba.
        Tarjeta(fusionar = false) {
            TextoTarjeta("Sistema afectado más probable")
            Text(
                d.nombreClase,
                modifier = Modifier.testTag(EtiquetasPrueba.NOMBRE_CLASE),
                fontSize = 26.sp,
                lineHeight = 32.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Clase ${d.clase} · probabilidad ${Formatos.porcentaje(d.probabilidad)}",
                modifier = Modifier.testTag(EtiquetasPrueba.TEXTO_PROBABILIDAD),
                fontSize = 17.sp
            )
            Spacer(Modifier.height(4.dp))
            BarraProbabilidad(d.probabilidad)
        }

        TituloSeccion("Parámetros desviados")
        if (d.parametrosDesviados.isEmpty()) {
            TextoTarjeta("Ningún parámetro fuera del rango sin falla")
        } else {
            d.parametrosDesviados.forEach { p ->
                Tarjeta(Modifier.testTag(EtiquetasPrueba.PARAMETRO_DESVIADO)) {
                    Text(p.etiqueta, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    TextoTarjeta(Formatos.lineaParametro(p))
                }
            }
        }

        TituloSeccion("Códigos leídos")
        TextoTarjeta(Formatos.codigosDiagnostico(vista.codigos), Modifier.testTag(EtiquetasPrueba.TEXTO_CODIGOS))

        Column(
            Modifier.padding(top = 6.dp).testTag(EtiquetasPrueba.PIE_DIAGNOSTICO),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            TextoSecundario("Atención ${vista.dictamen.resultado.texto} · modelo ${d.versionModelo}")
            if (vista.dictamen.resultado == ResultadoDictamen.PARCIAL && vista.dictamen.observacion.isNotBlank()) {
                TextoSecundario(vista.dictamen.observacion)
            }
            if (d.modeloProvisional) {
                Surface(
                    shape = FormaBoton,
                    color = AmarilloAviso,
                    contentColor = Negro,
                    border = BorderStroke(1.dp, Negro)
                ) {
                    Text(
                        "Modelo provisional",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
            }
            TextoSecundario("El diagnóstico es una hipótesis de apoyo; la decisión de reparar es del mecánico.")
        }
    }
}
