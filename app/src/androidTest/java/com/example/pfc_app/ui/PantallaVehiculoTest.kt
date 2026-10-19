package com.example.pfc_app.ui

import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.pfc_app.MainActivity
import com.example.pfc_app.PfcApp
import com.example.pfc_app.ui.EtiquetasPrueba as E
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** Pantalla Vehículos (CU-01) y Historial vacío: no dependen de la captura ni del modelo. */
@RunWith(AndroidJUnit4::class)
class PantallaVehiculoTest {
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val reglas: RuleChain = RuleChain.outerRule(ReglaBaseLimpia(modoDemostracion = true)).around(compose)

    @Test
    fun registroSenalaDatosFaltantesYAsignaCodigos() {
        compose.onNodeWithTag(E.BOTON_REGISTRAR).performScrollTo().performClick()
        compose.onNodeWithText("Ingrese la marca").assertExists()
        compose.onNodeWithText("Ingrese el modelo").assertExists()
        compose.onNodeWithText("Ingrese el año").assertExists()
        compose.onNodeWithText("Ingrese los cilindros").assertExists()

        registrar("Nissan", "Sentra", "1970", "4")
        compose.onNodeWithText("El año debe ser un número", substring = true).assertExists()

        registrarSoloAnio("2014")
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasTestTag(E.MENSAJE_REGISTRO) and hasText("VEH-001", substring = true))
                .fetchSemanticsNodes().isNotEmpty()
        }

        registrar("Hyundai", "Tucson", "2011", "4")
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasTestTag(E.MENSAJE_REGISTRO) and hasText("VEH-002", substring = true))
                .fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithTag(E.LISTA_VEHICULOS).performScrollToNode(hasTestTag(E.filaVehiculo("VEH-001")))
        compose.onNodeWithTag(E.filaVehiculo("VEH-001")).assertTextContains("VEH-001 · Nissan Sentra 2014")
        compose.onNodeWithTag(E.filaVehiculo("VEH-001")).assertTextContains("4 cilindros · familia pendiente")

        // Búsqueda por código.
        compose.onNodeWithTag(E.LISTA_VEHICULOS).performScrollToNode(hasTestTag(E.CAMPO_BUSCAR))
        compose.onNodeWithTag(E.CAMPO_BUSCAR).performTextInput("2")
        Espresso.closeSoftKeyboard()
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasTestTag(E.filaVehiculo("VEH-001"))).fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithTag(E.LISTA_VEHICULOS).performScrollToNode(hasTestTag(E.filaVehiculo("VEH-002")))
        compose.onNodeWithTag(E.filaVehiculo("VEH-002")).assertExists()
    }

    @Test
    fun interruptorDeModoDemostracionSeGuarda() {
        val prefs = ApplicationProvider.getApplicationContext<PfcApp>().contenedor.preferencias
        compose.onNodeWithTag(E.INTERRUPTOR_DEMO).assertIsOn()
        compose.onNodeWithTag(E.INTERRUPTOR_DEMO).performClick()
        compose.onNodeWithTag(E.INTERRUPTOR_DEMO).assertIsOff()
        compose.onNodeWithTag(E.BOTON_ELEGIR_ADAPTADOR).assertExists()
        assertFalse(prefs.modoDemostracion)
        compose.onNodeWithTag(E.INTERRUPTOR_DEMO).performClick()
        compose.onNodeWithTag(E.INTERRUPTOR_DEMO).assertIsOn()
        compose.onNodeWithTag(E.BOTON_ELEGIR_ADAPTADOR).assertDoesNotExist()
        assertTrue(prefs.modoDemostracion)
    }

    @Test
    fun historialVacioMuestraElVehiculo() {
        registrar("Kia", "Picanto", "2019", "4")
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasTestTag(E.MENSAJE_REGISTRO) and hasText("VEH-001", substring = true))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(E.LISTA_VEHICULOS).performScrollToNode(hasTestTag(E.botonHistorial("VEH-001")))
        compose.onNodeWithTag(E.botonHistorial("VEH-001")).performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasTestTag(E.HISTORIAL_VACIO)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(E.TARJETA_VEHICULO).assertTextContains("Kia Picanto 2019")
        compose.onNodeWithTag(E.TARJETA_VEHICULO).assertTextContains("4 cilindros · familia de gestión pendiente")
        compose.onNodeWithTag(E.BOTON_VOLVER).performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasTestTag(E.BOTON_REGISTRAR)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun registrar(marca: String, modelo: String, anio: String, cilindros: String) {
        compose.onNodeWithTag(E.LISTA_VEHICULOS).performScrollToNode(hasTestTag(E.CAMPO_MARCA))
        compose.onNodeWithTag(E.CAMPO_MARCA).performTextInput(marca)
        compose.onNodeWithTag(E.CAMPO_MODELO).performTextInput(modelo)
        compose.onNodeWithTag(E.CAMPO_ANIO).performTextInput(anio)
        compose.onNodeWithTag(E.CAMPO_CILINDROS).performTextInput(cilindros)
        Espresso.closeSoftKeyboard()
        compose.onNodeWithTag(E.BOTON_REGISTRAR).performScrollTo().performClick()
    }

    private fun registrarSoloAnio(anio: String) {
        compose.onNodeWithTag(E.LISTA_VEHICULOS).performScrollToNode(hasTestTag(E.CAMPO_ANIO))
        compose.onNodeWithTag(E.CAMPO_ANIO).performTextClearance()
        compose.onNodeWithTag(E.CAMPO_ANIO).performTextInput(anio)
        Espresso.closeSoftKeyboard()
        compose.onNodeWithTag(E.BOTON_REGISTRAR).performScrollTo().performClick()
    }
}
