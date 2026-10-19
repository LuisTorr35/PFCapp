package com.example.pfc_app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.pfc_app.AppContenedor
import com.example.pfc_app.Registro
import com.example.pfc_app.adquisicion.DispositivoBT
import com.example.pfc_app.adquisicion.DispositivosBluetooth
import com.example.pfc_app.adquisicion.GrabacionDemo
import com.example.pfc_app.datos.RepositorioVehiculos
import com.example.pfc_app.modelos.Vehiculo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Diálogo para elegir la atención grabada que reproduce el modo demostración. */
data class SolicitudGrabacion(
    val vehiculo: Vehiculo,
    val grabaciones: List<GrabacionDemo>,
    val indice: Int = 0,
    val simularRechazo: Boolean = false,
) {
    val elegida: GrabacionDemo? get() = grabaciones.getOrNull(indice)
}

data class EstadoVehiculos(
    val vehiculos: List<Vehiculo> = emptyList(),
    val busqueda: String = "",
    val marca: String = "",
    val modelo: String = "",
    val anio: String = "",
    val cilindros: String = "",
    val errores: ErroresVehiculo = ErroresVehiculo(),
    val registrando: Boolean = false,
    /** Confirmación del registro, por ejemplo "Vehículo registrado: VEH-011". */
    val mensaje: String? = null,
    val modoDemostracion: Boolean = true,
    val nombreAdaptador: String? = null,
    val macAdaptador: String? = null,
    val solicitudGrabacion: SolicitudGrabacion? = null,
    /** Dispositivos vinculados para elegir el adaptador; null si el selector está cerrado. */
    val dispositivos: List<DispositivoBT>? = null,
    /** "Atender" en modo real sin adaptador elegido. */
    val pideAdaptador: Boolean = false,
    val error: String? = null,
) {
    /** Lista filtrada por la búsqueda por código (acepta "VEH-010", "010" o "10"). */
    val vehiculosFiltrados: List<Vehiculo>
        get() {
            val texto = busqueda.trim()
            if (texto.isEmpty()) return vehiculos
            val normalizado = RepositorioVehiculos.normalizarCodigo(texto)
            return vehiculos.filter { it.codigo.contains(texto, ignoreCase = true) || it.codigo == normalizado }
        }
}

/** Pantalla Vehículos (CU-01): registro, búsqueda, modo demostración y adaptador. */
class VehiculoViewModel(private val contenedor: AppContenedor) : ViewModel() {
    private val _estado = MutableStateFlow(EstadoVehiculos())
    val estado: StateFlow<EstadoVehiculos> = _estado.asStateFlow()

    /** Relee la lista y las preferencias; se llama cada vez que la pantalla aparece. */
    fun recargar() {
        viewModelScope.launch {
            intentar({ t ->
                Registro.e(Registro.UI, "No se pudo leer la lista de vehículos", t)
                _estado.update { it.copy(error = "No se pudo leer la lista de vehículos: ${t.mensajeLegible()}") }
            }) {
                val (lista, demo, nombre, mac) = withContext(Dispatchers.IO) {
                    val p = contenedor.preferencias
                    Cuarteto(contenedor.repositorioVehiculos.listar(), p.modoDemostracion, p.nombreAdaptador, p.macAdaptador)
                }
                _estado.update {
                    it.copy(vehiculos = lista, modoDemostracion = demo, nombreAdaptador = nombre, macAdaptador = mac)
                }
            }
        }
    }

    fun cambiarMarca(v: String) = _estado.update { it.copy(marca = v, errores = it.errores.copy(marca = null), mensaje = null) }
    fun cambiarModelo(v: String) = _estado.update { it.copy(modelo = v, errores = it.errores.copy(modelo = null), mensaje = null) }
    fun cambiarAnio(v: String) = _estado.update { it.copy(anio = v.filter(Char::isDigit).take(4), errores = it.errores.copy(anio = null), mensaje = null) }
    fun cambiarCilindros(v: String) = _estado.update { it.copy(cilindros = v.filter(Char::isDigit).take(2), errores = it.errores.copy(cilindros = null), mensaje = null) }
    fun cambiarBusqueda(v: String) = _estado.update { it.copy(busqueda = v) }

