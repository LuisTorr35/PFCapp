package com.example.pfc_app.ui

import androidx.test.core.app.ApplicationProvider
import com.example.pfc_app.AppContenedor
import com.example.pfc_app.PfcApp
import org.junit.rules.ExternalResource

/**
 * Antes de abrir la actividad: borra la base de datos, activa el modo demostración,
 * descarta la atención en curso y vuelve a las fábricas reales, para que cada prueba
 * empiece limpia (el primer vehículo registrado es VEH-001). [configurar] permite
 * instalar falsos en el contenedor. Va por fuera de la regla de Compose en un RuleChain.
 * La sesión es siempre [cuenta], una falsa (por defecto con sesión abierta), y el respaldo va
 * a [nube], en memoria: las pruebas no usan Firebase. Programar el respaldo solo se cuenta.
 */
class ReglaBaseLimpia(
    private val modoDemostracion: Boolean = true,
    val cuenta: CuentaFalsa = CuentaFalsa(),
    private val configurar: (AppContenedor) -> Unit = {}
) : ExternalResource() {
    val nube = NubeFalsa()

    @Volatile var respaldosProgramados = 0
        private set

    private val contenedor: AppContenedor
        get() = ApplicationProvider.getApplicationContext<PfcApp>().contenedor

    override fun before() {
        val c = contenedor
        c.restablecerFabricas()
        c.borrarBaseDatos()
        c.preferencias.modoDemostracion = modoDemostracion
        c.sesion.limpiar()
        c.proveedorCuenta = { cuenta }
        c.proveedorNube = { nube }
        c.programarRespaldo = { respaldosProgramados++ }
        configurar(c)
    }

    override fun after() {
        contenedor.restablecerFabricas()
    }
}
