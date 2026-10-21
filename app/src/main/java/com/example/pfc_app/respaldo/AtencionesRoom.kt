package com.example.pfc_app.respaldo

import com.example.pfc_app.datos.FechasIso
import com.example.pfc_app.datos.RepositorioAtenciones
import com.example.pfc_app.datos.RepositorioVehiculos

/** Las atenciones por respaldar, leídas de la base Room del teléfono. */
class AtencionesRoom(
    private val atenciones: RepositorioAtenciones,
    private val vehiculos: RepositorioVehiculos,
    private val idInstalacion: () -> String
) : AtencionesPorRespaldar {
    override fun pendientes(uid: String): List<Long> = atenciones.pendientesDeRespaldo(uid)

    override fun documento(idAtencion: Long, uid: String): DocumentoRespaldo? {
        val guardada = atenciones.obtener(idAtencion) ?: return null
        val vehiculo = vehiculos.obtener(guardada.atencion.idVehiculo)
        val id = DocumentoAtencion.idDocumento(idInstalacion(), idAtencion)
        return DocumentoRespaldo(id, DocumentoAtencion.armar(guardada, vehiculo, uid, id, FechasIso.ahora()))
    }

    override fun marcarRespaldada(idAtencion: Long) = atenciones.marcarRespaldada(idAtencion)
}
