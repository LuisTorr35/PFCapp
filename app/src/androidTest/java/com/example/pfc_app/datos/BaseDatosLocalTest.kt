package com.example.pfc_app.datos

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.pfc_app.modelos.Atencion
import com.example.pfc_app.modelos.CodigoDTC
import com.example.pfc_app.modelos.ContadorModo06
import com.example.pfc_app.modelos.Diagnostico
import com.example.pfc_app.modelos.Dictamen
import com.example.pfc_app.modelos.Direccion
import com.example.pfc_app.modelos.FamiliaGestion
import com.example.pfc_app.modelos.Lectura
import com.example.pfc_app.modelos.ParametroDesviado
import com.example.pfc_app.modelos.Regimen
import com.example.pfc_app.modelos.ResultadoDictamen
import com.example.pfc_app.modelos.TipoDTC
import com.example.pfc_app.modelos.TipoRegimen
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Pruebas de la base local Room (Tabla 30): códigos, transacción, historial, FK, CHECK y regla 8. */
@RunWith(AndroidJUnit4::class)
class BaseDatosLocalTest {
    private lateinit var contexto: Context
    private lateinit var base: BaseDatosLocal
    private lateinit var vehiculos: RepositorioVehiculos
    private lateinit var atenciones: RepositorioAtenciones

    /** Acceso SQL directo a la misma base, para probar las restricciones sin pasar por los DAO. */
    private val sql: SupportSQLiteDatabase get() = base.openHelper.writableDatabase

    @Before
    fun preparar() {
        contexto = InstrumentationRegistry.getInstrumentation().targetContext
        contexto.deleteDatabase(NOMBRE)
        base = BaseDatosLocal.crear(contexto, NOMBRE)
        vehiculos = RepositorioVehiculos(base)
        atenciones = RepositorioAtenciones(base)
    }

    @After
    fun cerrar() {
        base.close()
        contexto.deleteDatabase(NOMBRE)
    }

    @Test
    fun registrarAsignaCodigosConsecutivosYBuscaPorCodigo() {
        val a = vehiculos.registrar("Hyundai", "Tucson", 2011, 4)
        val b = vehiculos.registrar(" Kia ", "Picanto", 2019, 4)
        val c = vehiculos.registrar("Haval", "H2", 2020, 4)
        assertEquals("VEH-001", a.codigo)
        assertEquals("VEH-002", b.codigo)
        assertEquals("VEH-003", c.codigo)
        assertEquals("Kia", b.marca)
        assertNull("La familia queda pendiente hasta la primera conexión", a.familiaGestion)

        assertEquals(listOf("VEH-003", "VEH-002", "VEH-001"), vehiculos.listar().map { it.codigo })
        assertEquals(b.id, vehiculos.buscarPorCodigo("VEH-002")?.id)
        assertEquals(b.id, vehiculos.buscarPorCodigo("veh-002")?.id)
        assertEquals(b.id, vehiculos.buscarPorCodigo("2")?.id)
        assertNull(vehiculos.buscarPorCodigo("VEH-099"))
        assertEquals(c, vehiculos.obtener(c.id))
    }

    @Test
    fun actualizarFamiliaDeGestion() {
        val v = vehiculos.registrar("Nissan", "Sentra", 2014, 4)
        assertTrue(vehiculos.actualizarFamilia(v.id, FamiliaGestion.MAP))
        assertEquals(FamiliaGestion.MAP, vehiculos.obtener(v.id)?.familiaGestion)
        assertTrue(vehiculos.actualizarFamilia(v.id, FamiliaGestion.AMBOS))
        assertEquals(FamiliaGestion.AMBOS, vehiculos.obtener(v.id)?.familiaGestion)
        assertFalse(vehiculos.actualizarFamilia(9999, FamiliaGestion.MAF))
    }

