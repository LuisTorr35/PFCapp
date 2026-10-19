package com.example.pfc_app.datos

import com.example.pfc_app.Registro
import com.example.pfc_app.modelos.FamiliaGestion
import com.example.pfc_app.modelos.Vehiculo
import java.util.Locale

/** Vehículos registrados bajo el código propio VEH-### (RF-01, CU-01). */
class RepositorioVehiculos(private val base: BaseDatosLocal) {
    private val dao = base.vehiculos()

    /**
     * Registra el vehículo con el siguiente código VEH-### libre. La familia de gestión
     * queda en NULL hasta la primera conexión (CU-02, paso 6).
     */
    fun registrar(marca: String, modelo: String, anio: Int, cilindros: Int): Vehiculo {
        require(marca.isNotBlank()) { "Falta la marca" }
        require(modelo.isNotBlank()) { "Falta el modelo" }
        // El código se calcula y se inserta en la misma transacción.
        return base.runInTransaction<Vehiculo> {
            val codigo = formatoCodigo((dao.mayorNumeroCodigo() ?: 0) + 1)
            val fila = EntidadVehiculo(
                codigo = codigo,
                marca = marca.trim(),
                modelo = modelo.trim(),
                anio = anio,
                cilindros = cilindros,
                familiaGestion = null,
            )
            val id = dao.insertar(fila)
            Registro.i(Registro.DATOS, "Vehículo registrado $codigo (id $id)")
            fila.copy(idVehiculo = id).aVehiculo()
        }
    }

    /** Vehículos del más reciente al más antiguo. */
    fun listar(): List<Vehiculo> = dao.listar().map { it.aVehiculo() }

    /** Busca por código exacto; acepta "VEH-010", "veh-010", "010" o "10". */
    fun buscarPorCodigo(codigo: String): Vehiculo? {
        val normalizado = normalizarCodigo(codigo) ?: return null
        return dao.buscarPorCodigo(normalizado)?.aVehiculo()
    }

    fun obtener(id: Long): Vehiculo? = dao.obtener(id)?.aVehiculo()

    /** Guarda la familia de gestión detectada en la conexión. Devuelve false si el vehículo no existe. */
    fun actualizarFamilia(id: Long, familia: FamiliaGestion): Boolean {
        val filas = dao.actualizarFamilia(id, familia.texto)
        Registro.i(Registro.DATOS, "Familia de gestión del vehículo $id: ${familia.texto}")
        return filas == 1
    }

    companion object {
        fun formatoCodigo(numero: Int): String = String.format(Locale.ROOT, "VEH-%03d", numero)

        /** Lleva lo que escribe el mecánico al formato VEH-###, o null si no tiene número. */
        fun normalizarCodigo(texto: String): String? {
            val digitos = texto.filter(Char::isDigit)
            if (digitos.isEmpty() || digitos.length > 6) return null
            return formatoCodigo(digitos.toInt())
        }

        private fun EntidadVehiculo.aVehiculo() = Vehiculo(
            id = idVehiculo,
            codigo = codigo,
            marca = marca,
            modelo = modelo,
            anio = anio,
            cilindros = cilindros,
            familiaGestion = familiaGestion?.let(FamiliaGestion::desdeTexto),
        )
    }
}
