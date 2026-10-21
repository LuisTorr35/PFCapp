package com.example.pfc_app.datos

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Consultas de la tabla vehiculo. */
@Dao
interface DaoVehiculos {
    @Insert
    fun insertar(vehiculo: EntidadVehiculo): Long

    @Query("SELECT * FROM vehiculo ORDER BY id_vehiculo DESC")
    fun listar(): List<EntidadVehiculo>

    @Query("SELECT * FROM vehiculo WHERE codigo = :codigo")
    fun buscarPorCodigo(codigo: String): EntidadVehiculo?

    @Query("SELECT * FROM vehiculo WHERE id_vehiculo = :id")
    fun obtener(id: Long): EntidadVehiculo?

    /** Devuelve las filas actualizadas: 0 si el vehículo no existe. */
    @Query("UPDATE vehiculo SET familia_gestion = :familia WHERE id_vehiculo = :id")
    fun actualizarFamilia(id: Long, familia: String): Int

    /** El mayor número de los códigos VEH-###, o null si no hay vehículos. */
    @Query("SELECT MAX(CAST(substr(codigo, 5) AS INTEGER)) FROM vehiculo")
    fun mayorNumeroCodigo(): Int?
}

/** Consultas de la atención y de sus tablas hijas. */
@Dao
interface DaoAtenciones {
    @Insert
    fun insertarAtencion(atencion: EntidadAtencion): Long

    @Insert
    fun insertarRegimen(regimen: EntidadRegimen): Long

    @Insert
    fun insertarLecturas(lecturas: List<EntidadLectura>)

    @Insert
    fun insertarCodigos(codigos: List<EntidadCodigoDtc>)

    @Insert
    fun insertarContadores(contadores: List<EntidadContadorModo06>)

    @Insert
    fun insertarDictamen(dictamen: EntidadDictamen): Long

    @Insert
    fun insertarDiagnostico(diagnostico: EntidadDiagnostico): Long

    @Insert
    fun insertarParametros(parametros: List<EntidadParametroDesviado>)

    /** Historial del vehículo, de la atención más reciente a la más antigua. */
    @Query(
        """
        SELECT a.id_atencion, a.fecha, d.resultado, d.observacion, g.clase, g.probabilidad, g.version_modelo,
            a.respaldada
        FROM atencion a
        JOIN dictamen d ON d.id_atencion = a.id_atencion
        LEFT JOIN diagnostico g ON g.id_atencion = a.id_atencion
        WHERE a.id_vehiculo = :idVehiculo
        ORDER BY a.fecha DESC, a.id_atencion DESC
        """
    )
    fun listarResumenes(idVehiculo: Long): List<FilaResumenAtencion>

    @Query("SELECT * FROM atencion WHERE id_atencion = :idAtencion")
    fun obtenerAtencion(idAtencion: Long): EntidadAtencion?

    @Query("SELECT * FROM regimen WHERE id_atencion = :idAtencion ORDER BY tipo")
    fun regimenes(idAtencion: Long): List<EntidadRegimen>

    @Query("SELECT * FROM lectura WHERE id_regimen = :idRegimen ORDER BY instante, id_lectura")
    fun lecturas(idRegimen: Long): List<EntidadLectura>

    @Query("SELECT * FROM codigo_dtc WHERE id_atencion = :idAtencion ORDER BY id_codigo")
    fun codigos(idAtencion: Long): List<EntidadCodigoDtc>

    @Query("SELECT * FROM contador_modo06 WHERE id_atencion = :idAtencion ORDER BY cilindro")
    fun contadores(idAtencion: Long): List<EntidadContadorModo06>

    @Query("SELECT * FROM dictamen WHERE id_atencion = :idAtencion")
    fun dictamen(idAtencion: Long): EntidadDictamen?

    @Query("SELECT * FROM diagnostico WHERE id_atencion = :idAtencion")
    fun diagnostico(idAtencion: Long): EntidadDiagnostico?

    @Query("SELECT * FROM parametro_desviado WHERE id_diagnostico = :idDiagnostico ORDER BY id_parametro")
    fun parametros(idDiagnostico: Long): List<EntidadParametroDesviado>

    // Respaldo en la nube (versión 3).

    /** Atenciones de la cuenta [uid] que todavía no se subieron, de la más antigua a la más reciente. */
    @Query("SELECT id_atencion FROM atencion WHERE respaldada = 0 AND uid_respaldo = :uid ORDER BY id_atencion")
    fun pendientesDeRespaldo(uid: String): List<Long>

    @Query("SELECT count(*) FROM atencion WHERE respaldada = 0 AND uid_respaldo = :uid")
    fun contarPendientes(uid: String): Int

    /** Pendientes de otras cuentas: se quedan en el teléfono hasta que esa cuenta vuelva a entrar. */
    @Query("SELECT count(*) FROM atencion WHERE respaldada = 0 AND uid_respaldo IS NOT NULL AND uid_respaldo != :uid")
    fun contarPendientesDeOtrasCuentas(uid: String): Int

    @Query("UPDATE atencion SET respaldada = 1, respaldada_en = :fecha WHERE id_atencion = :idAtencion")
    fun marcarRespaldada(idAtencion: Long, fecha: String): Int

    /** Se vuelve a emitir cada vez que cambia la tabla atencion (por ejemplo, al respaldarse una). */
    @Query("SELECT id_atencion, respaldada FROM atencion WHERE id_vehiculo = :idVehiculo")
    fun observarRespaldo(idVehiculo: Long): Flow<List<FilaEstadoRespaldo>>

    /** Asigna a [uid] las atenciones guardadas antes de la versión 3, que no tienen cuenta. */
    @Query("UPDATE atencion SET uid_respaldo = :uid WHERE uid_respaldo IS NULL")
    fun asignarSinCuenta(uid: String): Int
}
