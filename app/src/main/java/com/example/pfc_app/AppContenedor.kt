package com.example.pfc_app

import android.content.Context
import com.example.pfc_app.adquisicion.AdaptadorELM327
import com.example.pfc_app.adquisicion.AdaptadorELM327Bluetooth
import com.example.pfc_app.adquisicion.AdaptadorELM327Demo
import com.example.pfc_app.adquisicion.CatalogoDemo
import com.example.pfc_app.adquisicion.CatalogoDemoAssets
import com.example.pfc_app.adquisicion.GrabacionDemo
import com.example.pfc_app.adquisicion.ServicioAdquisicion
import com.example.pfc_app.adquisicion.ServicioAdquisicionImpl
import com.example.pfc_app.cuenta.ServicioCuenta
import com.example.pfc_app.cuenta.ServicioCuentaFirebase
import com.example.pfc_app.datos.BaseDatosLocal
import com.example.pfc_app.datos.PreferenciasApp
import com.example.pfc_app.datos.RepositorioAtenciones
import com.example.pfc_app.datos.RepositorioVehiculos
import com.example.pfc_app.inferencia.CargadorModelo
import com.example.pfc_app.inferencia.ModeloClasificacion
import com.example.pfc_app.inferencia.MotorInferencia
import com.example.pfc_app.modelos.Atencion
import com.example.pfc_app.modelos.ContratoModelo
import com.example.pfc_app.modelos.Diagnostico
import com.example.pfc_app.modelos.Dictamen
import com.example.pfc_app.procesamiento.ProcesadorCaracteristicas
import com.example.pfc_app.validacion.ValidadorAtencion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Qué adaptador arma la pantalla de Captura para una atención. */
sealed interface ConfiguracionAdaptador {
    data class Demostracion(val grabacion: GrabacionDemo, val simularRechazo: Boolean) : ConfiguracionAdaptador
    data class Bluetooth(val direccionMac: String) : ConfiguracionAdaptador
}

/**
 * Contenedor de dependencias de la aplicación. Todo se construye de forma perezosa:
 * la pantalla Vehículos funciona sin cargar el modelo ni tocar el Bluetooth.
 * Las fábricas son variables para que una prueba pueda reemplazarlas.
 */
class AppContenedor(contexto: Context) {
    val contexto: Context = contexto.applicationContext

    /** Alcance de la aplicación, para tareas que deben terminar aunque se cierre una pantalla. */
    val alcance: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** La base Room y sus repositorios. Una base Room cerrada no se reabre: se crea otra. */
    private class Datos(val base: BaseDatosLocal) {
        val vehiculos = RepositorioVehiculos(base)
        val atenciones = RepositorioAtenciones(base)
    }

    private val candadoDatos = Any()

    @Volatile private var datosAbiertos: Datos? = null

    private fun datos(): Datos = datosAbiertos ?: synchronized(candadoDatos) {
        datosAbiertos ?: Datos(BaseDatosLocal.crear(this.contexto)).also { datosAbiertos = it }
    }

    val baseDatos: BaseDatosLocal get() = datos().base
    val repositorioVehiculos: RepositorioVehiculos get() = datos().vehiculos
    val repositorioAtenciones: RepositorioAtenciones get() = datos().atenciones
    val preferencias: PreferenciasApp by lazy { PreferenciasApp(this.contexto) }

    /** Sesión de Google con Firebase Authentication; Firebase la crea la primera vez que se pide. */
    private val cuentaFirebase: ServicioCuenta by lazy { ServicioCuentaFirebase(this.contexto) }
    val cuenta: ServicioCuenta get() = proveedorCuenta()

    /** Modelo ONNX y su contrato, leídos de assets/modelo la primera vez que se piden. */
    val modelo: ModeloClasificacion by lazy {
        Registro.i(Registro.INFERENCIA, "Cargando el modelo desde assets")
        CargadorModelo.desdeAssets(this.contexto)
    }

    /** Contrato del modelo (clases, características y referencias sin falla). */
    val contrato: ContratoModelo get() = proveedorContrato()

    val validador: ValidadorAtencion by lazy { ValidadorAtencion(contrato) }
    val procesador: ProcesadorCaracteristicas by lazy { ProcesadorCaracteristicas() }
    val motorInferencia: MotorInferencia by lazy { MotorInferencia(modelo) }
    private val catalogoAssets: CatalogoDemo by lazy { CatalogoDemoAssets(this.contexto) }
    val catalogoDemo: CatalogoDemo get() = proveedorCatalogo()

    /** Atención en curso, entre Captura y Diagnóstico. */
    val sesion = SesionAtencion()

    // Fábricas y pasos del flujo. Son variables para que las pruebas de interfaz puedan
    // reemplazarlos por falsos; restablecerFabricas() vuelve a las implementaciones reales.

    /** Un servicio nuevo por atención. */
    var fabricaServicio: () -> ServicioAdquisicion = ::servicioReal
    var fabricaAdaptador: (ConfiguracionAdaptador) -> AdaptadorELM327 = ::adaptadorReal

    /** CU-04: ValidadorAtencion(contrato).validar(atencion). */
    var validarAtencion: (Atencion) -> Dictamen = ::validarReal

    /** CU-05 y CU-07: fila de características y modelo; null si a la fila le falta algo. */
    var diagnosticarAtencion: (Atencion) -> Diagnostico? = ::diagnosticarReal
    var proveedorContrato: () -> ContratoModelo = { modelo.contrato }
    var proveedorCatalogo: () -> CatalogoDemo = { catalogoAssets }
    var proveedorCuenta: () -> ServicioCuenta = { cuentaFirebase }

    fun restablecerFabricas() {
        fabricaServicio = ::servicioReal
        fabricaAdaptador = ::adaptadorReal
        validarAtencion = ::validarReal
        diagnosticarAtencion = ::diagnosticarReal
        proveedorContrato = { modelo.contrato }
        proveedorCatalogo = { catalogoAssets }
        proveedorCuenta = { cuentaFirebase }
    }

    /** Tras iniciar sesión, las atenciones guardadas antes de la versión 3 pasan a esta cuenta. */
    fun alIniciarSesion() {
        val uid = cuenta.actual.value?.uid ?: return
        alcance.launch(Dispatchers.IO) {
            try {
                repositorioAtenciones.asignarSinCuenta(uid)
            } catch (e: Exception) {
                Registro.e(Registro.DATOS, "No se pudieron asignar las atenciones sin cuenta", e)
            }
        }
    }

    private fun servicioReal(): ServicioAdquisicion = ServicioAdquisicionImpl()

    private fun adaptadorReal(config: ConfiguracionAdaptador): AdaptadorELM327 = when (config) {
        is ConfiguracionAdaptador.Demostracion ->
            AdaptadorELM327Demo(contexto, config.grabacion, config.simularRechazo)

        is ConfiguracionAdaptador.Bluetooth ->
            AdaptadorELM327Bluetooth(contexto, config.direccionMac)
    }

    private fun validarReal(atencion: Atencion): Dictamen = validador.validar(atencion)

    private fun diagnosticarReal(atencion: Atencion): Diagnostico? {
        val fila = procesador.prepararFila(atencion)
        return motorInferencia.predecir(fila, atencion.codigos)
    }

    /** Cierra y borra la base de datos (lo usan las pruebas para empezar limpias). */
    fun borrarBaseDatos() {
        synchronized(candadoDatos) {
            datosAbiertos?.base?.close()
            datosAbiertos = null
        }
        contexto.deleteDatabase(BaseDatosLocal.NOMBRE)
        Registro.w(Registro.DATOS, "Base de datos borrada")
    }
}
