package com.example.pfc_app.datos

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/*
 * Las nueve tablas de la base local (Capítulo 6, Tabla 30) como entidades de Room. Los nombres
 * de tabla y columna son los del esquema SQL; los CHECK no caben en @Entity y van como
 * triggers en RestriccionesCheck. Un id en 0 al insertar deja que SQLite asigne la clave.
 */

@Entity(
    tableName = "vehiculo",
    indices = [Index(value = ["codigo"], name = "idx_vehiculo_codigo", unique = true)]
)
data class EntidadVehiculo(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id_vehiculo") val idVehiculo: Long = 0,
    @ColumnInfo(name = "codigo") val codigo: String,
    @ColumnInfo(name = "marca") val marca: String,
    @ColumnInfo(name = "modelo") val modelo: String,
    @ColumnInfo(name = "anio") val anio: Int,
    @ColumnInfo(name = "cilindros") val cilindros: Int,
    /** 'MAF', 'MAP' o 'ambos'; NULL hasta la primera conexión. */
    @ColumnInfo(name = "familia_gestion") val familiaGestion: String?
)

@Entity(
    tableName = "atencion",
    foreignKeys = [
        ForeignKey(entity = EntidadVehiculo::class, parentColumns = ["id_vehiculo"], childColumns = ["id_vehiculo"])
    ],
    // Historial ordenado por fecha; también cubre la clave foránea.
    indices = [Index(value = ["id_vehiculo", "fecha"], name = "idx_atencion_vehiculo_fecha")]
)
data class EntidadAtencion(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id_atencion") val idAtencion: Long = 0,
    @ColumnInfo(name = "id_vehiculo") val idVehiculo: Long,
    /** ISO 8601 local (FechasIso). */
    @ColumnInfo(name = "fecha") val fecha: String,
    @ColumnInfo(name = "baro_kpa") val baroKpa: Double?,
    /** Versión 3: true cuando la atención ya se subió a la nube de la cuenta [uidRespaldo]. */
    @ColumnInfo(name = "respaldada", defaultValue = "0") val respaldada: Boolean = false,
    /** Fecha y hora ISO 8601 del respaldo, o NULL si está pendiente. */
    @ColumnInfo(name = "respaldada_en") val respaldadaEn: String? = null,
    /** uid de Firebase de la cuenta con que se guardó; NULL en las anteriores a la versión 3. */
    @ColumnInfo(name = "uid_respaldo") val uidRespaldo: String? = null
)

