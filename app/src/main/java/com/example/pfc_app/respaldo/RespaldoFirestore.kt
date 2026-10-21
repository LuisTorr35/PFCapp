package com.example.pfc_app.respaldo

import com.example.pfc_app.Registro
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.MemoryCacheSettings
import com.google.firebase.firestore.Source
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout

/**
 * Respaldo en Cloud Firestore (plan Spark). Room es la base principal: Firestore usa una caché
 * en memoria, sin copia de las atenciones ni cola de escrituras en el disco del teléfono.
 * [emulador] ("host:puerto") solo lo usa la variante debug, para probar con el emulador de Firestore.
 */
class RespaldoFirestore(private val emulador: String? = null) : RespaldoNube {
    private val bd: FirebaseFirestore by lazy {
        FirebaseFirestore.getInstance().apply {
            emulador?.let { e ->
                useEmulator(e.substringBefore(':'), e.substringAfter(':').toInt())
                Registro.w(Registro.NUBE, "Firestore apunta al emulador $e (solo debug)")
            }
            firestoreSettings = FirebaseFirestoreSettings.Builder()
                .setLocalCacheSettings(MemoryCacheSettings.newBuilder().build())
                .build()
        }
    }

    private fun atenciones(uid: String): CollectionReference = bd.collection(DocumentoAtencion.COLECCION_USUARIOS)
        .document(uid)
        .collection(DocumentoAtencion.COLECCION_ATENCIONES)

    override suspend fun subir(uid: String, idDocumento: String, documento: Map<String, Any?>) {
        // Sin respuesta del servidor en ese tiempo, el trabajo se reintenta más tarde.
        withTimeout(TIEMPO_MAXIMO_MS) {
            val conHora = documento + (DocumentoAtencion.CAMPO_SUBIDA_SERVIDOR to FieldValue.serverTimestamp())
            atenciones(uid).document(idDocumento).set(conHora).await()
        }
    }

    override suspend fun listarIds(uid: String): List<String> = withTimeout(TIEMPO_MAXIMO_MS) {
        atenciones(uid).get(Source.SERVER).await().documents.map { it.id }
    }

    private companion object {
        const val TIEMPO_MAXIMO_MS = 60_000L
    }
}
