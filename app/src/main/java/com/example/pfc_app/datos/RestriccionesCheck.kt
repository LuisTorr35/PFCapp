package com.example.pfc_app.datos

import androidx.room.RoomDatabase
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.example.pfc_app.Registro

/**
 * Los CHECK del esquema (Tabla 30). Room no admite CHECK en @Entity, así que cada uno se
 * impone con dos triggers: BEFORE INSERT y BEFORE UPDATE OF su columna (SQLite tampoco revisa
 * un CHECK en un UPDATE que no toca sus columnas). Abortan con RAISE(ABORT), que llega como
 * SQLiteConstraintException. Como en un CHECK, una condición NULL no aborta.
 *
 * herramientas/esquema_desde_room.py lee la lista [CHECKS] de este archivo para escribir los
 * triggers en app/schemas/schema.sql: si cambia el formato de los triggers, cambiarlo también allí.
 */
object RestriccionesCheck {

    /** Un CHECK sobre [columna]; [condicion] usa NEW.<columna>. */
    data class Check(val tabla: String, val columna: String, val condicion: String) {
        val triggerInsercion: String get() = "chk_${tabla}_${columna}_ins"
        val triggerActualizacion: String get() = "chk_${tabla}_${columna}_upd"
    }

    val CHECKS = listOf(
        Check("vehiculo", "codigo", "NEW.codigo GLOB 'VEH-[0-9][0-9][0-9]*'"),
        Check("vehiculo", "marca", "length(trim(NEW.marca)) > 0"),
        Check("vehiculo", "modelo", "length(trim(NEW.modelo)) > 0"),
        Check("vehiculo", "anio", "NEW.anio BETWEEN 1900 AND 2100"),
        Check("vehiculo", "cilindros", "NEW.cilindros BETWEEN 1 AND 16"),
        Check("vehiculo", "familia_gestion", "NEW.familia_gestion IN ('MAF', 'MAP', 'ambos')"),
        Check("regimen", "tipo", "NEW.tipo IN ('R1', 'R2', 'R3')"),
        Check("regimen", "muestras_utiles", "NEW.muestras_utiles >= 0"),
        Check("regimen", "descartadas_transitorio", "NEW.descartadas_transitorio >= 0"),
        Check("regimen", "descartadas_techo", "NEW.descartadas_techo >= 0"),
        Check("codigo_dtc", "codigo", "length(NEW.codigo) > 0"),
        Check("codigo_dtc", "tipo", "NEW.tipo IN ('almacenado', 'pendiente')"),
        Check("contador_modo06", "cilindro", "NEW.cilindro >= 1"),
        Check("contador_modo06", "conteo_fallo", "NEW.conteo_fallo >= 0"),
        Check("contador_modo06", "conteo_promedio", "NEW.conteo_promedio >= 0"),
        Check("dictamen", "resultado", "NEW.resultado IN ('completa', 'parcial')"),
        Check("diagnostico", "probabilidad", "NEW.probabilidad BETWEEN 0 AND 1"),
        Check("parametro_desviado", "direccion", "NEW.direccion IN ('mayor', 'menor')")
    )

    /** Los dos CREATE TRIGGER de cada CHECK, en el orden de [CHECKS]. */
    val SENTENCIAS: List<String> = CHECKS.flatMap { c ->
        listOf(
            sentencia(c.triggerInsercion, "INSERT", c),
            sentencia(c.triggerActualizacion, "UPDATE OF ${c.columna}", c)
        )
    }

    val NOMBRES_TRIGGERS: List<String> = CHECKS.flatMap { listOf(it.triggerInsercion, it.triggerActualizacion) }

    private fun sentencia(nombre: String, evento: String, c: Check) =
        "CREATE TRIGGER IF NOT EXISTS $nombre BEFORE $evento ON ${c.tabla}\n" +
            "WHEN NOT (${c.condicion})\n" +
            "BEGIN SELECT RAISE(ABORT, 'CHECK ${c.tabla}.${c.columna}'); END"

    fun crearTriggers(conexion: SQLiteConnection) {
        SENTENCIAS.forEach { conexion.execSQL(it) }
    }

    /** Crea los triggers con la base y, por si faltan en una base ya creada, al abrirla. */
    object Callback : RoomDatabase.Callback() {
        override fun onCreate(connection: SQLiteConnection) {
            Registro.i(Registro.DATOS, "Creando la base de datos local (versión ${BaseDatosLocal.VERSION})")
            crearTriggers(connection)
        }

        override fun onOpen(connection: SQLiteConnection) {
            crearTriggers(connection)
        }
    }
}