    @Test
    fun guardarAtencionCompletaYLeerlaConTodasSusFilas() {
        val v = vehiculos.registrar("Hyundai", "Tucson", 2011, 4)
        val atencion = atencionDePrueba(v.id, "2026-09-28T11:20:00")
        val id = atenciones.guardar(atencion, dictamenCompleta(), diagnosticoDePrueba())

        val g = atenciones.obtener(id)
        assertNotNull(g)
        g!!
        assertEquals(v.id, g.atencion.idVehiculo)
        assertEquals("2026-09-28T11:20:00", g.atencion.fecha)
        assertEquals(75.0, g.atencion.baroKpa!!, 1e-9)
        assertEquals(setOf(TipoRegimen.R1, TipoRegimen.R2, TipoRegimen.R3), g.atencion.regimenes.keys)

        val r2 = g.atencion.regimen(TipoRegimen.R2)!!
        assertEquals(3, r2.lecturas.size)
        assertEquals(46, r2.muestrasUtiles)
        assertEquals(2, r2.descartadasTransitorio)
        assertEquals(1, r2.descartadasTecho)
        assertEquals(2.5, r2.frecuenciaHz, 1e-9)
        // rpm conserva los cuartos de rpm del PID 0x0C.
        assertEquals(812.25, r2.lecturas[0].rpm!!, 1e-9)
        // Lecturas en orden de instante.
        assertEquals(listOf(0.0, 0.4, 0.8), r2.lecturas.map { it.instante })
        // Un parámetro no expuesto vuelve como null, no como cero.
        assertNull(r2.lecturas[0].maf)
        assertEquals(35.0, r2.lecturas[0].map!!, 1e-9)
        assertEquals(0.0, r2.lecturas[0].velocidad!!, 1e-9)

        assertEquals(listOf(CodigoDTC("P0303", TipoDTC.ALMACENADO), CodigoDTC("P0171", TipoDTC.PENDIENTE)), g.atencion.codigos)
        assertEquals(4, g.atencion.contadoresModo06.size)
        assertEquals(ContadorModo06(3, 12, 7), g.atencion.contadoresModo06[2])
        assertEquals(ContadorModo06(4, null, null), g.atencion.contadoresModo06[3])

        assertEquals(ResultadoDictamen.COMPLETA, g.dictamen.resultado)
        assertFalse(g.dictamen.faltanParametrosModelo)

        val d = g.diagnostico!!
        assertEquals("C1", d.clase)
        assertEquals(0.72, d.probabilidad, 1e-9)
        assertEquals("0.1.0-provisional", d.versionModelo)
        assertTrue(d.modeloProvisional)
        assertEquals(2, d.parametrosDesviados.size)
        val p = d.parametrosDesviados[0]
        assertEquals("R2_rpm_desv", p.parametro)
        assertEquals(14.9, p.valor, 1e-9)
        assertEquals(3.5, p.referencia, 1e-9)
        assertEquals(Direccion.MAYOR, p.direccion)
        assertEquals(Direccion.MENOR, d.parametrosDesviados[1].direccion)

        // Fila cruda: el NULL se guarda como NULL.
        sql.query("SELECT COUNT(*) FROM lectura WHERE maf IS NULL").use { c ->
            c.moveToFirst()
            assertEquals(9, c.getInt(0))
        }
    }

    @Test
    fun guardarEsUnaSolaTransaccion_unaFallaNoDejaNadaAMedias() {
        val v = vehiculos.registrar("Hyundai", "Tucson", 2011, 4)
        // La probabilidad 1,5 viola el CHECK de diagnostico, que se inserta al final.
        val malo = diagnosticoDePrueba().copy(probabilidad = 1.5)
        try {
            atenciones.guardar(atencionDePrueba(v.id, "2026-09-28T11:20:00"), dictamenCompleta(), malo)
            fail("Debía rechazar la probabilidad fuera de 0..1")
        } catch (_: SQLiteConstraintException) {
        }
        for (tabla in BaseDatosLocal.TABLAS.drop(1)) {
            assertEquals("La tabla $tabla debía quedar vacía", 0, contar(tabla))
        }
        assertEquals(1, contar("vehiculo"))
        assertTrue(atenciones.listarPorVehiculo(v.id).isEmpty())
    }

    @Test
    fun historialOrdenadoDeLaMasRecienteALaMasAntigua() {
        val v = vehiculos.registrar("Hyundai", "Tucson", 2011, 4)
        val otro = vehiculos.registrar("Kia", "Picanto", 2019, 4)
        val idMedia = atenciones.guardar(atencionDePrueba(v.id, "2026-09-28T10:05:00"), dictamenCompleta(), diagnosticoDePrueba())
        val idAntigua = atenciones.guardar(atencionDePrueba(v.id, "2026-09-26T16:40:00"), dictamenParcial(), diagnosticoDePrueba().copy(clase = "C0", probabilidad = 0.88))
        val idReciente = atenciones.guardar(atencionDePrueba(v.id, "2026-09-28T11:20:00"), dictamenCompleta(), diagnosticoDePrueba())
        atenciones.guardar(atencionDePrueba(otro.id, "2026-09-29T09:00:00"), dictamenCompleta(), diagnosticoDePrueba())

        val lista = atenciones.listarPorVehiculo(v.id)
        assertEquals(listOf(idReciente, idMedia, idAntigua), lista.map { it.idAtencion })
        assertEquals(ResultadoDictamen.PARCIAL, lista[2].resultado)
        assertEquals("C0", lista[2].clase)
        assertEquals(0.88, lista[2].probabilidad!!, 1e-9)
        assertEquals("Falta R1: el motor llegó caliente", lista[2].observacion)
        assertEquals(1, atenciones.listarPorVehiculo(otro.id).size)
    }

