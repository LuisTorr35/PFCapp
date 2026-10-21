package com.example.pfc_app.respaldo

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.pfc_app.PfcApp
import com.example.pfc_app.Registro
import java.util.concurrent.TimeUnit

/**
 * Trabajo único de WorkManager que sube las atenciones pendientes de la cuenta con sesión
 * abierta. Solo corre con red; si una subida falla, se reintenta con espera exponencial.
 * Se encola al guardar una atención, al abrir la app y al iniciar sesión.
 */
class TrabajoRespaldo(contexto: Context, parametros: WorkerParameters) : CoroutineWorker(contexto, parametros) {
    override suspend fun doWork(): Result {
        val contenedor = (applicationContext as PfcApp).contenedor
        Registro.i(Registro.NUBE, "Trabajo de respaldo: intento ${runAttemptCount + 1}, con red")
        return when (val r = contenedor.subidaRespaldo().ejecutar()) {
            ResultadoRespaldo.SinSesion -> Result.success()
            is ResultadoRespaldo.Completo -> Result.success(workDataOf(SUBIDAS to r.subidas))
            is ResultadoRespaldo.Reintentar -> Result.retry()
        }
    }

    companion object {
        const val NOMBRE_UNICO = "respaldo_atenciones"
        const val SUBIDAS = "subidas"
        const val ESPERA_INICIAL_S = 30L

        fun solicitud(): OneTimeWorkRequest = OneTimeWorkRequestBuilder<TrabajoRespaldo>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, ESPERA_INICIAL_S, TimeUnit.SECONDS)
            .build()

        /**
         * KEEP: si ya hay uno esperando la red o en curso, no se encola otro. El que corre vuelve a
         * consultar las pendientes al terminar, así que también sube las guardadas mientras tanto.
         */
        fun encolar(contexto: Context) {
            WorkManager.getInstance(contexto).enqueueUniqueWork(NOMBRE_UNICO, ExistingWorkPolicy.KEEP, solicitud())
            Registro.i(Registro.NUBE, "Respaldo encolado (trabajo único, espera la red)")
        }
    }
}