    /** CU-01: valida los cuatro datos, asigna VEH-### y lo guarda. */
    fun registrar() {
        val e = _estado.value
        if (e.registrando) return
        val (datos, errores) = ValidacionVehiculo.validar(e.marca, e.modelo, e.anio, e.cilindros)
        if (datos == null) {
            Registro.d(Registro.UI, "Registro incompleto: $errores")
            _estado.update { it.copy(errores = errores, mensaje = null) }
            return
        }
        _estado.update { it.copy(registrando = true, errores = ErroresVehiculo()) }
        viewModelScope.launch {
            intentar({ t ->
                Registro.e(Registro.UI, "No se pudo registrar el vehículo", t)
                _estado.update { it.copy(registrando = false, error = "No se pudo registrar el vehículo: ${t.mensajeLegible()}") }
            }) {
                val (vehiculo, lista) = withContext(Dispatchers.IO) {
                    val repo = contenedor.repositorioVehiculos
                    val v = repo.registrar(datos.marca, datos.modelo, datos.anio, datos.cilindros)
                    v to repo.listar()
                }
                Registro.i(Registro.UI, "Vehículo registrado ${vehiculo.codigo}")
                _estado.update {
                    it.copy(
                        vehiculos = lista, registrando = false,
                        marca = "", modelo = "", anio = "", cilindros = "",
                        busqueda = "",
                        mensaje = "Vehículo registrado: ${vehiculo.codigo}",
                    )
                }
            }
        }
    }

    fun cambiarModoDemostracion(activado: Boolean) {
        contenedor.preferencias.modoDemostracion = activado
        Registro.i(Registro.UI, "Modo demostración ${if (activado) "activado" else "desactivado"}")
        _estado.update { it.copy(modoDemostracion = activado) }
    }

    /** "Atender" en modo demostración: abre el diálogo con las grabaciones incluidas. */
    fun pedirGrabacion(vehiculo: Vehiculo) {
        viewModelScope.launch {
            intentar({ t ->
                Registro.e(Registro.UI, "No se pudo leer el catálogo de grabaciones", t)
                _estado.update { it.copy(error = "No se pudieron leer las atenciones grabadas: ${t.mensajeLegible()}") }
            }) {
                val grabaciones = withContext(Dispatchers.IO) { contenedor.catalogoDemo.grabaciones() }
                if (grabaciones.isEmpty()) {
                    _estado.update { it.copy(error = "La aplicación no trae atenciones grabadas para la demostración.") }
                } else {
                    _estado.update { it.copy(solicitudGrabacion = SolicitudGrabacion(vehiculo, grabaciones)) }
                }
            }
        }
    }

    fun elegirGrabacion(indice: Int) = _estado.update { e ->
        e.copy(solicitudGrabacion = e.solicitudGrabacion?.copy(indice = indice))
    }

    fun cambiarSimularRechazo(valor: Boolean) = _estado.update { e ->
        e.copy(solicitudGrabacion = e.solicitudGrabacion?.copy(simularRechazo = valor))
    }

    fun cerrarDialogoGrabacion() = _estado.update { it.copy(solicitudGrabacion = null) }

    /** "Atender" en modo real sin adaptador: se pide elegirlo primero. */
    fun pedirAdaptador(mostrar: Boolean) = _estado.update { it.copy(pideAdaptador = mostrar) }

    /** Lista los adaptadores vinculados en Ajustes (requiere BLUETOOTH_CONNECT en Android 12+). */
    fun abrirSelectorAdaptador() {
        viewModelScope.launch {
            intentar({ t ->
                Registro.e(Registro.UI, "No se pudieron leer los dispositivos vinculados", t)
                _estado.update { it.copy(error = "No se pudieron leer los dispositivos vinculados: ${t.mensajeLegible()}") }
            }) {
                val lista = withContext(Dispatchers.IO) {
                    val ctx = contenedor.contexto
                    check(DispositivosBluetooth.disponible(ctx)) {
                        "El Bluetooth no está disponible o está apagado. Actívelo y vincule el adaptador en Ajustes."
                    }
                    DispositivosBluetooth.vinculados(ctx)
                }
                _estado.update { it.copy(dispositivos = lista, pideAdaptador = false) }
            }
        }
    }

    fun elegirAdaptador(d: DispositivoBT) {
        contenedor.preferencias.guardarAdaptador(d.nombre, d.direccionMac)
        Registro.i(Registro.UI, "Adaptador elegido ${d.nombre} (${d.direccionMac})")
        _estado.update { it.copy(nombreAdaptador = d.nombre, macAdaptador = d.direccionMac, dispositivos = null) }
    }

    fun cerrarSelectorAdaptador() = _estado.update { it.copy(dispositivos = null) }

    fun mostrarError(mensaje: String) = _estado.update { it.copy(error = mensaje) }

    fun cerrarError() = _estado.update { it.copy(error = null) }

    private data class Cuarteto<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
}
