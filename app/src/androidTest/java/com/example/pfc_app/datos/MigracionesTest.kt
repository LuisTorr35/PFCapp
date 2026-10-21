package com.example.pfc_app.datos

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.pfc_app.modelos.ResultadoDictamen
import com.example.pfc_app.modelos.TipoRegimen
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Migración de la base local de la versión 2 a la 3 con MigrationTestHelper: el esquema
 * resultante es el que exporta Room (app/schemas/.../3.json) y las atenciones se conservan.
 */
@RunWith(AndroidJUnit4::class)
class MigracionesTest {
    private val instrumentacion = InstrumentationRegistry.getInstrumentation()
    private val contexto: Context = instrumentacion.targetContext

    @get:Rule
    val ayudante = MigrationTestHelper(instrumentacion, BaseDatosLocal::class.java, emptyList(), FrameworkSQLiteOpenHelperFactory())

    @Before
    fun limpiar() {
        contexto.deleteDatabase(NOMBRE)
    }

    @After
    fun borrar() {
        contexto.deleteDatabase(NOMBRE)
    }

    @Test
    fun de2a3ConservaLasAtencionesYLasDejaPendientesSinCuenta() {
        ayudante.createDatabase(NOMBRE, 2).use { db -> insertarAtencionVersion2(db) }

        // Valida que el esquema migrado sea igual al de la versión 3 que exporta Room.
        ayudante.runMigrationsAndValidate(NOMBRE, 3, true, Migraciones.MIGRACION_2_3).use { db ->
            db.query("SELECT id_atencion, fecha, baro_kpa, respaldada, respaldada_en, uid_respaldo FROM atencion").use { c ->
                assertEquals(1, c.count)
                c.moveToFirst()
                assertEquals(ID_ATENCION, c.getLong(0))
                assertEquals("2026-09-14T10:30:00", c.getString(1))
                assertEquals(100.0, c.getDouble(2), 0.0)
                assertEquals("Las atenciones existentes quedan pendientes", 0, c.getInt(3))
                assertNull(c.getString(4))
                assertNull("Sin cuenta hasta el primer inicio de sesión", c.getString(5))
            }
            for ((tabla, filas) in FILAS_HIJAS) assertEquals("Filas de $tabla", filas, contar(db, tabla))
        }
    }

    @Test
    fun roomAbreLaBaseMigradaConSusDatosYSusTriggers() {
        ayudante.createDatabase(NOMBRE, 2).use { db -> insertarAtencionVersion2(db) }

        // Room aplica la migración al abrir la base (como en el teléfono al actualizar la app).
        val base = BaseDatosLocal.crear(contexto, NOMBRE)
        ayudante.closeWhenFinished(base)
        val atenciones = RepositorioAtenciones(base)

        val guardada = checkNotNull(atenciones.obtener(ID_ATENCION)) { "La atención migrada no se encontró" }
        assertEquals(setOf(TipoRegimen.R2, TipoRegimen.R3), guardada.atencion.regimenes.keys)
        assertEquals(3, guardada.atencion.regimenes.getValue(TipoRegimen.R2).lecturas.size)
        assertEquals(listOf("P0301"), guardada.atencion.codigos.map { it.codigo })
        assertEquals(ResultadoDictamen.PARCIAL, guardada.dictamen.resultado)
        assertEquals("C1", guardada.diagnostico?.clase)
        assertEquals(listOf("stft_r2_media"), guardada.diagnostico?.parametrosDesviados?.map { it.parametro })

        val resumen = atenciones.listarPorVehiculo(ID_VEHICULO).single()
        assertFalse("El historial la muestra pendiente", resumen.respaldada)

        // La primera cuenta que inicia sesión recibe las atenciones sin cuenta.
        assertEquals(1, atenciones.asignarSinCuenta("uid-a"))
        assertEquals(listOf(ID_ATENCION), atenciones.pendientesDeRespaldo("uid-a"))
        assertEquals(emptyList<Long>(), atenciones.pendientesDeRespaldo("uid-b"))

        val db = base.openHelper.writableDatabase
        db.query("SELECT COUNT(*) FROM sqlite_master WHERE type = 'trigger'").use { c ->
            c.moveToFirst()
            assertEquals(RestriccionesCheck.NOMBRES_TRIGGERS.size, c.getInt(0))
        }
        db.query("PRAGMA user_version").use { c ->
            c.moveToFirst()
            assertEquals(3, c.getInt(0))
        }
    }

    /** Una atención completa con el SQL de la versión 2 (sin las columnas de respaldo). */
    private fun insertarAtencionVersion2(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO vehiculo (id_vehiculo, codigo, marca, modelo, anio, cilindros, familia_gestion) " +
                "VALUES ($ID_VEHICULO, 'VEH-001', 'Hyundai', 'Tucson', 2011, 4, 'MAF')"
        )
        db.execSQL(
            "INSERT INTO atencion (id_atencion, id_vehiculo, fecha, baro_kpa) VALUES ($ID_ATENCION, $ID_VEHICULO, '2026-09-14T10:30:00', 100.0)"
        )
        for ((idRegimen, tipo) in listOf(1 to "R2", 2 to "R3")) {
            db.execSQL(
                "INSERT INTO regimen (id_regimen, id_atencion, tipo, muestras_utiles, descartadas_transitorio, descartadas_techo, " +
                    "frecuencia_hz) VALUES ($idRegimen, $ID_ATENCION, '$tipo', 3, 0, 0, 2.5)"
            )
            for (i in 0 until 3) {
                db.execSQL(
                    "INSERT INTO lectura (id_regimen, instante, rpm, temp_refrigerante, stft, ltft, carga_calculada, " +
                        "avance_encendido, maf, map, velocidad) VALUES ($idRegimen, ${i * 0.4}, 2500.0, 90.0, 4.7, 2.3, 30.0, 18.0, 6.1, NULL, 0)"
                )
            }
        }
        db.execSQL("INSERT INTO codigo_dtc (id_atencion, codigo, tipo) VALUES ($ID_ATENCION, 'P0301', 'almacenado')")
        db.execSQL("INSERT INTO contador_modo06 (id_atencion, cilindro, conteo_fallo, conteo_promedio) VALUES ($ID_ATENCION, 1, 12, 9)")
        db.execSQL("INSERT INTO dictamen (id_atencion, resultado, observacion) VALUES ($ID_ATENCION, 'parcial', 'Falta R1')")
        db.execSQL(
            "INSERT INTO diagnostico (id_diagnostico, id_atencion, clase, probabilidad, version_modelo) " +
                "VALUES (1, $ID_ATENCION, 'C1', 0.9, '0.2.0-provisional')"
        )
        db.execSQL(
            "INSERT INTO parametro_desviado (id_diagnostico, parametro, valor, referencia, direccion) " +
                "VALUES (1, 'stft_r2_media', 4.7, 0.5, 'mayor')"
        )
    }

    private fun contar(db: SupportSQLiteDatabase, tabla: String): Int = db.query("SELECT COUNT(*) FROM $tabla").use { c ->
        c.moveToFirst()
        c.getInt(0)
    }

    private companion object {
        const val NOMBRE = "prueba_migracion.db"
        const val ID_VEHICULO = 1L
        const val ID_ATENCION = 7L
        val FILAS_HIJAS = mapOf(
            "vehiculo" to 1,
            "regimen" to 2,
            "lectura" to 6,
            "codigo_dtc" to 1,
            "contador_modo06" to 1,
            "dictamen" to 1,
            "diagnostico" to 1,
            "parametro_desviado" to 1
        )
    }
}
