package com.example.pfc_app.ui

import com.example.pfc_app.respaldo.DocumentoAtencion
import com.example.pfc_app.respaldo.RespaldoNube
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Nube en memoria para las pruebas instrumentadas, con la semántica de set de Firestore: el
 * mismo id reemplaza el documento. [fallosPendientes] hace fallar las próximas subidas.
 */
class NubeFalsa : RespaldoNube {
    val documentos = ConcurrentHashMap<String, Map<String, Any?>>()
    val escrituras = AtomicInteger()
    val fallosPendientes = AtomicInteger()

    override suspend fun subir(uid: String, idDocumento: String, documento: Map<String, Any?>) {
        if (fallosPendientes.getAndUpdate { if (it > 0) it - 1 else 0 } > 0) {
            throw IllegalStateException("UNAVAILABLE: sin respuesta del servidor")
        }
        escrituras.incrementAndGet()
        documentos[DocumentoAtencion.ruta(uid, idDocumento)] = documento
    }

    override suspend fun listarIds(uid: String): List<String> =
        documentos.keys.filter { it.startsWith("usuarios/$uid/") }.map { it.substringAfterLast('/') }.sorted()
}
