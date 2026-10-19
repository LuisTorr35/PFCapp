package com.example.pfc_app.ui

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.pfc_app.MainActivity
import com.example.pfc_app.modelos.TipoRegimen
import com.example.pfc_app.ui.EtiquetasPrueba as E
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * Prueba de extremo a extremo del modo demostración con las clases reales de captura, validación y modelo
 * (CatalogoDemoAssets, AdaptadorELM327Demo, ServicioAdquisicionImpl, ValidadorAtencion,
 * ProcesadorCaracteristicas, CargadorModelo y MotorInferencia):
 * registrar → Atender con la PRIMERA grabación de CatalogoDemoAssets.grabaciones() →
 * dictamen (rechazado y Repetir R2, o directo) → Diagnosticar → Guardar atención →
 * Historial → abrir la atención guardada → volver a Vehículos.
 *
 * Supone que la primera grabación expone los parámetros que pide el modelo (R2 y R3 con
 * rpm, carga, STFT, LTFT y avance), de modo que "Diagnosticar" queda habilitado, y que con
 * "Simular R2 rechazado" el dictamen sale rechazada por R2.
 */
@RunWith(AndroidJUnit4::class)
class FlujoDemostracionTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    private val pasos = PasosFlujo(compose)

    @get:Rule
    val reglas: RuleChain = RuleChain.outerRule(ReglaBaseLimpia(modoDemostracion = true)).around(compose)

    @Test
    fun r2RechazadoSeRepiteSeDiagnosticaYSeGuarda() {
        val codigo = pasos.registrarVehiculo("Hyundai", "Tucson", "2011", "4")
        pasos.atenderConGrabacion(codigo, simularRechazo = true, indice = 0)

        pasos.esperarDictamen("rechazada")
        compose.onNodeWithTag(E.TARJETA_DICTAMEN).assertTextContains("Repita solo R2", substring = true)
        compose.onNodeWithTag(E.BOTON_DIAGNOSTICAR).assertIsNotEnabled()

        compose.onNodeWithTag(E.botonRepetir(TipoRegimen.R2)).performScrollTo().performClick()
        pasos.esperarDictamenValido()
        compose.onNodeWithTag(E.botonRepetir(TipoRegimen.R2)).assertDoesNotExist()

        pasos.diagnosticarGuardarYAbrir()
    }

    @Test
    fun atencionSinRechazoSeDiagnosticaYSeGuarda() {
        val codigo = pasos.registrarVehiculo("Kia", "Picanto", "2019", "4")
        pasos.atenderConGrabacion(codigo, simularRechazo = false, indice = 0)
        pasos.esperarDictamenValido()
        pasos.diagnosticarGuardarYAbrir()
    }
}
