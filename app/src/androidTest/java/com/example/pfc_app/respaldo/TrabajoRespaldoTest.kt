package com.example.pfc_app.respaldo

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.ListenableWorker.Result
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import com.example.pfc_app.AppContenedor
import com.example.pfc_app.PfcApp
import com.example.pfc_app.cuenta.Cuenta
import com.example.pfc_app.modelos.Atencion
import com.example.pfc_app.modelos.Dictamen
import com.example.pfc_app.modelos.Lectura
import com.example.pfc_app.modelos.Regimen
import com.example.pfc_app.modelos.ResultadoDictamen
import com.example.pfc_app.modelos.TipoRegimen
import com.example.pfc_app.ui.CuentaFalsa
import com.example.pfc_app.ui.NubeFalsa
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * TrabajoRespaldo, el trabajo de WorkManager, con un RespaldoNube falso: sube las pendientes de
 * la cuenta con sesión, pide reintentar si la nube falla, no duplica y espera la red. La red se
 * simula con el TestDriver de WorkManager.
 */
@RunWith(AndroidJUnit4::class)
class TrabajoRespaldoTest {
    private val contexto: Context = ApplicationProvider.getApplicationContext()
    private val contenedor: AppContenedor get() = (contexto as PfcApp).contenedor
    private val nube = NubeFalsa()
    private lateinit var cuenta: CuentaFalsa
    private var idVehiculo = 0L

    @Before
    fun preparar() {
        contenedor.restablecerFabricas()
        contenedor.borrarBaseDatos()
        usarCuenta(CuentaFalsa.CUENTA_A)
        contenedor.proveedorNube = { nube }
        // WorkManager de prueba: ejecuta en el mismo hilo y la red la decide el TestDriver.
        val configuracion = Configuration.Builder().setMinimumLoggingLevel(Log.DEBUG).setExecutor(SynchronousExecutor()).build()
        WorkManagerTestInitHelper.initializeTestWorkManager(contexto, configuracion)
        idVehiculo = contenedor.repositorioVehiculos.registrar("Hyundai", "Tucson", 2011, 4).id
    }

    @After
    fun restablecer() {
        contenedor.restablecerFabricas()
        contenedor.borrarBaseDatos()
    }

    private fun usarCuenta(c: Cuenta?) {
        cuenta = CuentaFalsa(c)
        contenedor.proveedorCuenta = { cuenta }
    }

    private fun guardar(uid: String, minuto: Int): Long {
        val lecturas = List(5) { i -> Lectura(instante = i * 0.4, rpm = 760.0 + i, stft = 1.5, ltft = 2.0, cargaCalculada = 25.0) }
        val atencion = Atencion(
            idVehiculo = idVehiculo,
            fecha = "2026-10-21T10:%02d:00".format(minuto),
            regimenes = mapOf(TipoRegimen.R2 to Regimen(tipo = TipoRegimen.R2, lecturas = lecturas, muestrasUtiles = 5))
        )
        return contenedor.repositorioAtenciones.guardar(atencion, Dictamen(ResultadoDictamen.COMPLETA, emptyList()), null, uid)
    }

    private fun trabajar(): Result = runBlocking { TestListenableWorkerBuilder<TrabajoRespaldo>(contexto).build().doWork() }

    private fun pendientes(uid: String) = contenedor.repositorioAtenciones.pendientesDeRespaldo(uid)

    /** CoroutineWorker corre en su propio despachador: se espera a que el trabajo cambie de estado. */
    private fun esperar(id: UUID, condicion: (WorkInfo) -> Boolean): WorkInfo {
        val gestor = WorkManager.getInstance(contexto)
        val limite = System.currentTimeMillis() + 15_000
        while (true) {
            val info = checkNotNull(gestor.getWorkInfoById(id).get())
            if (condicion(info) || System.currentTimeMillis() > limite) return info
            Thread.sleep(100)
        }
    }

    @Test
    fun subeLasPendientesDeLaCuentaYTerminaBien() {
        val a1 = guardar(CuentaFalsa.CUENTA_A.uid, 1)
        val a2 = guardar(CuentaFalsa.CUENTA_A.uid, 2)

        assertEquals(Result.success(workDataOf(TrabajoRespaldo.SUBIDAS to 2)), trabajar())

        val instalacion = contenedor.preferencias.idInstalacion
        assertEquals(listOf("$instalacion-$a1", "$instalacion-$a2"), runBlocking { nube.listarIds(CuentaFalsa.CUENTA_A.uid) })
        assertTrue(pendientes(CuentaFalsa.CUENTA_A.uid).isEmpty())
        assertTrue(contenedor.repositorioAtenciones.listarPorVehiculo(idVehiculo).all { it.respaldada })
        val doc = nube.documentos.getValue(DocumentoAtencion.ruta(CuentaFalsa.CUENTA_A.uid, "$instalacion-$a1"))
        assertEquals(5, ((doc["regimenes"] as List<*>).single() as Map<*, *>).let { (it["lecturas"] as List<*>).size })
    }

