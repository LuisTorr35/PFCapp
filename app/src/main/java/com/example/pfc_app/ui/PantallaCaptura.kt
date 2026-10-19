package com.example.pfc_app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.pfc_app.adquisicion.EstadoEnlace
import com.example.pfc_app.adquisicion.EstadoRegimen
import com.example.pfc_app.adquisicion.ProgresoRegimen
import com.example.pfc_app.modelos.Dictamen
import com.example.pfc_app.modelos.EfectoRegla
import com.example.pfc_app.modelos.ResultadoDictamen
import com.example.pfc_app.modelos.TipoRegimen
import com.example.pfc_app.ui.theme.AmarilloAviso
import com.example.pfc_app.ui.theme.Negro

/**
 * Pantalla de Captura (Figuras 10 y 11): adaptador, lectura inicial, regímenes con su
 * avance, instrucción para el mecánico, dictamen y "Diagnosticar".
 */
@Composable
fun PantallaCaptura(
    vm: CapturaViewModel,
    alVolver: () -> Unit,
    alAbrirDiagnostico: () -> Unit,
    alAbrirHistorial: (idVehiculo: Long, aviso: String) -> Unit
) {
    val estado by vm.estado.collectAsStateWithLifecycle()
    var confirmarSalida by remember { mutableStateOf(false) }

    LaunchedEffect(vm) {
        vm.eventos.collect { evento ->
            when (evento) {
                EventoCaptura.AbrirDiagnostico -> alAbrirDiagnostico()
                is EventoCaptura.AbrirHistorial -> alAbrirHistorial(evento.idVehiculo, evento.aviso)
            }
        }
    }

    val salir = { if (estado.descartaAlSalir) confirmarSalida = true else alVolver() }
    BackHandler(onBack = salir)

    Column(Modifier.fillMaxSize()) {
        BarraSuperior("Captura · ${estado.vehiculo?.codigo.orEmpty()}", alVolver = salir)
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (estado.modoDemostracion) {
                val g = estado.grabacion
                AvisoDemostracion(
                    idGrabacion = g?.id,
                    detalle = g?.let { "Vehículo grabado: ${it.marca} ${it.modelo} ${it.anio}, ${Formatos.cilindros(it.cilindros)}" }
                )
            }
            val error = estado.error ?: estado.captura.error
            if (error != null) {
                TarjetaError("No se pudo completar la captura", error, alVolver = alVolver)
            }
            TarjetaAdaptador(estado)
            TarjetaLecturaInicial(estado)
            estado.captura.mensaje?.let { TarjetaMensaje(it) }
            if (estado.captura.enlace == EstadoEnlace.RECONECTANDO && estado.captura.mensaje == null) {
                TarjetaMensaje("Enlace caído, reconectando con el adaptador… Los regímenes capturados se conservan.")
            }
            TituloSeccion("Regímenes")
            TipoRegimen.entries.forEach { tipo -> TarjetaRegimen(tipo, estado, vm::repetirRegimen) }
            TarjetaDictamen(estado)
            estado.aviso?.let { TarjetaMensaje(it) }
        }
        PieCaptura(estado, vm)
    }

    if (estado.guardadaSinDiagnostico) {
        // CU-05 E2: la atención ya quedó en el historial; solo se informa y se pasa a él.
        AlertDialog(
            onDismissRequest = {},
            modifier = Modifier.testTag(EtiquetasPrueba.DIALOGO_GUARDADA_SIN_DIAGNOSTICO),
            title = { Text("Atención guardada sin diagnóstico") },
            text = {
                Text(
                    "El vehículo no expone los parámetros que requiere el modelo. " +
                        "La atención quedó guardada en el historial del vehículo sin diagnóstico."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = vm::aceptarSinDiagnostico,
                    modifier = Modifier.testTag(EtiquetasPrueba.BOTON_ACEPTAR_SIN_DIAGNOSTICO)
                ) { Text("Aceptar", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface) }
            }
        )
    }

    if (confirmarSalida) {
        AlertDialog(
            onDismissRequest = { confirmarSalida = false },
            title = { Text("¿Salir de la captura?") },
            text = { Text("La atención no se ha guardado y se descartará.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmarSalida = false
                    alVolver()
                }) {
                    Text("Salir y descartar", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmarSalida = false }) { Text("Seguir aquí", color = MaterialTheme.colorScheme.onSurface) }
            }
        )
    }
}

