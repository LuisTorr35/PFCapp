package com.example.pfc_app.ui

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.pfc_app.Registro
import com.example.pfc_app.adquisicion.DispositivosBluetooth
import com.example.pfc_app.modelos.Vehiculo
import com.example.pfc_app.ui.theme.Blanco
import com.example.pfc_app.ui.theme.GrisBorde
import com.example.pfc_app.ui.theme.Negro

/**
 * Pantalla Vehículos (Figura 9, CU-01): registro, búsqueda por código, lista con
 * "Atender" e "Historial", interruptor del modo demostración y adaptador ELM327.
 */
@Composable
fun PantallaVehiculo(
    vm: VehiculoViewModel,
    alAtender: (vehiculo: Vehiculo, idGrabacion: String?, simularRechazo: Boolean) -> Unit,
    alAbrirHistorial: (Vehiculo) -> Unit,
) {
    val estado by vm.estado.collectAsStateWithLifecycle()
    val contexto = LocalContext.current

    // Se relee la lista cada vez que la pantalla vuelve a mostrarse (la familia puede haber cambiado).
    LaunchedEffect(Unit) { vm.recargar() }

    // Tras registrar, se cierra el teclado para que se vea la lista.
    val foco = LocalFocusManager.current
    LaunchedEffect(estado.mensaje) { if (estado.mensaje != null) foco.clearFocus() }

    // Vehículo que espera el permiso de Bluetooth para abrir la Captura en modo real.
    var pendiente by remember { mutableStateOf<Vehiculo?>(null) }
    var pidiendoParaSelector by remember { mutableStateOf(false) }

    // Acción que espera a que el mecánico encienda el Bluetooth.
    var trasEncender by remember { mutableStateOf<(() -> Unit)?>(null) }

    val lanzadorEncender = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val accion = trasEncender
        trasEncender = null
        if (r.resultCode == Activity.RESULT_OK || DispositivosBluetooth.disponible(contexto)) {
            Registro.i(Registro.BT, "El mecánico encendió el Bluetooth")
            accion?.invoke()
        } else {
            Registro.w(Registro.BT, "El mecánico no encendió el Bluetooth")
            vm.mostrarError("El Bluetooth sigue apagado. Sin él no se puede usar el adaptador ELM327.")
        }
    }

    /** Ejecuta [accion] con el Bluetooth encendido; si está apagado, pide encenderlo primero. */
    fun conBluetoothEncendido(accion: () -> Unit) {
        when {
            !DispositivosBluetooth.existe(contexto) -> vm.mostrarError("Este teléfono no tiene Bluetooth.")
            DispositivosBluetooth.disponible(contexto) -> accion()
            else -> {
                Registro.i(Registro.BT, "Bluetooth apagado: se pide encenderlo")
                trasEncender = accion
                try {
                    lanzadorEncender.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                } catch (e: SecurityException) {
                    trasEncender = null
                    Registro.w(Registro.BT, "No se pudo pedir encender el Bluetooth", e)
                    vm.mostrarError("Encienda el Bluetooth del teléfono y vuelva a intentarlo.")
                } catch (e: ActivityNotFoundException) {
                    trasEncender = null
                    Registro.w(Registro.BT, "El teléfono no ofrece la pantalla para encender el Bluetooth", e)
                    vm.mostrarError("Encienda el Bluetooth del teléfono y vuelva a intentarlo.")
                }
            }
        }
    }

    val lanzadorPermiso = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { concedido ->
        if (!concedido) {
            Registro.w(Registro.BT, "El mecánico rechazó el permiso BLUETOOTH_CONNECT")
            vm.mostrarError(
                "Sin el permiso de dispositivos cercanos no se puede usar el adaptador Bluetooth. " +
                    "Puede concederlo en Ajustes › Aplicaciones › PFC-app › Permisos.",
            )
        } else if (pidiendoParaSelector) {
            conBluetoothEncendido { vm.abrirSelectorAdaptador() }
        } else {
            pendiente?.let { v -> conBluetoothEncendido { alAtender(v, null, false) } }
        }
        pendiente = null
        pidiendoParaSelector = false
    }

    fun tienePermisoBluetooth(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(contexto, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    fun elegirAdaptador() {
        if (tienePermisoBluetooth()) {
            conBluetoothEncendido { vm.abrirSelectorAdaptador() }
        } else {
            pidiendoParaSelector = true
            lanzadorPermiso.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }

    fun atender(v: Vehiculo) {
        when {
            estado.modoDemostracion -> vm.pedirGrabacion(v)
            estado.macAdaptador == null -> vm.pedirAdaptador(true)
            !tienePermisoBluetooth() -> {
                pendiente = v
                pidiendoParaSelector = false
                lanzadorPermiso.launch(Manifest.permission.BLUETOOTH_CONNECT)
            }
            else -> conBluetoothEncendido { alAtender(v, null, false) }
        }
    }

    Column(Modifier.fillMaxSize()) {
        BarraSuperior("Vehículos")
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag(EtiquetasPrueba.LISTA_VEHICULOS),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "modo") {
                TarjetaModo(
                    modoDemostracion = estado.modoDemostracion,
                    nombreAdaptador = estado.nombreAdaptador,
                    macAdaptador = estado.macAdaptador,
                    alCambiarModo = vm::cambiarModoDemostracion,
                    alElegirAdaptador = ::elegirAdaptador,
                )
            }
            item(key = "formulario") { FormularioRegistro(estado, vm) }
            item(key = "titulo") { TituloSeccion("Vehículos registrados") }
            item(key = "buscar") {
                OutlinedTextField(
                    value = estado.busqueda,
                    onValueChange = vm::cambiarBusqueda,
                    modifier = Modifier.fillMaxWidth().testTag(EtiquetasPrueba.CAMPO_BUSCAR),
                    placeholder = { Text("Buscar por código") },
                    singleLine = true,
                    shape = FormaBoton,
                    colors = coloresCampo(),
                )
            }
            val lista = estado.vehiculosFiltrados
            if (lista.isEmpty()) {
                item(key = "vacio") {
                    TextoSecundario(
                        if (estado.vehiculos.isEmpty()) "Todavía no hay vehículos registrados."
                        else "Ningún vehículo coincide con \"${estado.busqueda.trim()}\".",
                    )
                }
            }
            items(lista, key = { it.id }) { v ->
                FilaVehiculo(v, alAtender = { atender(v) }, alHistorial = { alAbrirHistorial(v) })
            }
        }
    }

    estado.solicitudGrabacion?.let { s ->
        DialogoGrabacion(
            solicitud = s,
            alElegir = vm::elegirGrabacion,
            alCambiarRechazo = vm::cambiarSimularRechazo,
            alConfirmar = {
                val g = s.elegida
                vm.cerrarDialogoGrabacion()
                if (g != null) alAtender(s.vehiculo, g.id, s.simularRechazo)
            },
            alCancelar = vm::cerrarDialogoGrabacion,
        )
    }

    if (estado.pideAdaptador) {
        AlertDialog(
            onDismissRequest = { vm.pedirAdaptador(false) },
            title = { Text("Elija el adaptador") },
            text = { Text("Para atender en modo real, elija primero el adaptador ELM327 vinculado con el teléfono.") },
            confirmButton = { TextButton(onClick = { vm.pedirAdaptador(false); elegirAdaptador() }) { Text("Elegir adaptador") } },
            dismissButton = { TextButton(onClick = { vm.pedirAdaptador(false) }) { Text("Cancelar") } },
        )
    }

    estado.dispositivos?.let { lista ->
        AlertDialog(
            modifier = Modifier.testTag(EtiquetasPrueba.DIALOGO_ADAPTADOR),
            onDismissRequest = vm::cerrarSelectorAdaptador,
            title = { Text("Adaptador ELM327") },
            text = {
                if (lista.isEmpty()) {
                    Text("No hay dispositivos vinculados. Vincule el adaptador ELM327 en Ajustes de Bluetooth y vuelva a intentarlo.")
                } else {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        lista.forEach { d ->
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .selectable(selected = d.direccionMac == estado.macAdaptador, onClick = { vm.elegirAdaptador(d) })
                                    .padding(vertical = 10.dp),
                            ) {
                                Text(d.nombre.ifBlank { "(sin nombre)" }, fontWeight = FontWeight.Bold)
                                TextoSecundario(d.direccionMac)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = vm::cerrarSelectorAdaptador) { Text("Cerrar") } },
        )
    }

    estado.error?.let { mensaje ->
        AlertDialog(
            onDismissRequest = vm::cerrarError,
            title = { Text("Aviso") },
            text = { Text(mensaje) },
            confirmButton = { TextButton(onClick = vm::cerrarError) { Text("Aceptar") } },
        )
    }
}

@Composable
private fun TarjetaModo(
    modoDemostracion: Boolean,
    nombreAdaptador: String?,
    macAdaptador: String?,
    alCambiarModo: (Boolean) -> Unit,
    alElegirAdaptador: () -> Unit,
) {
    Tarjeta(fusionar = false) {
        // Toda la fila cambia el modo, no solo el interruptor (en el teléfono se tocaba el texto).
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(value = modoDemostracion, onValueChange = alCambiarModo, role = Role.Switch)
                .testTag(EtiquetasPrueba.INTERRUPTOR_DEMO),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                TituloTarjeta("Modo demostración")
                TextoSecundario(
                    if (modoDemostracion) "Se reproducen atenciones grabadas, sin adaptador ni vehículo."
                    else "Se lee el vehículo con el adaptador ELM327 por Bluetooth.",
                )
            }
            Spacer(Modifier.width(12.dp))
            // Colores del tema: con colores fijos, en modo oscuro la pista negra no se veía
            // sobre el fondo negro y no se distinguía si el modo estaba activado.
            Switch(
                checked = modoDemostracion,
                onCheckedChange = null,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                    uncheckedThumbColor = GrisBorde,
                    uncheckedTrackColor = MaterialTheme.colorScheme.surface,
                    uncheckedBorderColor = GrisBorde,
                ),
            )
        }
        if (!modoDemostracion) {
            Spacer(Modifier.height(4.dp))
            Text("Adaptador ELM327", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            TextoTarjeta(
                if (macAdaptador == null) "Ningún adaptador elegido"
                else "${nombreAdaptador?.ifBlank { null } ?: "Adaptador"} · $macAdaptador",
            )
            BotonSecundario(
                if (macAdaptador == null) "Elegir adaptador" else "Cambiar adaptador",
                alElegirAdaptador,
                modifier = Modifier.testTag(EtiquetasPrueba.BOTON_ELEGIR_ADAPTADOR),
            )
        }
    }
}

