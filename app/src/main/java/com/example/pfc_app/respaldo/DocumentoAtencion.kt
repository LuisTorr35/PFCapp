package com.example.pfc_app.respaldo

import com.example.pfc_app.datos.AtencionGuardada
import com.example.pfc_app.modelos.Lectura
import com.example.pfc_app.modelos.Regimen
import com.example.pfc_app.modelos.TipoRegimen
import com.example.pfc_app.modelos.Vehiculo
import kotlin.math.roundToLong

/**
 * Arma el documento de Firestore de una atención guardada: vehículo, dictamen, diagnóstico con
 * sus parámetros desviados, códigos, modo 06 y regímenes con sus lecturas. Los nombres de los
 * campos son los de las columnas de la base local. Un parámetro no expuesto queda en null.
 * Solo usa tipos de Kotlin, para probarlo en la JVM sin Firebase.
 */
object DocumentoAtencion {
    /** Versión del formato del documento. */
    const val FORMATO = 1

    /** Límite propio, con margen bajo el 1 MiB de Firestore. */
    const val LIMITE_BYTES = 900 * 1024

    const val COLECCION_USUARIOS = "usuarios"
    const val COLECCION_ATENCIONES = "atenciones"

    /** Lo agrega RespaldoFirestore al subir: la hora del servidor (FieldValue.serverTimestamp()). */
    const val CAMPO_SUBIDA_SERVIDOR = "subida_servidor"

    /** Id fijo del documento: la instalación y el id_atencion local. */
    fun idDocumento(idInstalacion: String, idAtencion: Long): String = "$idInstalacion-$idAtencion"

    fun ruta(uid: String, idDocumento: String): String = "$COLECCION_USUARIOS/$uid/$COLECCION_ATENCIONES/$idDocumento"

    /** Tamaño en Firestore del documento tal como se sube, con la hora del servidor. */
    fun bytes(uid: String, idDocumento: String, campos: Map<String, Any?>): Int =
        TamanoFirestore.documento(ruta(uid, idDocumento), campos) + TamanoFirestore.texto(CAMPO_SUBIDA_SERVIDOR) + 8

    fun armar(guardada: AtencionGuardada, vehiculo: Vehiculo?, uid: String, idDocumento: String, respaldadaEn: String): Map<String, Any?> {
        val a = guardada.atencion
        val diagnostico = guardada.diagnostico
        return linkedMapOf(
            "formato" to FORMATO,
            "id_documento" to idDocumento,
            "id_atencion" to a.id,
            "uid" to uid,
            "fecha" to a.fecha,
            "baro_kpa" to a.baroKpa,
            "respaldada_en" to respaldadaEn,
            "vehiculo" to vehiculo?.let { v ->
                linkedMapOf(
                    "codigo" to v.codigo,
                    "marca" to v.marca,
                    "modelo" to v.modelo,
                    "anio" to v.anio,
                    "cilindros" to v.cilindros,
                    "familia_gestion" to v.familiaGestion?.texto
                )
            },
            "dictamen" to linkedMapOf(
                "resultado" to guardada.dictamen.resultado.texto,
                "observacion" to guardada.dictamen.observacion.takeUnless { it.isBlank() }
            ),
            "diagnostico" to diagnostico?.let { d ->
                linkedMapOf(
                    "clase" to d.clase,
                    "probabilidad" to d.probabilidad,
                    "version_modelo" to d.versionModelo,
                    "parametros_desviados" to d.parametrosDesviados.map { p ->
                        linkedMapOf(
                            "parametro" to p.parametro,
                            "valor" to p.valor,
                            "referencia" to p.referencia.takeUnless { it.isNaN() },
                            "direccion" to p.direccion.texto
                        )
                    }
                )
            },
            "codigos_dtc" to a.codigos.map { linkedMapOf("codigo" to it.codigo, "tipo" to it.tipo.texto) },
            "modo06" to a.contadoresModo06.map {
                linkedMapOf("cilindro" to it.cilindro, "conteo_fallo" to it.conteoActual, "conteo_promedio" to it.conteoPromedio)
            },
            "regimenes" to TipoRegimen.entries.mapNotNull { a.regimenes[it] }.map(::regimen)
        )
    }

    private fun regimen(r: Regimen): Map<String, Any?> = linkedMapOf(
        "tipo" to r.tipo.name,
        "muestras_utiles" to r.muestrasUtiles,
        "descartadas_transitorio" to r.descartadasTransitorio,
        "descartadas_techo" to r.descartadasTecho,
        "frecuencia_hz" to r.frecuenciaHz,
        "lecturas" to r.lecturas.map(::lectura)
    )

    private fun lectura(l: Lectura): Map<String, Any?> = linkedMapOf(
        "instante" to l.instante,
        "rpm" to l.rpm,
        "temp_refrigerante" to l.tempRefrigerante,
        "stft" to l.stft,
        "ltft" to l.ltft,
        "carga_calculada" to l.cargaCalculada,
        "avance_encendido" to l.avanceEncendido,
        "maf" to l.maf,
        "map" to l.map,
        // Como en la base local: la velocidad del PID 0x0D es un entero en km/h.
        "velocidad" to l.velocidad?.roundToLong()
    )
}
