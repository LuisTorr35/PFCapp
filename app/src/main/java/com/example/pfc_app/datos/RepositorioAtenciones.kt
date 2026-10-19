package com.example.pfc_app.datos

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteStatement
import com.example.pfc_app.Registro
import com.example.pfc_app.modelos.Atencion
import com.example.pfc_app.modelos.CodigoDTC
import com.example.pfc_app.modelos.ContadorModo06
import com.example.pfc_app.modelos.Diagnostico
import com.example.pfc_app.modelos.Dictamen
import com.example.pfc_app.modelos.Direccion
import com.example.pfc_app.modelos.Lectura
import com.example.pfc_app.modelos.ParametroDesviado
import com.example.pfc_app.modelos.Regimen
import com.example.pfc_app.modelos.ResultadoDictamen
import com.example.pfc_app.modelos.TipoDTC
import com.example.pfc_app.modelos.TipoRegimen
import kotlin.math.roundToLong

/** Una fila del historial (CU-06): fecha, dictamen y resultado del modelo, si lo hay. */
data class ResumenAtencion(
    val idAtencion: Long,
    val fecha: String,
    val resultado: ResultadoDictamen,
    val observacion: String,
    val clase: String?,
    val probabilidad: Double?,
    val versionModelo: String?,
) {
    val tieneDiagnostico: Boolean get() = clase != null
}

/** Una atención leída de la base con su dictamen y su diagnóstico (null si quedó sin él). */
data class AtencionGuardada(
    val atencion: Atencion,
    val dictamen: Dictamen,
    val diagnostico: Diagnostico?,
)

/** Atenciones guardadas en el historial del vehículo (RF-06). */
class RepositorioAtenciones(private val base: BaseDatosLocal) {

    /**
     * Guarda la atención con todas sus filas en una sola transacción: si algo falla, no
     * queda nada a medias. Las atenciones rechazadas no se guardan. Sin [diagnostico]
     * (regla 8, CU-05 E1) no se escribe fila en diagnostico.
     */
    fun guardar(atencion: Atencion, dictamen: Dictamen, diagnostico: Diagnostico?): Long {
        require(dictamen.permiteGuardar()) { "Las atenciones rechazadas no se guardan" }
        require(atencion.regimenes.isNotEmpty()) { "La atención no tiene regímenes capturados" }
        val db = base.writableDatabase
        db.beginTransaction()
        try {
            val idAtencion = db.insertOrThrow("atencion", null, ContentValues().apply {
                put("id_vehiculo", atencion.idVehiculo)
                put("fecha", FechasIso.normalizar(atencion.fecha))
                putDoble("baro_kpa", atencion.baroKpa)
            })
            val sentenciaLectura = db.compileStatement(INSERTAR_LECTURA)
            try {
                for (tipo in TipoRegimen.entries) {
                    val regimen = atencion.regimenes[tipo] ?: continue
                    insertarRegimen(db, sentenciaLectura, idAtencion, regimen)
                }
            } finally {
                sentenciaLectura.close()
            }
            for (c in atencion.codigos) {
                db.insertOrThrow("codigo_dtc", null, ContentValues().apply {
                    put("id_atencion", idAtencion)
                    put("codigo", c.codigo)
                    put("tipo", c.tipo.texto)
                })
            }
            for (c in atencion.contadoresModo06) {
                db.insertOrThrow("contador_modo06", null, ContentValues().apply {
                    put("id_atencion", idAtencion)
                    put("cilindro", c.cilindro)
                    putEntero("conteo_fallo", c.conteoActual)
                    putEntero("conteo_promedio", c.conteoPromedio)
                })
            }
            db.insertOrThrow("dictamen", null, ContentValues().apply {
                put("id_atencion", idAtencion)
                put("resultado", dictamen.resultado.texto)
                if (dictamen.observacion.isBlank()) putNull("observacion") else put("observacion", dictamen.observacion)
            })
            if (diagnostico != null) insertarDiagnostico(db, idAtencion, diagnostico)
            db.setTransactionSuccessful()
            Registro.i(
                Registro.DATOS,
                "Atención $idAtencion guardada: ${atencion.regimenes.size} regímenes, " +
                    "dictamen ${dictamen.resultado.texto}, diagnóstico ${diagnostico?.clase ?: "ninguno"}",
            )
            return idAtencion
        } catch (e: Exception) {
            Registro.e(Registro.DATOS, "No se pudo guardar la atención; no queda nada a medias", e)
            throw e
        } finally {
            db.endTransaction()
        }
    }

