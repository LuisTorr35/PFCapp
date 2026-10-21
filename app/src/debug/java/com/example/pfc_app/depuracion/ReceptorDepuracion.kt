package com.example.pfc_app.depuracion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Process
import com.example.pfc_app.PfcApp
import com.example.pfc_app.Registro
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Solo en la variante debug: órdenes por adb para verificar el respaldo sin tocar la pantalla.
 * Escribe el resultado en Logcat con la etiqueta PFC.Nube. Solo las acepta de adb (permiso DUMP).
 *
 *   adb shell am broadcast -n com.example.pfc_app/.depuracion.ReceptorDepuracion -a com.example.pfc_app.ESTADO_RESPALDO
 *   adb shell am broadcast -n com.example.pfc_app/.depuracion.ReceptorDepuracion -a com.example.pfc_app.LISTAR_NUBE [--es uid <uid>]
 *
 *   adb shell am broadcast -n com.example.pfc_app/.depuracion.ReceptorDepuracion -a com.example.pfc_app.USAR_EMULADOR [--es host 127.0.0.1:8080]
 *
 * LISTAR_NUBE lee del servidor usuarios/{uid}/atenciones con la sesión abierta; sin uid, el de
 * la sesión. Con el uid de otra cuenta, las reglas de Firestore deben negar la lectura.
 * USAR_EMULADOR apunta Firestore al emulador (sin host, vuelve al proyecto real) y cierra la app:
 * la configuración se aplica al volver a abrirla.
 */
class ReceptorDepuracion : BroadcastReceiver() {
    override fun onReceive(contexto: Context, intent: Intent) {
        val contenedor = (contexto.applicationContext as PfcApp).contenedor
        val pendiente = goAsync()
        contenedor.alcance.launch(Dispatchers.IO) {
            var cerrarApp = false
            try {
                val sesion = contenedor.cuenta.actual.value
                when (intent.action) {
                    ACCION_ESTADO -> {
                        val base = contenedor.baseDatos.openHelper.readableDatabase
                        val filas = base.query(CONSULTA_ESTADO).use { c ->
                            buildList { while (c.moveToNext()) add(estado(c.getLong(0), c.getInt(1) == 1, c.getString(2), c.getString(3))) }
                        }
                        Registro.i(
                            Registro.NUBE,
                            "Estado local: sesión ${sesion?.correoOculto} (uid ${sesion?.uid}), " +
                                "instalación ${contenedor.preferencias.idInstalacion}, atenciones $filas"
                        )
                    }

                    ACCION_EMULADOR -> {
                        val host = intent.getStringExtra("host")
                        ConfigDepuracion.usarEmuladorFirestore(contexto, host)
                        Registro.w(Registro.NUBE, "Firestore: ${host?.let { "emulador $it" } ?: "proyecto real"} desde el próximo inicio")
                        cerrarApp = true
                    }

                    ACCION_LISTAR -> {
                        val uid = intent.getStringExtra("uid") ?: sesion?.uid
                        if (uid == null) {
                            Registro.i(Registro.NUBE, "Nube: no hay sesión")
                        } else {
                            val ids = try {
                                contenedor.nube.listarIds(uid).sorted().toString()
                            } catch (e: Exception) {
                                "lectura negada o fallida: ${e.javaClass.simpleName}: ${e.message}"
                            }
                            Registro.i(Registro.NUBE, "Nube usuarios/$uid/atenciones (sesión uid ${sesion?.uid}): $ids")
                        }
                    }
                }
            } catch (e: Exception) {
                Registro.e(Registro.NUBE, "Orden de depuración fallida", e)
            } finally {
                pendiente.finish()
                if (cerrarApp) Process.killProcess(Process.myPid())
            }
        }
    }

    /** "3:respaldada 2026-10-21T10:00:00 uid=…" o "4:pendiente uid=…". */
    private fun estado(id: Long, respaldada: Boolean, cuando: String?, uid: String?): String =
        "$id:" + (if (respaldada) "respaldada $cuando" else "pendiente") + " uid=$uid"

    private companion object {
        const val CONSULTA_ESTADO = "SELECT id_atencion, respaldada, respaldada_en, uid_respaldo FROM atencion ORDER BY id_atencion"
        const val ACCION_ESTADO = "com.example.pfc_app.ESTADO_RESPALDO"
        const val ACCION_LISTAR = "com.example.pfc_app.LISTAR_NUBE"
        const val ACCION_EMULADOR = "com.example.pfc_app.USAR_EMULADOR"
    }
}
