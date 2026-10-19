package com.example.pfc_app.datos

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Los CHECK del esquema como triggers: existen, rechazan lo inválido y se recrean al abrir. */
@RunWith(AndroidJUnit4::class)
class RestriccionesCheckTest {
    private lateinit var contexto: Context
    private lateinit var base: BaseDatosLocal

    private val sql: SupportSQLiteDatabase get() = base.openHelper.writableDatabase

    @Before
    fun preparar() {
        contexto = InstrumentationRegistry.getInstrumentation().targetContext
        contexto.deleteDatabase(NOMBRE)
        base = BaseDatosLocal.crear(contexto, NOMBRE)
    }

    @After
    fun cerrar() {
        base.close()
        contexto.deleteDatabase(NOMBRE)
    }

    @Test
    fun cadaCheckDelEsquemaTieneSusDosTriggers() {
        assertEquals(VALORES_INVALIDOS.keys, RestriccionesCheck.CHECKS.map { "${it.tabla}.${it.columna}" }.toSet())
        val triggers = triggersEnLaBase()
        assertEquals(2 * VALORES_INVALIDOS.size, triggers.size)
        for (c in RestriccionesCheck.CHECKS) {
            assertEquals("Falta ${c.triggerInsercion}", c.tabla, triggers[c.triggerInsercion])
            assertEquals("Falta ${c.triggerActualizacion}", c.tabla, triggers[c.triggerActualizacion])
        }
    }

    @Test
    fun cadaTriggerRechazaUnValorInvalidoAlInsertarYAlActualizar() {
        poblar()
        for (c in RestriccionesCheck.CHECKS) {
            val invalido = VALORES_INVALIDOS.getValue("${c.tabla}.${c.columna}")
            // Copia de una fila válida con solo esa columna cambiada.
            val columnas = columnasSinClave(c.tabla)
            val seleccion = columnas.joinToString { if (it == c.columna) "?" else it }
            assertRechazo(c) {
                sql.execSQL(
                    "INSERT INTO ${c.tabla} (${columnas.joinToString()}) SELECT $seleccion FROM ${c.tabla} LIMIT 1",
                    arrayOf(invalido),
                )
            }
            assertRechazo(c) { sql.execSQL("UPDATE ${c.tabla} SET ${c.columna} = ?", arrayOf(invalido)) }
        }
    }

    @Test
    fun losLimitesDeCadaCheckSeAceptan() {
        poblar()
        for ((columna, valor) in listOf("anio" to 1900, "anio" to 2100, "cilindros" to 1, "cilindros" to 16)) {
            sql.execSQL("UPDATE vehiculo SET $columna = ?", arrayOf(valor))
        }
        sql.execSQL("UPDATE vehiculo SET codigo = 'VEH-1000'")
        // Como en un CHECK, NULL pasa: la familia de gestión queda pendiente.
        sql.execSQL("UPDATE vehiculo SET familia_gestion = NULL")
        sql.execSQL("UPDATE diagnostico SET probabilidad = 0")
        sql.execSQL("UPDATE diagnostico SET probabilidad = 1")
        sql.execSQL("UPDATE contador_modo06 SET conteo_fallo = 0, conteo_promedio = NULL")
    }

    @Test
    fun alAbrirUnaBaseSinTriggersSeVuelvenACrear() {
        sql.execSQL("DROP TRIGGER chk_vehiculo_codigo_ins")
        sql.execSQL("DROP TRIGGER chk_diagnostico_probabilidad_upd")
        assertEquals(2 * VALORES_INVALIDOS.size - 2, triggersEnLaBase().size)

        base.close()
        base = BaseDatosLocal.crear(contexto, NOMBRE)
        assertEquals(RestriccionesCheck.NOMBRES_TRIGGERS.toSet(), triggersEnLaBase().keys)
    }

    // ---------- Auxiliares ----------

    /** Nombre de cada trigger y su tabla. */
    private fun triggersEnLaBase(): Map<String, String> =
        sql.query("SELECT name, tbl_name FROM sqlite_master WHERE type = 'trigger'").use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(0), c.getString(1)) }
        }

    private fun columnasSinClave(tabla: String): List<String> =
        sql.query("PRAGMA table_info($tabla)").use { c ->
            val nombre = c.getColumnIndexOrThrow("name")
            val clave = c.getColumnIndexOrThrow("pk")
            buildList { while (c.moveToNext()) if (c.getInt(clave) == 0) add(c.getString(nombre)) }
        }

    private fun assertRechazo(c: RestriccionesCheck.Check, bloque: () -> Unit) {
        try {
            bloque()
            fail("${c.tabla}.${c.columna} debía rechazar el valor")
        } catch (e: SQLiteConstraintException) {
            assertTrue("Lo rechazó otra restricción: ${e.message}", e.message.orEmpty().contains("CHECK ${c.tabla}.${c.columna}"))
        }
    }

    /** Un vehículo con una atención completa: una fila válida en cada tabla. */
    private fun poblar() {
        val v = RepositorioVehiculos(base).registrar("Hyundai", "Tucson", 2011, 4)
        val lecturas = listOf(0.0, 0.4).map { t -> Lectura(instante = t, rpm = 812.25, map = 35.0, velocidad = 0.0) }
        val atencion = Atencion(
            idVehiculo = v.id,
            fecha = "2026-09-28T11:20:00",
            baroKpa = 75.0,
            codigos = listOf(CodigoDTC("P0303", TipoDTC.ALMACENADO)),
            contadoresModo06 = listOf(ContadorModo06(1, 0, 0)),
            regimenes = mapOf(TipoRegimen.R2 to Regimen(tipo = TipoRegimen.R2, lecturas = lecturas, muestrasUtiles = 46, frecuenciaHz = 2.5)),
        )
        val diagnostico = Diagnostico(
            clase = "C1", nombreClase = "C1", probabilidad = 0.72, versionModelo = "0.1.0-provisional",
            parametrosDesviados = listOf(ParametroDesviado("R2_rpm_desv", 14.9, 3.5, Direccion.MAYOR)),
        )
        RepositorioAtenciones(base).guardar(atencion, Dictamen(ResultadoDictamen.COMPLETA, emptyList()), diagnostico)
    }

    private companion object {
        const val NOMBRE = "pfc_prueba_restricciones.db"

        /** Los 18 CHECK del esquema anterior (Tabla 30) y un valor que cada uno rechaza. */
        val VALORES_INVALIDOS: Map<String, Any> = mapOf(
            "vehiculo.codigo" to "ABC",
            "vehiculo.marca" to "   ",
            "vehiculo.modelo" to "",
            "vehiculo.anio" to 1899,
            "vehiculo.cilindros" to 17,
            "vehiculo.familia_gestion" to "XYZ",
            "regimen.tipo" to "R4",
            "regimen.muestras_utiles" to -1,
            "regimen.descartadas_transitorio" to -1,
            "regimen.descartadas_techo" to -1,
            "codigo_dtc.codigo" to "",
            "codigo_dtc.tipo" to "permanente",
            "contador_modo06.cilindro" to 0,
            "contador_modo06.conteo_fallo" to -1,
            "contador_modo06.conteo_promedio" to -1,
            "dictamen.resultado" to "rechazada",
            "diagnostico.probabilidad" to 1.5,
            "parametro_desviado.direccion" to "igual",
        )
    }
}
