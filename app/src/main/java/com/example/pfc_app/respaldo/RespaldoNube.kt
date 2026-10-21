package com.example.pfc_app.respaldo

/**
 * Respaldo de las atenciones en la nube. Cada atención es un solo documento en
 * usuarios/{uid}/atenciones/{idDocumento}. El id es fijo y la escritura reemplaza el documento
 * (set), así que subir dos veces la misma atención no la duplica.
 */
interface RespaldoNube {
    suspend fun subir(uid: String, idDocumento: String, documento: Map<String, Any?>)

    /** Ids de los documentos de usuarios/{uid}/atenciones, leídos del servidor (para verificar). */
    suspend fun listarIds(uid: String): List<String>
}
