package com.example.pfc_app.ui

import android.os.SystemClock
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.Espresso
import com.example.pfc_app.ui.EtiquetasPrueba as E
import org.junit.Assert.assertTrue

/**
 * Pasos del flujo de una atención en modo demostración, compartidos por la prueba de
 * extremo a extremo (clases reales) y la prueba con falsos.
 */
class PasosFlujo(private val compose: AndroidComposeTestRule<*, *>) {

    /** Registra el vehículo y espera la confirmación con [codigoEsperado]. */
    fun registrarVehiculo(marca: String, modelo: String, anio: String, cilindros: String, codigoEsperado: String = "VEH-001"): String {
        compose.onNodeWithTag(E.LISTA_VEHICULOS).performScrollToNode(hasTestTag(E.CAMPO_MARCA))
        compose.onNodeWithTag(E.CAMPO_MARCA).performTextInput(marca)
        compose.onNodeWithTag(E.CAMPO_MODELO).performTextInput(modelo)
        compose.onNodeWithTag(E.CAMPO_ANIO).performTextInput(anio)
        compose.onNodeWithTag(E.CAMPO_CILINDROS).performTextInput(cilindros)
        Espresso.closeSoftKeyboard()
        compose.onNodeWithTag(E.BOTON_REGISTRAR).performScrollTo().performClick()
        esperar(10_000, "vehículo registrado") {
            existe(hasTestTag(E.MENSAJE_REGISTRO) and hasText(codigoEsperado, substring = true))
        }
        return codigoEsperado
    }

    /** "Atender" en modo demostración: elige la grabación [indice] y, si corresponde, el R2 corto. */
    fun atenderConGrabacion(codigo: String, simularRechazo: Boolean, indice: Int = 0) {
        compose.onNodeWithTag(E.LISTA_VEHICULOS).performScrollToNode(hasTestTag(E.botonAtender(codigo)))
        compose.onNodeWithTag(E.botonAtender(codigo)).performClick()
        esperar(15_000, "diálogo de grabación") { existe(hasTestTag(E.DIALOGO_GRABACION)) }
        compose.onNodeWithTag(E.opcionGrabacion(indice)).performClick()
        if (simularRechazo) {
            compose.onNodeWithTag(E.CASILLA_RECHAZO).performClick()
            compose.onNodeWithTag(E.CASILLA_RECHAZO).assertIsOn()
        }
        compose.onNodeWithTag(E.BOTON_CONFIRMAR_GRABACION).performClick()
        esperar(15_000, "pantalla de Captura con el aviso de demostración") { existe(hasTestTag(E.AVISO_DEMO)) }
        compose.onNodeWithTag(E.AVISO_DEMO).assertIsDisplayed()
        compose.onNodeWithTag(E.AVISO_DEMO).assertTextContains("Modo demostración", substring = true)
        compose.onNodeWithTag(E.AVISO_DEMO).assertTextContains("Se reproduce la atención grabada", substring = true)
    }

    fun esperarDictamen(resultado: String, tiempoMs: Long = TIEMPO_CAPTURA) {
        esperar(tiempoMs, "dictamen $resultado") {
            existe(hasTestTag(E.TARJETA_DICTAMEN) and hasText("Dictamen: $resultado", substring = true))
        }
    }

    fun esperarDictamenValido(tiempoMs: Long = TIEMPO_CAPTURA) {
        esperar(tiempoMs, "dictamen completa o parcial") {
            existe(
                hasTestTag(E.TARJETA_DICTAMEN) and
                    (hasText("Dictamen: completa", substring = true) or hasText("Dictamen: parcial", substring = true))
            )
        }
    }

    /** Diagnosticar → clase y probabilidad → Guardar atención → Historial → abrir → volver a Vehículos. */
    fun diagnosticarGuardarYAbrir(): String {
        val nombreClase = diagnosticar()
        guardarYAbrir(nombreClase)
        return nombreClase
    }

