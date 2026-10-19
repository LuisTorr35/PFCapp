package com.example.pfc_app.ui

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.pfc_app.AppContenedor
import com.example.pfc_app.MainActivity
import com.example.pfc_app.modelos.TipoRegimen
import com.example.pfc_app.ui.EtiquetasPrueba as E
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * El flujo completo de la interfaz con falsos en lugar de las clases reales de captura, validación y modelo:
 * comprueba la orquestación de CapturaViewModel, la navegación, el guardado en la base
 * y la lectura del historial sin depender de la captura ni del modelo reales.
 */
@RunWith(AndroidJUnit4::class)
class FlujoConFalsosTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    private val pasos = PasosFlujo(compose)

    private val servicios = mutableListOf<ServicioFalso>()
    private val adaptadores = mutableListOf<AdaptadorFalso>()
    private var sinParametros = false

    private fun instalarFalsos(c: AppContenedor) {
        c.proveedorCatalogo = { Falsos.catalogo }
        c.proveedorContrato = { Falsos.contrato }
        c.fabricaAdaptador = { AdaptadorFalso().also { adaptadores += it } }
        c.validarAtencion = { a -> Falsos.validar(a, sinParametros) }
        c.diagnosticarAtencion = { a -> Falsos.diagnosticar(a) }
    }

    @get:Rule
    val reglas: RuleChain = RuleChain.outerRule(ReglaBaseLimpia(true, ::instalarFalsos)).around(compose)

    /** El servicio se crea al abrir la Captura; su R2 sale corto según la casilla. */
    private fun usarServicio(c: AppContenedor, r2Corto: Boolean) {
        c.fabricaServicio = { ServicioFalso(r2Corto).also { servicios += it } }
    }

    private val contenedor: AppContenedor
        get() = (compose.activity.application as com.example.pfc_app.PfcApp).contenedor

    @Test
    fun rechazoRepeticionDiagnosticoGuardadoEHistorial() {
        usarServicio(contenedor, r2Corto = true)
        val codigo = pasos.registrarVehiculo("Hyundai", "Tucson", "2011", "4")
        pasos.atenderConGrabacion(codigo, simularRechazo = true, indice = 0)
        compose.onNodeWithTag(E.AVISO_DEMO).assertTextContains("ATN-037", substring = true)

        pasos.esperarDictamen("rechazada", 20_000)
        compose.onNodeWithTag(E.TARJETA_DICTAMEN).assertTextContains("R2 no reunió las 45 muestras útiles.", substring = true)
        compose.onNodeWithTag(E.TARJETA_DICTAMEN).assertTextContains("Repita solo R2; R3 se conserva.", substring = true)
        compose.onNodeWithTag(E.tarjetaRegimen(TipoRegimen.R1)).assertTextContains("No capturado · el motor llegó caliente")
        compose.onNodeWithTag(E.tarjetaRegimen(TipoRegimen.R2)).assertTextContains("Rechazado · 31 de 45 muestras útiles")
        compose.onNodeWithTag(E.tarjetaRegimen(TipoRegimen.R3)).assertTextContains("Capturado · 50 muestras útiles")
        compose.onNodeWithTag(E.TARJETA_LECTURA_INICIAL).assertTextContains("Códigos: P0303 almacenado")
        compose.onNodeWithTag(E.TARJETA_LECTURA_INICIAL).assertTextContains("Fallos de encendido (modo 06): cilindro 3, 12")
        compose.onNodeWithTag(E.TARJETA_ADAPTADOR).assertTextContains("Reproducción de atención grabada · ISO 15765-4 (CAN 11/500)")
        compose.onNodeWithTag(E.BOTON_DIAGNOSTICAR).assertIsNotEnabled()

        compose.onNodeWithTag(E.botonRepetir(TipoRegimen.R2)).performScrollTo().performClick()
        pasos.esperarDictamen("completa", 20_000)
        compose.onNodeWithTag(E.TARJETA_DICTAMEN).assertTextContains("Se cumplen todas las reglas de validación.", substring = true)
        compose.onNodeWithTag(E.tarjetaRegimen(TipoRegimen.R2)).assertTextContains("Capturado · 46 muestras útiles")
        compose.onNodeWithTag(E.BOTON_DIAGNOSTICAR).assertIsEnabled()

        val nombre = pasos.diagnosticar()
        assertEquals("Encendido y combustión", nombre)
        compose.onNodeWithTag(E.TEXTO_PROBABILIDAD).assertTextContains("Clase C1 · probabilidad 72 %")
        compose.onNodeWithTag(E.PARAMETRO_DESVIADO).assertTextContains("Variación de rpm en ralentí caliente")
        compose.onNodeWithTag(E.PARAMETRO_DESVIADO).assertTextContains("↑ Mayor que sin falla (14,9 frente a 3,5 rpm)")
        compose.onNodeWithTag(E.TEXTO_CODIGOS).assertTextContains("P0303 almacenado. Se muestra como contexto", substring = true)
        compose.onNodeWithText("Atención completa · modelo 0.1.0-provisional").assertExists()
        compose.onNodeWithText("Modelo provisional").assertExists()

        pasos.guardarYAbrir(nombre)

        // De vuelta en Vehículos: la familia detectada en la conexión quedó guardada.
        compose.onNodeWithTag(E.LISTA_VEHICULOS).performScrollToNode(hasTestTag(E.filaVehiculo(codigo)))
        compose.onNodeWithTag(E.filaVehiculo(codigo)).assertTextContains("4 cilindros · MAP")

        // Historial desde Vehículos, con el texto de la maqueta.
        compose.onNodeWithTag(E.botonHistorial(codigo)).performClick()
        pasos.esperar(10_000, "historial") { pasos.existe(hasTestTag(E.FILA_ATENCION)) }
        compose.onAllNodesWithTag(E.FILA_ATENCION)[0].assertTextContains("Completa · Encendido y combustión, 72 %")
        compose.onNodeWithTag(E.TARJETA_VEHICULO).assertTextContains("4 cilindros · presión del múltiple (MAP)")
        compose.onNodeWithTag(E.AVISO_HISTORIAL).assertDoesNotExist()

        // Al salir de la Captura se cerró el adaptador.
        pasos.esperar(5_000, "adaptador cerrado") { adaptadores.all { it.cerrado } && servicios.all { it.finalizado } }

        // La atención guardada en la base tiene sus filas.
        val resumen = contenedor.repositorioAtenciones.listarPorVehiculo(1).single()
        val guardada = contenedor.repositorioAtenciones.obtener(resumen.idAtencion)!!
        assertEquals(setOf(TipoRegimen.R2, TipoRegimen.R3), guardada.atencion.regimenes.keys)
        assertEquals(46, guardada.atencion.regimen(TipoRegimen.R2)!!.lecturas.size)
        assertEquals("C1", guardada.diagnostico!!.clase)
        assertEquals(1, guardada.diagnostico!!.parametrosDesviados.size)
    }

    @Test
    fun sinParametrosDelModeloSeGuardaSinDiagnostico() {
        sinParametros = true
        usarServicio(contenedor, r2Corto = false)
        val codigo = pasos.registrarVehiculo("Chevrolet", "Spark", "2012", "4")
        pasos.atenderConGrabacion(codigo, simularRechazo = false, indice = 1)
        compose.onNodeWithTag(E.AVISO_DEMO).assertTextContains("ATN-012", substring = true)

        pasos.esperarDictamen("completa", 20_000)
        compose.onNodeWithTag(E.TARJETA_DICTAMEN)
            .assertTextContains("El vehículo no expone los parámetros que pide el modelo", substring = true)
        compose.onNodeWithTag(E.BOTON_DIAGNOSTICAR).assertIsNotEnabled()
        compose.onNodeWithTag(E.BOTON_GUARDAR_SIN_DIAGNOSTICO).assertIsEnabled().performClick()

        pasos.esperar(10_000, "historial") { pasos.existe(hasTestTag(E.FILA_ATENCION)) }
        compose.onNodeWithTag(E.AVISO_HISTORIAL).assertTextContains("sin diagnóstico", substring = true)
        compose.onAllNodesWithTag(E.FILA_ATENCION)[0].assertTextContains("Completa · Sin diagnóstico")
        compose.onAllNodesWithTag(E.FILA_ATENCION)[0].performClick()
        pasos.esperar(5_000, "aviso sin diagnóstico") { pasos.existe(hasTestTag(E.DIALOGO_SIN_DIAGNOSTICO)) }
        compose.onNodeWithText("Aceptar").performClick()

        // Volver desde el historial lleva a Vehículos.
        pasos.volverA("Vehículos") { pasos.existe(hasTestTag(E.BOTON_REGISTRAR)) }
    }

    @Test
    fun salirDeLaCapturaPideConfirmarYDescarta() {
        usarServicio(contenedor, r2Corto = true)
        val codigo = pasos.registrarVehiculo("Haval", "H2", "2020", "4")
        pasos.atenderConGrabacion(codigo, simularRechazo = true)
        pasos.esperarDictamen("rechazada", 20_000)

        Espresso.pressBack()
        pasos.esperar(5_000, "diálogo de salida") { pasos.existe(hasText("¿Salir de la captura?")) }
        compose.onNodeWithText("Salir y descartar").performClick()
        pasos.esperar(10_000, "Vehículos") { pasos.existe(hasTestTag(E.BOTON_REGISTRAR)) }
        pasos.esperar(5_000, "adaptador cerrado") { adaptadores.isNotEmpty() && adaptadores.all { it.cerrado } }
        assertTrue(contenedor.repositorioAtenciones.listarPorVehiculo(1).isEmpty())
    }
}
