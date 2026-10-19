package com.example.pfc_app.datos

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

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
        SELECT a.id_atencion, a.fecha, d.resultado, d.observacion, g.clase, g.probabilidad, g.version_modelo
        FROM atencion a
        JOIN dictamen d ON d.id_atencion = a.id_atencion
        LEFT JOIN diagnostico g ON g.id_atencion = a.id_atencion
        WHERE a.id_vehiculo = :idVehiculo
        ORDER BY a.fecha DESC, a.id_atencion DESC
        """,
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
}