    /** Pulsa "Diagnosticar" y devuelve el nombre de la clase mostrada. */
    fun diagnosticar(): String {
        esperar(10_000, "Diagnosticar habilitado") { existe(hasTestTag(E.BOTON_DIAGNOSTICAR) and isEnabled()) }
        compose.onNodeWithTag(E.BOTON_DIAGNOSTICAR).performClick()

        // Diagnóstico de la atención en curso: clase y probabilidad.
        esperar(30_000, "pantalla Diagnóstico") { existe(hasTestTag(E.NOMBRE_CLASE)) }
        val nombreClase = texto(E.NOMBRE_CLASE)
        assertTrue("El nombre de la clase no debe estar vacío", nombreClase.isNotBlank())
        compose.onNodeWithTag(E.TEXTO_PROBABILIDAD).assertTextContains("probabilidad", substring = true)
        compose.onNodeWithTag(E.TEXTO_PROBABILIDAD).assertTextContains("%", substring = true)
        return nombreClase
    }

    /** "Guardar atención" → Historial con la fila → abrirla → volver a Vehículos. */
    fun guardarYAbrir(nombreClase: String) {
        compose.onNodeWithTag(E.BOTON_GUARDAR_ATENCION).performClick()

        // Historial con la atención guardada.
        esperar(15_000, "historial con la atención") { existe(hasTestTag(E.FILA_ATENCION)) }
        compose.onNodeWithTag(E.AVISO_HISTORIAL).assertExists()
        compose.onAllNodesWithTag(E.FILA_ATENCION).assertCountEquals(1)
        compose.onAllNodesWithTag(E.FILA_ATENCION)[0].assertTextContains(nombreClase, substring = true)

        // Abrir la atención: Diagnóstico leído de la base, sin "Guardar atención".
        compose.onAllNodesWithTag(E.FILA_ATENCION)[0].performClick()
        esperar(15_000, "diagnóstico guardado") {
            existe(hasTestTag(E.NOMBRE_CLASE)) && !existe(hasTestTag(E.FILA_ATENCION))
        }
        compose.onNodeWithTag(E.NOMBRE_CLASE).assertTextEquals(nombreClase)
        compose.onNodeWithTag(E.BOTON_GUARDAR_ATENCION).assertDoesNotExist()

        // Volver: Diagnóstico → Historial → Vehículos.
        volverA("historial") { existe(hasTestTag(E.FILA_ATENCION)) }
        volverA("Vehículos") { existe(hasTestTag(E.BOTON_REGISTRAR)) }
    }

    /** Pulsa "←" y espera la pantalla anterior (con una sola barra superior en pantalla). */
    fun volverA(que: String, condicion: () -> Boolean) {
        esperar(10_000, "un solo botón volver") { cuenta(E.BOTON_VOLVER) == 1 }
        compose.onNodeWithTag(E.BOTON_VOLVER).performClick()
        esperar(10_000, "volver a $que") { condicion() && cuenta(E.BOTON_VOLVER) <= 1 }
    }

    fun existe(matcher: SemanticsMatcher): Boolean =
        compose.onAllNodes(matcher).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()

    fun cuenta(tag: String): Int = compose.onAllNodesWithTag(tag).fetchSemanticsNodes(atLeastOneRootRequired = false).size

    fun texto(tag: String): String = compose.onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)
        ?.joinToString("") { it.text }.orEmpty()

    /**
     * Espera a que se cumpla [condicion]. Mientras tanto pulsa "Continuar" si la captura lo
     * pide, y falla de inmediato si aparece la tarjeta de error (con su texto en el mensaje).
     */
    fun esperar(tiempoMs: Long, que: String, condicion: () -> Boolean) {
        val limite = SystemClock.uptimeMillis() + tiempoMs
        while (true) {
            if (condicion()) return
            if (existe(hasTestTag(E.BOTON_CONTINUAR) and isEnabled())) {
                compose.onNodeWithTag(E.BOTON_CONTINUAR).performClick()
            }
            if (existe(hasTestTag(E.TARJETA_ERROR))) {
                val nodo = compose.onNodeWithTag(E.TARJETA_ERROR).fetchSemanticsNode()
                val detalle = nodo.config.getOrNull(SemanticsProperties.Text)?.joinToString(" / ") { it.text }
                throw AssertionError("Error en pantalla mientras se esperaba '$que': $detalle")
            }
            if (SystemClock.uptimeMillis() > limite) throw AssertionError("Tiempo agotado esperando: $que")
            Thread.sleep(200)
        }
    }

    companion object {
        /** La reproducción es acelerada, pero R1, R2 y R3 juntos tardan varios segundos. */
        const val TIEMPO_CAPTURA = 180_000L
    }
}