@Entity(
    tableName = "regimen",
    foreignKeys = [
        ForeignKey(
            entity = EntidadAtencion::class,
            parentColumns = ["id_atencion"],
            childColumns = ["id_atencion"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["id_atencion", "tipo"], name = "idx_regimen_atencion_tipo", unique = true)]
)
data class EntidadRegimen(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id_regimen") val idRegimen: Long = 0,
    @ColumnInfo(name = "id_atencion") val idAtencion: Long,
    /** 'R1', 'R2' o 'R3'. */
    @ColumnInfo(name = "tipo") val tipo: String,
    @ColumnInfo(name = "muestras_utiles", defaultValue = "0") val muestrasUtiles: Int,
    @ColumnInfo(name = "descartadas_transitorio", defaultValue = "0") val descartadasTransitorio: Int,
    @ColumnInfo(name = "descartadas_techo", defaultValue = "0") val descartadasTecho: Int,
    @ColumnInfo(name = "frecuencia_hz") val frecuenciaHz: Double?
)

@Entity(
    tableName = "lectura",
    foreignKeys = [
        ForeignKey(
            entity = EntidadRegimen::class,
            parentColumns = ["id_regimen"],
            childColumns = ["id_regimen"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    // Lecturas de un régimen en orden; también cubre la clave foránea.
    indices = [Index(value = ["id_regimen", "instante"], name = "idx_lectura_regimen_instante")]
)
data class EntidadLectura(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id_lectura") val idLectura: Long = 0,
    @ColumnInfo(name = "id_regimen") val idRegimen: Long,
    @ColumnInfo(name = "instante") val instante: Double,
    /** REAL: el PID 0x0C entrega cuartos de rpm. */
    @ColumnInfo(name = "rpm") val rpm: Double?,
    @ColumnInfo(name = "temp_refrigerante") val tempRefrigerante: Double?,
    @ColumnInfo(name = "stft") val stft: Double?,
    @ColumnInfo(name = "ltft") val ltft: Double?,
    @ColumnInfo(name = "carga_calculada") val cargaCalculada: Double?,
    @ColumnInfo(name = "avance_encendido") val avanceEncendido: Double?,
    @ColumnInfo(name = "maf") val maf: Double?,
    @ColumnInfo(name = "map") val map: Double?,
    /** Entero en km/h (PID 0x0D). */
    @ColumnInfo(name = "velocidad") val velocidad: Long?
)

@Entity(
    tableName = "codigo_dtc",
    foreignKeys = [
        ForeignKey(
            entity = EntidadAtencion::class,
            parentColumns = ["id_atencion"],
            childColumns = ["id_atencion"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["id_atencion"], name = "idx_codigo_dtc_atencion")]
)
data class EntidadCodigoDtc(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id_codigo") val idCodigo: Long = 0,
    @ColumnInfo(name = "id_atencion") val idAtencion: Long,
    @ColumnInfo(name = "codigo") val codigo: String,
    /** 'almacenado' o 'pendiente'. */
    @ColumnInfo(name = "tipo") val tipo: String
)

@Entity(
    tableName = "contador_modo06",
    foreignKeys = [
        ForeignKey(
            entity = EntidadAtencion::class,
            parentColumns = ["id_atencion"],
            childColumns = ["id_atencion"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["id_atencion"], name = "idx_contador_modo06_atencion")]
)
data class EntidadContadorModo06(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id_contador") val idContador: Long = 0,
    @ColumnInfo(name = "id_atencion") val idAtencion: Long,
    @ColumnInfo(name = "cilindro") val cilindro: Int,
    /** MISFIRE_COUNT, ciclo actual. */
    @ColumnInfo(name = "conteo_fallo") val conteoFallo: Int?,
    /** MISFIRE_AVERAGE, promedio de diez ciclos. */
    @ColumnInfo(name = "conteo_promedio") val conteoPromedio: Int?
)

@Entity(
    tableName = "dictamen",
    foreignKeys = [
        ForeignKey(
            entity = EntidadAtencion::class,
            parentColumns = ["id_atencion"],
            childColumns = ["id_atencion"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    // Un solo dictamen por atención.
    indices = [Index(value = ["id_atencion"], name = "idx_dictamen_atencion", unique = true)]
)
data class EntidadDictamen(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id_dictamen") val idDictamen: Long = 0,
    @ColumnInfo(name = "id_atencion") val idAtencion: Long,
    /** 'completa' o 'parcial': las rechazadas no se guardan. */
    @ColumnInfo(name = "resultado") val resultado: String,
    @ColumnInfo(name = "observacion") val observacion: String?
)

@Entity(
    tableName = "diagnostico",
    foreignKeys = [
        ForeignKey(
            entity = EntidadAtencion::class,
            parentColumns = ["id_atencion"],
            childColumns = ["id_atencion"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    // Un solo diagnóstico por atención.
    indices = [Index(value = ["id_atencion"], name = "idx_diagnostico_atencion", unique = true)]
)
data class EntidadDiagnostico(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id_diagnostico") val idDiagnostico: Long = 0,
    @ColumnInfo(name = "id_atencion") val idAtencion: Long,
    @ColumnInfo(name = "clase") val clase: String,
    @ColumnInfo(name = "probabilidad") val probabilidad: Double,
    @ColumnInfo(name = "version_modelo") val versionModelo: String
)

@Entity(
    tableName = "parametro_desviado",
    foreignKeys = [
        ForeignKey(
            entity = EntidadDiagnostico::class,
            parentColumns = ["id_diagnostico"],
            childColumns = ["id_diagnostico"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["id_diagnostico"], name = "idx_parametro_desviado_diagnostico")]
)
data class EntidadParametroDesviado(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id_parametro") val idParametro: Long = 0,
    @ColumnInfo(name = "id_diagnostico") val idDiagnostico: Long,
    @ColumnInfo(name = "parametro") val parametro: String,
    @ColumnInfo(name = "valor") val valor: Double,
    /** Valor sin falla que ve el mecánico; NULL si el contrato no lo trae. */
    @ColumnInfo(name = "referencia") val referencia: Double?,
    /** 'mayor' o 'menor'. */
    @ColumnInfo(name = "direccion") val direccion: String
)

/** Fila del historial: atención con su dictamen y, si lo hay, su diagnóstico. */
data class FilaResumenAtencion(
    @ColumnInfo(name = "id_atencion") val idAtencion: Long,
    @ColumnInfo(name = "fecha") val fecha: String,
    @ColumnInfo(name = "resultado") val resultado: String,
    @ColumnInfo(name = "observacion") val observacion: String?,
    @ColumnInfo(name = "clase") val clase: String?,
    @ColumnInfo(name = "probabilidad") val probabilidad: Double?,
    @ColumnInfo(name = "version_modelo") val versionModelo: String?,
    @ColumnInfo(name = "respaldada") val respaldada: Boolean
)