    @Test
    fun clavesForaneasRechazanFilasHuerfanas() {
        // Lectura de un régimen que no existe.
        assertConstraint {
            sql.insert("lectura", SQLiteDatabase.CONFLICT_ABORT, ContentValues().apply {
                put("id_regimen", 999)
                put("instante", 0.0)
            })
        }
        // Atención de un vehículo que no existe.
        assertConstraint { atenciones.guardar(atencionDePrueba(4242, "2026-09-28T11:20:00"), dictamenCompleta(), null) }
        assertEquals(0, contar("atencion"))
        // Parámetro desviado de un diagnóstico que no existe.
        assertConstraint {
            sql.insert("parametro_desviado", SQLiteDatabase.CONFLICT_ABORT, ContentValues().apply {
                put("id_diagnostico", 77)
                put("parametro", "R2_rpm_desv")
                put("valor", 1.0)
                put("direccion", "mayor")
            })
        }
    }

    @Test
    fun checkRechazanValoresInvalidos() {
        val v = vehiculos.registrar("Hyundai", "Tucson", 2011, 4)
        val idAtencion = atenciones.guardar(atencionDePrueba(v.id, "2026-09-28T11:20:00"), dictamenCompleta(), diagnosticoDePrueba())
        val db = sql

        // vehiculo: familia y código fuera de lo permitido.
        assertConstraint { db.execSQL("UPDATE vehiculo SET familia_gestion = 'XYZ' WHERE id_vehiculo = ${v.id}") }
        assertConstraint {
            db.insert("vehiculo", SQLiteDatabase.CONFLICT_ABORT, ContentValues().apply {
                put("codigo", "ABC"); put("marca", "X"); put("modelo", "Y"); put("anio", 2010); put("cilindros", 4)
            })
        }
        // Código repetido.
        assertConstraint {
            db.insert("vehiculo", SQLiteDatabase.CONFLICT_ABORT, ContentValues().apply {
                put("codigo", v.codigo); put("marca", "X"); put("modelo", "Y"); put("anio", 2010); put("cilindros", 4)
            })
        }
        // regimen.tipo solo R1, R2 o R3.
        assertConstraint {
            db.insert("regimen", SQLiteDatabase.CONFLICT_ABORT, ContentValues().apply { put("id_atencion", idAtencion); put("tipo", "R4") })
        }
        // codigo_dtc.tipo solo almacenado o pendiente.
        assertConstraint {
            db.insert("codigo_dtc", SQLiteDatabase.CONFLICT_ABORT, ContentValues().apply {
                put("id_atencion", idAtencion); put("codigo", "P0300"); put("tipo", "permanente")
            })
        }
        // dictamen: rechazada no se guarda y hay uno solo por atención.
        assertConstraint { db.execSQL("UPDATE dictamen SET resultado = 'rechazada' WHERE id_atencion = $idAtencion") }
        assertConstraint {
            db.insert("dictamen", SQLiteDatabase.CONFLICT_ABORT, ContentValues().apply { put("id_atencion", idAtencion); put("resultado", "parcial") })
        }
        // diagnostico: probabilidad entre 0 y 1 y uno solo por atención.
        assertConstraint { db.execSQL("UPDATE diagnostico SET probabilidad = -0.1 WHERE id_atencion = $idAtencion") }
        assertConstraint {
            db.insert("diagnostico", SQLiteDatabase.CONFLICT_ABORT, ContentValues().apply {
                put("id_atencion", idAtencion); put("clase", "C2"); put("probabilidad", 0.5); put("version_modelo", "x")
            })
        }
        // parametro_desviado.direccion solo mayor o menor.
        assertConstraint { db.execSQL("UPDATE parametro_desviado SET direccion = 'igual'") }

        // El repositorio no guarda atenciones rechazadas.
        try {
            atenciones.guardar(
                atencionDePrueba(v.id, "2026-09-28T12:00:00"),
                Dictamen(ResultadoDictamen.RECHAZADA, emptyList(), listOf(TipoRegimen.R2), "R2 no reunió las 45 muestras útiles."),
                null,
            )
            fail("Debía rechazar el dictamen rechazada")
        } catch (_: IllegalArgumentException) {
        }
        assertEquals(1, contar("atencion"))
    }

