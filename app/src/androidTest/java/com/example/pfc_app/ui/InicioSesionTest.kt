package com.example.pfc_app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.pfc_app.MainActivity
import com.example.pfc_app.cuenta.ResultadoInicioSesion as R
import com.example.pfc_app.modelos.Atencion
import com.example.pfc_app.modelos.Dictamen
import com.example.pfc_app.modelos.Lectura
import com.example.pfc_app.modelos.Regimen
import com.example.pfc_app.modelos.ResultadoDictamen
import com.example.pfc_app.modelos.TipoRegimen
import com.example.pfc_app.ui.EtiquetasPrueba as E
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** Inicio de sesión (solo sin sesión guardada) y "Cerrar sesión" del menú de Vehículos, con una sesión falsa. */
@RunWith(AndroidJUnit4::class)
class InicioSesionTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    private val regla = ReglaBaseLimpia(cuenta = CuentaFalsa(inicial = null))

    @get:Rule
    val reglas: RuleChain = RuleChain.outerRule(regla).around(compose)

    private val cuenta get() = regla.cuenta

    @Test
    fun sinSesionPideIniciarlaYLuegoAbreVehiculos() {
        compose.onNodeWithTag(E.BOTON_CONTINUAR_GOOGLE).assertIsDisplayed().performClick()
        esperar(E.LISTA_VEHICULOS)
        assertEquals(1, cuenta.inicios)
        // El menú muestra la cuenta sin el correo completo.
        compose.onNodeWithTag(E.BOTON_MENU_CUENTA).performClick()
        compose.onNodeWithText("me•••@example.com").assertIsDisplayed()
    }

    @Test
    fun sinConexionEnElPrimerInicioAvisaYSeQuedaEnElInicio() {
        cuenta.resultado = R.SinConexion
        compose.onNodeWithTag(E.BOTON_CONTINUAR_GOOGLE).performClick()
        esperar(E.AVISO_SIN_CONEXION)
        compose.onNodeWithText("El primer inicio de sesión necesita internet", substring = true).assertIsDisplayed()
        compose.onNodeWithTag(E.BOTON_CONTINUAR_GOOGLE).assertIsDisplayed()
    }

    @Test
    fun cerrarSesionSinPendientesVuelveAlInicio() {
        iniciar()
        compose.onNodeWithTag(E.BOTON_MENU_CUENTA).performClick()
        compose.onNodeWithTag(E.MENU_CERRAR_SESION).performClick()
        esperar(E.BOTON_CONTINUAR_GOOGLE)
        assertEquals(1, cuenta.cierres)
    }

    @Test
    fun cerrarSesionConPendientesAvisaAntes() {
        iniciar()
        guardarPendienteDe(CuentaFalsa.CUENTA_A.uid)
        compose.onNodeWithTag(E.BOTON_MENU_CUENTA).performClick()
        compose.onNodeWithTag(E.MENU_CERRAR_SESION).performClick()
        esperar(E.DIALOGO_CERRAR_SESION)
        compose.onNodeWithText("1 atención sin respaldar", substring = true).assertIsDisplayed()
        assertEquals("El aviso no cierra la sesión por sí solo", 0, cuenta.cierres)
        compose.onNodeWithTag(E.BOTON_CONFIRMAR_CERRAR_SESION).performClick()
        esperar(E.BOTON_CONTINUAR_GOOGLE)
        assertEquals(1, cuenta.cierres)
    }

    @Test
    fun lasPendientesDeOtraCuentaNoAvisan() {
        iniciar()
        guardarPendienteDe(CuentaFalsa.CUENTA_B.uid)
        compose.onNodeWithTag(E.BOTON_MENU_CUENTA).performClick()
        compose.onNodeWithTag(E.MENU_CERRAR_SESION).performClick()
        esperar(E.BOTON_CONTINUAR_GOOGLE)
    }

    private fun iniciar() {
        compose.onNodeWithTag(E.BOTON_CONTINUAR_GOOGLE).performClick()
        esperar(E.LISTA_VEHICULOS)
    }

    private fun esperar(etiqueta: String) = compose.waitUntil(10_000) {
        compose.onAllNodes(hasTestTag(etiqueta)).fetchSemanticsNodes().isNotEmpty()
    }

    /** Una atención guardada con la cuenta [uid] y todavía sin respaldar. */
    private fun guardarPendienteDe(uid: String) {
        val c = contenedor()
        val v = c.repositorioVehiculos.registrar("Hyundai", "Tucson", 2011, 4)
        val lecturas = List(3) { i -> Lectura(instante = i * 0.4, rpm = 750.0, stft = 1.0, ltft = 2.0) }
        val atencion = Atencion(
            idVehiculo = v.id,
            fecha = "2026-10-21T10:00:00",
            regimenes = mapOf(TipoRegimen.R2 to Regimen(tipo = TipoRegimen.R2, lecturas = lecturas, muestrasUtiles = 3))
        )
        c.repositorioAtenciones.guardar(atencion, Dictamen(ResultadoDictamen.COMPLETA, emptyList()), null, uid)
    }

    private fun contenedor() = (compose.activity.application as com.example.pfc_app.PfcApp).contenedor
}