    @Test
    fun siLaNubeFallaPideReintentarYLuegoSubeSinDuplicar() {
        guardar(CuentaFalsa.CUENTA_A.uid, 1)
        guardar(CuentaFalsa.CUENTA_A.uid, 2)
        nube.fallosPendientes.set(1)

        assertEquals(Result.retry(), trabajar())
        assertEquals(2, pendientes(CuentaFalsa.CUENTA_A.uid).size)

        assertEquals(Result.success(workDataOf(TrabajoRespaldo.SUBIDAS to 2)), trabajar())
        assertEquals("Una vez respaldadas, no se vuelven a subir", Result.success(workDataOf(TrabajoRespaldo.SUBIDAS to 0)), trabajar())
        assertEquals(2, nube.documentos.size)
        assertEquals(2, nube.escrituras.get())
    }

    @Test
    fun soloSubeLasDeLaCuentaConSesion() {
        val deA = guardar(CuentaFalsa.CUENTA_A.uid, 1)
        val deB = guardar(CuentaFalsa.CUENTA_B.uid, 2)
        val instalacion = contenedor.preferencias.idInstalacion

        usarCuenta(CuentaFalsa.CUENTA_B)
        assertEquals(Result.success(workDataOf(TrabajoRespaldo.SUBIDAS to 1)), trabajar())
        assertEquals(listOf("$instalacion-$deB"), runBlocking { nube.listarIds(CuentaFalsa.CUENTA_B.uid) })
        assertTrue(runBlocking { nube.listarIds(CuentaFalsa.CUENTA_A.uid) }.isEmpty())
        assertEquals(listOf(deA), pendientes(CuentaFalsa.CUENTA_A.uid))

        usarCuenta(CuentaFalsa.CUENTA_A)
        assertEquals(Result.success(workDataOf(TrabajoRespaldo.SUBIDAS to 1)), trabajar())
        assertEquals(listOf("$instalacion-$deA"), runBlocking { nube.listarIds(CuentaFalsa.CUENTA_A.uid) })
        assertEquals(listOf("$instalacion-$deB"), runBlocking { nube.listarIds(CuentaFalsa.CUENTA_B.uid) })
    }

    @Test
    fun sinSesionTerminaSinSubir() {
        guardar(CuentaFalsa.CUENTA_A.uid, 1)
        usarCuenta(null)

        assertEquals(Result.success(), trabajar())
        assertEquals(0, nube.escrituras.get())
    }

    @Test
    fun alGuardarSeEncolaUnTrabajoUnicoQueEsperaLaRed() {
        val gestor = WorkManager.getInstance(contexto)
        val driver = checkNotNull(WorkManagerTestInitHelper.getTestDriver(contexto))
        val lecturas = List(5) { i -> Lectura(instante = i * 0.4, rpm = 760.0, stft = 1.5) }
        val atencion = Atencion(
            idVehiculo = idVehiculo,
            fecha = "2026-10-21T11:00:00",
            regimenes = mapOf(TipoRegimen.R2 to Regimen(tipo = TipoRegimen.R2, lecturas = lecturas, muestrasUtiles = 5))
        )

        // Guardar con la sesión abierta encola el trabajo único; guardar otra no encola un segundo.
        contenedor.guardarAtencion(atencion, Dictamen(ResultadoDictamen.COMPLETA, emptyList()), null)
        contenedor.guardarAtencion(atencion.copy(fecha = "2026-10-21T11:05:00"), Dictamen(ResultadoDictamen.PARCIAL, emptyList()), null)
        val info = gestor.getWorkInfosForUniqueWork(TrabajoRespaldo.NOMBRE_UNICO).get().single()
        assertEquals(NetworkType.CONNECTED, info.constraints.requiredNetworkType)
        assertEquals("Sin red, espera", WorkInfo.State.ENQUEUED, info.state)
        assertEquals(0, nube.escrituras.get())
        assertEquals(2, pendientes(CuentaFalsa.CUENTA_A.uid).size)

        // Vuelve la red: corre y sube las dos.
        driver.setAllConstraintsMet(info.id)
        val final = esperar(info.id) { it.state.isFinished }
        assertEquals(WorkInfo.State.SUCCEEDED, final.state)
        assertEquals(2, final.outputData.getInt(TrabajoRespaldo.SUBIDAS, -1))
        assertEquals(2, nube.documentos.size)
        assertTrue(pendientes(CuentaFalsa.CUENTA_A.uid).isEmpty())
    }

    @Test
    fun conRedPeroSinRespuestaQuedaEnEsperaYReintenta() {
        val gestor = WorkManager.getInstance(contexto)
        val driver = checkNotNull(WorkManagerTestInitHelper.getTestDriver(contexto))
        guardar(CuentaFalsa.CUENTA_A.uid, 1)
        nube.fallosPendientes.set(1)

        TrabajoRespaldo.encolar(contexto)
        val id = gestor.getWorkInfosForUniqueWork(TrabajoRespaldo.NOMBRE_UNICO).get().single().id
        driver.setAllConstraintsMet(id)

        val tras = esperar(id) { it.state != WorkInfo.State.RUNNING && it.runAttemptCount > 0 }
        assertEquals("Falló: vuelve a la cola con espera exponencial", WorkInfo.State.ENQUEUED, tras.state)
        assertEquals(1, tras.runAttemptCount)
        assertEquals(0, nube.documentos.size)

        // Cumplida la espera, el reintento sube la atención una sola vez.
        driver.setInitialDelayMet(id)
        driver.setAllConstraintsMet(id)
        assertEquals(WorkInfo.State.SUCCEEDED, esperar(id) { it.state.isFinished }.state)
        assertEquals(1, nube.documentos.size)
        assertEquals(1, nube.escrituras.get())
    }
}