    /** Atenciones del vehículo, de la más reciente a la más antigua. */
    fun listarPorVehiculo(idVehiculo: Long): List<ResumenAtencion> =
        base.readableDatabase.rawQuery(
            """
            SELECT a.id_atencion, a.fecha, d.resultado, d.observacion, g.clase, g.probabilidad, g.version_modelo
            FROM atencion a
            JOIN dictamen d ON d.id_atencion = a.id_atencion
            LEFT JOIN diagnostico g ON g.id_atencion = a.id_atencion
            WHERE a.id_vehiculo = ?
            ORDER BY a.fecha DESC, a.id_atencion DESC
            """.trimIndent(),
            arrayOf(idVehiculo.toString()),
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        ResumenAtencion(
                            idAtencion = c.getLong(0),
                            fecha = c.getString(1),
                            resultado = ResultadoDictamen.desdeTexto(c.getString(2)) ?: ResultadoDictamen.PARCIAL,
                            observacion = c.textoONulo(3).orEmpty(),
                            clase = c.textoONulo(4),
                            probabilidad = c.dobleONulo(5),
                            versionModelo = c.textoONulo(6),
                        ),
                    )
                }
            }
        }

    /** La atención con sus regímenes, lecturas, códigos, contadores, dictamen y diagnóstico. */
    fun obtener(idAtencion: Long): AtencionGuardada? {
        val db = base.readableDatabase
        val id = arrayOf(idAtencion.toString())
        val cabecera = db.rawQuery(
            "SELECT id_vehiculo, fecha, baro_kpa FROM atencion WHERE id_atencion = ?", id,
        ).use { c -> if (c.moveToFirst()) Triple(c.getLong(0), c.getString(1), c.dobleONulo(2)) else null }
            ?: return null

        val regimenes = db.rawQuery(
            """
            SELECT id_regimen, tipo, muestras_utiles, descartadas_transitorio, descartadas_techo, frecuencia_hz
            FROM regimen WHERE id_atencion = ? ORDER BY tipo
            """.trimIndent(),
            id,
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val idRegimen = c.getLong(0)
                    add(
                        Regimen(
                            id = idRegimen,
                            tipo = TipoRegimen.valueOf(c.getString(1)),
                            lecturas = leerLecturas(db, idRegimen),
                            muestrasUtiles = c.getInt(2),
                            descartadasTransitorio = c.getInt(3),
                            descartadasTecho = c.getInt(4),
                            frecuenciaHz = c.dobleONulo(5) ?: 0.0,
                        ),
                    )
                }
            }
        }

        val codigos = db.rawQuery(
            "SELECT codigo, tipo FROM codigo_dtc WHERE id_atencion = ? ORDER BY id_codigo", id,
        ).use { c -> buildList { while (c.moveToNext()) add(CodigoDTC(c.getString(0), TipoDTC.desdeTexto(c.getString(1)))) } }

        val contadores = db.rawQuery(
            "SELECT cilindro, conteo_fallo, conteo_promedio FROM contador_modo06 WHERE id_atencion = ? ORDER BY cilindro",
            id,
        ).use { c ->
            buildList { while (c.moveToNext()) add(ContadorModo06(c.getInt(0), c.enteroONulo(1), c.enteroONulo(2))) }
        }

        val atencion = Atencion(
            id = idAtencion,
            idVehiculo = cabecera.first,
            fecha = cabecera.second,
            baroKpa = cabecera.third,
            codigos = codigos,
            contadoresModo06 = contadores,
            regimenes = regimenes.associateBy { it.tipo },
        )

        val diagnostico = leerDiagnostico(db, idAtencion, codigos)

        val dictamen = db.rawQuery(
            "SELECT resultado, observacion FROM dictamen WHERE id_atencion = ?", id,
        ).use { c ->
            if (c.moveToFirst()) {
                Dictamen(
                    resultado = ResultadoDictamen.desdeTexto(c.getString(0)) ?: ResultadoDictamen.PARCIAL,
                    reglas = emptyList(),
                    observacion = c.textoONulo(1).orEmpty(),
                    // Una atención guardada sin diagnóstico es la de la regla 8 (CU-05 E1).
                    faltanParametrosModelo = diagnostico == null,
                )
            } else {
                null
            }
        } ?: return null

        return AtencionGuardada(atencion, dictamen, diagnostico)
    }

    private fun insertarRegimen(db: SQLiteDatabase, sentencia: SQLiteStatement, idAtencion: Long, r: Regimen) {
        val idRegimen = db.insertOrThrow("regimen", null, ContentValues().apply {
            put("id_atencion", idAtencion)
            put("tipo", r.tipo.name)
            put("muestras_utiles", r.muestrasUtiles)
            put("descartadas_transitorio", r.descartadasTransitorio)
            put("descartadas_techo", r.descartadasTecho)
            put("frecuencia_hz", r.frecuenciaHz)
        })
        if (r.lecturas.isEmpty()) Registro.w(Registro.DATOS, "El régimen ${r.tipo} no tiene lecturas")
        for (l in r.lecturas) {
            sentencia.clearBindings()
            sentencia.bindLong(1, idRegimen)
            sentencia.bindDouble(2, l.instante)
            sentencia.bindDoble(3, l.rpm)
            sentencia.bindDoble(4, l.tempRefrigerante)
            sentencia.bindDoble(5, l.stft)
            sentencia.bindDoble(6, l.ltft)
            sentencia.bindDoble(7, l.cargaCalculada)
            sentencia.bindDoble(8, l.avanceEncendido)
            sentencia.bindDoble(9, l.maf)
            sentencia.bindDoble(10, l.map)
            // La velocidad del PID 0x0D es un entero en km/h.
            val velocidad = l.velocidad
            if (velocidad == null) sentencia.bindNull(11) else sentencia.bindLong(11, velocidad.roundToLong())
            sentencia.executeInsert()
        }
    }

    private fun insertarDiagnostico(db: SQLiteDatabase, idAtencion: Long, d: Diagnostico) {
        val idDiagnostico = db.insertOrThrow("diagnostico", null, ContentValues().apply {
            put("id_atencion", idAtencion)
            put("clase", d.clase)
            put("probabilidad", ajustarProbabilidad(d.probabilidad))
            put("version_modelo", d.versionModelo)
        })
        for (p in d.parametrosDesviados) {
            db.insertOrThrow("parametro_desviado", null, ContentValues().apply {
                put("id_diagnostico", idDiagnostico)
                put("parametro", p.parametro)
                put("valor", p.valor)
                putDoble("referencia", p.referencia.takeUnless { it.isNaN() })
                put("direccion", p.direccion.texto)
            })
        }
    }

    private fun leerLecturas(db: SQLiteDatabase, idRegimen: Long): List<Lectura> =
        db.rawQuery(
            """
            SELECT instante, rpm, temp_refrigerante, stft, ltft, carga_calculada, avance_encendido, maf, map, velocidad
            FROM lectura WHERE id_regimen = ? ORDER BY instante, id_lectura
            """.trimIndent(),
            arrayOf(idRegimen.toString()),
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        Lectura(
                            instante = c.getDouble(0),
                            rpm = c.dobleONulo(1),
                            tempRefrigerante = c.dobleONulo(2),
                            stft = c.dobleONulo(3),
                            ltft = c.dobleONulo(4),
                            cargaCalculada = c.dobleONulo(5),
                            avanceEncendido = c.dobleONulo(6),
                            maf = c.dobleONulo(7),
                            map = c.dobleONulo(8),
                            velocidad = c.dobleONulo(9),
                        ),
                    )
                }
            }
        }

    /**
     * El diagnóstico tal como se guardó. El nombre de la clase y la etiqueta y unidad de cada
     * parámetro no se guardan: la pantalla los toma del contrato del modelo por nombre.
     */
    private fun leerDiagnostico(db: SQLiteDatabase, idAtencion: Long, codigos: List<CodigoDTC>): Diagnostico? {
        val fila = db.rawQuery(
            "SELECT id_diagnostico, clase, probabilidad, version_modelo FROM diagnostico WHERE id_atencion = ?",
            arrayOf(idAtencion.toString()),
        ).use { c -> if (c.moveToFirst()) listOf(c.getLong(0), c.getString(1), c.getDouble(2), c.getString(3)) else null }
            ?: return null
        val idDiagnostico = fila[0] as Long
        val clase = fila[1] as String
        val version = fila[3] as String
        val parametros = db.rawQuery(
            "SELECT parametro, valor, referencia, direccion FROM parametro_desviado WHERE id_diagnostico = ? ORDER BY id_parametro",
            arrayOf(idDiagnostico.toString()),
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        ParametroDesviado(
                            parametro = c.getString(0),
                            valor = c.getDouble(1),
                            referencia = c.dobleONulo(2) ?: Double.NaN,
                            direccion = Direccion.desdeTexto(c.getString(3)) ?: Direccion.MAYOR,
                        ),
                    )
                }
            }
        }
        return Diagnostico(
            id = idDiagnostico,
            clase = clase,
            nombreClase = clase,
            probabilidad = fila[2] as Double,
            versionModelo = version,
            parametrosDesviados = parametros,
            codigosLeidos = codigos,
            modeloProvisional = version.contains("provisional", ignoreCase = true),
        )
    }

    private companion object {
        const val INSERTAR_LECTURA =
            "INSERT INTO lectura (id_regimen, instante, rpm, temp_refrigerante, stft, ltft, " +
                "carga_calculada, avance_encendido, maf, map, velocidad) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"

        /** El float32 del modelo puede pasarse de 1 por redondeo; un error mayor lo rechaza el CHECK. */
        fun ajustarProbabilidad(p: Double): Double = when {
            p > 1.0 && p <= 1.0 + 1e-5 -> 1.0
            p < 0.0 && p >= -1e-5 -> 0.0
            else -> p
        }

        // Un parámetro no expuesto se guarda como NULL, nunca como cero.
        fun ContentValues.putDoble(clave: String, valor: Double?) {
            if (valor == null) putNull(clave) else put(clave, valor)
        }

        fun ContentValues.putEntero(clave: String, valor: Int?) {
            if (valor == null) putNull(clave) else put(clave, valor)
        }

        fun SQLiteStatement.bindDoble(indice: Int, valor: Double?) {
            if (valor == null) bindNull(indice) else bindDouble(indice, valor)
        }

        fun Cursor.dobleONulo(i: Int): Double? = if (isNull(i)) null else getDouble(i)
        fun Cursor.enteroONulo(i: Int): Int? = if (isNull(i)) null else getInt(i)
        fun Cursor.textoONulo(i: Int): String? = if (isNull(i)) null else getString(i)
    }
}