@Composable
private fun TarjetaAdaptador(estado: EstadoPantallaCaptura) {
    val c = estado.captura
    val enlace = when (c.enlace) {
        EstadoEnlace.CONECTADO -> if (estado.modoDemostracion) "Reproducción de atención grabada" else "Conectado por Bluetooth"

        EstadoEnlace.CONECTANDO -> if (estado.modoDemostracion) "Abriendo la atención grabada…" else "Conectando con el adaptador…"

        EstadoEnlace.RECONECTANDO -> "Reconectando…"

        EstadoEnlace.DESCONECTADO -> when {
            estado.fase == FaseCaptura.PREPARANDO || (estado.fase == FaseCaptura.CAPTURANDO && !c.lecturaInicialHecha) ->
                if (estado.modoDemostracion) "Reproducción de atención grabada · preparando" else "Conectando con el adaptador…"

            estado.modoDemostracion -> "Reproducción de atención grabada"

            else -> "Desconectado"
        }
    }
    Tarjeta(Modifier.testTag(EtiquetasPrueba.TARJETA_ADAPTADOR)) {
        TituloTarjeta("Adaptador ELM327")
        TextoTarjeta(listOfNotNull(enlace, c.protocolo).joinToString(" · "))
        c.familiaGestion?.let { TextoSecundario("Familia de gestión: ${it.descripcion}") }
    }
}

@Composable
private fun TarjetaLecturaInicial(estado: EstadoPantallaCaptura) {
    val c = estado.captura
    Tarjeta(Modifier.testTag(EtiquetasPrueba.TARJETA_LECTURA_INICIAL)) {
        TituloTarjeta("Lectura inicial")
        if (!c.lecturaInicialHecha) {
            TextoSecundario("Se leen los códigos y los contadores del modo 06 al conectar.")
        } else {
            TextoTarjeta("Códigos: ${Formatos.codigos(c.codigos)}")
            TextoTarjeta("Fallos de encendido (modo 06): ${Formatos.fallosModo06(c.contadoresModo06)}")
            c.baroKpa?.let { TextoSecundario("Presión barométrica: ${Formatos.numero(it, 0)} kPa") }
        }
    }
}