@Composable
private fun FormularioRegistro(estado: EstadoVehiculos, vm: VehiculoViewModel) {
    Tarjeta(fusionar = false) {
        TituloTarjeta("Registrar vehículo")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CampoFormulario("Marca", estado.marca, vm::cambiarMarca, estado.errores.marca, EtiquetasPrueba.CAMPO_MARCA, Modifier.weight(1f))
            CampoFormulario("Modelo", estado.modelo, vm::cambiarModelo, estado.errores.modelo, EtiquetasPrueba.CAMPO_MODELO, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CampoFormulario(
                "Año", estado.anio, vm::cambiarAnio, estado.errores.anio, EtiquetasPrueba.CAMPO_ANIO, Modifier.weight(1f),
                numerico = true,
            )
            CampoFormulario(
                "Cilindros", estado.cilindros, vm::cambiarCilindros, estado.errores.cilindros, EtiquetasPrueba.CAMPO_CILINDROS,
                Modifier.weight(1f), numerico = true, alTerminar = vm::registrar,
            )
        }
        TextoTarjeta("La aplicación asigna el código y la familia de gestión.")
        Spacer(Modifier.height(4.dp))
        BotonPrincipal(
            texto = if (estado.registrando) "Registrando…" else "Registrar",
            alPulsar = vm::registrar,
            habilitado = !estado.registrando,
            modifier = Modifier.testTag(EtiquetasPrueba.BOTON_REGISTRAR),
        )
        estado.mensaje?.let {
            Text(
                it,
                modifier = Modifier.testTag(EtiquetasPrueba.MENSAJE_REGISTRO),
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
            )
        }
    }
}

