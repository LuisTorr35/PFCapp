package com.example.pfc_app.ui

import com.example.pfc_app.datos.RepositorioVehiculos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** CU-01, paso 3 y excepción E.1: se señala el dato que falta o no es válido. */
class ValidacionVehiculoTest {
    @Test
    fun datosCompletosYValidos() {
        val (datos, errores) = ValidacionVehiculo.validar(" Nissan ", "Sentra", "2014", "4", anioMaximo = 2027)
        assertNotNull(datos)
        assertFalse(errores.hayErrores)
        assertEquals(DatosVehiculo("Nissan", "Sentra", 2014, 4), datos)
    }

    @Test
    fun senalaCadaDatoFaltante() {
        val (datos, e) = ValidacionVehiculo.validar("", " ", "", "", anioMaximo = 2027)
        assertNull(datos)
        assertTrue(e.hayErrores)
        assertEquals("Ingrese la marca", e.marca)
        assertEquals("Ingrese el modelo", e.modelo)
        assertEquals("Ingrese el año", e.anio)
        assertEquals("Ingrese los cilindros", e.cilindros)
    }

    @Test
    fun senalaAnioYCilindrosInvalidos() {
        val (datos, e) = ValidacionVehiculo.validar("Kia", "Picanto", "19x9", "40", anioMaximo = 2027)
        assertNull(datos)
        assertNull(e.marca)
        assertNull(e.modelo)
        assertEquals("El año debe ser un número entre 1980 y 2027", e.anio)
        assertEquals("Debe ser un número entre 1 y 16", e.cilindros)
        assertNotNull(ValidacionVehiculo.validar("Kia", "Picanto", "2028", "4", anioMaximo = 2027).second.anio)
        assertNotNull(ValidacionVehiculo.validar("Kia", "Picanto", "2019", "0", anioMaximo = 2027).second.cilindros)
    }

    @Test
    fun normalizaElCodigoBuscado() {
        assertEquals("VEH-010", RepositorioVehiculos.normalizarCodigo("veh-10"))
        assertEquals("VEH-010", RepositorioVehiculos.normalizarCodigo("010"))
        assertEquals("VEH-1000", RepositorioVehiculos.normalizarCodigo("1000"))
        assertNull(RepositorioVehiculos.normalizarCodigo("VEH-"))
    }
}