    @Test
    fun atencionSinDiagnosticoSeGuardaSinFilaEnDiagnostico() {
        val v = vehiculos.registrar("Chevrolet", "Spark", 2012, 4)
        val dictamen = Dictamen(ResultadoDictamen.PARCIAL, emptyList(), observacion = "Sin avance de encendido", faltanParametrosModelo = true)
        val id = atenciones.guardar(atencionDePrueba(v.id, "2026-09-28T11:20:00"), dictamen, null)

        assertEquals(0, contar("diagnostico"))
        assertEquals(0, contar("parametro_desviado"))
        val g = atenciones.obtener(id)!!
        assertNull(g.diagnostico)
        assertTrue(g.dictamen.faltanParametrosModelo)
        assertEquals(ResultadoDictamen.PARCIAL, g.dictamen.resultado)
        val fila = atenciones.listarPorVehiculo(v.id).single()
        assertFalse(fila.tieneDiagnostico)
        assertNull(fila.probabilidad)
    }

    @Test
    fun fechaSeNormalizaAIso8601() {
        val v = vehiculos.registrar("Hyundai", "Tucson", 2011, 4)
        val id = atenciones.guardar(atencionDePrueba(v.id, "2026-09-28T11:20:00.123"), dictamenCompleta(), null)
        assertEquals("2026-09-28T11:20:00", atenciones.obtener(id)!!.atencion.fecha)
    }

    // ---------- Datos de prueba ----------

    private fun contar(tabla: String): Int =
        sql.query("SELECT COUNT(*) FROM $tabla").use { c -> c.moveToFirst(); c.getInt(0) }

    private fun assertConstraint(bloque: () -> Unit) {
        try {
            bloque()
            fail("Debía violar una restricción")
        } catch (_: SQLiteConstraintException) {
        }
    }

    private fun lecturas(rpmBase: Double): List<Lectura> = listOf(0.0, 0.4, 0.8).mapIndexed { i, t ->
        Lectura(
            instante = t,
            rpm = rpmBase + i * 0.25,
            tempRefrigerante = 88.0,
            stft = 1.6,
            ltft = 9.4,
            cargaCalculada = 23.1,
            avanceEncendido = 8.5,
            maf = null,
            map = 35.0,
            velocidad = 0.0,
        )
    }

    private fun atencionDePrueba(idVehiculo: Long, fecha: String) = Atencion(
        idVehiculo = idVehiculo,
        fecha = fecha,
        baroKpa = 75.0,
        protocolo = "ISO 15765-4 (CAN 11/500)",
        codigos = listOf(CodigoDTC("P0303", TipoDTC.ALMACENADO), CodigoDTC("P0171", TipoDTC.PENDIENTE)),
        contadoresModo06 = listOf(
            ContadorModo06(1, 0, 0), ContadorModo06(2, 0, 0), ContadorModo06(3, 12, 7), ContadorModo06(4, null, null),
        ),
        regimenes = mapOf(
            TipoRegimen.R1 to Regimen(tipo = TipoRegimen.R1, lecturas = lecturas(950.0), muestrasUtiles = 54, frecuenciaHz = 2.4),
            TipoRegimen.R2 to Regimen(
                tipo = TipoRegimen.R2, lecturas = lecturas(812.25), muestrasUtiles = 46,
                descartadasTransitorio = 2, descartadasTecho = 1, frecuenciaHz = 2.5,
            ),
            TipoRegimen.R3 to Regimen(tipo = TipoRegimen.R3, lecturas = lecturas(2500.0), muestrasUtiles = 78, frecuenciaHz = 2.6),
        ),
    )

    private fun dictamenCompleta() = Dictamen(ResultadoDictamen.COMPLETA, emptyList())

    private fun dictamenParcial() = Dictamen(ResultadoDictamen.PARCIAL, emptyList(), observacion = "Falta R1: el motor llegó caliente")

    private fun diagnosticoDePrueba() = Diagnostico(
        clase = "C1",
        nombreClase = "Encendido y combustión",
        probabilidad = 0.72,
        versionModelo = "0.1.0-provisional",
        parametrosDesviados = listOf(
            ParametroDesviado("R2_rpm_desv", 14.9, 3.5, Direccion.MAYOR),
            ParametroDesviado("R2_avance_media", 2.1, 8.0, Direccion.MENOR),
        ),
    )

    private companion object {
        const val NOMBRE = "pfc_prueba_base.db"
    }
}