@Composable
private fun CampoFormulario(
    etiqueta: String,
    valor: String,
    alCambiar: (String) -> Unit,
    error: String?,
    tag: String,
    modifier: Modifier = Modifier,
    numerico: Boolean = false,
    /** Acción "Listo" del teclado; sin ella, el teclado pasa al campo siguiente. */
    alTerminar: (() -> Unit)? = null,
) {
    Column(modifier) {
        Text(etiqueta, fontSize = 15.sp)
        OutlinedTextField(
            value = valor,
            onValueChange = alCambiar,
            modifier = Modifier.fillMaxWidth().testTag(tag),
            singleLine = true,
            isError = error != null,
            supportingText = error?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(
                keyboardType = if (numerico) KeyboardType.Number else KeyboardType.Text,
                imeAction = if (alTerminar != null) ImeAction.Done else ImeAction.Next,
            ),
            keyboardActions = KeyboardActions(onDone = { alTerminar?.invoke() }),
            shape = FormaBoton,
            colors = coloresCampo(),
        )
    }
}

@Composable
private fun coloresCampo() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = MaterialTheme.colorScheme.outline,
    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
    cursorColor = MaterialTheme.colorScheme.onSurface,
)

@Composable
private fun FilaVehiculo(v: Vehiculo, alAtender: () -> Unit, alHistorial: () -> Unit) {
    Tarjeta(modifier = Modifier.testTag(EtiquetasPrueba.filaVehiculo(v.codigo))) {
        Text("${v.codigo} · ${v.marca} ${v.modelo} ${v.anio}", fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextoTarjeta(
                "${Formatos.cilindros(v.cilindros)} · ${Formatos.familiaCorta(v.familiaGestion)}",
                modifier = Modifier.weight(1f),
            )
            BotonSecundario("Atender", alAtender, Modifier.testTag(EtiquetasPrueba.botonAtender(v.codigo)))
            Spacer(Modifier.width(8.dp))
            BotonSecundario("Historial", alHistorial, Modifier.testTag(EtiquetasPrueba.botonHistorial(v.codigo)))
        }
    }
}

