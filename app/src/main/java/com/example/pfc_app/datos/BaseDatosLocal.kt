package com.example.pfc_app.datos

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.example.pfc_app.Registro

/**
 * Base de datos local SQLite (Capítulo 6, Tabla 30): nueve tablas en el almacenamiento
 * privado de la aplicación, sin servidor. Las claves foráneas se activan en cada conexión.
 *
 * Diferencias con la Tabla 30 decididas para la aplicación:
 * - lectura.rpm es REAL y no INTEGER: el PID 0x0C entrega cuartos de rpm y el cálculo de
 *   características debe ser idéntico al de consolidar_lecturas.py.
 * - contador_modo06 guarda además conteo_promedio (MISFIRE_AVERAGE); conteo_fallo es el
 *   conteo del ciclo actual (MISFIRE_COUNT).
 * - parametro_desviado guarda además referencia, el valor sin falla que ve el mecánico.
 * - vehiculo.familia_gestion admite también 'ambos' (el vehículo expone MAF y MAP).
 *
 * [nombre] permite a las pruebas usar un archivo propio; null crea una base en memoria.
 */
class BaseDatosLocal(contexto: Context, nombre: String? = NOMBRE) :
    SQLiteOpenHelper(contexto.applicationContext, nombre, null, VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        // SQLite trae las claves foráneas desactivadas: se activan en cada conexión.
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        Registro.i(Registro.DATOS, "Creando la base de datos local (versión $VERSION)")
        ESQUEMA.forEach(db::execSQL)
    }

    override fun onUpgrade(db: SQLiteDatabase, versionAnterior: Int, versionNueva: Int) {
        // Versión 1: todavía no hay migraciones. Una versión nueva debe migrar sin borrar el historial.
        Registro.w(Registro.DATOS, "Sin migración de $versionAnterior a $versionNueva")
    }

    companion object {
        const val NOMBRE = "pfc_app.db"
        const val VERSION = 1

        /** Tablas en orden de dependencia (las hijas después de sus padres). */
        val TABLAS = listOf(
            "vehiculo", "atencion", "regimen", "lectura", "codigo_dtc",
            "contador_modo06", "dictamen", "diagnostico", "parametro_desviado",
        )

        private val ESQUEMA = listOf(
            """
            CREATE TABLE vehiculo (
                id_vehiculo INTEGER PRIMARY KEY,
                codigo TEXT NOT NULL UNIQUE CHECK (codigo GLOB 'VEH-[0-9][0-9][0-9]*'),
                marca TEXT NOT NULL CHECK (length(trim(marca)) > 0),
                modelo TEXT NOT NULL CHECK (length(trim(modelo)) > 0),
                anio INTEGER NOT NULL CHECK (anio BETWEEN 1900 AND 2100),
                cilindros INTEGER NOT NULL CHECK (cilindros BETWEEN 1 AND 16),
                familia_gestion TEXT CHECK (familia_gestion IN ('MAF', 'MAP', 'ambos'))
            )
            """,
            """
            CREATE TABLE atencion (
                id_atencion INTEGER PRIMARY KEY,
                id_vehiculo INTEGER NOT NULL REFERENCES vehiculo (id_vehiculo),
                fecha TEXT NOT NULL,
                baro_kpa REAL
            )
            """,
            """
            CREATE TABLE regimen (
                id_regimen INTEGER PRIMARY KEY,
                id_atencion INTEGER NOT NULL REFERENCES atencion (id_atencion) ON DELETE CASCADE,
                tipo TEXT NOT NULL CHECK (tipo IN ('R1', 'R2', 'R3')),
                muestras_utiles INTEGER NOT NULL DEFAULT 0 CHECK (muestras_utiles >= 0),
                descartadas_transitorio INTEGER NOT NULL DEFAULT 0 CHECK (descartadas_transitorio >= 0),
                descartadas_techo INTEGER NOT NULL DEFAULT 0 CHECK (descartadas_techo >= 0),
                frecuencia_hz REAL,
                UNIQUE (id_atencion, tipo)
            )
            """,
            """
            CREATE TABLE lectura (
                id_lectura INTEGER PRIMARY KEY,
                id_regimen INTEGER NOT NULL REFERENCES regimen (id_regimen) ON DELETE CASCADE,
                instante REAL NOT NULL,
                rpm REAL,
                temp_refrigerante REAL,
                stft REAL,
                ltft REAL,
                carga_calculada REAL,
                avance_encendido REAL,
                maf REAL,
                map REAL,
                velocidad INTEGER
            )
            """,
            """
            CREATE TABLE codigo_dtc (
                id_codigo INTEGER PRIMARY KEY,
                id_atencion INTEGER NOT NULL REFERENCES atencion (id_atencion) ON DELETE CASCADE,
                codigo TEXT NOT NULL CHECK (length(codigo) > 0),
                tipo TEXT NOT NULL CHECK (tipo IN ('almacenado', 'pendiente'))
            )
            """,
            """
            CREATE TABLE contador_modo06 (
                id_contador INTEGER PRIMARY KEY,
                id_atencion INTEGER NOT NULL REFERENCES atencion (id_atencion) ON DELETE CASCADE,
                cilindro INTEGER NOT NULL CHECK (cilindro >= 1),
                conteo_fallo INTEGER CHECK (conteo_fallo >= 0),
                conteo_promedio INTEGER CHECK (conteo_promedio >= 0)
            )
            """,
            """
            CREATE TABLE dictamen (
                id_dictamen INTEGER PRIMARY KEY,
                id_atencion INTEGER NOT NULL UNIQUE REFERENCES atencion (id_atencion) ON DELETE CASCADE,
                resultado TEXT NOT NULL CHECK (resultado IN ('completa', 'parcial')),
                observacion TEXT
            )
            """,
            """
            CREATE TABLE diagnostico (
                id_diagnostico INTEGER PRIMARY KEY,
                id_atencion INTEGER NOT NULL UNIQUE REFERENCES atencion (id_atencion) ON DELETE CASCADE,
                clase TEXT NOT NULL,
                probabilidad REAL NOT NULL CHECK (probabilidad BETWEEN 0 AND 1),
                version_modelo TEXT NOT NULL
            )
            """,
            """
            CREATE TABLE parametro_desviado (
                id_parametro INTEGER PRIMARY KEY,
                id_diagnostico INTEGER NOT NULL REFERENCES diagnostico (id_diagnostico) ON DELETE CASCADE,
                parametro TEXT NOT NULL,
                valor REAL NOT NULL,
                referencia REAL,
                direccion TEXT NOT NULL CHECK (direccion IN ('mayor', 'menor'))
            )
            """,
            // Historial ordenado por fecha y lecturas de un régimen en orden.
            "CREATE INDEX idx_atencion_vehiculo_fecha ON atencion (id_vehiculo, fecha)",
            "CREATE INDEX idx_lectura_regimen_instante ON lectura (id_regimen, instante)",
        ).map { it.trimIndent() }
    }
}
