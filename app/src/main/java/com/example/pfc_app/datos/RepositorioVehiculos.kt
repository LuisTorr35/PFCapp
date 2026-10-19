package com.example.pfc_app.datos

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.example.pfc_app.Registro
import com.example.pfc_app.modelos.FamiliaGestion
import com.example.pfc_app.modelos.Vehiculo
import java.util.Locale

/** Vehículos registrados bajo el código propio VEH-### (RF-01, CU-01). */
class RepositorioVehiculos(private val base: BaseDatosLocal) {

    /**
     * Registra el vehículo con el siguiente código VEH-### libre. La familia de gestión
     * queda en NULL hasta la primera conexión (CU-02, paso 6).
     */
    fun registrar(marca: String, modelo: String, anio: Int, cilindros: Int): Vehiculo {
        require(marca.isNotBlank()) { "Falta la marca" }
        require(modelo.isNotBlank()) { "Falta el modelo" }
        val db = base.writableDatabase
        db.beginTransaction()
        try {
            val codigo = siguienteCodigo(db)
            val valores = ContentValues().apply {
                put("codigo", codigo)
                put("marca", marca.trim())
                put("modelo", modelo.trim())
                put("anio", anio)
                put("cilindros", cilindros)
                putNull("familia_gestion")
            }
            val id = db.insertOrThrow("vehiculo", null, valores)
            db.setTransactionSuccessful()
            Registro.i(Registro.DATOS, "Vehículo registrado $codigo (id $id)")
            return Vehiculo(id, codigo, marca.trim(), modelo.trim(), anio, cilindros, null)
        } finally {
            db.endTransaction()
        }
    }

    /** Vehículos del más reciente al más antiguo. */
    fun listar(): List<Vehiculo> =
        base.readableDatabase.rawQuery("SELECT $COLUMNAS FROM vehiculo ORDER BY id_vehiculo DESC", null)
            .use { c -> buildList { while (c.moveToNext()) add(c.aVehiculo()) } }

    /** Busca por código exacto; acepta "VEH-010", "veh-010", "010" o "10". */
    fun buscarPorCodigo(codigo: String): Vehiculo? {
        val normalizado = normalizarCodigo(codigo) ?: return null
        return base.readableDatabase.rawQuery(
            "SELECT $COLUMNAS FROM vehiculo WHERE codigo = ?", arrayOf(normalizado),
        ).use { c -> if (c.moveToFirst()) c.aVehiculo() else null }
    }

    fun obtener(id: Long): Vehiculo? =
        base.readableDatabase.rawQuery(
            "SELECT $COLUMNAS FROM vehiculo WHERE id_vehiculo = ?", arrayOf(id.toString()),
        ).use { c -> if (c.moveToFirst()) c.aVehiculo() else null }

    /** Guarda la familia de gestión detectada en la conexión. Devuelve false si el vehículo no existe. */
    fun actualizarFamilia(id: Long, familia: FamiliaGestion): Boolean {
        val valores = ContentValues().apply { put("familia_gestion", familia.texto) }
        val filas = base.writableDatabase.update("vehiculo", valores, "id_vehiculo = ?", arrayOf(id.toString()))
        Registro.i(Registro.DATOS, "Familia de gestión del vehículo $id: ${familia.texto}")
        return filas == 1
    }

    private fun siguienteCodigo(db: SQLiteDatabase): String {
        val maximo = db.rawQuery(
            "SELECT MAX(CAST(substr(codigo, 5) AS INTEGER)) FROM vehiculo", null,
        ).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getInt(0) else 0 }
        return formatoCodigo(maximo + 1)
    }

    companion object {
        private const val COLUMNAS = "id_vehiculo, codigo, marca, modelo, anio, cilindros, familia_gestion"

        fun formatoCodigo(numero: Int): String = String.format(Locale.ROOT, "VEH-%03d", numero)

        /** Lleva lo que escribe el mecánico al formato VEH-###, o null si no tiene número. */
        fun normalizarCodigo(texto: String): String? {
            val digitos = texto.filter(Char::isDigit)
            if (digitos.isEmpty() || digitos.length > 6) return null
            return formatoCodigo(digitos.toInt())
        }

        private fun Cursor.aVehiculo() = Vehiculo(
            id = getLong(0),
            codigo = getString(1),
            marca = getString(2),
            modelo = getString(3),
            anio = getInt(4),
            cilindros = getInt(5),
            familiaGestion = if (isNull(6)) null else FamiliaGestion.desdeTexto(getString(6)),
        )
    }
}