/** Diálogo del modo demostración: qué atención grabada reproducir y si simular el R2 rechazado. */
@Composable
private fun DialogoGrabacion(
    solicitud: SolicitudGrabacion,
    alElegir: (Int) -> Unit,
    alCambiarRechazo: (Boolean) -> Unit,
    alConfirmar: () -> Unit,
    alCancelar: () -> Unit,
) {
    AlertDialog(
        modifier = Modifier.testTag(EtiquetasPrueba.DIALOGO_GRABACION),
        onDismissRequest = alCancelar,
        title = { Text("Atender ${solicitud.vehiculo.codigo}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Elija la atención grabada que se va a reproducir:", fontSize = 15.sp)
                Spacer(Modifier.height(8.dp))
                solicitud.grabaciones.forEachIndexed { i, g ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = i == solicitud.indice, onClick = { alElegir(i) }, role = Role.RadioButton)
                            .testTag(EtiquetasPrueba.opcionGrabacion(i))
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = i == solicitud.indice,
                            onClick = null,
                            colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.onSurface),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(g.descripcion, fontSize = 15.sp)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(value = solicitud.simularRechazo, onValueChange = alCambiarRechazo, role = Role.Checkbox)
                        .testTag(EtiquetasPrueba.CASILLA_RECHAZO)
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = solicitud.simularRechazo,
                        onCheckedChange = null,
                        colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.onSurface),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Simular R2 rechazado (R2 corto)", fontSize = 15.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = alConfirmar, modifier = Modifier.testTag(EtiquetasPrueba.BOTON_CONFIRMAR_GRABACION)) {
                Text("Confirmar", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            }
        },
        dismissButton = {
            TextButton(onClick = alCancelar) { Text("Cancelar", color = MaterialTheme.colorScheme.onSurface) }
        },
    )
}
