package com.example.pfc_app.procesamiento

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Redondeo con la semántica de round() de Python para números de punto flotante:
 * mitad al par sobre el valor binario EXACTO del double, no sobre su texto.
 * Así round(10.9375, 3) da 10.938 y round(2.675, 2) da 2.67, igual que en
 * consolidar_lecturas.py (4 decimales) y en valor() de registrar_obd.py (3).
 */
object Redondeo {
    fun python(x: Double, decimales: Int): Double {
        if (x.isNaN() || x.isInfinite()) return x
        return BigDecimal(x).setScale(decimales, RoundingMode.HALF_EVEN).toDouble()
    }

    /** Los estadísticos de la fila de características van a 4 decimales. */
    fun caracteristica(x: Double): Double = python(x, 4)

    /** Las lecturas del vehículo se guardan a 3 decimales, como valor() de registrar_obd.py. */
    fun lectura(x: Double): Double = python(x, 3)
}
