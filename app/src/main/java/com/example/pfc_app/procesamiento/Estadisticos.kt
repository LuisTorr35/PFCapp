package com.example.pfc_app.procesamiento

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Estadísticos con el mismo orden de operaciones que pandas y numpy, para que la
 * fila de características salga idéntica a la de consolidar_lecturas.py.
 *
 *  - media: Series.mean() suma con la suma por pares de numpy y divide entre n.
 *  - desviación: Series.std(ddof=1) en dos pasadas, primero la media y después
 *    la suma por pares de (media − x)², dividida entre n − 1, y la raíz.
 *  - pendiente: numpy.polyfit(x, y, 1) resuelve mínimos cuadrados con SVD; aquí
 *    se usa la fórmula cerrada con sumas compensadas, que coincide hasta el
 *    último dígito útil y no cambia el redondeo a 4 decimales.
 */
object Estadisticos {

    /** Bloque de numpy a partir del cual la suma se parte en dos mitades. */
    private const val BLOQUE_PARES = 128

    /**
     * Suma por pares de numpy (pairwise_sum de loops_utils.h): menos de 8
     * valores se suman en orden; hasta 128, con ocho acumuladores que se juntan
     * en árbol; más de 128, se parte en dos mitades múltiplos de 8.
     */
    fun sumaPareada(v: DoubleArray, desde: Int = 0, n: Int = v.size - desde): Double {
        if (n < 8) {
            var r = 0.0
            for (i in 0 until n) r += v[desde + i]
            return r
        }
        if (n <= BLOQUE_PARES) {
            val r = DoubleArray(8) { v[desde + it] }
            var i = 8
            val limite = n - (n % 8)
            while (i < limite) {
                for (j in 0 until 8) r[j] += v[desde + i + j]
                i += 8
            }
            var res = ((r[0] + r[1]) + (r[2] + r[3])) + ((r[4] + r[5]) + (r[6] + r[7]))
            while (i < n) {
                res += v[desde + i]
                i++
            }
            return res
        }
        var n2 = n / 2
        n2 -= n2 % 8
        return sumaPareada(v, desde, n2) + sumaPareada(v, desde + n2, n - n2)
    }

    /** Media como pandas: suma por pares entre n. NaN si no hay valores. */
    fun media(v: DoubleArray): Double = if (v.isEmpty()) Double.NaN else sumaPareada(v) / v.size

    /** Desviación muestral (ddof = 1) en dos pasadas; null con menos de dos valores, como el NaN de pandas. */
    fun desviacionMuestral(v: DoubleArray): Double? {
        val n = v.size
        if (n < 2) return null
        val m = media(v)
        val cuadrados = DoubleArray(n) {
            val d = m - v[it]
            d * d
        }
        return sqrt(sumaPareada(cuadrados) / (n - 1))
    }

    fun maximo(v: DoubleArray): Double? = if (v.isEmpty()) null else v.max()

    fun minimo(v: DoubleArray): Double? = if (v.isEmpty()) null else v.min()

    /** Pendiente de la recta de mínimos cuadrados de y contra x (grado 1). */
    fun pendiente(x: DoubleArray, y: DoubleArray): Double? {
        val n = x.size
        if (n < 2 || y.size != n) return null
        val mx = sumaCompensada(x) / n
        val my = sumaCompensada(y) / n
        val sxx = SumaCompensada()
        val sxy = SumaCompensada()
        for (i in 0 until n) {
            val dx = x[i] - mx
            sxx.sumar(dx * dx)
            sxy.sumar(dx * (y[i] - my))
        }
        val den = sxx.valor()
        if (den == 0.0) return null
        return sxy.valor() / den
    }

    private fun sumaCompensada(v: DoubleArray): Double {
        val s = SumaCompensada()
        for (x in v) s.sumar(x)
        return s.valor()
    }

    /** Suma de Neumaier: arrastra el error de redondeo de cada suma. */
    private class SumaCompensada {
        private var suma = 0.0
        private var compensacion = 0.0

        fun sumar(x: Double) {
            val t = suma + x
            compensacion += if (abs(suma) >= abs(x)) (suma - t) + x else (x - t) + suma
            suma = t
        }

        fun valor(): Double = suma + compensacion
    }
}
