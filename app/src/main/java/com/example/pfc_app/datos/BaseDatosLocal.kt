package com.example.pfc_app.datos

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Base de datos local SQLite con Room (Capítulo 6, Tabla 30): nueve tablas en el almacenamiento
 * privado de la aplicación, sin servidor. Room activa las claves foráneas en cada conexión y
 * exporta el esquema a app/schemas; los CHECK van como triggers (RestriccionesCheck).
 *
 * Diferencias con la Tabla 30 decididas para la aplicación:
 * - lectura.rpm es REAL y no INTEGER: el PID 0x0C entrega cuartos de rpm y el cálculo de
 *   características debe ser idéntico al de consolidar_lecturas.py.
 * - contador_modo06 guarda además conteo_promedio (MISFIRE_AVERAGE); conteo_fallo es el
 *   conteo del ciclo actual (MISFIRE_COUNT).
 * - parametro_desviado guarda además referencia, el valor sin falla que ve el mecánico.
 * - vehiculo.familia_gestion admite también 'ambos' (el vehículo expone MAF y MAP).
 */
@Database(
    entities = [
        EntidadVehiculo::class, EntidadAtencion::class, EntidadRegimen::class, EntidadLectura::class,
        EntidadCodigoDtc::class, EntidadContadorModo06::class, EntidadDictamen::class,
        EntidadDiagnostico::class, EntidadParametroDesviado::class,
    ],
    version = BaseDatosLocal.VERSION,
    exportSchema = true,
)
abstract class BaseDatosLocal : RoomDatabase() {
    abstract fun vehiculos(): DaoVehiculos

    abstract fun atenciones(): DaoAtenciones

    companion object {
        const val NOMBRE = "pfc_app.db"

        /** La versión 1 fue la base con SQLiteOpenHelper; la 2 es la primera con Room. */
        const val VERSION = 2

        /** Tablas en orden de dependencia (las hijas después de sus padres). */
        val TABLAS = listOf(
            "vehiculo", "atencion", "regimen", "lectura", "codigo_dtc",
            "contador_modo06", "dictamen", "diagnostico", "parametro_desviado",
        )

        /** [nombre] permite a las pruebas usar un archivo propio; null crea una base en memoria. */
        fun crear(contexto: Context, nombre: String? = NOMBRE): BaseDatosLocal {
            val app = contexto.applicationContext
            val constructor = if (nombre == null) {
                Room.inMemoryDatabaseBuilder(app, BaseDatosLocal::class.java)
            } else {
                Room.databaseBuilder(app, BaseDatosLocal::class.java, nombre)
            }
            return constructor
                // La versión 1 no llegó a usuarios: se descarta sin migrar. Las siguientes deben migrar.
                .fallbackToDestructiveMigrationFrom(dropAllTables = true, 1)
                .addCallback(RestriccionesCheck.Callback)
                .build()
        }
    }
}
