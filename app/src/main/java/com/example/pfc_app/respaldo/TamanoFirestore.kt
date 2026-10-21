package com.example.pfc_app.respaldo

/**
 * Tamaño de un documento según las reglas de almacenamiento de Cloud Firestore
 * (firebase.google.com/docs/firestore/storage-size): el nombre del documento, más cada nombre
 * de campo y su valor, más 32 bytes. Firestore rechaza documentos de más de 1 MiB.
 */
object TamanoFirestore {
    const val LIMITE_FIRESTORE = 1_048_576

    /** [ruta] es la del documento, por ejemplo "usuarios/uid/atenciones/id". */
    fun documento(ruta: String, campos: Map<String, Any?>): Int = nombre(ruta) + campos(campos) + 32

    /** Cada id de colección y de documento de la ruta como texto, más 16 bytes. */
    fun nombre(ruta: String): Int = ruta.split('/').sumOf(::texto) + 16

    /** Un texto ocupa sus bytes en UTF-8 más 1. */
    fun texto(s: String): Int = s.toByteArray(Charsets.UTF_8).size + 1

    fun valor(v: Any?): Int = when (v) {
        null, is Boolean -> 1

        is Number -> 8

        is String -> texto(v)

        is Map<*, *> -> v.entries.sumOf { texto(it.key.toString()) + valor(it.value) }

        is List<*> -> v.sumOf(::valor)

        // Marca de tiempo del servidor (FieldValue.serverTimestamp()).
        else -> 8
    }

    private fun campos(c: Map<String, Any?>): Int = c.entries.sumOf { texto(it.key) + valor(it.value) }
}
