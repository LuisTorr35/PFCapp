package com.example.pfc_app.respaldo

import com.example.pfc_app.Registro
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Un documento listo para subir: su id fijo y sus campos. */
data class DocumentoRespaldo(val id: String, val campos: Map<String, Any?>)

/** Lo que el respaldo necesita de la base local. */
interface AtencionesPorRespaldar {
    /** Atenciones guardadas con la cuenta [uid] que siguen pendientes, de la más antigua a la más reciente. */
    fun pendientes(uid: String): List<Long>

    /** El documento de la atención, o null si ya no está en la base. */
    fun documento(idAtencion: Long, uid: String): DocumentoRespaldo?

    fun marcarRespaldada(idAtencion: Long)
}

sealed interface ResultadoRespaldo {
    /** Sin sesión no se sube nada: las atenciones esperan a su cuenta. */
    data object SinSesion : ResultadoRespaldo

    data class Completo(val subidas: Int, val omitidas: Int = 0) : ResultadoRespaldo

    /** Falló una subida: lo ya subido queda marcado y el resto se reintenta más tarde. */
    data class Reintentar(val subidas: Int, val causa: Exception) : ResultadoRespaldo
}

/**
 * Sube las atenciones pendientes de la cuenta con sesión abierta y las marca como respaldadas.
 * La base es del teléfono: las pendientes de otra cuenta no se tocan. Si la sesión cambia a
 * mitad de camino, se detiene. Vuelve a consultar al terminar por si se guardó otra mientras
 * subía. Una atención solo se marca después de que la nube confirma la escritura.
 */
class SubidaRespaldo(
    private val atenciones: AtencionesPorRespaldar,
    private val nube: RespaldoNube,
    private val uidActual: () -> String?,
    private val contextoBase: CoroutineContext = Dispatchers.IO
) {
    suspend fun ejecutar(): ResultadoRespaldo {
        val uid = uidActual()
        if (uid == null) {
            Registro.i(Registro.NUBE, "Respaldo: no hay sesión, no se sube nada")
            return ResultadoRespaldo.SinSesion
        }
        var subidas = 0
        var omitidas = 0
        val vistas = mutableSetOf<Long>()
        while (true) {
            val pendientes = withContext(contextoBase) { atenciones.pendientes(uid) }.filter { it !in vistas }
            if (pendientes.isEmpty()) break
            Registro.i(Registro.NUBE, "Respaldo de uid $uid: ${pendientes.size} pendientes $pendientes")
            for (id in pendientes) {
                vistas += id
                val doc = withContext(contextoBase) { atenciones.documento(id, uid) } ?: continue
                // Con otra sesión, Firestore escribiría con la cuenta nueva: se detiene antes de subir.
                if (uidActual() != uid) {
                    Registro.w(Registro.NUBE, "La sesión cambió durante el respaldo: se detiene ($subidas subidas)")
                    return ResultadoRespaldo.Completo(subidas, omitidas)
                }
                val bytes = DocumentoAtencion.bytes(uid, doc.id, doc.campos)
                if (bytes > DocumentoAtencion.LIMITE_BYTES) {
                    Registro.e(Registro.NUBE, "Atención $id: el documento ocupa $bytes bytes y no cabe; queda pendiente")
                    omitidas++
                    continue
                }
                try {
                    nube.subir(uid, doc.id, doc.campos)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Registro.w(Registro.NUBE, "Atención $id: falló la subida, se reintentará ($subidas subidas antes)", e)
                    return ResultadoRespaldo.Reintentar(subidas, e)
                }
                withContext(contextoBase) { atenciones.marcarRespaldada(id) }
                subidas++
                Registro.i(Registro.NUBE, "Atención $id respaldada en ${DocumentoAtencion.ruta(uid, doc.id)} ($bytes bytes)")
            }
        }
        Registro.i(Registro.NUBE, "Respaldo terminado: $subidas subidas" + if (omitidas > 0) ", $omitidas omitidas" else "")
        return ResultadoRespaldo.Completo(subidas, omitidas)
    }
}