@Composable
private fun TarjetaMensaje(texto: String) {
    Tarjeta(Modifier.testTag(EtiquetasPrueba.TARJETA_MENSAJE), fondo = AmarilloAviso.copy(alpha = 0.35f)) {
        Text(texto, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Tarjeta de un régimen: estado, muestras útiles, avance n/45 y "Repetir" si fue rechazado (CU-08). */
@Composable
private fun TarjetaRegimen(tipo: TipoRegimen, estado: EstadoPantallaCaptura, alRepetir: (TipoRegimen) -> Unit) {
    val p: ProgresoRegimen = estado.captura.regimenes[tipo] ?: ProgresoRegimen(tipo)
    val dictamen = estado.dictamen
    val rechazado = dictamen?.resultado == ResultadoDictamen.RECHAZADA && tipo in dictamen.regimenesARepetir
    val enRepeticion = estado.regimenEnRepeticion == tipo
    // Motivo junto al régimen (CU-08), salvo que la tarjeta del dictamen ya lo diga igual.
    val motivoDictamen = dictamen?.let { Formatos.motivoDictamen(it, EfectoRegla.RECHAZA) }.orEmpty()
    val motivos = dictamen?.reglas
        ?.filter { it.regimen == tipo && it.efecto == EfectoRegla.RECHAZA && it.motivo.isNotBlank() }
        ?.map { it.motivo }
        ?.filterNot { motivoDictamen.contains(it) }
        .orEmpty()

    Tarjeta(Modifier.testTag(EtiquetasPrueba.tarjetaRegimen(tipo))) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TituloTarjeta("${tipo.name} · ${tipo.nombre}")
                val linea = when {
                    rechazado && !enRepeticion -> "Rechazado · ${p.muestrasUtiles} de ${p.objetivo} muestras útiles"

                    p.estado == EstadoRegimen.CAPTURANDO -> "Capturando · ${p.muestrasUtiles}/${p.objetivo} muestras útiles"

                    p.estado == EstadoRegimen.CAPTURADO -> "Capturado · ${p.muestrasUtiles} muestras útiles"

                    p.estado == EstadoRegimen.OMITIDO ->
                        "No capturado" + (p.motivoOmision?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")

                    enRepeticion -> "Por repetir"

                    else -> "Pendiente"
                }
                TextoTarjeta(linea)
                if (p.estado == EstadoRegimen.CAPTURANDO) {
                    val lecturas = listOfNotNull(
                        p.rpm?.let { "${Formatos.numero(it, 0)} rpm" },
                        p.ect?.let { "refrigerante ${Formatos.numero(it, 0)} °C" }
                    )
                    if (lecturas.isNotEmpty()) TextoSecundario(lecturas.joinToString(" · "))
                    BarraAvance(if (p.objetivo > 0) p.muestrasUtiles.toFloat() / p.objetivo else 0f)
                }
                if (rechazado && !enRepeticion) motivos.forEach { TextoSecundario(it) }
            }
            if (rechazado && !enRepeticion) {
                Spacer(Modifier.width(8.dp))
                BotonSecundario(
                    "Repetir ${tipo.name}",
                    { alRepetir(tipo) },
                    Modifier.testTag(EtiquetasPrueba.botonRepetir(tipo)),
                    habilitado = estado.puedeRepetir
                )
            }
        }
    }
}

/** Dictamen de la validación (CU-04): completa, parcial con su observación o rechazada con su motivo. */
@Composable
private fun TarjetaDictamen(estado: EstadoPantallaCaptura) {
    val d: Dictamen? = estado.dictamen
    when {
        d != null -> Tarjeta(Modifier.testTag(EtiquetasPrueba.TARJETA_DICTAMEN), grosorBorde = 2) {
            Text("Dictamen: ${d.resultado.texto}", fontSize = 21.sp, fontWeight = FontWeight.Bold)
            when (d.resultado) {
                ResultadoDictamen.COMPLETA -> TextoTarjeta("Se cumplen todas las reglas de validación.")

                ResultadoDictamen.PARCIAL -> TextoTarjeta(
                    Formatos.motivoDictamen(d, EfectoRegla.DEGRADA).ifBlank { "Se incumple alguna regla graduada." }
                )

                ResultadoDictamen.RECHAZADA -> {
                    TextoTarjeta(Formatos.motivoDictamen(d, EfectoRegla.RECHAZA).ifBlank { "Se incumple una regla obligatoria." })
                    TextoTarjeta(Formatos.indicacionRepeticion(d.regimenesARepetir, estado.regimenesCapturados))
                }
            }
            if (estado.sinDiagnostico && d.permiteGuardar()) {
                Spacer(Modifier.size(2.dp))
                Text(
                    "El vehículo no expone los parámetros que pide el modelo. " +
                        "Al pulsar «Diagnosticar», la atención se guardará en el historial sin diagnóstico.",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        estado.fase == FaseCaptura.VALIDANDO -> Tarjeta(Modifier.testTag(EtiquetasPrueba.TARJETA_DICTAMEN)) {
            TituloTarjeta("Dictamen")
            TextoSecundario("Validando la atención…")
        }

        estado.fase == FaseCaptura.REPITIENDO -> Tarjeta(Modifier.testTag(EtiquetasPrueba.TARJETA_DICTAMEN)) {
            TituloTarjeta("Dictamen")
            TextoSecundario("Repitiendo ${estado.regimenEnRepeticion?.name.orEmpty()}. Al terminar se vuelve a validar la atención.")
        }
    }
}

/** Pie fijo: instrucción con "Continuar" y "Diagnosticar". */
@Composable
private fun PieCaptura(estado: EstadoPantallaCaptura, vm: CapturaViewModel) {
    val c = estado.captura
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val enMarcha = estado.fase == FaseCaptura.CAPTURANDO || estado.fase == FaseCaptura.REPITIENDO
        // Si el servicio espera confirmación, el botón se muestra siempre para no trabar la captura.
        if (c.esperaConfirmacion || (enMarcha && c.instruccion != null)) {
            Tarjeta(
                Modifier.testTag(EtiquetasPrueba.TARJETA_INSTRUCCION),
                fondo = AmarilloAviso.copy(alpha = 0.35f),
                borde = Negro,
                grosorBorde = 2,
                fusionar = false
            ) {
                Text(c.instruccion ?: "Confirme para continuar.", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                if (c.esperaConfirmacion) {
                    BotonPrincipal("Continuar", vm::confirmar, Modifier.testTag(EtiquetasPrueba.BOTON_CONTINUAR))
                }
            }
        }
        val ocupado = when (estado.fase) {
            FaseCaptura.PREPARANDO -> "Preparando la atención…"
            FaseCaptura.VALIDANDO -> "Validando…"
            FaseCaptura.DIAGNOSTICANDO -> "Obteniendo el diagnóstico…"
            FaseCaptura.GUARDANDO -> "Guardando…"
            else -> null
        }
        if (ocupado != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.width(10.dp))
                TextoSecundario(ocupado)
            }
        }
        BotonPrincipal(
            "Diagnosticar",
            vm::diagnosticar,
            Modifier.testTag(EtiquetasPrueba.BOTON_DIAGNOSTICAR),
            habilitado = estado.puedeDiagnosticar
        )
    }
}
